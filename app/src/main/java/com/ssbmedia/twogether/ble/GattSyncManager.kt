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
import com.ssbmedia.twogether.data.datastore.SettingsStore
import com.ssbmedia.twogether.data.db.DateIdea
import com.ssbmedia.twogether.data.db.Milestone
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.data.db.MomentNote
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.data.repo.DateIdeaRepository
import com.ssbmedia.twogether.data.repo.MilestoneRepository
import com.ssbmedia.twogether.data.repo.MomentNoteRepository
import com.ssbmedia.twogether.data.repo.MomentRepository
import com.ssbmedia.twogether.data.repo.SessionRepository
import com.ssbmedia.twogether.events.AppEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Tiny custom GATT protocol piggybacked on the proximity connection: exchange ONE combined JSON
 * envelope covering date ideas, closed together-sessions (Feature A), moment metadata + per-partner
 * moment notes (Feature D), and milestones (Feature F) as chunked bytes over one write+notify
 * characteristic, then merge each piece locally with its own table-appropriate strategy. One side acts
 * as GATT server (passive), the other as GATT client (initiates) - the caller decides the role via a
 * deterministic tie-break so both phones never both try the same role.
 *
 * Bundling everything into one envelope (rather than a separate GATT exchange per table) keeps the
 * connection/handshake/MTU-negotiation machinery below exactly as it was for the original date-ideas-only
 * version - only serialize()/deserialize() and what happens with the parsed result changed.
 *
 * Every connection must first prove it knows the shared pair secret (a short handshake token derived
 * from the pairing code, see BleConstants.HANDSHAKE_TOKEN_*) before any real chunk data is accepted -
 * without this, any nearby stranger's GATT client could connect to our open server and read back the
 * couple's data, or write a forged tombstone to delete a real item (merge is last-write-wins with no
 * origin check otherwise).
 *
 * FEATURE 2 (photo sync): once the metadata JSON round-trip above completes, the CLIENT side (the only
 * side that ever actively initiates anything, matching the existing central/peripheral role split) also
 * drives a second, photo-bytes phase over a SEPARATE characteristic (PHOTO_CHARACTERISTIC_UUID, same
 * service/connection/authentication). Every Moment's metadata now carries a `hasPhoto` flag (backed by
 * Moment.photoDownloaded) alongside syncId/photoUri/takenAt, so by the time the client has applied the
 * server's response it already knows, for every synced moment, both what IT has and what the SERVER has.
 * From that it computes two lists and, in one sitting:
 *   - pushes (write) the photos it has that the server is missing - this is how the passive server side
 *     ever "gets" photos, without ever needing to actively request anything itself;
 *   - sends a PHOTO_REQUEST listing the photos it wants FROM the server, then waits for the server to
 *     notify back PHOTO_DATA frames (capped/newest-first, see MAX_PHOTOS_PER_DIRECTION_PER_SESSION) and a
 *     trailing PHOTO_DONE marker.
 * This means the SAME per-session budget cap and priority order apply symmetrically in both directions
 * without the passive server ever having to initiate a connection or a write of its own - it only ever
 * responds to what arrives on its own characteristic. See runPhotoPhaseAsClient/respondToPhotoRequest.
 *
 * SIMPLIFICATION (documented per the task spec): interrupted transfers restart cleanly rather than doing
 * true byte-level resume. A photo's bytes are only ever written to their final destination file after the
 * ENTIRE frame has been reassembled in memory (mirroring how the JSON envelope is already reassembled
 * before being parsed) and even then via a temp-file-then-rename - so a connection drop mid-transfer
 * (Bluetooth toggled off, apart transition, app killed) can never leave a partially-written or corrupted
 * file at the real path; the moment's photoDownloaded simply stays false, and the very next together-
 * session's computeToRequest/computeToSend naturally retries that whole photo from scratch. True
 * byte-level resume (persisting a partial offset across disconnects and re-synchronizing with the peer's
 * send position) would add meaningfully more protocol complexity for benefit that's marginal given photos
 * are capped per session anyway - so it was left out of scope here in favor of "just try this photo again
 * next time", which is simple, provably correct, and good enough for a background catch-up queue.
 */
