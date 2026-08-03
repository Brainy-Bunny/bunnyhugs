package com.ssbmedia.twogether.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.ssbmedia.twogether.data.db.DateIdea
import com.ssbmedia.twogether.data.repo.DateIdeaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Tiny custom GATT protocol piggybacked on the proximity connection: exchange the whole date-ideas
 * list as chunked JSON over one write+notify characteristic, then merge last-write-wins locally.
 * One side acts as GATT server (passive), the other as GATT client (initiates) - the caller decides
 * the role via a deterministic tie-break so both phones never both try the same role.
 *
 * Every connection must first prove it knows the shared pair secret (a short handshake token derived
 * from the pairing code, see BleConstants.HANDSHAKE_TOKEN_*) before any real chunk data is accepted -
 * without this, any nearby stranger's GATT client could connect to our open server and read back the
 * couple's date-ideas list, or write a forged tombstone to delete a real item (merge is last-write-wins
 * with no origin check otherwise).
 */
class GattSyncManager(
    private val context: Context,
    private val repository: DateIdeaRepository,
    private val scope: CoroutineScope
) {
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    // ---- shared chunk protocol helpers ----

    private fun serialize(ideas: List<DateIdea>): ByteArray {
        val arr = JSONArray()
        for (idea in ideas) {
            val o = JSONObject()
            o.put("id", idea.id)
            o.put("text", idea.text)
            o.put("category", idea.category ?: JSONObject.NULL)
            o.put("done", idea.done)
            o.put("updatedAt", idea.updatedAt)
            o.put("deleted", idea.deleted)
            arr.put(o)
        }
        return arr.toString().toByteArray(Charsets.UTF_8)
    }

    private fun deserialize(bytes: ByteArray): List<DateIdea> {
        if (bytes.isEmpty()) return emptyList()
        val arr = JSONArray(String(bytes, Charsets.UTF_8))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DateIdea(
                id = o.getString("id"),
                text = o.getString("text"),
                category = if (o.isNull("category")) null else o.getString("category"),
                done = o.getBoolean("done"),
                updatedAt = o.getLong("updatedAt"),
                deleted = o.getBoolean("deleted")
            )
        }
    }

    private fun toChunks(data: ByteArray, maxPayload: Int): List<ByteArray> {
        if (data.isEmpty()) return listOf(byteArrayOf(BleConstants.CHUNK_FLAG_LAST))
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + maxPayload, data.size)
            val isLast = end >= data.size
            val flag = if (isLast) BleConstants.CHUNK_FLAG_LAST else BleConstants.CHUNK_FLAG_MORE
            val chunk = ByteArray(1 + (end - offset))
            chunk[0] = flag
            data.copyInto(chunk, 1, offset, end)
            chunks.add(chunk)
            offset = end
        }
        return chunks
    }

    /** Derives the actual usable chunk payload size from a negotiated ATT MTU, never exceeding
     * BleConstants.MAX_CHUNK_PAYLOAD and never assuming more than the default 23-byte MTU unless the
     * negotiation actually reported success with a larger value. */
    private fun effectiveChunkPayload(mtu: Int, status: Int): Int {
        val safeMtu = if (status == BluetoothGatt.GATT_SUCCESS && mtu > DEFAULT_ATT_MTU) mtu else DEFAULT_ATT_MTU
        val usable = safeMtu - ATT_HEADER_BYTES - CHUNK_FLAG_HEADER_BYTES
        return usable.coerceIn(MIN_CHUNK_PAYLOAD, BleConstants.MAX_CHUNK_PAYLOAD)
    }

    // ---- SERVER side ----

    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    private val serverIncoming = HashMap<String, ByteArrayOutputStream>()
    private val serverOutQueue = HashMap<String, MutableList<ByteArray>>()
    private val authenticatedDevices = HashSet<String>()
    private val deviceMtus = HashMap<String, Int>()
    // Guards serverIncoming / serverOutQueue / authenticatedDevices / deviceMtus, which are otherwise
    // mutated both from GATT binder-thread callbacks and from coroutines launched via [scope].
    private val serverLock = Any()
    private var expectedHandshakeToken: ByteArray = ByteArray(0)

    // The most recently registered "sync finished" callback. Kept as a mutable field (rather than
    // captured directly in the BluetoothGattServerCallback closure below) so that startServer() can be
    // called again - e.g. a manual "Sync now" tap while this device's GATT server is already open from
    // earlier in the together-session - and have that new call's callback actually get used for the
    // next completed sync, without tearing down and re-registering the whole GATT service each time
    // (which would drop any in-flight write from the other side, and can fail outright if the
    // characteristic is added again while a service with the same UUID is still registered).
    private var activeServerOnSyncDone: (Boolean) -> Unit = {}

    fun startServer(handshakeToken: ByteArray, onSyncDone: (Boolean) -> Unit) {
        expectedHandshakeToken = handshakeToken
        activeServerOnSyncDone = onSyncDone
        if (gattServer != null) {
            // Already listening this session (new callback is wired in above for whenever the next real
            // exchange completes). As a passive GATT peripheral we can't proactively "pull" a sync from
            // here - only the client side can initiate a fresh connection+write - so there's nothing
            // actionable left for a redundant startServer() call to do right now.
            //
            // IMPORTANT: deliberately does NOT call onSyncDone(true) here. Doing so used to report a
            // false-positive success (and update the "last synced" timestamp) even though no fresh data
            // exchange happened on this tap at all - e.g. tapping "Sync now" on the device that already
            // opened its GATT server earlier in the session, with the partner never having connected in
            // between. Leaving this call unresolved lets it settle honestly: either a real exchange
            // completes shortly (the partner connects as client and activeServerOnSyncDone - already
            // repointed to this call's callback above - fires with a genuine result), or
            // DateIdeasScreen's own timeout resolves the UI to "Couldn't sync - make sure you're
            // together" after a few seconds. Either outcome is honest; silently claiming success never was.
            return
        }

        val adapter = bluetoothManager?.adapter ?: return onSyncDone(false)
        if (!BlePermissions.hasBlePermissions(context)) return onSyncDone(false)

        val characteristic = BluetoothGattCharacteristic(
            BleConstants.SYNC_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val cccd = BluetoothGattDescriptor(
            BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(cccd)
        serverCharacteristic = characteristic

        val service = BluetoothGattService(BleConstants.SYNC_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)

        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val addr = device.address
                    synchronized(serverLock) {
                        authenticatedDevices.remove(addr)
                        serverIncoming.remove(addr)
                        serverOutQueue.remove(addr)
                        deviceMtus.remove(addr)
                    }
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                synchronized(serverLock) { deviceMtus[device.address] = mtu }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                val addr = device.address
                val isAuthenticated = synchronized(serverLock) { authenticatedDevices.contains(addr) }

                if (!isAuthenticated) {
                    // The very first write from a not-yet-authenticated device must be exactly the
                    // shared-secret-derived handshake token, proving it knows our pairing code, before
                    // we accept or act on anything else it sends.
                    val ok = expectedHandshakeToken.isNotEmpty() && value.contentEquals(expectedHandshakeToken)
                    if (ok) {
                        synchronized(serverLock) { authenticatedDevices.add(addr) }
                        if (responseNeeded) {
                            try {
                                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
                            } catch (e: SecurityException) { Log.w(TAG, "sendResponse failed", e) }
                        }
                    } else {
                        Log.w(TAG, "Rejecting GATT write from unauthenticated device $addr - missing/invalid handshake")
                        if (responseNeeded) {
                            try {
                                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, offset, null)
                            } catch (e: SecurityException) { Log.w(TAG, "sendResponse failed", e) }
                        }
                        try { gattServer?.cancelConnection(device) } catch (e: SecurityException) { /* ignore */ }
                    }
                    return
                }

                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
                    } catch (e: SecurityException) { Log.w(TAG, "sendResponse failed", e) }
                }
                if (value.isEmpty()) return
                val buffer = synchronized(serverLock) { serverIncoming.getOrPut(addr) { ByteArrayOutputStream() } }
                val flag = value[0]
                if (value.size > 1) synchronized(serverLock) { buffer.write(value, 1, value.size - 1) }
                if (flag == BleConstants.CHUNK_FLAG_LAST) {
                    val raw = synchronized(serverLock) {
                        serverIncoming.remove(addr)
                        buffer.toByteArray()
                    }
                    val remoteIdeas = try {
                        deserialize(raw)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse incoming sync payload - reporting failure instead of a silent empty merge", e)
                        scope.launch { activeServerOnSyncDone(false) }
                        return
                    }
                    scope.launch {
                        repository.mergeRemote(remoteIdeas)
                        val ourIdeas = repository.getAll()
                        sendToClient(device, ourIdeas)
                        activeServerOnSyncDone(true)
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
                    } catch (e: SecurityException) { Log.w(TAG, "sendResponse failed", e) }
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                val next = synchronized(serverLock) {
                    val queue = serverOutQueue[device.address]
                    if (queue.isNullOrEmpty()) null else queue.removeAt(0)
                }
                if (next != null) sendRawNotification(device, next)
            }
        }

        try {
            val server = bluetoothManager?.openGattServer(context, callback)
            gattServer = server
            if (server == null) {
                // openGattServer can return null (adapter off, registration refused by the stack, etc)
                // without throwing. Previously nothing handled this case, so the caller only ever found
                // out via DateIdeasScreen's own ~8s UI timeout instead of an immediate, accurate failure.
                Log.w(TAG, "openGattServer returned null - failing immediately instead of leaving the caller to time out")
                onSyncDone(false)
                return
            }
            server.addService(service)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to open GATT server", e)
            onSyncDone(false)
        }
    }

    private fun sendToClient(device: BluetoothDevice, ideas: List<DateIdea>) {
        val mtu = synchronized(serverLock) { deviceMtus[device.address] } ?: DEFAULT_ATT_MTU
        val chunkPayload = effectiveChunkPayload(mtu, BluetoothGatt.GATT_SUCCESS)
        val chunks = toChunks(serialize(ideas), chunkPayload).toMutableList()
        if (chunks.isEmpty()) return
        val first = chunks.removeAt(0)
        synchronized(serverLock) { serverOutQueue[device.address] = chunks }
        sendRawNotification(device, first)
    }

    private fun sendRawNotification(device: BluetoothDevice, chunk: ByteArray) {
        val characteristic = serverCharacteristic ?: return
        characteristic.value = chunk
        try {
            @Suppress("DEPRECATION")
            gattServer?.notifyCharacteristicChanged(device, characteristic, false)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to notify", e)
        }
    }

    fun stopServer() {
        try {
            gattServer?.close()
        } catch (e: SecurityException) { /* ignore */ }
        gattServer = null
        synchronized(serverLock) {
            serverIncoming.clear()
            serverOutQueue.clear()
            authenticatedDevices.clear()
            deviceMtus.clear()
        }
    }

    // ---- CLIENT side ----

    private var clientGatt: BluetoothGatt? = null
    private val clientIncoming = ByteArrayOutputStream()
    private var clientOutQueue: MutableList<ByteArray> = mutableListOf()
    private val clientLock = Any()
    private var clientChunkPayload = BleConstants.MAX_CHUNK_PAYLOAD

    fun connectAsClient(device: BluetoothDevice, handshakeToken: ByteArray, onSyncDone: (Boolean) -> Unit) {
        if (!BlePermissions.hasBlePermissions(context)) return onSyncDone(false)
        // A previous attempt that timed out or failed before its onConnectionStateChange(DISCONNECTED)
        // callback ever fired (e.g. the peer never responded at all) would otherwise leave its
        // BluetoothGatt handle registered with the stack forever once overwritten here - Android caps
        // the number of concurrent GATT client registrations per app, so repeated failed/timed-out
        // syncs would eventually exhaust that cap and start failing to connect at all.
        try {
            clientGatt?.disconnect()
            clientGatt?.close()
        } catch (e: SecurityException) { /* ignore */ }
        clientGatt = null
        clientIncoming.reset()
        // Both of these are mutated from BluetoothGattCallback methods, which run on binder threads (not
        // necessarily the same thread, and not guaranteed not to interleave) - just like
        // serverIncoming/serverOutQueue/authenticatedDevices/deviceMtus on the server side above, a
        // plain unguarded var here is a real race: two callback invocations landing close together could
        // both observe the pre-flip value and both proceed (e.g. both pass `if (completed) return`,
        // double-invoking the sync-completion path; or both pass the handshakeAcked check and each
        // independently kick off sending the local ideas list). Guarded by clientLock, the same lock
        // already used below for clientOutQueue.
        var handshakeAcked = false
        var completed = false

        // Guards against calling onSyncDone() twice (e.g. once from a failure path and again from the
        // disconnect that follows it) and makes sure a connection that drops before completing - GATT
        // error, the peer's server not being open, timeout, etc - actually resolves the caller's
        // "syncing..." state to a failure instead of leaving it hanging forever. onConnectionStateChange
        // previously just closed the gatt on disconnect without ever calling onSyncDone() at all.
        fun finish(success: Boolean) {
            val shouldInvoke = synchronized(clientLock) {
                if (completed) false else { completed = true; true }
            }
            if (shouldInvoke) onSyncDone(success)
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // IMPORTANT: BLE connections start at the default ATT MTU of 23 bytes (20 usable
                    // after the 3-byte header) until something negotiates higher. Our chunk protocol
                    // writes up to 1 + chunk-payload bytes per characteristic write - at the default MTU,
                    // Android *silently truncates* any write larger than the negotiated MTU instead of
                    // erroring, so the receiving side gets a corrupted mid-JSON fragment that fails to
                    // parse. Request a bigger MTU and only proceed to discoverServices() once that
                    // negotiation finishes (onMtuChanged fires either way, granted or not); the actual
                    // chunk size used is then derived from whatever MTU was really granted, never assumed.
                    try {
                        gatt.requestMtu(REQUESTED_MTU)
                    } catch (e: SecurityException) {
                        try { gatt.discoverServices() } catch (e2: SecurityException) { finish(false) }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finish(false)
                    try { gatt.close() } catch (e: SecurityException) { /* ignore */ }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                clientChunkPayload = effectiveChunkPayload(mtu, status)
                Log.d(TAG, "MTU negotiated: $mtu (status=$status) -> chunk payload $clientChunkPayload")
                try { gatt.discoverServices() } catch (e: SecurityException) { finish(false) }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(BleConstants.SYNC_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BleConstants.SYNC_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    finish(false)
                    return
                }
                try {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val cccd = characteristic.getDescriptor(BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID)
                    if (cccd != null) {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                } catch (e: SecurityException) {
                    finish(false)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                // Notifications are now enabled. Prove we know the shared pair secret before sending any
                // real data - the server ignores/rejects everything until it sees this exact token as
                // the first write (see startServer's handshake check).
                val service = gatt.getService(BleConstants.SYNC_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BleConstants.SYNC_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    finish(false)
                    return
                }
                try {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    characteristic.value = handshakeToken
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                } catch (e: SecurityException) {
                    finish(false)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Characteristic write failed with status $status")
                    finish(false)
                    return
                }
                val firstHandshakeAck = synchronized(clientLock) {
                    if (handshakeAcked) false else { handshakeAcked = true; true }
                }
                if (firstHandshakeAck) {
                    scope.launch {
                        val localIdeas = repository.getAll()
                        synchronized(clientLock) { clientOutQueue = toChunks(serialize(localIdeas), clientChunkPayload).toMutableList() }
                        sendNextClientChunk(gatt, characteristic)
                    }
                    return
                }
                sendNextClientChunk(gatt, characteristic)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value ?: return
                if (value.isEmpty()) return
                val flag = value[0]
                if (value.size > 1) clientIncoming.write(value, 1, value.size - 1)
                if (flag == BleConstants.CHUNK_FLAG_LAST) {
                    val raw = clientIncoming.toByteArray()
                    clientIncoming.reset()
                    val remoteIdeas = try {
                        deserialize(raw)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse incoming sync payload - reporting failure instead of a silent empty merge", e)
                        scope.launch {
                            finish(false)
                            try { gatt.disconnect() } catch (e2: SecurityException) { /* ignore */ }
                        }
                        return
                    }
                    scope.launch {
                        repository.mergeRemote(remoteIdeas)
                        finish(true)
                        try { gatt.disconnect() } catch (e: SecurityException) { /* ignore */ }
                    }
                }
            }
        }

        try {
            clientGatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to connect GATT", e)
            finish(false)
        }
    }

    private fun sendNextClientChunk(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val next = synchronized(clientLock) {
            if (clientOutQueue.isEmpty()) null else clientOutQueue.removeAt(0)
        } ?: return
        try {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = next
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to write characteristic", e)
        }
    }

    fun disconnectClient() {
        try {
            clientGatt?.disconnect()
            clientGatt?.close()
        } catch (e: SecurityException) { /* ignore */ }
        clientGatt = null
    }

    companion object {
        private const val TAG = "GattSyncManager"
        /** 1 flag byte + BleConstants.MAX_CHUNK_PAYLOAD (180) + a little headroom, plus the 3-byte ATT
         * header - comfortably covers our largest possible single chunk write, if granted. */
        private const val REQUESTED_MTU = 200
        private const val DEFAULT_ATT_MTU = 23
        private const val ATT_HEADER_BYTES = 3
        private const val CHUNK_FLAG_HEADER_BYTES = 1
        private const val MIN_CHUNK_PAYLOAD = 5
    }
}
