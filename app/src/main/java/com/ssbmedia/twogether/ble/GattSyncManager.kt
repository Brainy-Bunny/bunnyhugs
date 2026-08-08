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
import com.ssbmedia.twogether.data.db.DEFAULT_LIST_ID
import com.ssbmedia.twogether.data.db.DateIdea
import com.ssbmedia.twogether.data.db.ListCategory
import com.ssbmedia.twogether.data.db.Milestone
import com.ssbmedia.twogether.notif.MilestoneAlarmScheduler
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.data.db.MomentNote
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.data.repo.DateIdeaRepository
import com.ssbmedia.twogether.data.repo.ListCategoryRepository
import com.ssbmedia.twogether.data.repo.MilestoneRepository
import com.ssbmedia.twogether.data.repo.MomentNoteRepository
import com.ssbmedia.twogether.data.repo.MomentRepository
import com.ssbmedia.twogether.data.repo.SessionRepository
import com.ssbmedia.twogether.events.AppEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

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
 * Every connection must first prove it knows the shared pair secret before any real chunk data is
 * accepted - without this, any nearby stranger's GATT client could connect to our open server and read
 * back the couple's data, or write a forged tombstone to delete a real item (merge is last-write-wins
 * with no origin check otherwise). This is a nonce/HMAC challenge-response, NOT a static bearer token:
 * the moment a client subscribes to the sync characteristic's notifications, the server generates a
 * fresh random nonce (BleConstants.HANDSHAKE_NONCE_BYTES) and notifies it back immediately, before
 * authenticating anything; the client's first characteristic WRITE is then
 * BleConstants.computeHandshakeResponse(handshakeKey, thatNonce) rather than the key itself, which the
 * server independently recomputes from its own copy of handshakeKey (both sides derive the same key from
 * the shared pairing code, see BleConstants.HANDSHAKE_TOKEN_*) and the nonce it issued, and compares. A
 * fresh nonce every connection means a captured response can never be replayed against a later
 * connection - see startServer/connectAsClient below and BleConstants' class doc.
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
    private val listCategoryRepository: ListCategoryRepository,
    private val sessionRepository: SessionRepository,
    private val momentRepository: MomentRepository,
    private val momentNoteRepository: MomentNoteRepository,
    private val milestoneRepository: MilestoneRepository,
    private val timeCapsuleRepository: com.ssbmedia.twogether.data.repo.TimeCapsuleRepository,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope
) {
    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    // ---- shared chunk protocol helpers ----

    /** Gathers everything this device has to offer into one combined JSON payload:
     *  - dateIdeas: the full local list, now carrying listId (which ListCategory it belongs to) instead
     *    of the old unused category field - DateIdeaRepository.mergeRemote is already a full
     *    last-write-wins merge, so sending the whole list every time is correct and cheap.
     *  - listCategories: "Our Lists" - the full local list of named lists (same full-list LWW-merge shape
     *    as dateIdeas/milestones - ListCategoryRepository.mergeRemote is already a full last-write-wins
     *    merge, so sending the whole list every time is correct and cheap here too).
     *  - sessions: CLOSED sessions only (endedAt != null). Feature A deliberately never sends an open
     *    session - an in-progress session copied onto the partner's device as "still open" would let two
     *    devices each show a different "currently open" row; each device's own open session closes
     *    naturally through its own normal proximity logic instead. Each closed session also now carries
     *    updatedAt/deleted, so a manually-backfilled session deleted on this device (SessionRepository.
     *    softDeleteManual) has its tombstone propagate to the partner on the next sync - this is why the
     *    full table (including already-deleted rows), not just the active ones, is read here.
     *  - moments: metadata + Feature 2's hasPhoto flag (== Moment.photoDownloaded on THIS device) so the
     *    partner can both reference/annotate a moment it doesn't have the photo bytes for, AND know
     *    whether it's worth requesting the bytes from us this session. Each moment also now carries
     *    updatedAt/deleted, so a moment deleted on this device (MomentRepository.softDelete) has its
     *    tombstone propagate to the partner on the next sync - this is why the full table (including
     *    already-deleted rows), not just the active ones, is read here.
     *  - notes: only THIS device's own notes (authorDeviceId == our id) - the receiving side treats
     *    every row here as "the partner's", never re-merges its own notes back onto itself.
     *  - milestones: the full local list (same full-list LWW-merge shape as dateIdeas).
     */
    private suspend fun buildPayload(): ByteArray {
        val deviceId = settingsStore.getOrCreateLocalDeviceId()
        val obj = JSONObject()
        // MAJOR fix (ultimate-app-review, Fable F-4): lets the receiver correct every updatedAt/
        // startedAt/endedAt below into ITS OWN clock's frame instead of trusting our raw wall-clock
        // value verbatim - see applyPayload's peerClockOffsetMillis doc for why. Missing on an old
        // build's payload degrades safely (applyPayload's optLong default treats that as "no observed
        // offset", i.e. today's stricter-but-correct-for-matched-clocks behavior).
        obj.put("deviceTimestamp", System.currentTimeMillis())
        obj.put("dateIdeas", serializeDateIdeas(dateIdeaRepository.getAll()))
        obj.put("listCategories", serializeListCategories(listCategoryRepository.getAll()))
        obj.put("sessions", serializeSessions(sessionRepository.getAllIncludingDeleted().filter { it.endedAt != null }))
        obj.put("moments", serializeMoments(momentRepository.getAllIncludingDeleted()))
        obj.put("notes", serializeNotes(momentNoteRepository.getAllForAuthor(deviceId)))
        obj.put("milestones", serializeMilestones(milestoneRepository.getAll()))
        // Feature: Time Capsule sync - full table including tombstones, same reasoning as
        // sessions/moments/milestones above (a capsule deleted on this device must propagate that
        // deletion, not just live capsules).
        obj.put("timeCapsules", serializeTimeCapsules(timeCapsuleRepository.getAllIncludingDeleted()))
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    /** Applies a received combined payload: merges each table with its own strategy (see each
     * repository's mergeRemote / mergeRemoteSessions / mergeRemoteStubs doc for why they differ). Returns
     * the sender's per-moment syncId/hasPhoto/takenAt snapshot (Feature 2) so the caller can decide what
     * photo bytes to push/request next, without re-parsing the raw bytes a second time. */
    private suspend fun applyPayload(bytes: ByteArray): List<RemoteMomentInfo> {
        val deviceId = settingsStore.getOrCreateLocalDeviceId()
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        // MAJOR fix (ultimate-app-review, Fable F-4): isPlausibleWireUpdatedAt rejects anything more
        // than MAX_CLOCK_SKEW_TOLERANCE_MILLIS ahead of OUR OWN clock - but in a serverless two-device
        // system with no time authority, a peer whose clock is merely wrong (no NTP - this app is
        // explicitly fully offline) is indistinguishable from a forger by that test alone. Live-verified:
        // a partner phone just 1 hour fast had every one of its honest edits silently dropped, forever,
        // on every sync, while the UI still reported "Synced!". Since both devices already completed the
        // authenticated GATT handshake before any of this runs, the peer's own claimed "now" is no more
        // trusted than everything else it's about to send (S1) - using it to correct for skew doesn't
        // weaken the boundary the handshake already enforces. Every timestamp below is corrected into
        // OUR clock's frame before either the plausibility check or storage, so (a) a real clock
        // difference no longer causes silent, permanent data loss, and (b) a genuinely forged value
        // (e.g. year 2050) is still exactly as far outside the peer's OWN reported "now" as it was
        // before, so replay-resistance (Condition 5) is unaffected - and every entity is stored in a
        // consistent, receiver-anchored frame, so a later LWW comparison against a non-skewed device's
        // own edit isn't permanently biased by this peer's clock error either.
        val rawPeerClockOffsetMillis = root.optLong("deviceTimestamp", System.currentTimeMillis()) - System.currentTimeMillis()
        // MAJOR fix (ultimate-app-review, post-restart full-scope round, Opus): the offset above was
        // unbounded - a peer reporting a wildly wrong `deviceTimestamp` (e.g. its clock set to 2050)
        // produced a huge offset that, subtracted from that peer's otherwise-honest CURRENT timestamps,
        // rewrote its entire history into the distant PAST on the receiving device (live-verified: an
        // ordinary session synced in as year-2003, silently, no warning). Capped to a generous but sane
        // window - genuine unsynced-clock drift is minutes to days, never years - so an obviously-broken
        // peer clock falls back to offset=0 (today's stricter pre-correction behavior: its data is then
        // judged against OUR clock unmodified, which correctly rejects it as implausible rather than
        // laundering it into a plausible-looking but wrong moment in the past).
        val peerClockOffsetMillis = boundPeerClockOffset(rawPeerClockOffsetMillis)
        // Self-heal: a remote idea can arrive pointing at a list that got deleted on the OTHER device
        // while this one was independently adding to it (see DateIdeaRepository.reassignOrphans' doc for
        // the exact race). Both merges + the reassign sweep run as ONE atomic transaction (see
        // ListCategoryRepository.mergeRemoteWithIdeas' doc) so no Flow observer - notably
        // OurListsViewModel's own opportunistic reassignOrphans collector - can ever see an intermediate
        // state where one table's merge has committed but the other's hasn't yet.
        val listCategoriesArr = root.optJSONArray("listCategories")
        val dateIdeasArr = root.optJSONArray("dateIdeas")
        val listCategoriesParsed = deserializeListCategories(listCategoriesArr, peerClockOffsetMillis)
        val dateIdeasParsed = deserializeDateIdeas(dateIdeasArr, peerClockOffsetMillis)
        listCategoryRepository.mergeRemoteWithIdeas(listCategoriesParsed, dateIdeasParsed)
        val sessionsArr = root.optJSONArray("sessions")
        val sessionsParsed = deserializeSessions(sessionsArr, peerClockOffsetMillis)
        sessionRepository.mergeRemoteSessions(sessionsParsed)
        val momentsArr = root.optJSONArray("moments")
        val momentsParsed = deserializeMoments(momentsArr, peerClockOffsetMillis)
        momentRepository.mergeRemoteStubs(momentsParsed)
        val notesArr = root.optJSONArray("notes")
        val notesParsed = deserializeNotes(notesArr, peerClockOffsetMillis)
        momentNoteRepository.mergeRemote(notesParsed, deviceId)
        // BUG fix: an independent audit round found milestones arriving via sync never got their yearly
        // alarm armed until the next cold start/boot, unlike every other way a Milestone enters the DB
        // (local add, backup restore, app start) - all of which call MilestoneAlarmScheduler right away.
        // Arms only what was actually upserted here, not the whole table, for the same reason
        // BackupManager.scheduleAll is only ever called once per restore rather than on every sync.
        val milestonesArr = root.optJSONArray("milestones")
        val milestonesParsed = deserializeMilestones(milestonesArr, peerClockOffsetMillis)
        val upsertedMilestones = milestoneRepository.mergeRemote(milestonesParsed)
        MilestoneAlarmScheduler.scheduleAll(context, upsertedMilestones.filter { !it.deleted })
        // Feature: Time Capsule sync. See TimeCapsuleRepository.mergeRemote's doc for why unlockedAt is
        // never trusted from this parsed data even though the definitional fields are.
        val timeCapsulesArr = root.optJSONArray("timeCapsules")
        val timeCapsulesParsed = deserializeTimeCapsules(timeCapsulesArr, peerClockOffsetMillis)
        timeCapsuleRepository.mergeRemote(timeCapsulesParsed)
        // MAJOR fix (ultimate-app-review, Fable F-4, assertion 6): even with the correction above, a
        // row can still be legitimately rejected (e.g. genuinely implausible even once corrected) - the
        // sync outcome must say so instead of an unqualified "Synced!" that hides real data loss.
        // Counted as an array-length delta (not inside each deserialize* function) so this stays a
        // read-only observation with no risk to the merge logic itself.
        lastSyncDroppedImplausibleCount =
            (listCategoriesArr?.length() ?: 0) - listCategoriesParsed.size +
            (dateIdeasArr?.length() ?: 0) - dateIdeasParsed.size +
            (sessionsArr?.length() ?: 0) - sessionsParsed.size +
            (momentsArr?.length() ?: 0) - momentsParsed.size +
            (notesArr?.length() ?: 0) - notesParsed.size +
            (milestonesArr?.length() ?: 0) - milestonesParsed.size +
            (timeCapsulesArr?.length() ?: 0) - timeCapsulesParsed.size
        return parseRemoteMomentInfo(momentsArr)
    }

    /** MAJOR fix (ultimate-app-review, Fable F-4, assertion 6): set at the end of every [applyPayload]
     * call to however many incoming rows this device itself rejected as implausible (forged or, now
     * that peerClockOffsetMillis exists, still-implausible-even-corrected) - read by the sync-completion
     * callbacks in [ProximityForegroundService] so the UI can say "partial" instead of an unqualified
     * "Synced!" when something was genuinely dropped. `@Volatile` since it's written on this manager's
     * own coroutine but read from the completion callback's context. */
    @Volatile
    var lastSyncDroppedImplausibleCount: Int = 0
        private set

    private fun serializeDateIdeas(ideas: List<DateIdea>): JSONArray {
        val arr = JSONArray()
        for (idea in ideas) {
            arr.put(JSONObject().apply {
                put("id", idea.id)
                put("text", idea.text)
                put("listId", idea.listId)
                put("done", idea.done)
                put("updatedAt", idea.updatedAt)
                put("deleted", idea.deleted)
            })
        }
        return arr
    }

    private fun deserializeDateIdeas(arr: JSONArray?, peerClockOffsetMillis: Long = 0L): List<DateIdea> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val updatedAt = o.getLong("updatedAt") - peerClockOffsetMillis
            if (!isPlausibleWireUpdatedAt(updatedAt)) {
                Log.w(TAG, "Dropping remote dateIdea $id with implausible updatedAt=$updatedAt")
                return@mapNotNull null
            }
            DateIdea(
                id = id,
                text = o.getString("text"),
                // Defensive fallback (not getString): a payload from a not-yet-updated partner device, or
                // any other edge case where the key is somehow missing, degrades safely into the default
                // list rather than throwing and aborting the whole merge.
                listId = o.optString("listId", DEFAULT_LIST_ID),
                done = o.getBoolean("done"),
                updatedAt = updatedAt,
                deleted = o.getBoolean("deleted")
            )
        }
    }

    private fun serializeListCategories(categories: List<ListCategory>): JSONArray {
        val arr = JSONArray()
        for (c in categories) {
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("createdAt", c.createdAt)
                put("updatedAt", c.updatedAt)
                put("deleted", c.deleted)
            })
        }
        return arr
    }

    private fun deserializeListCategories(arr: JSONArray?, peerClockOffsetMillis: Long = 0L): List<ListCategory> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val updatedAt = o.getLong("updatedAt") - peerClockOffsetMillis
            if (!isPlausibleWireUpdatedAt(updatedAt)) {
                Log.w(TAG, "Dropping remote listCategory $id with implausible updatedAt=$updatedAt")
                return@mapNotNull null
            }
            ListCategory(
                id = id,
                name = o.getString("name"),
                createdAt = o.getLong("createdAt"),
                updatedAt = updatedAt,
                deleted = o.optBoolean("deleted", false)
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
                put("updatedAt", s.updatedAt)
                put("deleted", s.deleted)
            })
        }
        return arr
    }

    private fun deserializeSessions(arr: JSONArray?, peerClockOffsetMillis: Long = 0L): List<TogetherSession> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val syncId = o.optString("syncId", "")
            if (syncId.isBlank() || o.isNull("endedAt")) return@mapNotNull null
            // MAJOR fix (ultimate-app-review, Fable F-4): startedAt/endedAt/updatedAt are all wall-clock
            // stamps from the SENDER's own clock - corrected into the receiver's frame the same way as
            // every updatedAt below, so a peer whose clock merely differs (not forged) doesn't have its
            // honest session bounds rejected as implausible either.
            val startedAt = o.getLong("startedAt") - peerClockOffsetMillis
            val endedAt = o.getLong("endedAt") - peerClockOffsetMillis
            // BLOCKER fix: an independent testing round found this accepted a peer's session bounds
            // verbatim - a forged/buggy endedAt far in the future (or before startedAt) merges in as a
            // normal closed session and permanently corrupts all-time stats / irreversibly unlocks time
            // capsules, with no in-app way to remove it. Reject anything that couldn't plausibly be a
            // real together-session: end before start, either bound outside a small clock-skew window
            // around now, or an implausibly long duration. A deleted-tombstone row (endedAt still present
            // per the isNull check above) is exempt from the "not in the future" check on startedAt/endedAt
            // individually but still must satisfy end >= start and the duration ceiling.
            //
            // BLOCKER fix, round 2 (ultimate-app-review, post-restart full-scope round, Opus): the
            // original version above only ever checked an UPPER bound - a startedAt near Long.MIN_VALUE
            // made `endedAt - startedAt` integer-overflow to a small/negative number, silently defeating
            // the duration ceiling entirely. Live-verified: such a row merged in, then
            // StatsCalculator.buildDailyMinuteMap (which walks the interval one calendar day at a time)
            // threw an unrecoverable OutOfMemoryError - a crash-loop escaping even the app's own
            // exception hardening (OutOfMemoryError is an Error, not an Exception), fixable only by
            // `pm clear`. Now delegates to SessionBoundsValidator, the SAME validator
            // BackupManager.parseSessions uses (Opus's own proposal to stop these two paths from
            // independently drifting out of sync the way they just did) - its lower-bound check makes
            // the subtraction above structurally overflow-safe.
            val updatedAt = o.optLong("updatedAt", 0L) - peerClockOffsetMillis
            if (!com.ssbmedia.twogether.util.SessionBoundsValidator.isPlausible(
                    startedAt, endedAt, System.currentTimeMillis(), MAX_CLOCK_SKEW_TOLERANCE_MILLIS, MAX_PLAUSIBLE_SESSION_DURATION_MILLIS
                ) || !isPlausibleWireUpdatedAt(updatedAt)
            ) {
                Log.w(TAG, "Rejecting implausible remote session $syncId: startedAt=$startedAt endedAt=$endedAt updatedAt=$updatedAt")
                return@mapNotNull null
            }
            TogetherSession(
                startedAt = startedAt,
                endedAt = endedAt,
                isManual = o.optBoolean("isManual", false),
                syncId = syncId,
                updatedAt = updatedAt,
                deleted = o.optBoolean("deleted", false)
            )
        }
    }

    /** BLOCKER fix, round 3 (shared helper): an independent testing round found the original approach of
     * CLAMPING an implausible peer-supplied `updatedAt` to `now + tolerance` (rather than rejecting it)
     * didn't actually self-heal as intended - because full tables are resent on every sync, and the clamp
     * ceiling is the RECEIVER's current `now` at the moment of each sync, a still-poisoned row on one
     * device gets re-clamped to a fresh, ever-later ceiling every round, letting it repeatedly beat and
     * overwrite the victim's genuine edits made in between syncs, not just win once. Rejecting the record
     * outright instead (used at every LWW merge call site below, same as deserializeSessions above)
     * eliminates the drift entirely: an implausible remote value can never win, no matter how many times
     * it's retransmitted or how much time passes, since it's simply never merged in the first place. */
    private fun isPlausibleWireUpdatedAt(wireUpdatedAt: Long): Boolean =
        wireUpdatedAt <= System.currentTimeMillis() + MAX_CLOCK_SKEW_TOLERANCE_MILLIS

    /** MAJOR fix (ultimate-app-review, post-restart full-scope round, Opus): see applyPayload's own doc
     * for why an unbounded peer clock offset let an obviously-broken peer clock launder its data into a
     * plausible-looking but wrong moment in the past. Extracted as its own function purely so it's
     * independently testable. */
    private fun boundPeerClockOffset(rawOffsetMillis: Long): Long =
        if (kotlin.math.abs(rawOffsetMillis) > MAX_PLAUSIBLE_PEER_CLOCK_OFFSET_MILLIS) 0L else rawOffsetMillis

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
                put("updatedAt", m.updatedAt)
                put("deleted", m.deleted)
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
    private fun deserializeMoments(arr: JSONArray?, peerClockOffsetMillis: Long = 0L): List<Moment> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val syncId = o.optString("syncId", "")
            if (syncId.isBlank()) return@mapNotNull null
            val updatedAt = o.optLong("updatedAt", 0L) - peerClockOffsetMillis
            if (!isPlausibleWireUpdatedAt(updatedAt)) {
                Log.w(TAG, "Dropping remote moment $syncId with implausible updatedAt=$updatedAt")
                return@mapNotNull null
            }
            Moment(
                photoUri = o.optString("photoUri", ""),
                takenAt = o.getLong("takenAt"),
                syncId = syncId,
                isRemote = true,
                updatedAt = updatedAt,
                deleted = o.optBoolean("deleted", false)
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

    private fun deserializeNotes(arr: JSONArray?, peerClockOffsetMillis: Long = 0L): List<MomentNote> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val momentSyncId = o.getString("momentSyncId")
            val updatedAt = o.getLong("updatedAt") - peerClockOffsetMillis
            if (!isPlausibleWireUpdatedAt(updatedAt)) {
                Log.w(TAG, "Dropping remote moment note for $momentSyncId with implausible updatedAt=$updatedAt")
                return@mapNotNull null
            }
            MomentNote(
                momentSyncId = momentSyncId,
                authorDeviceId = o.getString("authorDeviceId"),
                text = o.optString("text", ""),
                updatedAt = updatedAt,
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

    private fun deserializeMilestones(arr: JSONArray?, peerClockOffsetMillis: Long = 0L): List<Milestone> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val updatedAt = o.getLong("updatedAt") - peerClockOffsetMillis
            if (!isPlausibleWireUpdatedAt(updatedAt)) {
                Log.w(TAG, "Dropping remote milestone $id with implausible updatedAt=$updatedAt")
                return@mapNotNull null
            }
            Milestone(
                id = id,
                label = o.getString("label"),
                // BLOCKER fix: month/day here are WIRE DATA from the paired peer, unvalidated. An
                // independent testing round found that an out-of-range month (e.g. 0 or 13) reaches
                // MilestoneAlarmScheduler's YearMonth.of(year, month) uncaught, which - since it's called
                // from TwogetherApp.onCreate on app start to re-arm every milestone's alarm - crashes the
                // app on EVERY subsequent cold start with no in-app recovery path (only `pm clear`, which
                // wipes all data, escapes it). Clamped to a always-constructible range here, at the one
                // point this untrusted data enters the local DB from a peer device - see
                // BackupManager.parseMilestones' matching fix for the same issue via a crafted backup file.
                month = o.getInt("month").coerceIn(1, 12),
                day = o.getInt("day").coerceIn(1, 31),
                year = if (o.isNull("year")) null else o.getInt("year"),
                createdAt = o.getLong("createdAt"),
                updatedAt = updatedAt,
                deleted = o.optBoolean("deleted", false)
            )
        }
    }

    /** MINOR fix (Opus+Sonnet+Fable all independently proposed this): `unlockedAt` used to be included
     * on the wire "for forward-compat/debuggability" even though TimeCapsuleRepository.mergeRemote and
     * deserializeTimeCapsules already both refused to ever read it back - the top invariant held by
     * convention (every reader happened to ignore the field), not by construction. Omitting it here
     * removes the field from this device's own payload entirely, so there is nothing left for a future
     * reader to accidentally trust - the invariant is now structurally unbreakable rather than
     * convention-enforced. Both devices still converge on the same real unlock moment regardless, since
     * totalHours is derived from the SAME (already-synced) session data on both sides - nothing is lost. */
    private fun serializeTimeCapsules(capsules: List<com.ssbmedia.twogether.data.db.TimeCapsule>): JSONArray {
        val arr = JSONArray()
        for (c in capsules) {
            arr.put(JSONObject().apply {
                put("syncId", c.syncId)
                put("text", c.text)
                put("unlockAtHours", c.unlockAtHours.toDouble())
                put("createdAt", c.createdAt)
                put("manualHoursAtCreation", c.manualHoursAtCreation.toDouble())
                put("updatedAt", c.updatedAt)
                put("deleted", c.deleted)
            })
        }
        return arr
    }

    private fun deserializeTimeCapsules(arr: JSONArray?, peerClockOffsetMillis: Long = 0L): List<com.ssbmedia.twogether.data.db.TimeCapsule> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val syncId = o.optString("syncId", "")
            if (syncId.isBlank()) return@mapNotNull null
            val updatedAt = o.getLong("updatedAt") - peerClockOffsetMillis
            if (!isPlausibleWireUpdatedAt(updatedAt)) {
                Log.w(TAG, "Dropping remote timeCapsule $syncId with implausible updatedAt=$updatedAt")
                return@mapNotNull null
            }
            // Defensive: an unlockAtHours that's negative, zero, non-finite (NaN/Infinity - both
            // representable in a JSON double despite not being valid JSON per spec, and org.json parses
            // them anyway), or absurdly large would either never unlock or trivially always-unlock -
            // same "typo guardrail" CapsulesScreen's own MAX_CAPSULE_UNLOCK_HOURS enforces on local
            // creation, re-applied here since this value arrives from a peer, not this device's own
            // validated UI input.
            val unlockAtHours = o.getDouble("unlockAtHours").toFloat()
            if (!com.ssbmedia.twogether.data.repo.TimeCapsuleRepository.isPlausibleUnlockAtHours(unlockAtHours)) {
                Log.w(TAG, "Dropping remote timeCapsule $syncId with implausible unlockAtHours=$unlockAtHours")
                return@mapNotNull null
            }
            // BLOCKER fix (ultimate-app-review round 2, Opus+Sonnet both independently live-reproduced):
            // this field had ZERO validation - unlike unlockAtHours two lines above. Since
            // TimeCapsuleRepository.unlockEligible computes `effectiveThreshold = unlockAtHours +
            // (currentManualHoursCredit - manualHoursAtCreation)`, a peer claiming a huge value here drives
            // the threshold deeply negative, forcing an instant, irreversible false unlock on the receiving
            // device WITHOUT ever touching unlockedAt on the wire - the two enforcement layers documented on
            // TimeCapsuleRepository.mergeRemote guard the unlockedAt field itself, not this input to the
            // formula that sets it. Separately, a non-finite value (a plain finite-in-JSON double like 1e39
            // that overflows Float to Infinity, or a JSON string "NaN") used to get persisted and then made
            // every subsequent serializeTimeCapsules() call throw an uncaught JSONException inside
            // buildPayload()'s bare scope.launch - a permanent per-device sync crash loop, live-reproduced
            // by Opus. Same bound shape as unlockAtHours (finite, non-negative, capped well above any
            // plausible real "manual hours credit" - the largest value either reviewer's live exploit used
            // was ~1e6/1e9/1e39, all comfortably rejected here).
            val manualHoursAtCreation = o.optDouble("manualHoursAtCreation", 0.0).toFloat()
            if (!com.ssbmedia.twogether.data.repo.TimeCapsuleRepository.isPlausibleManualHoursAtCreation(manualHoursAtCreation)) {
                Log.w(TAG, "Dropping remote timeCapsule $syncId with implausible manualHoursAtCreation=$manualHoursAtCreation")
                return@mapNotNull null
            }
            com.ssbmedia.twogether.data.db.TimeCapsule(
                text = o.optString("text", ""),
                unlockAtHours = unlockAtHours,
                createdAt = o.getLong("createdAt"),
                // SECURITY: deliberately NOT read from the wire here - always null on a freshly-
                // deserialized row. TimeCapsuleRepository.mergeRemote is what actually enforces this
                // never overwrites a local unlock either; this is defense-in-depth at the parse layer
                // too, so no future caller of this function could accidentally trust it.
                unlockedAt = null,
                manualHoursAtCreation = manualHoursAtCreation,
                syncId = syncId,
                updatedAt = updatedAt,
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
        // Moment delete: getBySyncId is the raw/unfiltered lookup (it has to be - see MomentDao's doc),
        // so it's still possible to land here for a moment we ourselves soft-deleted locally after
        // requesting its bytes but before this delivery arrived. Writing the bytes now would just leave
        // an orphaned file on disk (softDelete's own file-delete already ran, and won't run again for an
        // already-tombstoned row), so skip it - the moment is gone from this device's perspective either way.
        if (moment.deleted) return
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
        // MINOR fix (ultimate-app-review, Fable F-1): the destination path was already safe (UUID/
        // syncId-derived, canonical-parent-checked above), but the CONTENT was never checked at all -
        // an authenticated peer could stream any of its own local files (live-verified: its own Room DB
        // and DataStore protobuf arrived and were stored as "photos" with no error). Not an escalation
        // (a sender can only leak its own data to a partner that already trusts it, and the safe
        // destination path was never in question), but cheap and worth closing: refuse anything that
        // doesn't start with a real image's magic bytes, matching the same jpg/jpeg/png/webp allowlist
        // this class already uses for photoUri's extension hint (S10).
        if (!looksLikeImage(bytes)) {
            Log.w(TAG, "Refusing to save photo for moment $syncId - received bytes don't start with a recognized image signature")
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
            // Re-check after the write: a concurrent softDelete/mergeRemoteStubs tombstone-apply could
            // have landed between our `moment.deleted` guard above and this write completing. If it did,
            // its own file-delete branch would have skipped (photoDownloaded was still false at that
            // instant, so it saw nothing to clean up) - meaning the file we just wrote would otherwise
            // become a permanently-orphaned disk-space leak, since a delete can never fire twice for an
            // already-tombstoned row. Clean it up ourselves instead of leaving it behind.
            if (momentRepository.getBySyncId(syncId)?.deleted == true) {
                withContext(Dispatchers.IO) { destFile.delete() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save received photo for moment $syncId", e)
            withContext(Dispatchers.IO) { tempFile.delete() }
        }
    }

    /** MINOR fix (ultimate-app-review, Fable F-1): magic-byte check for [savePhotoBytes] - matches the
     * same jpg/jpeg/png/webp allowlist this class already uses for photoUri's extension hint (S10).
     * Deliberately just a signature check, not a full image decode (this app never renders these bytes
     * as anything other than an image via Coil, which already fails safe on genuinely malformed image
     * data - this only needs to stop an obviously-non-image payload from being written at all). */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val webp = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        return jpeg || png || webp
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

    // MINOR fix: same reasoning as expectedHandshakeKey below - all three are written from startServer()/
    // stopServer() (called from the service's own coroutine) but read from GATT binder-thread callbacks,
    // and none of that is covered by serverLock's guard set. Most exposed on the explicitly-supported
    // repeat startServer() call (a "Sync now" tap while the server's already open, see its own doc below)
    // - without @Volatile a binder thread could observe a stale reference to any of these.
    @Volatile
    private var gattServer: BluetoothGattServer? = null
    @Volatile
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
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
    // Per-device nonce issued at CCCD-subscribe time (see onDescriptorWriteRequest) and consumed the
    // moment that device's handshake response is verified - see the nonce/HMAC handshake doc at the top
    // of this file.
    private val serverNonces = HashMap<String, ByteArray>()
    // Guards serverIncoming / serverOutQueue / serverPhotoIncoming / serverPhotoOutQueue /
    // authenticatedDevices / deviceMtus / serverNonces, which are otherwise mutated both from GATT
    // binder-thread callbacks and from coroutines launched via [scope].
    private val serverLock = Any()
    // MINOR fix: read from GATT binder-thread callbacks (onCharacteristicWriteRequest) but written from
    // startServer() - not covered by serverLock's own guard set (a lock there wouldn't help a caller that
    // isn't taking it), so @Volatile is what actually guarantees a write here is visible to those reads.
    @Volatile
    private var expectedHandshakeKey: ByteArray = ByteArray(0)

    // The most recently registered "sync finished" callback. Kept as a mutable field (rather than
    // captured directly in the BluetoothGattServerCallback closure below) so that startServer() can be
    // called again - e.g. a manual "Sync now" tap while this device's GATT server is already open from
    // earlier in the together-session - and have that new call's callback actually get used for the
    // next completed sync, without tearing down and re-registering the whole GATT service each time
    // (which would drop any in-flight write from the other side, and can fail outright if the
    // characteristic is added again while a service with the same UUID is still registered).
    // MINOR fix: same reasoning as expectedHandshakeKey/gattServer above - written from startServer()
    // (repeat-call case especially), read from binder-thread callbacks (onCharacteristicWriteRequest).
    @Volatile
    private var activeServerOnSyncDone: (Boolean) -> Unit = {}

    fun startServer(handshakeKey: ByteArray, onSyncDone: (Boolean) -> Unit) {
        expectedHandshakeKey = handshakeKey
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
            // OurListsScreen's own timeout resolves the UI to "Couldn't sync - make sure you're
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
                        serverNonces.remove(addr)
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
                    // The very first write from a not-yet-authenticated device must be the correct
                    // HMAC(handshakeKey, nonce) response to the nonce THIS device was issued when it
                    // subscribed (see onDescriptorWriteRequest below and this file's top-of-file doc) -
                    // proving it knows our pairing code without ever transmitting the key itself - before
                    // we accept or act on anything else it sends (on EITHER characteristic - the client
                    // always sends the handshake on the sync characteristic first, see connectAsClient).
                    val nonce = synchronized(serverLock) { serverNonces[addr] }
                    val expected = if (nonce != null && expectedHandshakeKey.isNotEmpty()) {
                        BleConstants.computeHandshakeResponse(expectedHandshakeKey, nonce)
                    } else null
                    val ok = expected != null && value.contentEquals(expected)
                    if (ok) {
                        synchronized(serverLock) { authenticatedDevices.add(addr); serverNonces.remove(addr) }
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
                if (value.size > 1) {
                    // BUG fix: see metadataResponseSignal's own doc (client-side) - mirrors
                    // serverPhotoIncoming's own cap (handleServerPhotoChunk below); this buffer had no
                    // equivalent one, so an already-authenticated client that never sent a LAST flag could
                    // grow it unbounded.
                    synchronized(serverLock) {
                        if (buffer.size() + value.size - 1 > MAX_SYNC_JSON_BYTES) {
                            Log.w(TAG, "Incoming metadata payload from client exceeded sanity cap - dropping")
                            buffer.reset()
                        } else {
                            buffer.write(value, 1, value.size - 1)
                        }
                    }
                }
                if (flag == BleConstants.CHUNK_FLAG_LAST) {
                    val raw = synchronized(serverLock) {
                        serverIncoming.remove(addr)
                        buffer.toByteArray()
                    }
                    scope.launch {
                        try {
                            applyPayload(raw)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse incoming sync payload - reporting failure instead of a silent empty merge", e)
                            activeServerOnSyncDone(false)
                            return@launch
                        }
                        // MAJOR fix (test-code-allmodels, Opus - unfixed twin of round 2's
                        // serializeTimeCapsules crash-loop fix): buildPayload()/sendToClient()/
                        // activeServerOnSyncDone() used to sit outside this try/catch entirely, inside a
                        // bare scope.launch with no CoroutineExceptionHandler on this scope
                        // (ProximityForegroundService.lifecycleScope) - any uncaught throw here (a
                        // DataStore IOException, a Room read failure, or exactly the kind of poisoned-value
                        // JSONException round 2 already fixed the INPUT side of but never guarded the
                        // call site itself) crashed the whole process. Since this service is START_STICKY
                        // and the peer keeps reconnecting, a persistently-bad local state (not even
                        // adversarial - a disk-full DataStore write, say) would crash-loop indefinitely
                        // instead of failing this one sync cleanly. Same report-and-stop shape as the
                        // parse-failure branch above.
                        try {
                            val payload = buildPayload()
                            sendToClient(device, payload)
                            activeServerOnSyncDone(true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to build/send sync response - reporting failure instead of crashing", e)
                            activeServerOnSyncDone(false)
                        }
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
                // MINOR fix: only issue a nonce to a device that isn't authenticated yet. Our own client
                // never re-subscribes mid-connection, so this guard is unreachable in normal operation
                // today, but without it, any CCCD write on the sync characteristic from an ALREADY
                // authenticated device would inject a fresh raw (unframed) notification into the stream
                // that device is otherwise parsing as flag-framed JSON chunks, corrupting whatever sync
                // was in progress - defensive robustness against any future/OEM-quirk re-subscribe.
                val alreadyAuthenticated = synchronized(serverLock) { authenticatedDevices.contains(device.address) }
                if (descriptor.characteristic.uuid == BleConstants.SYNC_CHARACTERISTIC_UUID && !alreadyAuthenticated) {
                    // The client just subscribed to sync notifications - the earliest point at which we
                    // can push it anything - so issue a fresh random nonce right away for it to sign with
                    // the shared handshake key (see this file's top-of-file doc). A brand new nonce every
                    // subscribe/connection means a handshake response captured from a past connection is
                    // never valid again.
                    val nonce = ByteArray(BleConstants.HANDSHAKE_NONCE_BYTES).also { SecureRandom().nextBytes(it) }
                    synchronized(serverLock) { serverNonces[device.address] = nonce }
                    sendRawNotification(device, nonce)
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
                // out via OurListsScreen's own ~8s UI timeout instead of an immediate, accurate failure.
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
                } catch (e: CancellationException) {
                    throw e
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
            // Moment delete: getBySyncId is the raw/unfiltered lookup, so a moment the partner requested
            // moments ago (before we deleted it) could still show up here - decline to serve it rather
            // than handing back bytes for something that, from this device's perspective, no longer
            // exists. The client-side computeToRequestIds already excludes deleted moments going forward
            // via the now-filtered getAll(); this just covers a request already in flight when the
            // delete happened.
            if (moment.deleted) continue
            if (!moment.photoDownloaded) continue
            val file = File(moment.photoUri)
            if (!file.isFile) continue
            // MAJOR fix: file reads inherit this class's dispatcher (Dispatchers.Main.immediate via
            // ProximityForegroundService's lifecycleScope) unless explicitly moved off it - see
            // savePhotoBytes's doc.
            val bytes = try {
                withContext(Dispatchers.IO) { file.readBytes() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
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
        serverCharacteristic = null
        serverPhotoCharacteristic = null
        synchronized(serverLock) {
            serverIncoming.clear()
            serverOutQueue.clear()
            serverPhotoIncoming.clear()
            serverPhotoOutQueue.clear()
            authenticatedDevices.clear()
            deviceMtus.clear()
            // MINOR fix: hygiene - every lingering entry here is for a nonce that was never successfully
            // authenticated against (a successful auth already removes its own entry), so this isn't
            // exploitable either way, but a stopped server shouldn't leave stale per-device state behind.
            serverNonces.clear()
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
    // BUG fix: an independent audit round found the JSON metadata phase had neither of the two
    // defenses the photo phase already has (MAX_PHOTO_FRAME_BYTES + PHOTO_PHASE_TIMEOUT_MILLIS) - a
    // malformed/misbehaving already-authenticated peer that kept sending chunks without ever sending
    // the LAST flag could grow clientIncoming unbounded, and there was no timeout on waiting for the
    // server's response at all, so a wedged sync could hold this device's single-sync-attempt-at-a-time
    // guard (see connectAsClient callers) open indefinitely instead of failing and releasing it for the
    // next attempt. Completed the moment clientIncoming's LAST flag arrives (see onCharacteristicChanged)
    // - mirrors clientPhotoSendComplete/photoDoneSignal's own pattern exactly.
    //
    // MAJOR fix, round 2: the first version of this fix raced this signal against a single fixed
    // METADATA_PHASE_TIMEOUT_MILLIS deadline covering the WHOLE phase (this device's full upload +
    // the server's DB merge + the server's full response) - a second independent review round did the
    // throughput math and found that deadline was far too tight for the protocol's real chunk size
    // (MAX_CHUNK_PAYLOAD, ~180 bytes, one chunk per BLE connection event) once a couple's synced history
    // grew large: a perfectly legitimate, large-but-real payload could genuinely take longer than a
    // fixed deadline sized for the "well under 1MB" common case, and once a couple's data crossed that
    // line, EVERY future sync would abort at the same point forever - strictly worse than the unbounded
    // wait this was meant to fix. Replaced with a STALL timeout instead (see
    // METADATA_STALL_TIMEOUT_MILLIS): the deadline resets on every chunk of genuine progress (sent OR
    // received - see metadataLastActivityAtMillis), so a slow-but-real large transfer keeps making
    // progress indefinitely, while a peer that goes silent mid-phase still gets caught quickly. A
    // separate, much longer METADATA_PHASE_ABSOLUTE_CEILING_MILLIS bounds the worst case too (a peer
    // trickling one chunk just under the stall threshold forever), matching the same
    // stall-plus-absolute-ceiling shape a careful timeout design needs.
    // BUG fix: made @Volatile per a review round's own suggestion - this is written from
    // BluetoothGattCallback methods (binder threads) and read from the watchdog coroutine's own
    // dispatcher, and the stale-watchdog guard added alongside it (`if (metadataResponseSignal !== signal)
    // break`) only actually works if that reassignment is guaranteed visible across threads.
    @Volatile private var metadataResponseSignal: CompletableDeferred<Unit>? = null
    @Volatile private var metadataLastActivityAtMillis: Long = 0L

    fun connectAsClient(device: BluetoothDevice, handshakeKey: ByteArray, onSyncDone: (Boolean) -> Unit) {
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
        metadataResponseSignal = null
        metadataLastActivityAtMillis = 0L
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
        // Completed by onCharacteristicChanged the moment the server's handshake nonce notification
        // arrives (always the very first notification on the sync characteristic - see this file's
        // top-of-file doc) - awaited (with a timeout) right before computing+sending the handshake
        // response, since the nonce can arrive at any point after this device's CCCD write, independent
        // of when the photo characteristic's own subscription finishes.
        val nonceDeferred = CompletableDeferred<ByteArray>()

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
                    metadataResponseSignal?.complete(Unit)
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
                // secret before sending any real data, by answering the server's handshake nonce (already
                // in flight or about to be - see startServer's onDescriptorWriteRequest) with
                // HMAC(handshakeKey, nonce) rather than a static token. The server ignores/rejects
                // everything until it sees this exact response as the first write (see startServer's
                // handshake check).
                val characteristic = service?.getCharacteristic(BleConstants.SYNC_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    finish(false)
                    return
                }
                scope.launch {
                    val nonce = withTimeoutOrNull(HANDSHAKE_NONCE_TIMEOUT_MILLIS) { nonceDeferred.await() }
                    if (nonce == null) {
                        Log.w(TAG, "Timed out waiting for server's handshake nonce")
                        finish(false)
                        // MEDIUM fix: without this, a connection that times out waiting for the nonce
                        // (e.g. the peer is on the pre-nonce protocol and never sends one) left the GATT
                        // link itself open - finish(false) only resolves the caller's result, it doesn't
                        // tear down the connection. onConnectionStateChange's own DISCONNECTED cleanup
                        // never got a chance to run, so the link (and, server-side, its authenticated-
                        // device slot/nonce entry) would linger until something else eventually closed it.
                        try { gatt.disconnect() } catch (e: SecurityException) { /* ignore */ }
                        return@launch
                    }
                    // MINOR fix: computeHandshakeResponse moved inside the try - a JCE failure (e.g. an
                    // unsupported algorithm on some OEM's crypto provider) used to be able to escape
                    // uncaught into [scope], since the try below only ever wrapped the actual GATT write.
                    try {
                        val response = BleConstants.computeHandshakeResponse(handshakeKey, nonce)
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        characteristic.value = response
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(characteristic)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to compute/send handshake response", e)
                        finish(false)
                        try { gatt.disconnect() } catch (e2: SecurityException) { /* ignore */ }
                    }
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
                    // BUG fix: see metadataResponseSignal's own doc - without this watchdog, a peer that
                    // authenticated correctly but then never sent back a metadata response (or sent one
                    // that never completed with a LAST flag) left this device waiting forever, holding
                    // its single-sync-attempt guard open with no way to recover except killing the app.
                    // Stall-based, not a fixed deadline - see metadataResponseSignal's own "round 2" doc
                    // for why. Polls rather than a single withTimeoutOrNull so it can compare against a
                    // deadline that keeps moving forward as long as genuine progress (metadataLastActivityAtMillis)
                    // keeps happening.
                    val signal = CompletableDeferred<Unit>()
                    metadataResponseSignal = signal
                    metadataLastActivityAtMillis = System.currentTimeMillis()
                    val phaseStartedAtMillis = metadataLastActivityAtMillis
                    scope.launch {
                        while (!signal.isCompleted) {
                            delay(METADATA_STALL_CHECK_INTERVAL_MILLIS)
                            if (signal.isCompleted) break
                            // BUG fix: a second independent review round pointed out this closure reads
                            // the SHARED metadataLastActivityAtMillis, not one scoped to its own `signal`
                            // - so if this exact attempt were ever abandoned without its `signal` being
                            // completed (disconnectClient() closes the gatt without going through the
                            // normal onConnectionStateChange DISCONNECTED path, which is what would
                            // otherwise complete it), this watchdog could linger up to the full ceiling
                            // and then call finish(false) on behalf of a sync that's no longer this
                            // device's current attempt - a stale finish() is a harmless no-op by itself,
                            // but is one guard too many to rely on alone. Bailing out the moment
                            // metadataResponseSignal has been reassigned to a NEWER attempt's signal means
                            // this watchdog only ever acts on its own, still-current attempt.
                            if (metadataResponseSignal !== signal) break
                            val now = System.currentTimeMillis()
                            val sinceLastActivity = now - metadataLastActivityAtMillis
                            val sincePhaseStart = now - phaseStartedAtMillis
                            val stalled = sinceLastActivity > METADATA_STALL_TIMEOUT_MILLIS
                            val exceededCeiling = sincePhaseStart > METADATA_PHASE_ABSOLUTE_CEILING_MILLIS
                            if (!stalled && !exceededCeiling) continue
                            Log.w(
                                TAG,
                                if (stalled) "Metadata phase stalled - no progress for ${sinceLastActivity}ms"
                                else "Metadata phase exceeded absolute ceiling (${sincePhaseStart}ms) despite ongoing progress"
                            )
                            finish(false)
                            try { gatt.disconnect() } catch (e: SecurityException) { /* ignore */ }
                            break
                        }
                    }
                    // MAJOR fix (test-code-allmodels, Opus - unfixed twin of round 2's serializeTimeCapsules
                    // crash-loop fix, same as the server-side buildPayload() call site above): this used to
                    // have no try/catch at all, inside a bare scope.launch on ProximityForegroundService.
                    // lifecycleScope with no CoroutineExceptionHandler - any uncaught throw (a DataStore
                    // IOException, a Room read failure) crashed the whole process instead of failing this
                    // one sync attempt. Same report-and-disconnect shape every other failure branch in this
                    // handshake/sync flow already uses (finish(false) + best-effort disconnect).
                    scope.launch {
                        try {
                            val payload = buildPayload()
                            synchronized(clientLock) { clientOutQueue = toChunks(payload, clientChunkPayload).toMutableList() }
                            sendNextClientChunk(gatt, characteristic)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to build/send outgoing sync payload - reporting failure instead of crashing", e)
                            finish(false)
                            try { gatt.disconnect() } catch (e2: SecurityException) { /* ignore */ }
                        }
                    }
                    return
                }
                sendNextClientChunk(gatt, characteristic)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value ?: return
                if (value.isEmpty()) return

                if (characteristic.uuid == BleConstants.SYNC_CHARACTERISTIC_UUID && !nonceDeferred.isCompleted) {
                    // The very first notification the server ever sends on the sync characteristic is
                    // always its handshake nonce (see startServer's onDescriptorWriteRequest) - raw bytes,
                    // no chunk-flag framing, since the server won't send real (flag-framed) JSON data
                    // until AFTER it has authenticated this device via that nonce's HMAC response.
                    nonceDeferred.complete(value)
                    return
                }

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
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to process incoming photo frame", e)
                            }
                        }
                    }
                    return
                }

                val flag = value[0]
                if (value.size > 1) {
                    // BUG fix: see metadataResponseSignal's own doc - mirrors clientPhotoIncoming's own
                    // cap immediately above; this buffer had no equivalent one at all.
                    if (clientIncoming.size() + value.size - 1 > MAX_SYNC_JSON_BYTES) {
                        Log.w(TAG, "Incoming metadata payload from server exceeded sanity cap - dropping")
                        clientIncoming.reset()
                    } else {
                        clientIncoming.write(value, 1, value.size - 1)
                    }
                    // BUG fix: see metadataResponseSignal's own doc - every chunk actually received
                    // counts as progress for the stall watchdog too, not just chunks sent.
                    metadataLastActivityAtMillis = System.currentTimeMillis()
                }
                if (flag == BleConstants.CHUNK_FLAG_LAST) {
                    val raw = clientIncoming.toByteArray()
                    clientIncoming.reset()
                    metadataResponseSignal?.complete(Unit)
                    scope.launch {
                        val remoteMomentInfo = try {
                            applyPayload(raw)
                        } catch (e: CancellationException) {
                            throw e
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
                        } catch (e: CancellationException) {
                            throw e
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
            // BUG fix: see metadataResponseSignal's own doc - every chunk actually sent counts as
            // progress for the stall watchdog, resetting its deadline.
            metadataLastActivityAtMillis = System.currentTimeMillis()
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
                val bytes = try {
                    withContext(Dispatchers.IO) { File(moment.photoUri).readBytes() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: continue
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

        /** How long the client waits for the server's handshake nonce notification (see this file's
         * top-of-file doc) before giving up on this connection attempt - generous relative to how fast a
         * local GATT notification normally arrives after a CCCD write completes, since the only realistic
         * cause of a real delay this long is a stalled/dying connection that should fail anyway. */
        private const val HANDSHAKE_NONCE_TIMEOUT_MILLIS = 10_000L

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

        /** BUG fix: sanity ceiling on the reassembled JSON metadata payload (sessions/moments/dateIdeas/
         * milestones/etc, both directions) - see metadataResponseSignal's own doc for why this and the
         * timeout constants below exist. This is a defensive ceiling against a misbehaving peer growing
         * the buffer forever, NOT a sizing estimate of a normal payload - MAX_CHUNK_PAYLOAD-sized chunks
         * at real BLE throughput would take a long time to actually reach this, which is exactly why the
         * timeout below is stall-based rather than sized off this constant. */
        private const val MAX_SYNC_JSON_BYTES = 10_000_000

        /** BLOCKER fix: an independent testing round found that every LWW merge (dateIdeas,
         * listCategories, milestones, momentNotes) trusted the peer's `updatedAt` absolutely, with no
         * clamp - a peer with a wrong clock (or a forged payload) could stamp a value far in the future,
         * after which no local edit could ever win again since local writes stamp real wall-clock time.
         * Clamping incoming `updatedAt` to `now + this tolerance` bounds the damage to a small, genuine
         * clock-skew window: a poisoned/forged row can still win once, but any subsequent real local edit
         * (stamped with actual current time, which will already exceed the clamped value) wins back. */
        const val MAX_CLOCK_SKEW_TOLERANCE_MILLIS = 5 * 60_000L

        /** MAJOR fix (ultimate-app-review, post-restart full-scope round, Opus): caps how large a
         * peer-reported clock offset applyPayload's correction will ever act on - see its own doc for
         * why an unbounded offset let an obviously-broken peer clock (e.g. set to 2050) launder its data
         * into a plausible-looking but wrong moment in the past instead of being rejected outright.
         *
         * MINOR fix, round 2 (ultimate-app-review, round-2 re-verification, Opus): the original 1-year
         * cap was looser than this constant's own doc justified ("minutes to days") - a peer 10 months
         * fast still got its honest current data fully corrected and landed on the partner dated ~10
         * months in the past, the same silent-mis-dating failure mode this cap exists to prevent, just
         * smaller. 30 days comfortably covers a genuinely unsynced clock (including "phone sat off for a
         * month") while a peer wrong by a whole season or more now correctly falls back to no
         * correction instead of being "helpfully" laundered to a still-very-wrong date. */
        const val MAX_PLAUSIBLE_PEER_CLOCK_OFFSET_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** BLOCKER fix: an independent testing round found `deserializeSessions` accepted a peer's
         * `startedAt`/`endedAt` verbatim with zero bounds checking - a forged or buggy session (e.g.
         * `endedAt` far in the future, or a multi-thousand-hour duration) merges in as a normal closed
         * session, permanently corrupting all-time stats and irreversibly unlocking time capsules, with
         * no in-app way to remove it (delete only works for locally-manual entries). Reject anything that
         * couldn't be a real "together" session: end before start, either bound outside a small window
         * around now, or a duration longer than any plausible continuous together-session. */
        const val MAX_PLAUSIBLE_SESSION_DURATION_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30 days

        /** BUG fix, round 2: how often the metadata-phase watchdog polls for stalled progress - see
         * metadataResponseSignal's own "round 2" doc for the full reasoning behind this stall-based
         * design replacing the original fixed-deadline one. */
        private const val METADATA_STALL_CHECK_INTERVAL_MILLIS = 5_000L

        /** BUG fix, round 2: the metadata phase gives up if NO chunk (sent or received) has made
         * progress for this long - not "the whole phase took longer than this," which a large-but-real
         * payload could legitimately exceed at this protocol's real throughput (~180-byte chunks, one
         * per BLE connection event). A peer that's genuinely gone silent mid-phase is caught quickly;
         * one still trickling real data, however slowly, is allowed to keep going. */
        private const val METADATA_STALL_TIMEOUT_MILLIS = 20_000L

        /** BUG fix, round 2: absolute worst-case ceiling on the whole metadata phase regardless of
         * ongoing trickle progress - defense-in-depth against a peer that deliberately sends just enough
         * to keep resetting the stall timeout above without ever actually finishing, which would
         * otherwise be able to hold this device's single-sync-attempt guard open indefinitely. Generous
         * enough that no legitimate transfer at this protocol's real throughput should ever hit it. */
        private const val METADATA_PHASE_ABSOLUTE_CEILING_MILLIS = 600_000L
    }
}