class GattSyncManager(
    private val context: Context,
    private val dateIdeaRepository: DateIdeaRepository,
    private val sessionRepository: SessionRepository,
    private val momentRepository: MomentRepository,
    private val momentNoteRepository: MomentNoteRepository,
    private val milestoneRepository: MilestoneRepository,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope
) {
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    // ---- shared chunk protocol helpers ----

    /** Gathers everything this device has to offer into one combined JSON payload:
     *  - dateIdeas: the full local list (unchanged from before - DateIdeaRepository.mergeRemote is
     *    already a full last-write-wins merge, so sending the whole list every time is correct and cheap).
     *  - sessions: CLOSED sessions only (endedAt != null). Feature A deliberately never sends an open
     *    session - an in-progress session copied onto the partner's device as "still open" would let two
     *    devices each show a different "currently open" row; each device's own open session closes
     *    naturally through its own normal proximity logic instead.
     *  - moments: metadata + Feature 2's hasPhoto flag (== Moment.photoDownloaded on THIS device) so the
     *    partner can both reference/annotate a moment it doesn't have the photo bytes for, AND know
     *    whether it's worth requesting the bytes from us this session.
     *  - notes: only THIS device's own notes (authorDeviceId == our id) - the receiving side treats
     *    every row here as "the partner's", never re-merges its own notes back onto itself.
     *  - milestones: the full local list (same full-list LWW-merge shape as dateIdeas).
     */
    private suspend fun buildPayload(): ByteArray {
        val deviceId = settingsStore.getOrCreateLocalDeviceId()
        val obj = JSONObject()
        obj.put("dateIdeas", serializeDateIdeas(dateIdeaRepository.getAll()))
        obj.put("sessions", serializeSessions(sessionRepository.getAll().filter { it.endedAt != null }))
        obj.put("moments", serializeMoments(momentRepository.getAll()))
        obj.put("notes", serializeNotes(momentNoteRepository.getAllForAuthor(deviceId)))
        obj.put("milestones", serializeMilestones(milestoneRepository.getAll()))
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    /** Applies a received combined payload: merges each table with its own strategy (see each
     * repository's mergeRemote / mergeRemoteSessions / mergeRemoteStubs doc for why they differ). Returns
     * the sender's per-moment syncId/hasPhoto/takenAt snapshot (Feature 2) so the caller can decide what
     * photo bytes to push/request next, without re-parsing the raw bytes a second time. */
    private suspend fun applyPayload(bytes: ByteArray): List<RemoteMomentInfo> {
        val deviceId = settingsStore.getOrCreateLocalDeviceId()
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        dateIdeaRepository.mergeRemote(deserializeDateIdeas(root.optJSONArray("dateIdeas")))
        sessionRepository.mergeRemoteSessions(deserializeSessions(root.optJSONArray("sessions")))
        val momentsArr = root.optJSONArray("moments")
        momentRepository.mergeRemoteStubs(deserializeMoments(momentsArr))
        momentNoteRepository.mergeRemote(deserializeNotes(root.optJSONArray("notes")), deviceId)
        milestoneRepository.mergeRemote(deserializeMilestones(root.optJSONArray("milestones")))
        return parseRemoteMomentInfo(momentsArr)
    }

    private fun serializeDateIdeas(ideas: List<DateIdea>): JSONArray {
        val arr = JSONArray()
        for (idea in ideas) {
            arr.put(JSONObject().apply {
                put("id", idea.id)
                put("text", idea.text)
                put("category", idea.category ?: JSONObject.NULL)
                put("done", idea.done)
                put("updatedAt", idea.updatedAt)
                put("deleted", idea.deleted)
            })
        }
        return arr
    }

    private fun deserializeDateIdeas(arr: JSONArray?): List<DateIdea> {
        if (arr == null) return emptyList()
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

    private fun serializeSessions(sessions: List<TogetherSession>): JSONArray {
        val arr = JSONArray()
        for (s in sessions) {
            arr.put(JSONObject().apply {
                put("syncId", s.syncId)
                put("startedAt", s.startedAt)
                put("endedAt", s.endedAt ?: JSONObject.NULL)
                put("isManual", s.isManual)
            })
        }
        return arr
    }

    private fun deserializeSessions(arr: JSONArray?): List<TogetherSession> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val syncId = o.optString("syncId", "")
            if (syncId.isBlank() || o.isNull("endedAt")) return@mapNotNull null
            TogetherSession(
                startedAt = o.getLong("startedAt"),
                endedAt = o.getLong("endedAt"),
                isManual = o.optBoolean("isManual", false),
                syncId = syncId
            )
        }
    }

    private fun serializeMoments(moments: List<Moment>): JSONArray {
        val arr = JSONArray()
        for (m in moments) {
            arr.put(JSONObject().apply {
                put("syncId", m.syncId)
                put("photoUri", m.photoUri)
                put("takenAt", m.takenAt)
                // Feature 2: lets the receiver know whether WE actually hold the photo bytes, so it can
                // decide whether to request them from us this session - see this class's top-of-file doc.
                put("hasPhoto", m.photoDownloaded)
            })
        }
        return arr
    }

    /** SECURITY: `photoUri` here is UNTRUSTED wire data from the network peer and must NEVER be used as a
     * local filesystem path - it's carried through only so MomentRepository.mergeRemoteStubs can pull a
     * best-effort, allowlist-sanitized file-EXTENSION hint out of it (see that function's doc for the
     * full story of why, and MAX_PHOTO_FRAME_BYTES-style reasoning). The actual local photoUri column is
     * always overwritten by mergeRemoteStubs with a path deterministically derived from [syncId] before
     * this Moment is ever inserted into Room. */
    private fun deserializeMoments(arr: JSONArray?): List<Moment> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val syncId = o.optString("syncId", "")
            if (syncId.isBlank()) return@mapNotNull null
            Moment(
                photoUri = o.optString("photoUri", ""),
                takenAt = o.getLong("takenAt"),
                syncId = syncId,
                isRemote = true
            )
        }
    }

    /** Feature 2: the sender's own syncId/hasPhoto/takenAt snapshot, parsed alongside (but independently
     * of) deserializeMoments - used only to decide what photo bytes to push/request next, never stored. */
    private data class RemoteMomentInfo(val syncId: String, val hasPhoto: Boolean, val takenAt: Long)

    private fun parseRemoteMomentInfo(arr: JSONArray?): List<RemoteMomentInfo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val syncId = o.optString("syncId", "")
            if (syncId.isBlank()) return@mapNotNull null
            RemoteMomentInfo(syncId, o.optBoolean("hasPhoto", false), o.optLong("takenAt", 0L))
        }
    }

    private fun serializeNotes(notes: List<MomentNote>): JSONArray {
        val arr = JSONArray()
        for (n in notes) {
            arr.put(JSONObject().apply {
                put("momentSyncId", n.momentSyncId)
                put("authorDeviceId", n.authorDeviceId)
                put("text", n.text)
                put("updatedAt", n.updatedAt)
                put("deleted", n.deleted)
            })
        }
        return arr
    }

    private fun deserializeNotes(arr: JSONArray?): List<MomentNote> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MomentNote(
                momentSyncId = o.getString("momentSyncId"),
                authorDeviceId = o.getString("authorDeviceId"),
                text = o.optString("text", ""),
                updatedAt = o.getLong("updatedAt"),
                deleted = o.optBoolean("deleted", false)
            )
        }
    }

    private fun serializeMilestones(milestones: List<Milestone>): JSONArray {
        val arr = JSONArray()
        for (m in milestones) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("label", m.label)
                put("month", m.month)
                put("day", m.day)
                put("year", m.year ?: JSONObject.NULL)
                put("createdAt", m.createdAt)
                put("updatedAt", m.updatedAt)
                put("deleted", m.deleted)
            })
        }
        return arr
    }

    private fun deserializeMilestones(arr: JSONArray?): List<Milestone> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Milestone(
                id = o.getString("id"),
                label = o.getString("label"),
                month = o.getInt("month"),
                day = o.getInt("day"),
                year = if (o.isNull("year")) null else o.getInt("year"),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.getLong("updatedAt"),
                deleted = o.optBoolean("deleted", false)
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
     * [maxPayload] (BleConstants.MAX_CHUNK_PAYLOAD for JSON, MAX_CHUNK_PAYLOAD_PHOTO for photo bytes) and
     * never assuming more than the default 23-byte MTU unless the negotiation actually reported success
     * with a larger value. */
    private fun effectiveChunkPayload(mtu: Int, status: Int, maxPayload: Int = BleConstants.MAX_CHUNK_PAYLOAD): Int {
        val safeMtu = if (status == BluetoothGatt.GATT_SUCCESS && mtu > DEFAULT_ATT_MTU) mtu else DEFAULT_ATT_MTU
        val usable = safeMtu - ATT_HEADER_BYTES - CHUNK_FLAG_HEADER_BYTES
        return usable.coerceIn(MIN_CHUNK_PAYLOAD, maxPayload)
    }

    // ---- Feature 2: photo wire-frame helpers (shared by client + server) ----

    private fun buildPhotoDataFrame(syncId: String, bytes: ByteArray): ByteArray {
        val idBytes = syncId.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + idBytes.size + bytes.size)
        out[0] = BleConstants.PHOTO_FRAME_TYPE_DATA
        out[1] = idBytes.size.toByte()
        idBytes.copyInto(out, 2)
        bytes.copyInto(out, 2 + idBytes.size)
        return out
    }

    private fun parsePhotoDataFrame(frame: ByteArray): Pair<String, ByteArray>? {
        if (frame.size < 2) return null
        val idLen = frame[1].toInt() and 0xFF
        if (frame.size < 2 + idLen) return null
        val syncId = String(frame, 2, idLen, Charsets.UTF_8)
        val bytes = frame.copyOfRange(2 + idLen, frame.size)
        return syncId to bytes
    }

    private fun buildPhotoRequestFrame(syncIds: List<String>): ByteArray {
        val json = JSONObject().apply { put("want", JSONArray(syncIds)) }.toString().toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + json.size)
        out[0] = BleConstants.PHOTO_FRAME_TYPE_REQUEST
        json.copyInto(out, 1)
        return out
    }

    private fun parsePhotoRequestFrame(frame: ByteArray): List<String> {
        if (frame.size <= 1) return emptyList()
        return try {
            val json = String(frame, 1, frame.size - 1, Charsets.UTF_8)
            val arr = JSONObject(json).optJSONArray("want") ?: return emptyList()
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildPhotoDoneFrame(): ByteArray = byteArrayOf(BleConstants.PHOTO_FRAME_TYPE_DONE)

    /** Feature 2: newest-first, capped candidate lists - see this class's top-of-file doc for why newest
     * photos are prioritized (recent moments matter most immediately for a couple checking in on each
     * other) and why a per-session cap exists at all (a big backlog must never hog the connection or
     * block the metadata/date-ideas sync that also needs to happen - it just continues catching up over
     * however many future together-sessions it takes). */
    private fun computeToSend(local: List<Moment>, remote: List<RemoteMomentInfo>, cap: Int): List<Moment> {
        val remoteLacks = remote.filter { !it.hasPhoto }.map { it.syncId }.toSet()
        return local.asSequence()
            .filter { it.photoDownloaded && it.syncId in remoteLacks && File(it.photoUri).isFile }
            .sortedByDescending { it.takenAt }
            .take(cap)
            .toList()
    }

    private fun computeToRequestIds(local: List<Moment>, remote: List<RemoteMomentInfo>, cap: Int): List<String> {
        val remoteHas = remote.filter { it.hasPhoto }.associateBy { it.syncId }
        return local.asSequence()
            .filter { !it.photoDownloaded && remoteHas.containsKey(it.syncId) }
            .sortedByDescending { it.takenAt }
            .take(cap)
            .map { it.syncId }
            .toList()
    }

    /** Feature 2: writes received photo bytes to a temp file first, only renaming into place at the
     * Moment's real photoUri once the write fully succeeds - so a crash/low-storage failure partway
     * through the DISK WRITE itself (the frame is already fully reassembled in memory by this point, see
     * this class's top-of-file "SIMPLIFICATION" doc) can never leave a broken/partial file sitting at the
     * path the UI renders from. No-ops safely if we don't recognize the syncId (race with a local
     * deletion, or a malformed/malicious frame from an already-authenticated peer) or already have it. */
    private suspend fun savePhotoBytes(syncId: String, bytes: ByteArray) {
        val moment = momentRepository.getBySyncId(syncId) ?: return
        // Always clear any "receiving…" UI marker for this id, even if we're about to no-op below (e.g.
        // a duplicate delivery) - otherwise a stray marker could linger forever.
        AppEvents.setMomentsTransferring(AppEvents.momentsTransferring.value - syncId)
        if (moment.photoDownloaded) return
        val destFile = File(moment.photoUri)

        // BLOCKER fix, defense-in-depth layer: moment.photoUri is ALWAYS derived from syncId now (never
        // the wire value - see MomentRepository.mergeRemoteStubs's doc, which is where this is actually
        // guaranteed), but this hard assertion is what stops a future regression anywhere upstream from
        // ever turning back into an arbitrary-file-write - refuse to write anywhere outside this app's
        // own filesDir/moments/ directory, checked via the CANONICAL path (not a naive string prefix, so
        // ".." components can't fool it).
        val momentsDirCanonical = try {
            File(context.filesDir, "moments").canonicalFile
        } catch (e: Exception) {
            Log.w(TAG, "Refusing to save photo for moment $syncId - couldn't canonicalize moments dir", e)
            return
        }
        val destCanonical = try {
            destFile.canonicalFile
        } catch (e: Exception) {
            Log.w(TAG, "Refusing to save photo for moment $syncId - couldn't canonicalize destination path", e)
            return
        }
        if (destCanonical.parentFile != momentsDirCanonical) {
            Log.w(TAG, "Refusing to save photo for moment $syncId - resolved path $destCanonical is outside $momentsDirCanonical")
            return
        }

        val tempFile = File(destFile.parentFile ?: context.filesDir, "${destFile.name}.part")
        try {
            // MAJOR fix: this file I/O previously ran on whatever dispatcher GattSyncManager was
            // constructed with (ProximityForegroundService passes lifecycleScope, i.e.
            // Dispatchers.Main.immediate) - risking jank/ANR. GalleryImportFlow/BackupManager already
            // correctly use Dispatchers.IO for their own file work; this matches that.
            withContext(Dispatchers.IO) {
                destFile.parentFile?.mkdirs()
                tempFile.outputStream().use { it.write(bytes) }
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
            }
            momentRepository.markPhotoDownloaded(syncId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save received photo for moment $syncId", e)
            withContext(Dispatchers.IO) { tempFile.delete() }
        }
    }

    /** Feature 2: dispatches one fully-reassembled photo-characteristic frame, shared by both the server
     * (which only ever sees DATA pushes and REQUESTs) and the client (which only ever sees DATA pushes
     * and the DONE marker in response to its own REQUEST) - see this class's top-of-file doc for the
     * overall push/pull shape. */
    private suspend fun processPhotoFrame(frame: ByteArray, isServerSide: Boolean, device: BluetoothDevice?) {
        if (frame.isEmpty()) return
        when (frame[0]) {
            BleConstants.PHOTO_FRAME_TYPE_DATA -> {
                val (syncId, bytes) = parsePhotoDataFrame(frame) ?: return
                savePhotoBytes(syncId, bytes)
                if (!isServerSide) {
                    clientPhotoReceivedCount++
                    if (clientPhotoReceivedCount >= clientPhotoExpectedCount) photoDoneSignal?.complete(Unit)
                }
            }
            BleConstants.PHOTO_FRAME_TYPE_REQUEST -> {
                if (isServerSide && device != null) respondToPhotoRequest(device, frame)
            }
            BleConstants.PHOTO_FRAME_TYPE_DONE -> {
                if (!isServerSide) photoDoneSignal?.complete(Unit)
            }
        }
    }

    // ---- SERVER side ----

    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    private var serverPhotoCharacteristic: BluetoothGattCharacteristic? = null
    private val serverIncoming = HashMap<String, ByteArrayOutputStream>()
    private val serverOutQueue = HashMap<String, MutableList<ByteArray>>()
    // Feature 2: same shape as serverIncoming/serverOutQueue above, but for the photo characteristic -
    // kept as separate maps (rather than reusing the JSON ones) so a device mid-photo-transfer can never
    // be confused with a device mid-JSON-sync, even though in practice the two never overlap in time for
    // a single connection (the photo phase only ever starts after the JSON round trip is fully done).
    private val serverPhotoIncoming = HashMap<String, ByteArrayOutputStream>()
    private val serverPhotoOutQueue = HashMap<String, MutableList<ByteArray>>()
    private val authenticatedDevices = HashSet<String>()
    private val deviceMtus = HashMap<String, Int>()
    // Guards serverIncoming / serverOutQueue / serverPhotoIncoming / serverPhotoOutQueue /
    // authenticatedDevices / deviceMtus, which are otherwise mutated both from GATT binder-thread
    // callbacks and from coroutines launched via [scope].
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

        // Feature 2: second characteristic, same service, dedicated to photo bytes - see this class's
        // top-of-file doc for the overall protocol shape.
        val photoCharacteristic = BluetoothGattCharacteristic(
            BleConstants.PHOTO_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val photoCccd = BluetoothGattDescriptor(
            BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        photoCharacteristic.addDescriptor(photoCccd)
        serverPhotoCharacteristic = photoCharacteristic

        val service = BluetoothGattService(BleConstants.SYNC_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        service.addCharacteristic(photoCharacteristic)

        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val addr = device.address
                    synchronized(serverLock) {
                        authenticatedDevices.remove(addr)
                        serverIncoming.remove(addr)
                        serverOutQueue.remove(addr)
                        serverPhotoIncoming.remove(addr)
                        serverPhotoOutQueue.remove(addr)
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
                    // we accept or act on anything else it sends (on EITHER characteristic - the client
                    // always sends the handshake on the sync characteristic first, see connectAsClient).
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

                if (characteristic.uuid == BleConstants.PHOTO_CHARACTERISTIC_UUID) {
                    handleServerPhotoChunk(device, value)
                    return
                }

                val buffer = synchronized(serverLock) { serverIncoming.getOrPut(addr) { ByteArrayOutputStream() } }
                val flag = value[0]
                if (value.size > 1) synchronized(serverLock) { buffer.write(value, 1, value.size - 1) }
                if (flag == BleConstants.CHUNK_FLAG_LAST) {
                    val raw = synchronized(serverLock) {
                        serverIncoming.remove(addr)
                        buffer.toByteArray()
                    }
                    scope.launch {
                        try {
                            applyPayload(raw)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse incoming sync payload - reporting failure instead of a silent empty merge", e)
                            activeServerOnSyncDone(false)
                            return@launch
                        }
                        val payload = buildPayload()
                        sendToClient(device, payload)
                        activeServerOnSyncDone(true)
                        // Feature 2: the server never needs to know what IT wants from the client here -
                        // the client already deduced that itself from this same response payload and will
                        // push it unprompted (see runPhotoPhaseAsClient). The server's only remaining job
                        // is to react to whatever arrives next on the photo characteristic (handled above).
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
                val addr = device.address
                // Photo-queue chunks take priority if any are pending - in practice the two queues never
                // have items at the same time for one connection (see the class doc), but checking photo
                // first is harmless either way and keeps a photo response from ever waiting behind a
                // leftover JSON chunk.
                val nextPhoto = synchronized(serverLock) {
                    val q = serverPhotoOutQueue[addr]
                    if (!q.isNullOrEmpty()) q.removeAt(0) else null
                }
                if (nextPhoto != null) {
                    sendRawPhotoNotification(device, nextPhoto)
                    return
                }
                val nextJson = synchronized(serverLock) {
                    val q = serverOutQueue[addr]
                    if (!q.isNullOrEmpty()) q.removeAt(0) else null
                }
                if (nextJson != null) sendRawNotification(device, nextJson)
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

    private fun sendToClient(device: BluetoothDevice, payload: ByteArray) {
        val mtu = synchronized(serverLock) { deviceMtus[device.address] } ?: DEFAULT_ATT_MTU
        val chunkPayload = effectiveChunkPayload(mtu, BluetoothGatt.GATT_SUCCESS)
        val chunks = toChunks(payload, chunkPayload).toMutableList()
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

    /** Feature 2: reassembles chunks arriving on the photo characteristic exactly like the JSON path
     * does (flag byte + payload, LAST marks a complete frame) - but, unlike JSON, a single connection can
     * carry several frames back-to-back (the client's pushes followed by its request), so this keeps
     * accumulating fresh frames after each LAST rather than treating the whole exchange as one-shot. */
    private fun handleServerPhotoChunk(device: BluetoothDevice, value: ByteArray) {
        val addr = device.address
        val flag = value[0]
        if (value.size > 1) {
            val buffer = synchronized(serverLock) { serverPhotoIncoming.getOrPut(addr) { ByteArrayOutputStream() } }
            synchronized(serverLock) {
                if (buffer.size() + value.size - 1 > MAX_PHOTO_FRAME_BYTES) {
                    Log.w(TAG, "Incoming photo frame from $addr exceeded sanity cap - dropping")
                    buffer.reset()
                } else {
                    buffer.write(value, 1, value.size - 1)
                }
            }
        }
        if (flag == BleConstants.CHUNK_FLAG_LAST) {
            val frame = synchronized(serverLock) {
                val buf = serverPhotoIncoming.remove(addr) ?: ByteArrayOutputStream()
                buf.toByteArray()
            }
            scope.launch {
                try {
                    processPhotoFrame(frame, isServerSide = true, device = device)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to process incoming photo frame", e)
                }
            }
        }
    }

    /** Feature 2: server's reactive half of a PHOTO_REQUEST - looks up each wanted syncId in its OWN
     * local Moment table (never trusts a path from the wire), and pushes back whatever it can actually
     * provide, capped the same way the client caps its own requests, plus a trailing DONE marker so the
     * client knows serving is complete (rather than needing to infer it purely from a received count,
     * which would hang forever if the server silently couldn't provide something it was asked for). */
    private suspend fun respondToPhotoRequest(device: BluetoothDevice, frame: ByteArray) {
        val wantIds = parsePhotoRequestFrame(frame)
        val framesToSend = mutableListOf<ByteArray>()
        for (id in wantIds.take(MAX_PHOTOS_PER_DIRECTION_PER_SESSION)) {
            val moment = momentRepository.getBySyncId(id) ?: continue
            if (!moment.photoDownloaded) continue
            val file = File(moment.photoUri)
            if (!file.isFile) continue
            // MAJOR fix: file reads inherit this class's dispatcher (Dispatchers.Main.immediate via
            // ProximityForegroundService's lifecycleScope) unless explicitly moved off it - see
            // savePhotoBytes's doc.
            val bytes = try { withContext(Dispatchers.IO) { file.readBytes() } } catch (e: Exception) { continue }
            framesToSend += buildPhotoDataFrame(id, bytes)
        }
        framesToSend += buildPhotoDoneFrame()

        val mtu = synchronized(serverLock) { deviceMtus[device.address] } ?: DEFAULT_ATT_MTU
        val chunkPayload = effectiveChunkPayload(mtu, BluetoothGatt.GATT_SUCCESS, BleConstants.MAX_CHUNK_PAYLOAD_PHOTO)
        val allChunks = framesToSend.flatMap { toChunks(it, chunkPayload) }.toMutableList()
        if (allChunks.isEmpty()) return
        val first = allChunks.removeAt(0)
        synchronized(serverLock) { serverPhotoOutQueue[device.address] = allChunks }
        sendRawPhotoNotification(device, first)
    }

    private fun sendRawPhotoNotification(device: BluetoothDevice, chunk: ByteArray) {
        val characteristic = serverPhotoCharacteristic ?: return
        characteristic.value = chunk
        try {
            @Suppress("DEPRECATION")
            gattServer?.notifyCharacteristicChanged(device, characteristic, false)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to notify (photo)", e)
        }
    }

    fun stopServer() {
        try {
            gattServer?.close()
        } catch (e: SecurityException) { /* ignore */ }
        gattServer = null
        serverPhotoCharacteristic = null
        synchronized(serverLock) {
            serverIncoming.clear()
            serverOutQueue.clear()
            serverPhotoIncoming.clear()
            serverPhotoOutQueue.clear()
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

    // Feature 2: client-side photo phase state. All reset fresh at the top of connectAsClient() (a new
    // connection attempt must never see leftovers from a previous one), and cleared again on disconnect.
    private val clientPhotoIncoming = ByteArrayOutputStream()
    private var clientPhotoOutQueue: MutableList<ByteArray> = mutableListOf()
    private var clientPhotoChunkPayload = BleConstants.MAX_CHUNK_PAYLOAD_PHOTO
    private var clientPhotoSendComplete: CompletableDeferred<Unit>? = null
    private var photoDoneSignal: CompletableDeferred<Unit>? = null
    private var clientPhotoReceivedCount = 0
    private var clientPhotoExpectedCount = 0

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
        clientPhotoIncoming.reset()
        synchronized(clientLock) { clientPhotoOutQueue = mutableListOf() }
        clientPhotoSendComplete = null
        photoDoneSignal = null
        clientPhotoReceivedCount = 0
        clientPhotoExpectedCount = 0
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
        // Set once both the sync AND photo characteristics have enabled notifications, so the handshake
        // (the actual start of real data exchange) never races ahead of either subscription being ready.
        var syncCccdDone = false

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
                    // Feature 2: unblock anything still awaiting a photo-phase signal so that coroutine
                    // can proceed to its own cleanup promptly instead of sitting until
                    // PHOTO_PHASE_TIMEOUT_MILLIS elapses for no reason - CompletableDeferred.complete()
                    // is a safe no-op if already completed.
                    synchronized(clientLock) { clientPhotoOutQueue.clear() }
                    clientPhotoIncoming.reset()
                    clientPhotoSendComplete?.complete(Unit)
                    photoDoneSignal?.complete(Unit)
                    finish(false)
                    try { gatt.close() } catch (e: SecurityException) { /* ignore */ }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                clientChunkPayload = effectiveChunkPayload(mtu, status)
                clientPhotoChunkPayload = effectiveChunkPayload(mtu, status, BleConstants.MAX_CHUNK_PAYLOAD_PHOTO)
                Log.d(TAG, "MTU negotiated: $mtu (status=$status) -> chunk payload $clientChunkPayload (photo $clientPhotoChunkPayload)")
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
                val service = gatt.getService(BleConstants.SYNC_SERVICE_UUID)
                if (!syncCccdDone && descriptor.characteristic.uuid == BleConstants.SYNC_CHARACTERISTIC_UUID) {
                    // Feature 2: the sync characteristic's own notification subscription is ready - now
                    // enable the photo characteristic's too, BEFORE sending the handshake, so the server
                    // is never in a position where it could (in theory) notify the photo characteristic
                    // before this side has subscribed to it.
                    syncCccdDone = true
                    val photoCharacteristic = service?.getCharacteristic(BleConstants.PHOTO_CHARACTERISTIC_UUID)
                    val photoCccd = photoCharacteristic?.getDescriptor(BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID)
                    if (photoCharacteristic == null || photoCccd == null) {
                        finish(false)
                        return
                    }
                    try {
                        gatt.setCharacteristicNotification(photoCharacteristic, true)
                        @Suppress("DEPRECATION")
                        photoCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(photoCccd)
                    } catch (e: SecurityException) {
                        finish(false)
                    }
                    return
                }

                // Both characteristics now have notifications enabled - prove we know the shared pair
                // secret before sending any real data. The server ignores/rejects everything until it
                // sees this exact token as the first write (see startServer's handshake check).
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
                if (characteristic.uuid == BleConstants.PHOTO_CHARACTERISTIC_UUID) {
                    if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Photo characteristic write failed with status $status")
                        clientPhotoSendComplete?.complete(Unit)
                        return
                    }
                    sendNextClientPhotoChunk(gatt, characteristic)
                    return
                }

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
                        val payload = buildPayload()
                        synchronized(clientLock) { clientOutQueue = toChunks(payload, clientChunkPayload).toMutableList() }
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

                if (characteristic.uuid == BleConstants.PHOTO_CHARACTERISTIC_UUID) {
                    val flag = value[0]
                    if (value.size > 1) {
                        // MINOR fix: mirror the server-side serverPhotoIncoming cap (see
                        // handleServerPhotoChunk) - without this, a malformed/malicious peer that never
                        // sends the LAST flag could grow this buffer unbounded.
                        if (clientPhotoIncoming.size() + value.size - 1 > MAX_PHOTO_FRAME_BYTES) {
                            Log.w(TAG, "Incoming photo frame from server exceeded sanity cap - dropping")
                            clientPhotoIncoming.reset()
                        } else {
                            clientPhotoIncoming.write(value, 1, value.size - 1)
                        }
                    }
                    if (flag == BleConstants.CHUNK_FLAG_LAST) {
                        val frame = clientPhotoIncoming.toByteArray()
                        clientPhotoIncoming.reset()
                        scope.launch {
                            try {
                                processPhotoFrame(frame, isServerSide = false, device = null)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to process incoming photo frame", e)
                            }
                        }
                    }
                    return
                }

                val flag = value[0]
                if (value.size > 1) clientIncoming.write(value, 1, value.size - 1)
                if (flag == BleConstants.CHUNK_FLAG_LAST) {
                    val raw = clientIncoming.toByteArray()
                    clientIncoming.reset()
                    scope.launch {
                        val remoteMomentInfo = try {
                            applyPayload(raw)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse incoming sync payload - reporting failure instead of a silent empty merge", e)
                            finish(false)
                            try { gatt.disconnect() } catch (e2: SecurityException) { /* ignore */ }
                            return@launch
                        }
                        // Feature 2: metadata sync succeeded - now attempt the photo phase over this same
                        // authenticated connection before disconnecting. A failure/timeout here must never
                        // undo the metadata sync that already genuinely succeeded above.
                        try {
                            runPhotoPhaseAsClient(gatt, remoteMomentInfo)
                        } catch (e: Exception) {
                            Log.w(TAG, "Photo phase failed - metadata sync itself already succeeded", e)
                        }
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

    private fun sendNextClientPhotoChunk(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val next = synchronized(clientLock) {
            if (clientPhotoOutQueue.isEmpty()) null else clientPhotoOutQueue.removeAt(0)
        }
        if (next == null) {
            clientPhotoSendComplete?.complete(Unit)
            return
        }
        try {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = next
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to write photo characteristic", e)
            clientPhotoSendComplete?.complete(Unit)
        }
    }

    /**
     * Feature 2: the client's active half of the photo phase - see this class's top-of-file doc for the
     * overall push/pull shape. Runs only after the metadata JSON round trip has already fully succeeded.
     * Time-boxed by [PHOTO_PHASE_TIMEOUT_MILLIS] so one huge photo, or a stalled/half-dead connection,
     * can never hang the sync indefinitely - if the timeout fires mid-transfer, whatever is in flight is
     * simply abandoned (no partial file is ever written to a real path - see savePhotoBytes) and the next
     * together-session's own computeToSend/computeToRequestIds naturally retries it.
     */
    private suspend fun runPhotoPhaseAsClient(gatt: BluetoothGatt, remoteMoments: List<RemoteMomentInfo>) {
        val service = gatt.getService(BleConstants.SYNC_SERVICE_UUID) ?: return
        val photoCharacteristic = service.getCharacteristic(BleConstants.PHOTO_CHARACTERISTIC_UUID) ?: return

        val localMoments = momentRepository.getAll()
        val toSend = computeToSend(localMoments, remoteMoments, MAX_PHOTOS_PER_DIRECTION_PER_SESSION)
        val toRequestIds = computeToRequestIds(localMoments, remoteMoments, MAX_PHOTOS_PER_DIRECTION_PER_SESSION)
        if (toSend.isEmpty() && toRequestIds.isEmpty()) return

        withTimeoutOrNull(PHOTO_PHASE_TIMEOUT_MILLIS) {
            if (toRequestIds.isNotEmpty()) AppEvents.setMomentsTransferring(toRequestIds.toSet())

            // MINOR fix (E): arm the DONE/received-count signal BEFORE any frames are sent, not right
            // before done.await() further down. Pushes (our writes) and the server's response
            // (notifications back to us) are two independent GATT-level channels that can interleave -
            // the server can start responding to our PHOTO_REQUEST (and even finish, notifying its own
            // DONE marker) while we're still mid-flight sending our own push frames/the request itself.
            // Arming late used to mean an early DONE/DATA frame arriving before this was set just got
            // silently dropped (processPhotoFrame's `photoDoneSignal?.complete` is a safe no-op on a null
            // reference), stalling the client for the full PHOTO_PHASE_TIMEOUT_MILLIS instead of
            // completing promptly. Completing a CompletableDeferred before anyone awaits it is fine -
            // done.await() below just returns immediately in that case.
            val done = if (toRequestIds.isNotEmpty()) CompletableDeferred<Unit>() else null
            photoDoneSignal = done
            clientPhotoExpectedCount = toRequestIds.size
            clientPhotoReceivedCount = 0

            val frames = mutableListOf<ByteArray>()
            for (moment in toSend) {
                // MAJOR fix: keep this file read off the Main dispatcher - see savePhotoBytes's doc.
                val bytes = try { withContext(Dispatchers.IO) { File(moment.photoUri).readBytes() } } catch (e: Exception) { null } ?: continue
                frames += buildPhotoDataFrame(moment.syncId, bytes)
            }
            if (toRequestIds.isNotEmpty()) frames += buildPhotoRequestFrame(toRequestIds)

            if (frames.isNotEmpty()) {
                val sendComplete = CompletableDeferred<Unit>()
                clientPhotoSendComplete = sendComplete
                val allChunks = frames.flatMap { toChunks(it, clientPhotoChunkPayload) }
                synchronized(clientLock) { clientPhotoOutQueue = allChunks.toMutableList() }
                sendNextClientPhotoChunk(gatt, photoCharacteristic)
                sendComplete.await()
            }

            done?.await()
        }

        // Whether we finished cleanly, timed out, or the connection dropped mid-phase - always clear any
        // leftover "receiving…" markers for ids we asked for this round, so the UI never shows a stuck
        // spinner for a photo that's actually just going to be retried next together-session.
        if (toRequestIds.isNotEmpty()) {
            AppEvents.setMomentsTransferring(AppEvents.momentsTransferring.value - toRequestIds.toSet())
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
        /** 1 flag byte + BleConstants.MAX_CHUNK_PAYLOAD_PHOTO (500) + a little headroom, plus the 3-byte
         * ATT header. Requesting the larger of the two ceilings we might use on this connection is safe
         * even for the JSON-only case (JSON chunk size stays capped independently at MAX_CHUNK_PAYLOAD
         * regardless of how big an MTU was actually granted) and meaningfully speeds up photo transfers,
         * which is the entire reason a bigger MTU is worth asking for at all. */
        private const val REQUESTED_MTU = 517
        private const val DEFAULT_ATT_MTU = 23
        private const val ATT_HEADER_BYTES = 3
        private const val CHUNK_FLAG_HEADER_BYTES = 1
        private const val MIN_CHUNK_PAYLOAD = 5

        /** Feature 2: how many photos this device will push AND how many it will request, PER DIRECTION,
         * per together-session - see computeToSend/computeToRequestIds and this class's top-of-file doc
         * for why a cap exists and why newest-first. Deliberately small: this is a background catch-up
         * queue, not a one-shot bulk migration - a couple with a big backlog just keeps closing the gap a
         * little more every time they're together, without ever starving the metadata/date-ideas sync
         * that matters more immediately. */
        private const val MAX_PHOTOS_PER_DIRECTION_PER_SESSION = 3

        /** Feature 2: hard ceiling on the whole photo phase (push + request/receive combined) so a
         * stalled connection or an unexpectedly huge single photo can never hang a sync indefinitely -
         * see runPhotoPhaseAsClient's doc. MAJOR fix: bumped from 45s to 100s as a second layer of safety
         * margin on top of ImageDownscaler's pre-transfer resize (~1280px/78% JPEG, typically well under
         * 500KB) - real BLE conditions vary (obstacles, interference, other radio traffic), and even a
         * downscaled photo at the low end of real-world throughput (~5 kB/s) deserves more headroom than
         * 45s, especially now that up to MAX_PHOTOS_PER_DIRECTION_PER_SESSION*2 photos can share one
         * phase. */
        private const val PHOTO_PHASE_TIMEOUT_MILLIS = 100_000L

        /** Feature 2: sanity ceiling on a single reassembled photo frame's size, purely defensive against
         * a bug or a misbehaving already-authenticated peer sending chunks without ever sending a LAST
         * flag (which would otherwise grow serverPhotoIncoming/clientPhotoIncoming unbounded) - generous
         * enough for any real phone-camera JPEG. */
        private const val MAX_PHOTO_FRAME_BYTES = 25_000_000
    }
}
