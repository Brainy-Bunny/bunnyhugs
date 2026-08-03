package com.ssbmedia.twogether.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log

/** Scans for any Twogether beacon, then app-level filters to the exact partner by comparing the secret prefix. */
class ScannerManager(private val context: Context) {
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var isScanning = false
    private var scanCallback: ScanCallback? = null

    /** True once scanning is actually active. Exposed so the service can retry on later ticks instead of latching a one-time attempt. */
    val isActive: Boolean get() = isScanning

    /** Returns true iff scanning is (now, or already was) actually running. */
    fun start(expectedSecretPrefix: ByteArray, onPartnerSeen: (device: BluetoothDevice, tieBreakByte: Byte, rssi: Int) -> Unit): Boolean {
        if (isScanning) return true
        if (!BlePermissions.hasBlePermissions(context)) return false
        val adapter = bluetoothManager?.adapter ?: return false
        if (!adapter.isEnabled) return false
        val scanner = adapter.bluetoothLeScanner ?: return false

        // IMPORTANT: on API 27+, Android suppresses delivery of *unfiltered* background BLE scan
        // results while the screen is off - since a phone spends almost all its time screen-off, an
        // unfiltered startScan(emptyList(), ...) effectively only works during active screen-on
        // testing and silently stops detecting the partner the moment the screen locks. A real
        // ScanFilter is required for reliable background delivery.
        //
        // We can't use ScanFilter.setServiceUuid() here: AdvertiserManager only broadcasts a Service
        // Data AD structure (not a separate Service UUID list - see the comment there for why), so a
        // setServiceUuid() filter would never match anything. Instead we filter on the Service Data AD
        // structure itself via setServiceData(), matching our UUID plus the leading
        // SECRET_PREFIX_BYTES of the payload (the partner's secret prefix), with a mask that leaves the
        // trailing tie-break byte as a wildcard since it varies per install. This exactly mirrors what
        // AdvertiserManager broadcasts (BleConstants.PROXIMITY_PARCEL_UUID -> secretPrefix + tieBreakByte).
        val serviceData = expectedSecretPrefix + byteArrayOf(0)
        // ScanFilter's mask is bitwise, not "1 byte = compare this byte": a mask byte of 0x01 only
        // compares the lowest bit of that byte, letting through anything that merely shares that one
        // bit with our prefix - 0xFF is required to actually compare (mask) the whole byte at the OS
        // filter level. The app still double-checks the full prefix itself in onScanResult below
        // regardless, so this was never a correctness bug, just a far-less-selective OS-level filter
        // than intended (more scan callbacks delivered to the app than necessary).
        val serviceDataMask = ByteArray(BleConstants.SECRET_PREFIX_BYTES) { 0xFF.toByte() } + byteArrayOf(0)
        val filter = ScanFilter.Builder()
            .setServiceData(BleConstants.PROXIMITY_PARCEL_UUID, serviceData, serviceDataMask)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val serviceData = result.scanRecord?.getServiceData(ParcelUuid(BleConstants.PROXIMITY_SERVICE_UUID)) ?: return
                if (serviceData.size < BleConstants.SECRET_PREFIX_BYTES + 1) return
                val prefix = serviceData.copyOfRange(0, BleConstants.SECRET_PREFIX_BYTES)
                if (!prefix.contentEquals(expectedSecretPrefix)) return
                val tieBreak = serviceData[BleConstants.SECRET_PREFIX_BYTES]
                onPartnerSeen(result.device, tieBreak, result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                // Explicitly stop/clear this registration rather than just flipping isScanning=false: if
                // the failure was SCAN_FAILED_ALREADY_STARTED, a stale registration can still be alive at
                // the OS level even though this callback object is being abandoned locally. Leaving
                // scanCallback pointing at it means the next start() call installs a brand-new callback,
                // orphaning the stale one - and repeated rapid start() retries can trip Android's scan
                // throttling (~5 starts per 30s), suspending scanning altogether. stopScan is best-effort
                // here (safe even if this callback was never actually registered).
                try {
                    scanner.stopScan(this)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean up failed scan registration", e)
                }
                isScanning = false
                scanCallback = null
            }
        }

        return try {
            scanner.startScan(listOf(filter), settings, callback)
            scanCallback = callback
            isScanning = true
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to scan", e)
            false
        }
    }

    fun stop() {
        if (!isScanning) return
        val adapter = bluetoothManager?.adapter
        val scanner = adapter?.bluetoothLeScanner
        val callback = scanCallback
        try {
            if (callback != null) scanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to stop scan", e)
        }
        isScanning = false
        scanCallback = null
    }

    companion object {
        private const val TAG = "ScannerManager"
    }
}
