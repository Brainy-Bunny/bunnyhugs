package com.ssbmedia.twogether.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.util.Log

/** Broadcasts a tiny service-data payload (pair-secret prefix + a role tie-break byte) so only our real partner's phone recognizes us. */
class AdvertiserManager(private val context: Context) {
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var isAdvertising = false

    /** True once advertising is actually active. Exposed so the service can retry on later ticks instead of latching a one-time attempt. */
    val isActive: Boolean get() = isAdvertising

    /** Returns true iff advertising is (now, or already was) actually running. Note startAdvertising()
     * itself is async - this optimistically reports true right after the call succeeds, and gets
     * corrected back to false by [callback]'s onStartFailure if the platform ultimately rejects it, so
     * the next ensureBleRunning() tick in the service naturally retries. */
    fun start(secretPrefix: ByteArray, tieBreakByte: Byte): Boolean {
        if (isAdvertising) return true
        if (!BlePermissions.hasBlePermissions(context)) return false
        val adapter = bluetoothManager?.adapter ?: return false
        if (!adapter.isEnabled) return false
        val advertiser = adapter.bluetoothLeAdvertiser ?: return false

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val payload = secretPrefix + tieBreakByte
        // IMPORTANT: legacy BLE advertisements are capped at 31 bytes total. The mandatory Flags AD
        // structure the platform adds costs 3 bytes, and a Service Data AD structure for a 128-bit
        // UUID costs 1 (length) + 1 (type) + 16 (UUID) + payload.size = 18 + payload.size here, for
        // 21 + payload.size total - comfortably under the limit on its own. But also calling
        // addServiceUuid() adds a *second*, separate "Complete List of 128-bit Service UUIDs" AD
        // structure (1 + 1 + 16 = 18 more bytes), which pushes the total past 31 and makes
        // startAdvertising() fail immediately with ADVERTISE_FAILED_DATA_TOO_LARGE (error code 1) -
        // meaning this beacon never actually broadcasts anything and the partner can never be found.
        // The Service UUID AD structure was redundant anyway: ScannerManager already does its own
        // app-level UUID + prefix matching by reading the Service Data field directly (see
        // ScannerManager.onScanResult), so it doesn't need the OS-level ScanFilter to match on a
        // separate Service UUID list.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(BleConstants.PROXIMITY_PARCEL_UUID, payload)
            .build()

        return try {
            advertiser.startAdvertising(settings, data, callback)
            isAdvertising = true
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to advertise", e)
            false
        }
    }

    fun stop() {
        if (!isAdvertising) return
        val adapter = bluetoothManager?.adapter
        val advertiser = adapter?.bluetoothLeAdvertiser
        try {
            advertiser?.stopAdvertising(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to stop advertising", e)
        }
        isAdvertising = false
    }

    private val callback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertise start failed: $errorCode")
            isAdvertising = false
        }
    }

    companion object {
        private const val TAG = "AdvertiserManager"
    }
}
