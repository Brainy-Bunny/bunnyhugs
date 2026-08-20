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
import com.ssbmedia.twogether.data.datastore.PairingStore
import com.ssbmedia.twogether.data.datastore.SettingsStore
import com.ssbmedia.twogether.data.db.DEFAULT_LIST_ID
import com.ssbmedia.twogether.data.db.DateIdea
import com.ssbmedia.twogether.data.db.ListCategory
import com.ssbmedia.twogether.data.db.Milestone
import com.ssbmedia.twogether.notif.MilestoneAlarmScheduler
import com.ssbmedia.twogether.notif.Notifications
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.data.db.MomentNote
import com.ssbmedia.twogether.data.db.DayNote
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.data.repo.DateIdeaRepository
import com.ssbmedia.twogether.data.repo.DayNoteRepository
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
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Tiny custom GATT protocol piggybacked on the proximity connection: exchange ONE combined JSON
 * envelope covering date ideas, closed together-sessions (Feature A), moment metadata + per-partner
 * moment notes (Feature D), milestones (Feature F), per-partner calendar day notes (UX-FIX-PLAN.md
 * Phase 4 item 25), and two couple-level settings (session grace window + reunion threshold, plain
 * last-write-wins scalars rather than a table) as chunked bytes over one write+notify characteristic,
 * then merge each piece locally with its own table-appropriate strategy. One side acts
 * as GATT server (passive), the other as GATT client (initiates) - the caller decides the role via a
 * deterministic tie-break so both phones never both try the same role.
 *
 * Bundling everything into one envelope (rather than a separate GATT exchange per table) keeps the
 * connection/handshake/MTU-negotiation machinery below exactly as it was for the original date-ideas-only
 * version - only serialize()/deserialize() and what happens with the parsed result changed.
 *
 * SECURITY (mutual handshake): every connection must prove BOTH ways - each side proves it knows the
 * shared pair secret to the OTHER, and each side's identity is checked against this pairing's pinned
 * partner (see checkPinOrRecordPending) - before EITHER side transmits a byte of real chunk data. This
 * closes a gap an earlier one-way version of this handshake had: a fake "server" that knew nothing about
 * the shared secret could still receive a real client's full payload, since the client used to send it
 * the moment its own handshake write locally succeeded, without ever checking the other side proved
 * anything back. See BleConstants.computeMutualHandshakeResponse's own doc for the wire protocol (message
 * 2: the client's nonce + proof + identity; message 3: the server's proof + identity, no nonce needed
 * since both are already known by then) and BleConstants' class doc for what this mutual proof does and
 * does not guarantee (notably: no session key is derived, so this is not the same as an encrypted
 * channel). A fresh nonce every connection means a captured message can never be replayed against a later
 * connection - see startServer/connectAsClient below.
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
    private val dayNoteRepository: DayNoteRepository,
    private val milestoneRepository: MilestoneRepository,
    private val timeCapsuleRepository: com.ssbmedia.twogether.data.repo.TimeCapsuleRepository,
    private val settingsStore: SettingsStore,
    private val pairingStore: PairingStore,
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
        // SECURITY: lets the receiver's applyPayload pin (or verify against an already-pinned) sender
        // identity - see PairingStore.pinPartnerDeviceIdIfAbsent's doc.
        obj.put("senderDeviceId", deviceId)
        // Identifying context for the receiver's pending-resync-request UI (see PendingResyncRequest's
        // doc) - lets a user who gets an unexpected resync request tell it apart from their real partner
        // by name, not just a bare device id. Best-effort only: whatever this device's own partnerName
        // currently is (may be the still-default "Your Person" if the user never set one).
        obj.put("senderPartnerName", pairingStore.current().partnerName)
        // UX (user-requested follow-up): lets the receiver adopt whichever of the two live "together
        // since" moments is earlier - see SessionRepository.adoptEarlierOpenSessionStart's doc. Never
        // an actual session row (still deliberately excluded, see the class doc above) - just the raw
        // moment, so the receiver's own already-open session (if it has one) can be timestamp-corrected
        // rather than a duplicate row being created.
        obj.put("openSessionStartedAt", sessionRepository.getOpenSession()?.startedAt ?: JSONObject.NULL)
        obj.put("dateIdeas", serializeDateIdeas(dateIdeaRepository.getAll()))
        obj.put("listCategories", serializeListCategories(listCategoryRepository.getAll()))
        obj.put("sessions", serializeSessions(sessionRepository.getAllIncludingDeleted().filter { it.endedAt != null }))
        obj.put("moments", serializeMoments(momentRepository.getAllIncludingDeleted()))
        obj.put("notes", serializeNotes(momentNoteRepository.getAllForAuthor(deviceId)))
        // UX-FIX-PLAN.md Phase 4 item 25: same "only ever send MY OWN authored rows" shape as notes just
        // above - the partner already has their own copy of their own day notes, so there is nothing to
        // gain (and a real risk of the own-deviceId guard below ever seeing a value it doesn't expect) in
        // ever sending back rows authored by someone else.
        obj.put("dayNotes", serializeDayNotes(dayNoteRepository.getAllForAuthor(deviceId)))
        obj.put("milestones", serializeMilestones(milestoneRepository.getAll()))
        // Feature: Time Capsule sync - full table including tombstones, same reasoning as
        // sessions/moments/milestones above (a capsule deleted on this device must propagate that
        // deletion, not just live capsules).
        obj.put("timeCapsules", serializeTimeCapsules(timeCapsuleRepository.getAllIncludingDeleted()))
        // Couple-level settings sync: sessionGraceMinutes/reunionThresholdMinutes only - see
        // AppSettings.sessionGraceMinutesUpdatedAt/reunionThresholdMinutesUpdatedAt's own docs for why a
        // plain per-field last-write-wins merge (by these two timestamps) is the whole mechanism, same
        // shape as every other synced entity's updatedAt. Deliberately NOT syncing
        // ProximityPersistedState.reunionCount itself here or anywhere - each phone's own reunion count
        // stays independently detected, only the threshold going forward is shared.
        val localSettings = settingsStore.current()
        obj.put("sessionGraceMinutes", localSettings.sessionGraceMinutes)
        obj.put("sessionGraceMinutesUpdatedAt", localSettings.sessionGraceMinutesUpdatedAt)
        obj.put("reunionThresholdMinutes", localSettings.reunionThresholdMinutes)
        obj.put("reunionThresholdMinutesUpdatedAt", localSettings.reunionThresholdMinutesUpdatedAt)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    /** Applies a received combined payload: merges each table with its own strategy (see each
     * repository's mergeRemote / mergeRemoteSessions / mergeRemoteStubs doc for why they differ). Returns
     * the sender's per-moment syncId/hasPhoto/takenAt snapshot (Feature 2) so the caller can decide what
     * photo bytes to push/request next, without re-parsing the raw bytes a second time. */
    /** [handshakeVerifiedDeviceId] is the identity the mutual handshake already cryptographically proved
     * for this connection (see checkPinOrRecordPending's own call sites) - see this function's own
     * SECURITY comment below for why it, not the JSON body's self-reported field, is what the pin check
     * and pin write must actually trust. */
    private suspend fun applyPayload(bytes: ByteArray, remoteDevice: BluetoothDevice? = null, handshakeVerifiedDeviceId: String): List<RemoteMomentInfo> {
        // MAJOR fix (ultimate-app-review round 1, Opus+Sonnet; placement corrected round 2, Opus): reset
        // at the true FIRST line of every attempt, before anything below that can throw (device-id
        // lookup, JSON parsing) - round 2 live-caught a real JSONTokener.syntaxError thrown from the JSON
        // parse below with the reset still sitting after it, so a mismatch flagged by one attempt was
        // still `true` when a LATER attempt failed for an unrelated reason (a malformed/truncated
        // payload), and the UI told the user to unpair over what was actually a transfer/parse failure.
        lastSyncFailedDueToPartnerMismatch = false
        val deviceId = settingsStore.getOrCreateLocalDeviceId()
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        // SECURITY (test-code-allmodels round 3, Sonnet + Opus independently, clean-room): this used to
        // read `senderDeviceId` from the JSON body itself (`root.optString("senderDeviceId", "")`) for
        // BOTH the pin check below AND the pin write later in this function - but that field is completely
        // unauthenticated (never bound into the handshake's HMAC), unlike `handshakeVerifiedDeviceId`
        // (the caller's parameter), which the mutual handshake already cryptographically proved for this
        // exact connection before applyPayload was ever reached. A peer that legitimately passes the
        // handshake with its real id could still put a DIFFERENT, arbitrary value in this JSON field -
        // defeating the entire point of binding identity into the HMAC, and on first pairing (no pin yet)
        // could get this device to pin to a value that matches neither the peer nor any real partner,
        // permanently breaking future syncs. Now sourced exclusively from the parameter for every
        // security-relevant purpose (the check below, and the write near this function's end); the JSON
        // field itself is no longer read here at all.
        val senderPartnerName = root.optString("senderPartnerName", "").ifBlank { null }
        val currentPairing = pairingStore.current()
        val pinnedPartnerDeviceId = currentPairing.pinnedPartnerDeviceId
        // SECURITY (Fix #2, Fable review - closes Gap B): a blank sender id USED TO be treated as
        // "unknown, not necessarily hostile" and allowed through unpinned once this device was already
        // pinned - meant as backward compatibility for a payload from a build that predates this field,
        // but in practice this was a standing bypass of the entire pinning invariant: anyone who simply
        // omitted it skipped the check outright. Once a pin exists, EVERY sender - blank id included - is
        // now compared against it, so the comparison below no longer exempts blank. (A blank
        // handshakeVerifiedDeviceId can't actually reach here in practice - checkPinOrRecordPending
        // already rejects the connection before applyPayload runs if the handshake-bound id doesn't match
        // an existing pin, blank included - but the comparison stays correct/defensive either way.)
        if (!pinnedPartnerDeviceId.isNullOrBlank() && handshakeVerifiedDeviceId != pinnedPartnerDeviceId) {
            Log.w(TAG, "Rejecting sync payload: sender device id did not match this pairing's pinned partner")
            // MAJOR fix (ultimate-app-review round 1, Opus+Sonnet): both independently live-reproduced a
            // permanent, one-directional sync lockout after a partner reinstall (fresh local device id) -
            // the ONLY feedback was the generic "Couldn't sync - make sure you're together" message, which
            // is actively misleading (the devices WERE together and connecting) and points the user at
            // nothing useful, since the prominent "Reconnect to X" post-unpair button just restores this
            // same stale pin and fails again. Flagging the reason here lets the UI say something the user
            // can actually act on (see lastSyncFailedDueToPartnerMismatch's doc + Settings/OurListsScreen).
            lastSyncFailedDueToPartnerMismatch = true
            // SECURITY (user-designed follow-up): the rejection above never used to be remembered
            // anywhere - a permanently-stale pin (this device's partner reinstalled, or someone unpaired
            // and rejoined leaving THIS side's pin looking at a now-dead identity) had no path back to
            // working except the user stumbling onto Settings' subtitle text and manually unpairing. This
            // records who just tried so the user can be asked directly "is this actually your partner?"
            // instead of the attempt just silently vanishing - see PairingStore.recordPendingResyncRequest's
            // own doc, and Home's pending-resync dialog for where this gets surfaced. A blank
            // handshakeVerifiedDeviceId has nothing to record against (recordPendingResyncRequest itself
            // no-ops on blank) or notify about, but is still rejected above by the comparison itself.
            // NOTE: this whole branch is now defense-in-depth only, not the primary enforcement point -
            // checkPinOrRecordPending (run during the handshake, before this function is ever called)
            // already rejects a mismatched connection earlier and records the SAME pending-resync entry;
            // reaching this branch at all would mean that earlier check was somehow bypassed.
            if (handshakeVerifiedDeviceId.isNotBlank()) {
                // Best-effort Bluetooth device name - requires BLUETOOTH_CONNECT on API 31+, which this
                // sync path already implicitly required to get this far (see BlePermissions.hasBlePermissions
                // gating both connectAsClient and the server accept path); wrapped defensively anyway since
                // reading .name specifically can still throw on some OEM/permission-timing edge cases.
                val bluetoothName = try { remoteDevice?.name } catch (e: SecurityException) { null }
                // Fix #5 (Fable review, notification throttle): only notify on a genuinely NEW pending
                // device, not on every retry of an already-known one - a rejected peer's own retry loop
                // (this app's own Home reconnect-verification included) fires every few seconds, and
                // re-notifying on each attempt would be spam for something the user already saw once.
                val isNewPendingDevice = pairingStore.recordPendingResyncRequest(handshakeVerifiedDeviceId, bluetoothName, senderPartnerName)
                if (isNewPendingDevice) {
                    Notifications.showResyncRequestNotification(context, currentPairing.partnerName)
                }
            }
            throw SecurityException("Sync payload sender did not match pinned partner device")
        }
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
        // UX (user-requested follow-up): see SessionRepository.adoptEarlierOpenSessionStart's doc. Only
        // runs on an already-trusted payload (below the pin-mismatch check above), same as every other
        // merge in this function - corrected into OUR clock's frame with the same peerClockOffsetMillis
        // every other timestamp here uses.
        if (!root.isNull("openSessionStartedAt")) {
            sessionRepository.adoptEarlierOpenSessionStart(root.optLong("openSessionStartedAt", 0L) - peerClockOffsetMillis)
        }
        val momentsArr = root.optJSONArray("moments")
        val momentsParsed = deserializeMoments(momentsArr, peerClockOffsetMillis)
        momentRepository.mergeRemoteStubs(momentsParsed)
        val notesArr = root.optJSONArray("notes")
        val notesParsed = deserializeNotes(notesArr, peerClockOffsetMillis)
        momentNoteRepository.mergeRemote(notesParsed, deviceId)
        // UX-FIX-PLAN.md Phase 4 item 25: see DayNoteRepository.mergeRemote's doc for the identical
        // own-deviceId-never-trusted reasoning momentNoteRepository.mergeRemote above already applies.
        val dayNotesArr = root.optJSONArray("dayNotes")
        val dayNotesParsed = deserializeDayNotes(dayNotesArr, peerClockOffsetMillis)
        dayNoteRepository.mergeRemote(dayNotesParsed, deviceId)
        // BUG fix: an independent audit round found milestones arriving via sync never got their yearly
        // alarm armed until the next cold start/boot, unlike every other way a Milestone enters the DB
        // (local add, backup restore, app start) - all of which call MilestoneAlarmScheduler right away.
        // Arms only what was actually upserted here, not the whole table, for the same reason
        // BackupManager.scheduleAll is only ever called once per restore rather than on every sync.
        val milestonesArr = root.optJSONArray("milestones")
        val (milestonesParsed, milestonesMissingLinkedMomentField) = deserializeMilestones(milestonesArr, peerClockOffsetMillis)
        val upsertedMilestones = milestoneRepository.mergeRemote(milestonesParsed, milestonesMissingLinkedMomentField)
        MilestoneAlarmScheduler.scheduleAll(context, upsertedMilestones.filter { !it.deleted })
        // Feature: Time Capsule sync. See TimeCapsuleRepository.mergeRemote's doc for why unlockedAt is
        // never trusted from this parsed data even though the definitional fields are.
        val timeCapsulesArr = root.optJSONArray("timeCapsules")
        val timeCapsulesParsed = deserializeTimeCapsules(timeCapsulesArr, peerClockOffsetMillis)
        timeCapsuleRepository.mergeRemote(timeCapsulesParsed)
        // Couple-level settings sync: plain per-field last-write-wins, same shape as every other
        // entity's updatedAt merge in this function, just against two bare scalars instead of a table -
        // see AppSettings.sessionGraceMinutesUpdatedAt/reunionThresholdMinutesUpdatedAt's own docs. A
        // peer that never touched a setting sends its timestamp as 0L, which - after the same
        // peerClockOffsetMillis correction every other timestamp here gets - naturally loses to any real
        // local customization (real timestamp > 0) and naturally loses to another untouched local default
        // (0 > 0 is false either way), so no special-casing of "never touched" is needed: the ordinary
        // comparison already does the right thing in both directions.
        val localSettingsForMerge = settingsStore.current()
        val remoteSessionGraceMinutes = root.optInt("sessionGraceMinutes", -1)
        val remoteSessionGraceUpdatedAt = root.optLong("sessionGraceMinutesUpdatedAt", 0L) - peerClockOffsetMillis
        if (shouldAdoptSyncedSetting(remoteSessionGraceMinutes, remoteSessionGraceUpdatedAt, localSettingsForMerge.sessionGraceMinutesUpdatedAt)) {
            settingsStore.applySyncedSessionGraceMinutes(remoteSessionGraceMinutes, remoteSessionGraceUpdatedAt)
        }
        val remoteReunionThresholdMinutes = root.optInt("reunionThresholdMinutes", -1)
        val remoteReunionThresholdUpdatedAt = root.optLong("reunionThresholdMinutesUpdatedAt", 0L) - peerClockOffsetMillis
        if (shouldAdoptSyncedSetting(remoteReunionThresholdMinutes, remoteReunionThresholdUpdatedAt, localSettingsForMerge.reunionThresholdMinutesUpdatedAt)) {
            settingsStore.applySyncedReunionThresholdMinutes(remoteReunionThresholdMinutes, remoteReunionThresholdUpdatedAt)
        }
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
            (dayNotesArr?.length() ?: 0) - dayNotesParsed.size +
            (milestonesArr?.length() ?: 0) - milestonesParsed.size +
            (timeCapsulesArr?.length() ?: 0) - timeCapsulesParsed.size
        // SECURITY (Fix #3, Fable review): reaching this line at all means the sender passed the pin check
        // above (or there was no pin yet) and every table finished merging - the strongest available
        // signal that any OTHER device's earlier pending resync request is stale, since the real partner
        // just proved they're still alive and reachable under this pairing. See
        // PairingStore.clearPendingResyncRequestsOlderThan's doc; a no-op when the list is already empty.
        pairingStore.clearPendingResyncRequestsOlderThan(System.currentTimeMillis())
        // SECURITY: only pin once the whole payload has genuinely merged successfully (a no-op after the
        // first time - see pinPartnerDeviceIdIfAbsent's doc), and only pin the HANDSHAKE-VERIFIED identity
        // (see this function's own SECURITY comment near its start for why - this is the fix, not just
        // the check above: pinning must never trust the unauthenticated JSON body for the value that
        // becomes this device's permanent trust anchor for the relationship). A blank
        // handshakeVerifiedDeviceId (old-build peer) is simply never pinned, which is fine -
        // unauthenticated pairs still get the exact pre-pinning behavior they always had.
        if (handshakeVerifiedDeviceId.isNotBlank()) pairingStore.pinPartnerDeviceIdIfAbsent(handshakeVerifiedDeviceId)
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

    /** MAJOR fix (ultimate-app-review round 1, Opus+Sonnet): set (and reset) at the start of every
     * [applyPayload] call, true only if THIS attempt was specifically rejected by the device-pinning
     * check above - as opposed to any other sync failure (not together, timeout, malformed payload).
     * Read by [ProximityForegroundService] right before it emits the sync-completion event, same
     * read-right-before-emit pattern as [lastSyncDroppedImplausibleCount] above, so the UI can show a
     * message the user can actually act on (unpair + fresh code) instead of the generic
     * "make sure you're together" text, which is actively misleading for this specific failure - the
     * devices really were together and really did connect. `@Volatile` for the same cross-coroutine
     * read/write reason as lastSyncDroppedImplausibleCount. */
    @Volatile
    var lastSyncFailedDueToPartnerMismatch: Boolean = false
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

    /** Couple-level settings sync: the whole last-write-wins decision for one scalar setting
     * (sessionGraceMinutes/reunionThresholdMinutes), extracted as a pure function purely so it's directly
     * unit-testable without a real SettingsStore/DataStore - see applyPayload's settings-sync block for
     * the two call sites. [remoteUpdatedAt] must already be peerClockOffsetMillis-corrected by the
     * caller, same as every other wire timestamp in this file. */
    private fun shouldAdoptSyncedSetting(remoteMinutes: Int, remoteUpdatedAt: Long, localUpdatedAt: Long): Boolean =
        remoteMinutes in 1..MAX_SETTINGS_MINUTES &&
            isPlausibleWireUpdatedAt(remoteUpdatedAt) &&
            remoteUpdatedAt > localUpdatedAt

    /** MAJOR fix (ultimate-app-review, post-restart full-scope round, Opus): see applyPayload's own doc
     * for why an unbounded peer clock offset let an obviously-broken peer clock launder its data into a
     * plausible-looking but wrong moment in the past. Extracted as its own function purely so it's
     * independently testable. */
    /**
     * NOTE (independent review of v2.6, round 2 - a deliberately REJECTED fix, recorded so it is not
     * re-attempted): the raw offset is `peer.deviceTimestamp - our System.currentTimeMillis()`, but
     * `deviceTimestamp` is stamped when the peer BUILDS the payload (see buildPayload) while our "now" is
     * read when we APPLY it, so the chunked-BLE transfer time in between is counted as if it were clock
     * skew and inflates every incoming timestamp by roughly the one-way transfer time (live-measured at
     * 250..390ms). That inflation is what USED to drive a permanent `updatedAt` ratchet: the echoed copy
     * of an unchanged row looked newer, was accepted, re-written, and echoed back bigger still, forever.
     *
     * The first attempt at fixing that added a 60s floor here - ignore any offset too small to be
     * distinguishable from transfer latency. Round 2 of the review rejected it, correctly: a floor cannot
     * tell "transfer latency" from "the partner's clock is genuinely 59s fast", and suppressing the
     * correction in the latter case makes LWW resolve by WHOSE CLOCK IS AHEAD rather than who edited
     * last. Concretely, with B's clock 59s fast: B edits at wall time T (stored T+59); A edits the same
     * row 30s LATER (stored T+30); on the next sync A's own newer edit loses to B's older one and is
     * silently overwritten on both phones. That is exactly the silent-data-loss class this whole file's
     * skew correction exists to prevent, so the floor traded a churn bug for a correctness bug.
     *
     * The ratchet is instead fixed entirely on the merge side, where it belongs: the LWW merges now skip
     * writing a row whose meaningful content is unchanged (see DateIdeaRepository.mergeRemote), so an
     * echo is never written and therefore never re-stamped or re-echoed. A row that genuinely DID change
     * absorbs the one-way inflation exactly once on the receiving side and then stops - live-verified as
     * a single frozen ~288ms offset between the two devices' copies rather than a monotonic climb. No
     * floor is needed, and none should be added back.
     */
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
                // MAJOR fix (independent review): see Moment.takenWhileTogether's doc. `sessionId` is
                // deliberately NOT on the wire (it's a foreign local auto-increment id and is nulled on
                // receipt), so before this field existed the receiver had no way to know a photo was taken
                // during a together-session and captioned every single one of the partner's photos "Taken
                // apart" - contradicting the sending phone's own caption for the identical photo.
                put("takenWhileTogether", m.takenWhileTogether)
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
                // MUST be passed explicitly: this constructor leaves `sessionId` at its null default, so
                // the entity's own `takenWhileTogether = sessionId != null` default would resolve to
                // false here and silently recreate the exact "every partner photo says Taken apart" bug
                // this field was added to fix. Defaults to false only for a payload from a partner still
                // on a build that predates this field - degrading to the old behaviour for that one case
                // rather than guessing. Display-only and therefore safe to take from the peer verbatim -
                // see Moment.takenWhileTogether's TRUST NOTE.
                takenWhileTogether = o.optBoolean("takenWhileTogether", false),
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

    /** UX-FIX-PLAN.md Phase 4 item 25: wire support for DayNote, wired in by hand (not by any sub-agent -
     * see DayNote's own doc for why) after the rest of the feature (entity/DAO/repository/migration/
     * backup/UI) was already built and verified separately. Deliberately as close to a byte-for-byte copy
     * of serializeNotes/deserializeNotes above as the different field names allow - same composite-key
     * shape (date + authorDeviceId instead of momentSyncId + authorDeviceId), same plausibility gate on
     * updatedAt, same "only my own authored rows are ever sent" restriction at the call site in
     * buildPayload. */
    private fun serializeDayNotes(notes: List<DayNote>): JSONArray {
        val arr = JSONArray()
        for (n in notes) {
            arr.put(JSONObject().apply {
                put("date", n.date)
                put("authorDeviceId", n.authorDeviceId)
                put("text", n.text)
                put("updatedAt", n.updatedAt)
                put("deleted", n.deleted)
            })
        }
        return arr
    }

    private fun deserializeDayNotes(arr: JSONArray?, peerClockOffsetMillis: Long = 0L): List<DayNote> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val date = o.getLong("date")
            val updatedAt = o.getLong("updatedAt") - peerClockOffsetMillis
            if (!isPlausibleWireUpdatedAt(updatedAt)) {
                Log.w(TAG, "Dropping remote day note for epochDay=$date with implausible updatedAt=$updatedAt")
                return@mapNotNull null
            }
            DayNote(
                date = date,
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
                put("linkedMomentSyncId", m.linkedMomentSyncId ?: JSONObject.NULL)
            })
        }
        return arr
    }

    /** Returns the deserialized milestones alongside the id set of any whose wire payload had NO
     * "linkedMomentSyncId" key at all (as opposed to the key being present-and-null) - see the field's
     * own comment below and [MilestoneRepository.mergeRemote]'s matching parameter for why this
     * distinction has to survive past this function instead of collapsing to null here. */
    private fun deserializeMilestones(arr: JSONArray?, peerClockOffsetMillis: Long = 0L): Pair<List<Milestone>, Set<String>> {
        if (arr == null) return emptyList<Milestone>() to emptySet()
        val missingLinkedMomentField = mutableSetOf<String>()
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val updatedAt = o.getLong("updatedAt") - peerClockOffsetMillis
            if (!isPlausibleWireUpdatedAt(updatedAt)) {
                Log.w(TAG, "Dropping remote milestone $id with implausible updatedAt=$updatedAt")
                return@mapNotNull null
            }
            if (!o.has("linkedMomentSyncId")) missingLinkedMomentField += id
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
                deleted = o.optBoolean("deleted", false),
                // BLOCKER fix (ultimate-app-review round 1, Opus): a partner still on a pre-this-commit
                // build echoes this milestone back with no "linkedMomentSyncId" key at all. org.json's
                // isNull() treats "key absent" identically to "key present and null", so this used to
                // deserialize to null unconditionally - and since the bounded clock-skew correction above
                // usually makes an unmodified echo's updatedAt land just after ours, the whole-row LWW
                // merge would then silently null out a link the user had just set, with zero user action
                // and zero error. The "key genuinely absent" case is now flagged via missingLinkedMomentField
                // (built above) so mergeRemote can preserve the local value instead of trusting this null -
                // this line still correctly maps an EXPLICIT null (same-build partner intentionally cleared
                // the link) to null, since only mergeRemote can tell the two cases apart.
                linkedMomentSyncId = if (o.isNull("linkedMomentSyncId")) null else o.getString("linkedMomentSyncId")
            )
        }
        return list to missingLinkedMomentField
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

    // ---- SECURITY: mutual-handshake wire framing (see BleConstants.computeMutualHandshakeResponse's
    // doc for why this exists). Both message shapes below are chunked exactly like the JSON/photo
    // payloads (MORE/LAST flag byte + toChunks/effectiveChunkPayload) rather than sent as one raw
    // write/notify - a JCE-derived nonce+response+deviceId+partnerName blob can run past 20 bytes,
    // which would silently truncate on a device where MTU negotiation fell back to the 23-byte
    // default (see effectiveChunkPayload's own doc), corrupting the handshake in a way that's easy to
    // miss in testing on hardware that always negotiates a large MTU. ----

    /** Message 2 (client -> server) and, structurally identical, what the server parses it back into:
     * the client's own fresh nonce, its proof-of-key HMAC, and its (length-prefixed) deviceId/
     * partnerName - see BleConstants.computeMutualHandshakeResponse's doc for why deviceId/partnerName
     * are bound into the HMAC rather than sent as a separate unauthenticated field. */
    private data class HandshakeProofMessage(
        val nonce: ByteArray,
        val response: ByteArray,
        val deviceId: ByteArray,
        val partnerName: ByteArray
    )

    private fun encodeHandshakeProofMessage(nonce: ByteArray, response: ByteArray, deviceId: ByteArray, partnerName: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(nonce)
        out.write(response)
        out.write(deviceId.size)
        out.write(deviceId)
        out.write(partnerName.size)
        out.write(partnerName)
        return out.toByteArray()
    }

    /** Returns null on any malformed/truncated input (a corrupt reassembly, a stray/adversarial write) -
     * callers must treat null exactly like a failed HMAC check (reject, don't crash), never throw. */
    private fun decodeHandshakeProofMessage(bytes: ByteArray): HandshakeProofMessage? {
        val nonceLen = BleConstants.HANDSHAKE_NONCE_BYTES
        val respLen = BleConstants.HANDSHAKE_RESPONSE_BYTES
        if (bytes.size < nonceLen + respLen + 1) return null
        val nonce = bytes.copyOfRange(0, nonceLen)
        val response = bytes.copyOfRange(nonceLen, nonceLen + respLen)
        var offset = nonceLen + respLen
        val deviceIdLen = bytes[offset].toInt() and 0xFF
        offset += 1
        if (offset + deviceIdLen > bytes.size) return null
        val deviceId = bytes.copyOfRange(offset, offset + deviceIdLen)
        offset += deviceIdLen
        if (offset >= bytes.size) return null
        val partnerNameLen = bytes[offset].toInt() and 0xFF
        offset += 1
        if (offset + partnerNameLen > bytes.size) return null
        val partnerName = bytes.copyOfRange(offset, offset + partnerNameLen)
        return HandshakeProofMessage(nonce, response, deviceId, partnerName)
    }

    /** Message 3 (server -> client): the server's own proof-of-key HMAC plus its (length-prefixed)
     * deviceId/partnerName - no nonce field, since by this point the server has already sent nonceS as
     * the bare first notification (unchanged, pre-existing behavior) and the client generated its own
     * nonceC locally, so both nonces are already known to whoever's computing/verifying this message. */
    private data class HandshakeReplyMessage(val response: ByteArray, val deviceId: ByteArray, val partnerName: ByteArray)

    private fun encodeHandshakeReplyMessage(response: ByteArray, deviceId: ByteArray, partnerName: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(response)
        out.write(deviceId.size)
        out.write(deviceId)
        out.write(partnerName.size)
        out.write(partnerName)
        return out.toByteArray()
    }

    private fun decodeHandshakeReplyMessage(bytes: ByteArray): HandshakeReplyMessage? {
        val respLen = BleConstants.HANDSHAKE_RESPONSE_BYTES
        if (bytes.size < respLen + 1) return null
        val response = bytes.copyOfRange(0, respLen)
        var offset = respLen
        val deviceIdLen = bytes[offset].toInt() and 0xFF
        offset += 1
        if (offset + deviceIdLen > bytes.size) return null
        val deviceId = bytes.copyOfRange(offset, offset + deviceIdLen)
        offset += deviceIdLen
        if (offset >= bytes.size) return null
        val partnerNameLen = bytes[offset].toInt() and 0xFF
        offset += 1
        if (offset + partnerNameLen > bytes.size) return null
        val partnerName = bytes.copyOfRange(offset, offset + partnerNameLen)
        return HandshakeReplyMessage(response, deviceId, partnerName)
    }

    /** Defensively truncates a UTF-8-encoded field to BleConstants.MAX_HANDSHAKE_FIELD_BYTES before it
     * goes anywhere near the wire or the HMAC - see that constant's own doc. Applied identically on
     * both the encode and (implicitly, since the check side only ever reads back what was actually
     * sent) decode side, so this never causes a spurious mismatch on its own. */
    private fun String.toHandshakeFieldBytes(): ByteArray {
        val bytes = toByteArray(Charsets.UTF_8)
        return if (bytes.size > BleConstants.MAX_HANDSHAKE_FIELD_BYTES) bytes.copyOf(BleConstants.MAX_HANDSHAKE_FIELD_BYTES) else bytes
    }

    /** SECURITY (mutual-handshake follow-up): the identity-and-consent gate now enforced BEFORE either
     * side of a connection ever transmits a byte of real payload data - see the class doc's "Every
     * connection must first prove..." section and PairingStore.recordPendingResyncRequest's own doc.
     * Returns true (proceed) when there's no pin yet, or [remoteDeviceId] matches the existing pin;
     * false (the caller must refuse to continue - no payload, no pin update, disconnect) otherwise, with
     * the pending-resync-request + throttled notification already recorded as a side effect of the
     * false path. Shared by BOTH the server's and the client's handshake completion, which is exactly
     * the point: neither role gets to skip this check anymore, unlike the old design where only the
     * side that happened to receive a full JSON payload ever ran it. */
    private suspend fun checkPinOrRecordPending(remoteDeviceId: String, remoteDevice: BluetoothDevice?, remotePartnerName: String): Boolean {
        val currentPairing = pairingStore.current()
        val pinned = currentPairing.pinnedPartnerDeviceId
        // SECURITY (test-code-allmodels round 1, Haiku): a blank remoteDeviceId must NOT be treated as
        // an automatic pass once a pin exists - that's exactly the blank-senderDeviceId bypass this
        // session already closed once in applyPayload's own check (see its own SECURITY comment); this
        // helper had accidentally reintroduced an equivalent gap. Blank is only ever a pass when there's
        // no pin yet at all (the legitimate first-pairing case) - once pinned, blank is compared like any
        // other id and correctly fails (blank never equals a real pinned id).
        // MAJOR fix (test-code-allmodels round 1, Opus): reset here, at the top of every call, exactly
        // like applyPayload's own reset at its first line - since a handshake-time rejection now means
        // applyPayload is never reached at all for this attempt, its reset alone no longer covers every
        // path, and a stale `true` left over from an EARLIER, unrelated failed attempt could otherwise
        // still be sitting there when THIS attempt's result is read.
        lastSyncFailedDueToPartnerMismatch = false
        // SECURITY (test-code-allmodels round 4, Opus - blocker): a blank deviceId has no legitimate
        // sender (getOrCreateLocalDeviceId() is never blank) - previously this passed trivially
        // whenever no pin existed yet (via the pinned.isNullOrBlank() fast-path below), and a blank id
        // is also never pinned afterward (see applyPayload's own guard), so that pairing would stay
        // stuck in trust-on-first-use forever. Reject outright rather than silently accepting an
        // identity that can never be pinned.
        if (remoteDeviceId.isBlank()) {
            Log.w(TAG, "Rejecting handshake: peer's proven deviceId was blank")
            lastSyncFailedDueToPartnerMismatch = true
            return false
        }
        // SECURITY (test-code-allmodels round 4, Opus - BLOCKER): same-role handshake reflection. One
        // phone holds BOTH GATT roles at once (its own server, plus its own client on the periodic
        // tick/manual "Sync now"), sharing the same handshakeKey - so an attacker who relays a message
        // THIS device's own client produced (proving this device's identity) back to this device's own
        // server (or vice versa) hands over a handshake message the HMAC genuinely verifies, without
        // the attacker ever knowing the shared secret. Every message a device signs always carries its
        // OWN deviceId (see computeMutualHandshakeResponse's call sites), so a peer that "proves"
        // itself using OUR OWN deviceId can only be a reflected copy of our own signature - a real
        // partner always signs with its own, different, deviceId. Reject unconditionally, before the
        // pin check below, so this can never fall through the "no pin yet" fast-path and self-pin.
        if (remoteDeviceId == settingsStore.getOrCreateLocalDeviceId()) {
            Log.w(TAG, "Rejecting handshake: peer's proven deviceId is our own device id (reflection)")
            lastSyncFailedDueToPartnerMismatch = true
            return false
        }
        if (pinned.isNullOrBlank() || remoteDeviceId == pinned) return true
        Log.w(TAG, "Rejecting handshake: connecting device did not match this pairing's pinned partner")
        // MAJOR fix (test-code-allmodels round 1, Opus): this rejection now happens BEFORE applyPayload
        // ever runs, so applyPayload's own `lastSyncFailedDueToPartnerMismatch = true` (which used to be
        // the only place this got set) never fires for this case anymore. Without setting it here too,
        // Home's post-pairing verification and Settings/OurListsScreen's messaging would regress to the
        // generic "make sure you're together" text for the exact case (a stale/reinstalled partner) that
        // fix was built to give an actionable message for.
        lastSyncFailedDueToPartnerMismatch = true
        val bluetoothName = try { remoteDevice?.name } catch (e: SecurityException) { null }
        val isNewPendingDevice = pairingStore.recordPendingResyncRequest(remoteDeviceId, bluetoothName, remotePartnerName.ifBlank { null })
        if (isNewPendingDevice) {
            Notifications.showResyncRequestNotification(context, currentPairing.partnerName)
        }
        return false
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
    // KNOWN ACCEPTED RESIDUAL (test-code-allmodels round 5 Opus, round 6 Sonnet + Opus corrections -
    // reviewed and consciously deferred, not an oversight): this set's clear paths are
    // onConnectionStateChange(STATE_DISCONNECTED) and stopServer() (called on the apart transition, BT
    // adapter off, unpair, and service onDestroy - so the window is bounded to at most one
    // together-session, not unbounded). STATE_CONNECTED now also removes a stale entry as a one-way,
    // trust-REMOVING-only signal (see onConnectionStateChange's own doc) - defense in depth, not a full
    // close, since it depends on the same potentially-unreliable callback. If BOTH a disconnect AND the
    // next connection's own STATE_CONNECTED are missed on some stack, an attacker who can spoof the exact
    // BLE address of a still-(stale-)authenticated entry could skip the handshake entirely -
    // onCharacteristicWriteRequest's `isAuthenticated` check is address-keyed with no further liveness
    // proof. This is a FULL top-invariant violation if hit, not just "receives without proving the
    // secret": serverAuthenticatedDeviceIds[addr] is cleared in lockstep with this set (same callback,
    // same block) and holds the REAL pinned partner's proven id, so applyPayload's pin check (219) passes
    // for the attacker's forged data too - both a poisoned merge attributed to the trusted partner AND
    // exfiltration of this device's real payload back to the attacker. That lockstep coupling is
    // load-bearing: if a future change ever cleared one of these two maps without the other, this residual
    // would silently become either fully closed or fully open depending on which - keep them clearing
    // together. Considered always reissuing a fresh nonce on every CCCD re-subscribe as a full fix, but
    // rejected it for two independent reasons, not just the one originally recorded here: (1)
    // onDescriptorWriteRequest's own doc explains that a spurious mid-sync re-subscribe (an OEM quirk, not
    // attacker-controlled) would inject a raw notification into an otherwise-authenticated connection's
    // JSON/photo stream, corrupting an ACTIVE legitimate sync; (2) even setting that aside, it wouldn't
    // actually close the gap, because Android's GATT server does not require or enforce a CCCD write
    // before accepting a characteristic write - an attacker can simply skip onDescriptorWriteRequest
    // entirely and write straight to the sync characteristic, so any self-healing hung off that callback
    // never triggers for them. A structurally sound full fix needs a real per-connection cryptographic
    // binding that survives regardless of which connection-state callbacks fire - exactly what Stage 2's
    // real key exchange (see BleConstants.kt's own doc) is for. Also note: a legitimately reconnecting
    // partner does NOT race an attacker for this slot - alreadyAuthenticated blocks a fresh nonce from
    // ever being issued to it, so it just times out (HANDSHAKE_NONCE_TIMEOUT_MILLIS) and fails closed,
    // locked out of syncing until the stale entry clears by one of the paths above. Reviewed with the user
    // and explicitly accepted as a narrow, Stage-2-scope residual rather than blocking Stage 1 on it.
    private val authenticatedDevices = HashSet<String>()
    private val deviceMtus = HashMap<String, Int>()
    // Per-device nonce issued at CCCD-subscribe time (see onDescriptorWriteRequest) and consumed the
    // moment that device's handshake response is verified - see the nonce/HMAC handshake doc at the top
    // of this file.
    private val serverNonces = HashMap<String, ByteArray>()
    // SECURITY (test-code-allmodels round 3, Sonnet + Opus): the identity the mutual handshake
    // cryptographically proved for this specific connection - stashed here (keyed by addr, set the
    // moment authentication succeeds, read when the client's actual JSON payload arrives later on this
    // same connection) so applyPayload's pin check/pin write can trust THIS instead of the payload's own
    // unauthenticated, self-reported "senderDeviceId" JSON field. Cleared alongside every other per-addr
    // map on disconnect.
    private val serverAuthenticatedDeviceIds = HashMap<String, String>()
    // SECURITY (test-code-allmodels round 3 Opus, round 4 Sonnet + Opus - three successive refinements
    // of the same finding): before this rework, authenticatedDevices was populated SYNCHRONOUSLY on the
    // binder thread, so it could never outlive the connection that earned it. Now that acceptance
    // requires a suspend call (checkPinOrRecordPending reads DataStore), the commit happens from a
    // scope.launch that can resume AFTER the connection has already dropped and been cleaned up by
    // onConnectionStateChange(DISCONNECTED). A plain "is this address currently connected" set (round 3's
    // fix) closes the case where the address is connected AT ALL, but round 4 Sonnet correctly pointed
    // out that's not enough: BLE addresses are reused across connections (no per-connection identifier
    // exists at the BluetoothGattServerCallback level), so a SECOND, genuinely different connection could
    // reconnect at the SAME address while the first one's coroutine is still suspended - the address-only
    // check would then wrongly see "connected" and commit the FIRST connection's proven identity on
    // behalf of the SECOND (different) connection, which never itself proved anything. An intermediate
    // fix (a per-address HashMap<String, Any> token minted in onConnectionStateChange(CONNECTED)) closed
    // that, but round 4 Opus then pointed out that fix depended on STATE_CONNECTED actually being
    // delivered - not guaranteed on every Android/OEM BLE stack for a passive GATT server that never
    // itself calls gattServer.connect() (this one doesn't). The FINAL fix below has no such dependency:
    // it reuses the per-connection nonce object (serverNonces[addr], see its own doc) as the identity
    // token instead. A nonce is issued in onDescriptorWriteRequest - guaranteed to fire, since the whole
    // handshake already depends on it - fresh ByteArray per connection (so `!==` is a true per-connection
    // discriminator), and already removed on disconnect / replaced on a fresh subscribe. The
    // handshake-completion coroutine below captures its own connection's nonce reference before
    // suspending and, in the same synchronized block as the commit, re-checks that serverNonces[addr]
    // still IS that exact object - never true again once this connection has dropped or a different one
    // has reconnected at the same address. See the coroutine's own call site.
    // SECURITY (mutual-handshake follow-up): reassembly buffer for message 2 (the client's own
    // nonce+proof+identity, see HandshakeProofMessage) and the outgoing chunk queue for message 3 (this
    // server's own proof+identity reply, see HandshakeReplyMessage) - kept SEPARATE from
    // serverIncoming/serverOutQueue (the post-auth JSON buffers) for the same reason serverPhotoIncoming
    // is separate from both: a device mid-handshake must never be confused with a device mid-JSON-sync,
    // even though the two phases never overlap in time for one connection.
    private val serverHandshakeIncoming = HashMap<String, ByteArrayOutputStream>()
    private val serverHandshakeOutQueue = HashMap<String, MutableList<ByteArray>>()
    // Guards serverIncoming / serverOutQueue / serverPhotoIncoming / serverPhotoOutQueue /
    // authenticatedDevices / deviceMtus / serverNonces / serverHandshakeIncoming / serverHandshakeOutQueue,
    // which are otherwise mutated both from GATT binder-thread callbacks and from coroutines launched via
    // [scope].
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
                val addr = device.address
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // SECURITY (test-code-allmodels round 6, Opus - defense-in-depth, see
                    // authenticatedDevices' own doc for the residual this narrows): the per-connection
                    // identity discriminator used to COMMIT a handshake is serverNonces[addr]'s own object
                    // reference (see serverAuthenticatedDeviceIds' neighboring doc), deliberately NOT tied
                    // to STATE_CONNECTED being delivered, since that isn't guaranteed on every stack
                    // (round 4, Opus). But this event, when it DOES fire, is safe to use as a one-way
                    // TRUST-REMOVING signal (never trust-granting) for a brand new connection at this
                    // address: unlike reissuing a nonce here (rejected - see the doc below for why),
                    // simply clearing any previously-authenticated state does not send anything and
                    // cannot corrupt an in-flight legitimate JSON/photo stream, so it carries none of that
                    // rejected fix's risk. Worst case if this ever fires spuriously on an actively-syncing
                    // connection: that connection's next write is treated as unauthenticated, rejected
                    // (serverNonces[addr] is already null post-auth), and the peer's own retry/reconnect
                    // logic recovers it - fail-closed, not silent corruption.
                    // SECURITY (test-code-allmodels, clean-room round 2, Fable): the two trust maps
                    // above are NOT the only state that must be cleared here. onNotificationSent below
                    // pumps serverOutQueue/serverPhotoOutQueue/serverHandshakeOutQueue purely by address,
                    // with NO authentication check of its own - it trusts that anything queued there was
                    // legitimately queued for whoever is currently connected at that address. If the
                    // ORIGINAL authenticated connection's real payload was still mid-send (queued chunks)
                    // when its STATE_DISCONNECTED was missed, those leftover chunks would otherwise still
                    // be sitting in the queue for this NEW connection at the same address - and the very
                    // next notification confirmation for it (e.g. its own handshake nonce, sent moments
                    // from now in onDescriptorWriteRequest) would drain them straight through. Clearing
                    // every per-address buffer here, not just the two trust maps, closes that: for a
                    // genuinely NEW connection none of these buffers legitimately hold anything yet, so
                    // this is safe even in the false-positive case where STATE_CONNECTED spuriously
                    // refires on an already-genuinely-connected device - it just forces a fail-closed
                    // reassembly restart, not a bypass. Deliberately NOT clearing serverNonces here (see
                    // its own doc) or deviceMtus (not security-relevant) - serverNonces is load-bearing
                    // for a legitimate in-flight handshake's atomic commit check, and clearing it out from
                    // under a real in-progress handshake would self-inflict a spurious rejection.
                    synchronized(serverLock) {
                        authenticatedDevices.remove(addr)
                        serverAuthenticatedDeviceIds.remove(addr)
                        serverIncoming.remove(addr)
                        serverOutQueue.remove(addr)
                        serverPhotoIncoming.remove(addr)
                        serverPhotoOutQueue.remove(addr)
                        serverHandshakeIncoming.remove(addr)
                        serverHandshakeOutQueue.remove(addr)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    synchronized(serverLock) {
                        authenticatedDevices.remove(addr)
                        serverIncoming.remove(addr)
                        serverOutQueue.remove(addr)
                        serverPhotoIncoming.remove(addr)
                        serverPhotoOutQueue.remove(addr)
                        deviceMtus.remove(addr)
                        serverNonces.remove(addr)
                        serverHandshakeIncoming.remove(addr)
                        serverHandshakeOutQueue.remove(addr)
                        serverAuthenticatedDeviceIds.remove(addr)
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
                    // MINOR fix (test-code-allmodels round 3, Opus, clean-room): an unauthenticated write
                    // on the PHOTO characteristic used to fall through into the handshake-reassembly logic
                    // below (which never checked which characteristic it was on) - self-inflicted only
                    // (same address, fails at the HMAC either way), but the post-auth branch further down
                    // DOES check the characteristic, so this branch should too for the same reason.
                    if (characteristic.uuid != BleConstants.SYNC_CHARACTERISTIC_UUID) {
                        Log.w(TAG, "Rejecting GATT write from unauthenticated device $addr - wrong characteristic")
                        if (responseNeeded) {
                            try {
                                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, offset, null)
                            } catch (e: SecurityException) { Log.w(TAG, "sendResponse failed", e) }
                        }
                        try { gattServer?.cancelConnection(device) } catch (e: SecurityException) { /* ignore */ }
                        return
                    }
                    // SECURITY (mutual-handshake follow-up, per multi-round advisory review): the
                    // client's own proof (message 2 - HandshakeProofMessage) is chunked like everything
                    // else past the default MTU (see this file's "mutual-handshake wire framing" doc) -
                    // reassembled here into a buffer SEPARATE from the post-auth JSON one, so a device
                    // mid-handshake is never confused with one mid-JSON-sync.
                    val nonceS = synchronized(serverLock) { serverNonces[addr] }
                    if (nonceS == null || expectedHandshakeKey.isEmpty() || value.isEmpty()) {
                        Log.w(TAG, "Rejecting GATT write from unauthenticated device $addr - no nonce on record")
                        if (responseNeeded) {
                            try {
                                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, offset, null)
                            } catch (e: SecurityException) { Log.w(TAG, "sendResponse failed", e) }
                        }
                        try { gattServer?.cancelConnection(device) } catch (e: SecurityException) { /* ignore */ }
                        return
                    }
                    // Ack the raw BLE write immediately regardless of chunk position or eventual
                    // accept/reject outcome - the accept/reject DECISION only happens once the full
                    // message is reassembled below, matching the post-auth JSON path's own shape.
                    if (responseNeeded) {
                        try {
                            gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
                        } catch (e: SecurityException) { Log.w(TAG, "sendResponse failed", e) }
                    }
                    val buffer = synchronized(serverLock) { serverHandshakeIncoming.getOrPut(addr) { ByteArrayOutputStream() } }
                    val flag = value[0]
                    if (value.size > 1) {
                        synchronized(serverLock) {
                            if (buffer.size() + value.size - 1 > MAX_HANDSHAKE_MESSAGE_BYTES) {
                                Log.w(TAG, "Incoming handshake message from client exceeded sanity cap - dropping")
                                buffer.reset()
                            } else {
                                buffer.write(value, 1, value.size - 1)
                            }
                        }
                    }
                    if (flag != BleConstants.CHUNK_FLAG_LAST) return
                    val raw = synchronized(serverLock) { serverHandshakeIncoming.remove(addr); buffer.toByteArray() }
                    val decoded = decodeHandshakeProofMessage(raw)
                    // MINOR fix (test-code-allmodels round 2, Opus): this HMAC computation runs directly on
                    // the GATT binder thread, not inside a coroutine - the client's equivalent computation
                    // was deliberately wrapped for exactly this reason (an unsupported-algorithm JCE
                    // failure on some OEM's crypto provider), but an uncaught throw HERE doesn't just fail
                    // one coroutine, it kills the whole process. HmacSHA256 is effectively always present
                    // on Android in practice, so this is a consistency/robustness fix, not a live crash
                    // path - any failure is treated the same as a genuinely invalid response (reject).
                    val expectedResponse = try {
                        decoded?.let {
                            BleConstants.computeMutualHandshakeResponse(
                                expectedHandshakeKey, BleConstants.HANDSHAKE_ROLE_CLIENT, nonceS, it.nonce, it.deviceId, it.partnerName
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to compute expected handshake response", e)
                        null
                    }
                    // SECURITY (test-code-allmodels, clean-room final pass, Opus + Fable, S1): constant-time
                    // comparison rather than contentEquals' byte-wise early exit - a fresh nonce per
                    // connection already makes an adaptive timing oracle impractical here, but this costs
                    // nothing and removes the argument entirely.
                    if (decoded == null || expectedResponse == null || !MessageDigest.isEqual(expectedResponse, decoded.response)) {
                        Log.w(TAG, "Rejecting GATT write from unauthenticated device $addr - missing/invalid handshake")
                        try { gattServer?.cancelConnection(device) } catch (e: SecurityException) { /* ignore */ }
                        // SECURITY (test-code-allmodels round 5, Sonnet): guarded to only remove OUR OWN
                        // nonce (nonceS, captured above before this HMAC check) - see the identical guard
                        // on the sibling pin-check-false branch below for why an unconditional removal
                        // here could delete a genuinely different, still-live connection's nonce.
                        synchronized(serverLock) { if (serverNonces[addr] === nonceS) serverNonces.remove(addr) }
                        return
                    }
                    // SECURITY: the client has now proven it knows the shared secret AND its claimed
                    // identity (deviceId/partnerName are bound into the HMAC it just produced, so neither
                    // could have been tampered with in transit). Now the identity-and-consent gate, BEFORE
                    // this device ever sends anything real back - see checkPinOrRecordPending's own doc
                    // for why this must happen HERE, not after-the-fact inside applyPayload as it used to
                    // (that check remains too, as defence-in-depth, but this is the one that actually
                    // stops a rejected device from ever receiving our data in the first place).
                    val clientDeviceId = String(decoded.deviceId, Charsets.UTF_8)
                    val clientPartnerName = String(decoded.partnerName, Charsets.UTF_8)
                    val clientNonce = decoded.nonce
                    scope.launch {
                        try {
                            if (!checkPinOrRecordPending(clientDeviceId, device, clientPartnerName)) {
                                try { gattServer?.cancelConnection(device) } catch (e: SecurityException) { /* ignore */ }
                                // MINOR fix (test-code-allmodels round 3, Opus, clean-room): the sibling
                                // HMAC-rejection branch above already removes the now-useless nonce; this
                                // branch didn't. Hygiene only (a nonce is worthless without the key
                                // regardless), but inconsistent with that branch and with stopServer()'s
                                // own stated discipline of leaving no stale per-device state behind.
                                // SECURITY (test-code-allmodels round 4, Opus follow-up): guarded to only
                                // remove OUR OWN nonce (`nonceS`, captured before this coroutine suspended
                                // in checkPinOrRecordPending) - an unconditional removal here could delete
                                // a genuinely different, still-live connection's nonce if one reconnected
                                // at this same address while this (rejected) attempt's coroutine was
                                // suspended, spuriously failing that unrelated connection's own handshake.
                                synchronized(serverLock) { if (serverNonces[addr] === nonceS) serverNonces.remove(addr) }
                                activeServerOnSyncDone(false)
                                return@launch
                            }
                            // SECURITY (test-code-allmodels round 4, Fable + Sonnet + Opus, three combined
                            // fixes - see serverAuthenticatedDeviceIds' neighboring doc for the full
                            // history): (1) Fable: re-check liveness and commit the authentication in the
                            // SAME synchronized block - this coroutine just suspended
                            // (checkPinOrRecordPending reads DataStore), and the connection could have
                            // dropped (and been cleaned up by onConnectionStateChange, running on a
                            // different thread) while it was suspended. A separate check-then-commit left a
                            // TOCTOU gap where a disconnect landing in between two blocks could still let
                            // this coroutine re-add a stale entry. (2) Sonnet: checking mere address
                            // PRESENCE isn't enough either, even atomically - a second, genuinely different
                            // connection could reconnect at the SAME address while this coroutine was
                            // suspended, and an address-only check can't tell them apart; needs a real
                            // per-connection identity token. (3) Opus: that per-connection token must not
                            // itself depend on STATE_CONNECTED being delivered (not guaranteed on every
                            // stack) - so reuse `nonceS` (captured above, before this coroutine was
                            // launched) as the token instead: a fresh ByteArray issued in
                            // onDescriptorWriteRequest for every connection, so `!==` genuinely
                            // discriminates THIS connection from any earlier or later one at the same
                            // address, without relying on connection-state callbacks at all.
                            val committed = synchronized(serverLock) {
                                if (serverNonces[addr] !== nonceS) {
                                    false
                                } else {
                                    // Identity accepted - mark authenticated NOW (before replying) so a
                                    // stray write arriving mid-reply is routed to the post-auth JSON path,
                                    // never back into this handshake branch. Also stash the verified
                                    // identity itself (see serverAuthenticatedDeviceIds' own doc) for when
                                    // the JSON payload arrives.
                                    authenticatedDevices.add(addr)
                                    serverNonces.remove(addr)
                                    serverAuthenticatedDeviceIds[addr] = clientDeviceId
                                    true
                                }
                            }
                            if (!committed) {
                                Log.w(TAG, "Connection $addr dropped mid-handshake - not committing authentication")
                                activeServerOnSyncDone(false)
                                return@launch
                            }
                            val serverDeviceId = settingsStore.getOrCreateLocalDeviceId().toHandshakeFieldBytes()
                            val serverPartnerName = pairingStore.current().partnerName.toHandshakeFieldBytes()
                            val responseS = BleConstants.computeMutualHandshakeResponse(
                                expectedHandshakeKey, BleConstants.HANDSHAKE_ROLE_SERVER, clientNonce, nonceS, serverDeviceId, serverPartnerName
                            )
                            sendHandshakeReplyToClient(device, encodeHandshakeReplyMessage(responseS, serverDeviceId, serverPartnerName))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to complete mutual handshake as server", e)
                            activeServerOnSyncDone(false)
                        }
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
                    val handshakeVerifiedDeviceId = synchronized(serverLock) { serverAuthenticatedDeviceIds[addr] } ?: ""
                    scope.launch {
                        try {
                            applyPayload(raw, remoteDevice = device, handshakeVerifiedDeviceId = handshakeVerifiedDeviceId)
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
                // SECURITY (mutual-handshake follow-up): message 3's remaining chunks (this server's own
                // proof+identity reply) take top priority - they can only ever be in flight BEFORE the
                // device is authenticated, strictly earlier than any photo/JSON queue could have
                // anything for the same connection, so there's no real ordering conflict with the checks
                // below - this is just the natural sequencing.
                val nextHandshake = synchronized(serverLock) {
                    val q = serverHandshakeOutQueue[addr]
                    if (!q.isNullOrEmpty()) q.removeAt(0) else null
                }
                if (nextHandshake != null) {
                    sendRawNotification(device, nextHandshake)
                    return
                }
                synchronized(serverLock) { serverHandshakeOutQueue.remove(addr) }
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

    // SECURITY (test-code-allmodels, clean-room final pass, Opus - TOP INVARIANT): the caller
    // (onCharacteristicWriteRequest's post-auth LAST branch) suspends for buildPayload() - real Room
    // I/O across every table, easily hundreds of ms - between reading the incoming payload and calling
    // this. Nothing previously re-checked that `device`'s address was STILL an authenticated connection
    // once that suspension resolved: an ordinary mid-sync disconnect (no attacker needed) followed by a
    // different device reconnecting at the same address (a spoofed clone, or Android reusing/rotating a
    // random BLE address) would otherwise receive this device's full real payload with no re-proof of
    // the shared secret at all, since notifyCharacteristicChanged targets whatever central currently
    // holds that address, not the specific session that was originally authenticated. Re-checking here,
    // right before the send, closes it: authenticatedDevices/serverAuthenticatedDeviceIds are cleared on
    // disconnect and (see onConnectionStateChange's STATE_CONNECTED doc) on a new connection replacing a
    // stale one, so a dropped-and-replaced address fails this check and the send is silently skipped.
    private fun sendToClient(device: BluetoothDevice, payload: ByteArray) {
        if (!synchronized(serverLock) { authenticatedDevices.contains(device.address) }) {
            Log.w(TAG, "Not sending sync response - ${device.address} is no longer an authenticated connection")
            return
        }
        val mtu = synchronized(serverLock) { deviceMtus[device.address] } ?: DEFAULT_ATT_MTU
        val chunkPayload = effectiveChunkPayload(mtu, BluetoothGatt.GATT_SUCCESS)
        val chunks = toChunks(payload, chunkPayload).toMutableList()
        if (chunks.isEmpty()) return
        val first = chunks.removeAt(0)
        synchronized(serverLock) { serverOutQueue[device.address] = chunks }
        sendRawNotification(device, first)
    }

    /** SECURITY (mutual-handshake follow-up): sends message 3 (this server's own proof+identity reply,
     * see HandshakeReplyMessage) - chunked exactly like sendToClient's post-auth payload, just queued
     * separately (serverHandshakeOutQueue, pumped first by onNotificationSent). By the time this is
     * called the device IS already marked authenticated (the atomic commit block adds it to
     * authenticatedDevices before calling this) - the caller still suspends twice after that commit
     * (getOrCreateLocalDeviceId, pairingStore.current()) building the reply contents, so, same as
     * sendToClient/respondToPhotoRequest, re-verify liveness immediately before sending (test-code-
     * allmodels, clean-room round 2, Opus) rather than trusting the commit is still valid this much
     * later. Message 3 itself carries no real payload (only this device's own deviceId/partnerName/
     * proof, useless to anyone but the exact dead connection its MAC is bound to), so this is a
     * consistency hardening rather than a top-invariant fix. */
    private fun sendHandshakeReplyToClient(device: BluetoothDevice, message: ByteArray) {
        if (!synchronized(serverLock) { authenticatedDevices.contains(device.address) }) {
            Log.w(TAG, "Not sending handshake reply - ${device.address} is no longer an authenticated connection")
            return
        }
        val mtu = synchronized(serverLock) { deviceMtus[device.address] } ?: DEFAULT_ATT_MTU
        val chunkPayload = effectiveChunkPayload(mtu, BluetoothGatt.GATT_SUCCESS)
        val chunks = toChunks(message, chunkPayload).toMutableList()
        if (chunks.isEmpty()) return
        val first = chunks.removeAt(0)
        synchronized(serverLock) { serverHandshakeOutQueue[device.address] = chunks }
        sendRawNotification(device, first)
    }

    // SECURITY (test-code-allmodels, clean-room final pass, Opus - TOP INVARIANT): serverCharacteristic
    // is ONE object shared by every connected central, not per-connection - the deprecated 3-arg
    // notifyCharacteristicChanged reads characteristic.getValue() internally when it marshals the
    // binder call, so "set .value, then notify" is a genuine race between ANY two calls to this
    // function for DIFFERENT devices (this server accepts multiple simultaneous centrals - nothing
    // restricts it to one). Without a lock around the whole set+notify pair, one device's chunk could
    // be sent to a DIFFERENT device's notify() call - either handing a real payload chunk to a
    // never-authenticated peer, or corrupting the intended recipient's stream. synchronized(serverLock)
    // here forces every notification (across every device, every characteristic-send call site) to
    // fully complete its set+notify before the next one can begin, closing the race at its root instead
    // of trying to special-case which callers can overlap.
    private fun sendRawNotification(device: BluetoothDevice, chunk: ByteArray) {
        synchronized(serverLock) {
            val characteristic = serverCharacteristic ?: return
            characteristic.value = chunk
            try {
                @Suppress("DEPRECATION")
                gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            } catch (e: SecurityException) {
                Log.w(TAG, "Missing permission to notify", e)
            }
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

        // SECURITY (test-code-allmodels, clean-room final pass, Opus - TOP INVARIANT): see sendToClient's
        // own doc for the identical hazard - the loop above suspends per photo file read, easily longer
        // than buildPayload's own suspension, so this re-check matters at least as much here.
        if (!synchronized(serverLock) { authenticatedDevices.contains(device.address) }) {
            Log.w(TAG, "Not sending photo response - ${device.address} is no longer an authenticated connection")
            return
        }
        val mtu = synchronized(serverLock) { deviceMtus[device.address] } ?: DEFAULT_ATT_MTU
        val chunkPayload = effectiveChunkPayload(mtu, BluetoothGatt.GATT_SUCCESS, BleConstants.MAX_CHUNK_PAYLOAD_PHOTO)
        val allChunks = framesToSend.flatMap { toChunks(it, chunkPayload) }.toMutableList()
        if (allChunks.isEmpty()) return
        val first = allChunks.removeAt(0)
        synchronized(serverLock) { serverPhotoOutQueue[device.address] = allChunks }
        sendRawPhotoNotification(device, first)
    }

    // SECURITY: see sendRawNotification's own doc - identical shared-characteristic race, same fix.
    private fun sendRawPhotoNotification(device: BluetoothDevice, chunk: ByteArray) {
        synchronized(serverLock) {
            val characteristic = serverPhotoCharacteristic ?: return
            characteristic.value = chunk
            try {
                @Suppress("DEPRECATION")
                gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            } catch (e: SecurityException) {
                Log.w(TAG, "Missing permission to notify (photo)", e)
            }
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
            // MINOR fix (test-code-allmodels round 2, Fable): this block predates the mutual-handshake
            // rework and missed clearing the two new handshake maps it added - a peer mid-handshake when
            // stopServer() runs (unpair, BT toggle, apart transition) could otherwise leave stale chunks
            // that get pumped/reassembled into the NEXT session's genuinely-new handshake attempt for the
            // same address, corrupting it. Fails closed either way (garbage can never pass the HMAC check),
            // but a stopped server should leave no more stale per-device state here than anywhere else.
            serverHandshakeIncoming.clear()
            serverHandshakeOutQueue.clear()
            serverAuthenticatedDeviceIds.clear()
        }
    }

    // ---- CLIENT side ----

    // SECURITY (test-code-allmodels round 3, Sonnet, clean-room): also doubles as the per-attempt
    // liveness discriminator for the shared class-level buffers below (clientIncoming,
    // clientHandshakeIncoming, clientPhotoIncoming, clientOutQueue, clientPhotoOutQueue,
    // metadataResponseSignal, clientChunkPayload/clientPhotoChunkPayload) - a callback belonging to an
    // OLDER BluetoothGatt object (superseded by a newer connectAsClient() call reassigning this field)
    // can still fire asynchronously and would otherwise corrupt whatever the newer attempt is
    // reassembling into those same shared fields. Every callback that touches them checks
    // `gatt !== clientGatt` first and bails out if it's stale - see onMtuChanged/onCharacteristicWrite/
    // onCharacteristicChanged below.
    // SECURITY (test-code-allmodels round 4, Sonnet): written from the service coroutine
    // (connectAsClient/disconnectClient) and read, with no lock, as the very first statement of every
    // GATT binder-thread callback above - @Volatile is what actually guarantees a write here is visible
    // to those reads across threads (object-identity comparison itself is already sound on its own,
    // since connectGatt() always allocates a fresh BluetoothGatt instance, but without this a stale read
    // could make the guard wrongly pass for an abandoned gatt or wrongly fail for the current one).
    @Volatile
    private var clientGatt: BluetoothGatt? = null
    private val clientIncoming = ByteArrayOutputStream()
    private var clientOutQueue: MutableList<ByteArray> = mutableListOf()
    private val clientLock = Any()
    // MINOR fix (test-code-allmodels round 1, Opus): written from onMtuChanged (a binder-thread GATT
    // callback) and read from coroutines building/sending the handshake proof and the real payload - the
    // same cross-thread-visibility reasoning as every other @Volatile field in this class already
    // documents, but these two were missed when they were introduced. If requestMtu() throws and
    // onMtuChanged never fires (see this file's own comment at the requestMtu call site), a stale value
    // from a PRIOR attempt could otherwise be used for this one without even the visibility guarantee to
    // ensure the write from that prior attempt (if any) is seen at all.
    @Volatile private var clientChunkPayload = BleConstants.MAX_CHUNK_PAYLOAD
    // SECURITY (mutual-handshake follow-up): reassembly buffer for message 3 (the server's own
    // proof+identity reply, see HandshakeReplyMessage) - kept separate from clientIncoming (the
    // post-auth JSON buffer), same reasoning as clientPhotoIncoming's own separation. Reset at the top
    // of every connectAsClient() call, same as clientIncoming/clientPhotoIncoming.
    private val clientHandshakeIncoming = ByteArrayOutputStream()

    // Feature 2: client-side photo phase state. All reset fresh at the top of connectAsClient() (a new
    // connection attempt must never see leftovers from a previous one), and cleared again on disconnect.
    private val clientPhotoIncoming = ByteArrayOutputStream()
    private var clientPhotoOutQueue: MutableList<ByteArray> = mutableListOf()
    @Volatile private var clientPhotoChunkPayload = BleConstants.MAX_CHUNK_PAYLOAD_PHOTO
    // SECURITY (test-code-allmodels round 6, Sonnet): completed from onConnectionStateChange's
    // STATE_DISCONNECTED branch (a raw GATT binder callback) as well as this manager's own coroutines -
    // the exact same cross-thread visibility reasoning that already earned metadataResponseSignal its
    // own @Volatile a few lines below applies equally here; these two just never got it.
    @Volatile private var clientPhotoSendComplete: CompletableDeferred<Unit>? = null
    @Volatile private var photoDoneSignal: CompletableDeferred<Unit>? = null
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
        clientHandshakeIncoming.reset()
        clientPhotoIncoming.reset()
        // MINOR fix (test-code-allmodels round 1, Opus): clientOutQueue was the one piece of per-attempt
        // mutable state this reset block missed - a previous attempt's leftover real-payload chunks
        // would otherwise survive into this one. Not currently reachable (the handshake queue is drained
        // first and a later write to clientOutQueue always happens before any payload send), but that
        // safety depended entirely on call-ordering rather than an explicit reset, which is one guard too
        // few to rely on given everything else in this block is reset explicitly.
        synchronized(clientLock) { clientOutQueue = mutableListOf() }
        synchronized(clientLock) { clientPhotoOutQueue = mutableListOf() }
        clientPhotoSendComplete = null
        photoDoneSignal = null
        clientPhotoReceivedCount = 0
        clientPhotoExpectedCount = 0
        metadataResponseSignal = null
        metadataLastActivityAtMillis = 0L
        // MINOR fix (test-code-allmodels round 2, Opus): @Volatile alone only fixed this pair's
        // cross-thread VISIBILITY, not staleness - a value negotiated by a PRIOR attempt (e.g. a large
        // granted MTU) could still be reused for this one if onMtuChanged never fires this time (see the
        // requestMtu() SecurityException fallback below, which skips straight to discoverServices()).
        // Resetting to the SAME conservative value onMtuChanged itself would compute for a non-negotiated
        // link (not MAX_CHUNK_PAYLOAD, which is the CEILING a successful large-MTU negotiation produces -
        // reusing that would be exactly the stale-oversized-chunk bug this is meant to prevent) means a
        // missing onMtuChanged this attempt always degrades to a size that's safe at the default ATT MTU,
        // rather than silently reusing whatever a previous, unrelated attempt happened to negotiate.
        clientChunkPayload = effectiveChunkPayload(DEFAULT_ATT_MTU, android.bluetooth.BluetoothGatt.GATT_FAILURE)
        clientPhotoChunkPayload = effectiveChunkPayload(DEFAULT_ATT_MTU, android.bluetooth.BluetoothGatt.GATT_FAILURE, BleConstants.MAX_CHUNK_PAYLOAD_PHOTO)
        // Mutated from BluetoothGattCallback methods, which run on binder threads (not necessarily the
        // same thread, and not guaranteed not to interleave) - just like
        // serverIncoming/serverOutQueue/authenticatedDevices/deviceMtus on the server side above, a
        // plain unguarded var here is a real race: two callback invocations landing close together could
        // both observe the pre-flip value and both proceed (e.g. both pass `if (completed) return`,
        // double-invoking the sync-completion path). Guarded by clientLock, the same lock already used
        // below for clientOutQueue.
        var completed = false
        // SECURITY (test-code-allmodels, clean-room round 2, Opus - doc accuracy): unlike `completed`
        // above, this one is genuinely NOT lock-guarded (read/written directly from onDescriptorWrite, a
        // binder-thread callback, with no synchronized(clientLock) around either access) - an earlier
        // version of this comment incorrectly implied it was. Left unguarded deliberately rather than
        // fixed: a torn/stale read here can only cause onDescriptorWrite's `!syncCccdDone &&
        // descriptor.characteristic.uuid == SYNC_CHARACTERISTIC_UUID` branch to be skipped or re-entered
        // at most once, and the two effects that branch has (enabling photo notifications, moving on to
        // the handshake) are each themselves idempotent/one-shot-latched elsewhere - it cannot loop or
        // double-launch the handshake proof coroutine (that latch is `clientHandshakeOutQueue`, under
        // clientLock). Set once both the sync AND photo characteristics have enabled notifications, so
        // the handshake (the actual start of real data exchange) never races ahead of either subscription
        // being ready.
        var syncCccdDone = false
        // Completed by onCharacteristicChanged the moment the server's handshake nonce notification
        // arrives (always the very first notification on the sync characteristic - see this file's
        // top-of-file doc) - awaited (with a timeout) right before computing+sending this device's own
        // proof (message 2 - HandshakeProofMessage), since the nonce can arrive at any point after this
        // device's CCCD write, independent of when the photo characteristic's own subscription finishes.
        val nonceDeferred = CompletableDeferred<ByteArray>()
        // SECURITY (mutual-handshake follow-up): completed by onCharacteristicChanged once message 3
        // (the server's own proof+identity reply, HandshakeReplyMessage) is fully reassembled - awaited
        // right after message 2 finishes sending, and verified BEFORE this device ever builds/sends its
        // real payload. See BleConstants.computeMutualHandshakeResponse's own doc for why this exists.
        val handshakeReplyDeferred = CompletableDeferred<ByteArray>()
        // Chunks of message 2 (this device's own proof+identity) still to send, pumped by
        // onCharacteristicWrite exactly like clientOutQueue is post-auth - kept SEPARATE (see this
        // file's "mutual-handshake wire framing" doc) and set back to null the moment it's fully sent,
        // which onCharacteristicWrite uses as the one-shot latch (replacing the old handshakeAcked
        // boolean) to know when to stop pumping this queue and start awaiting message 3 instead.
        var clientHandshakeOutQueue: MutableList<ByteArray>? = null
        // Stashed the moment each is generated/received - verifying message 3 later (in a SEPARATE
        // callback invocation from the one that sent message 2) needs both nonces to recompute the
        // expected server proof. Both guarded by clientLock alongside clientHandshakeOutQueue, for the
        // same cross-binder-thread-callback reason documented below.
        var clientNonceC: ByteArray? = null
        var clientNonceS: ByteArray? = null
        // SECURITY (test-code-allmodels round 1, Opus): `handshakeReplyDeferred.isCompleted` only means
        // SOME bytes arrived claiming to be message 3 - it says nothing about whether they actually
        // verified, since verification happens asynchronously in a separate coroutine (below) that
        // awaits this same deferred. Routing onCharacteristicChanged's JSON/photo fallback purely off
        // `isCompleted` left a real race: a peer that sends garbage as message 3 and then IMMEDIATELY
        // follows with a fabricated JSON/photo notification could get that notification processed and
        // MERGED before the verification coroutine has even resumed, let alone rejected it and
        // disconnected - completely bypassing the mutual proof this whole rewrite exists to enforce, and
        // requiring no knowledge of the shared secret at all. This flag is the actual gate: only set true
        // by the verification coroutine itself, only after BOTH the HMAC check and checkPinOrRecordPending
        // have genuinely succeeded - onCharacteristicChanged's fallback branches must check THIS, not the
        // deferred's completion state, before ever treating incoming bytes as real payload/photo data.
        var clientHandshakeVerified = false
        // SECURITY (test-code-allmodels round 3, Sonnet + Opus): the identity the mutual handshake
        // cryptographically proved for THIS connection - stashed the moment verification succeeds (see
        // clientHandshakeVerified's own doc for the exact point), read later when the server's actual
        // JSON payload arrives, so applyPayload's pin check/pin write can trust THIS instead of the
        // payload's own unauthenticated, self-reported "senderDeviceId" JSON field. Guarded by clientLock
        // alongside clientHandshakeVerified.
        var serverHandshakeVerifiedDeviceId: String? = null

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
                    // SECURITY (test-code-allmodels round 4, Sonnet + Fable, independently converged):
                    // this callback belongs to a SPECIFIC BluetoothGatt object and can still fire
                    // asynchronously after a NEWER connectAsClient() call has already reassigned
                    // clientGatt (both connectAsClient and disconnectClient explicitly disconnect()+
                    // close() any old gatt before superseding it, but ITS OWN async DISCONNECTED
                    // callback can still arrive later) - every other client callback in this file
                    // already guards against exactly this (see clientGatt's own doc), but this one
                    // didn't. Completing shared clientPhotoSendComplete/photoDoneSignal/
                    // metadataResponseSignal or clearing shared clientPhotoOutQueue/clientPhotoIncoming
                    // on behalf of a stale attempt could resolve/corrupt state belonging to a newer,
                    // still-in-flight attempt; calling finish(false) could release the shared
                    // clientSyncAttemptInProgress guard on behalf of an attempt that isn't this one.
                    // gatt.close() below still runs unconditionally regardless - it's always safe/
                    // idempotent and is exactly the right cleanup for THIS gatt object either way.
                    if (gatt === clientGatt) {
                        // Feature 2: unblock anything still awaiting a photo-phase signal so that
                        // coroutine can proceed to its own cleanup promptly instead of sitting until
                        // PHOTO_PHASE_TIMEOUT_MILLIS elapses for no reason - CompletableDeferred.complete()
                        // is a safe no-op if already completed.
                        synchronized(clientLock) { clientPhotoOutQueue.clear() }
                        clientPhotoIncoming.reset()
                        clientPhotoSendComplete?.complete(Unit)
                        photoDoneSignal?.complete(Unit)
                        metadataResponseSignal?.complete(Unit)
                        finish(false)
                    }
                    try { gatt.close() } catch (e: SecurityException) { /* ignore */ }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                // SECURITY (test-code-allmodels round 3, Sonnet, clean-room): a stale callback belonging
                // to an abandoned/superseded connectAsClient() attempt (its BluetoothGatt object is no
                // longer the current clientGatt) must not be allowed to write shared class-level state on
                // behalf of a NEWER attempt that's already using it - see clientGatt's own field doc and
                // the identical guard in onCharacteristicWrite/onCharacteristicChanged below.
                if (gatt !== clientGatt) return
                clientChunkPayload = effectiveChunkPayload(mtu, status)
                clientPhotoChunkPayload = effectiveChunkPayload(mtu, status, BleConstants.MAX_CHUNK_PAYLOAD_PHOTO)
                Log.d(TAG, "MTU negotiated: $mtu (status=$status) -> chunk payload $clientChunkPayload (photo $clientPhotoChunkPayload)")
                try { gatt.discoverServices() } catch (e: SecurityException) { finish(false) }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                // SECURITY (test-code-allmodels round 6, Fable): this was the one client GATT callback
                // still missing the entry-level staleness guard every sibling has - see clientGatt's own
                // doc. A stale callback from a torn-down attempt could otherwise call finish(false) on
                // behalf of a newer attempt that's already holding the shared clientSyncAttemptInProgress
                // guard.
                if (gatt !== clientGatt) return
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
                // SECURITY (test-code-allmodels round 5, Sonnet): this callback was the one client
                // callback missing the entry-level staleness guard every sibling (onMtuChanged,
                // onCharacteristicWrite, onCharacteristicChanged) already has - see clientGatt's own doc.
                if (gatt !== clientGatt) return
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
                    val nonceS = withTimeoutOrNull(HANDSHAKE_NONCE_TIMEOUT_MILLIS) { nonceDeferred.await() }
                    if (nonceS == null) {
                        // SECURITY (test-code-allmodels round 3, Opus): if a NEWER connectAsClient() attempt
                        // has already superseded this one (clientGatt reassigned), don't call finish(false)
                        // at all - this attempt's own onSyncDone is a wrapper that also releases
                        // ProximityForegroundService's single shared clientSyncAttemptInProgress guard, and
                        // firing it this late (up to HANDSHAKE_NONCE_TIMEOUT_MILLIS after this attempt was
                        // abandoned) could otherwise release it while a newer, genuinely in-flight attempt
                        // still holds it.
                        if (gatt !== clientGatt) return@launch
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
                    // SECURITY (test-code-allmodels round 5, Sonnet): nonceDeferred.await() above is a
                    // suspension point - a NEWER connectAsClient() attempt could have superseded this one
                    // while it was suspended. The entry-level guard just added only proves this was the
                    // current attempt when the callback first fired, not after this suspend. Re-check
                    // before doing anything with the result, same discipline as the sibling
                    // handshake-completion coroutine in onCharacteristicWrite.
                    if (gatt !== clientGatt) return@launch
                    // MINOR fix: crypto/DataStore work moved inside the try - a JCE failure (e.g. an
                    // unsupported algorithm on some OEM's crypto provider) used to be able to escape
                    // uncaught into [scope], since the try below only ever wrapped the actual GATT write.
                    try {
                        // SECURITY (mutual-handshake follow-up): builds message 2 - this device's own
                        // fresh nonce, its proof-of-key HMAC, and its (length-prefixed) deviceId/
                        // partnerName, all bound into the same MAC - see
                        // BleConstants.computeMutualHandshakeResponse's own doc. Chunked like everything
                        // else past the default MTU (see this file's "mutual-handshake wire framing" doc).
                        val nonceC = ByteArray(BleConstants.HANDSHAKE_NONCE_BYTES).also { SecureRandom().nextBytes(it) }
                        val myDeviceId = settingsStore.getOrCreateLocalDeviceId().toHandshakeFieldBytes()
                        val myPartnerName = pairingStore.current().partnerName.toHandshakeFieldBytes()
                        val responseC = BleConstants.computeMutualHandshakeResponse(
                            handshakeKey, BleConstants.HANDSHAKE_ROLE_CLIENT, nonceS, nonceC, myDeviceId, myPartnerName
                        )
                        val message2 = encodeHandshakeProofMessage(nonceC, responseC, myDeviceId, myPartnerName)
                        synchronized(clientLock) { clientNonceC = nonceC; clientNonceS = nonceS }
                        val chunks = toChunks(message2, clientChunkPayload).toMutableList()
                        val first = chunks.removeAt(0)
                        synchronized(clientLock) { clientHandshakeOutQueue = chunks }
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        characteristic.value = first
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(characteristic)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to compute/send handshake proof", e)
                        // SECURITY (test-code-allmodels round 5, Sonnet): this catch can be reached after
                        // the suspend points above (getOrCreateLocalDeviceId/pairingStore.current) - don't
                        // call finish()/disconnect() on behalf of an attempt that's no longer current.
                        if (gatt !== clientGatt) return@launch
                        finish(false)
                        try { gatt.disconnect() } catch (e2: SecurityException) { /* ignore */ }
                    }
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                // SECURITY (test-code-allmodels round 3, Sonnet, clean-room): see clientGatt's own doc.
                if (gatt !== clientGatt) return
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
                    // MINOR fix (test-code-allmodels, clean-room final pass, Opus + Fable): unlike every
                    // sibling failure branch in this file, this one left the link itself open -
                    // finish(false) only resolves the caller's result, it doesn't tear down the
                    // connection, so the dead link (and, server-side, its authenticated-device
                    // slot/nonce entry) would linger until something else eventually closed it.
                    try { gatt.disconnect() } catch (e: SecurityException) { /* ignore */ }
                    return
                }
                // SECURITY (mutual-handshake follow-up): still pumping message 2 (this device's own
                // proof+identity, possibly several chunks past the default MTU)? Send the next chunk and
                // return - nothing else happens until the whole message is out.
                val nextHandshakeChunk = synchronized(clientLock) {
                    val q = clientHandshakeOutQueue
                    if (q != null && q.isNotEmpty()) q.removeAt(0) else null
                }
                if (nextHandshakeChunk != null) {
                    try {
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        characteristic.value = nextHandshakeChunk
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(characteristic)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Missing permission to write characteristic", e)
                        finish(false)
                        // MINOR fix (test-code-allmodels, clean-room final pass, Opus + Fable): same
                        // dead-link hazard as the status-failure branch above - see its comment.
                        try { gatt.disconnect() } catch (e2: SecurityException) { /* ignore */ }
                    }
                    return
                }
                // Message 2 just finished sending (the queue existed and is now empty) - null it out (a
                // one-shot latch, replacing the old handshakeAcked boolean) and move on to awaiting
                // message 3. Any FUTURE call here (once real payload chunks start flowing, after the
                // block below completes) sees clientHandshakeOutQueue already null and falls through to
                // the ordinary post-auth pump at the bottom of this function.
                val justFinishedHandshakeSend = synchronized(clientLock) {
                    if (clientHandshakeOutQueue != null) { clientHandshakeOutQueue = null; true } else false
                }
                if (justFinishedHandshakeSend) {
                    // MAJOR fix (test-code-allmodels, Opus - unfixed twin of round 2's serializeTimeCapsules
                    // crash-loop fix, same as the server-side buildPayload() call site above): any uncaught
                    // throw here (a DataStore IOException, a Room read failure, a JCE failure) must fail
                    // this one sync attempt cleanly, not crash the whole process - same report-and-
                    // disconnect shape every other failure branch in this handshake/sync flow already uses.
                    scope.launch {
                        try {
                            val nonceS = synchronized(clientLock) { clientNonceS }
                            val nonceC = synchronized(clientLock) { clientNonceC }
                            val replyRaw = withTimeoutOrNull(HANDSHAKE_NONCE_TIMEOUT_MILLIS) { handshakeReplyDeferred.await() }
                            if (replyRaw == null || nonceS == null || nonceC == null) {
                                // SECURITY (test-code-allmodels round 3, Opus): see the nonce-timeout
                                // branch's identical guard above for why this check matters here too.
                                if (gatt !== clientGatt) return@launch
                                Log.w(TAG, "Timed out waiting for server's mutual handshake reply")
                                finish(false)
                                try { gatt.disconnect() } catch (e: SecurityException) { /* ignore */ }
                                return@launch
                            }
                            // SECURITY (test-code-allmodels round 4, Opus): the await() above is this
                            // coroutine's first suspension point - a NEWER connectAsClient() attempt could
                            // have superseded this one (clientGatt reassigned) while it was suspended. The
                            // entry-time guard on gatt !== clientGatt (checked when this whole scope.launch
                            // was first dispatched, back in onCharacteristicWrite) does NOT cover this -
                            // it only proves this was the current attempt BEFORE suspending, not after.
                            // Re-check before touching anything below on behalf of a possibly-abandoned
                            // attempt; every subsequent suspension point in this coroutine gets the same
                            // re-check for the same reason.
                            if (gatt !== clientGatt) return@launch
                            // SECURITY (mutual-handshake follow-up): verifies message 3 (the server's own
                            // proof+identity reply) BEFORE this device ever builds/sends its real payload -
                            // see BleConstants.computeMutualHandshakeResponse's own doc. A fake "server"
                            // that doesn't know the shared secret can never produce a valid response here,
                            // closing the gap where this device used to send its full data payload the
                            // moment its OWN write locally succeeded, without ever checking the other side
                            // proved anything back.
                            val decoded = decodeHandshakeReplyMessage(replyRaw)
                            val expected = decoded?.let {
                                BleConstants.computeMutualHandshakeResponse(
                                    handshakeKey, BleConstants.HANDSHAKE_ROLE_SERVER, nonceC, nonceS, it.deviceId, it.partnerName
                                )
                            }
                            // SECURITY: see the server-side verification's identical comment - constant-time
                            // comparison, same reasoning.
                            if (decoded == null || expected == null || !MessageDigest.isEqual(expected, decoded.response)) {
                                Log.w(TAG, "Rejecting server's handshake reply - missing/invalid mutual proof")
                                finish(false)
                                try { gatt.disconnect() } catch (e: SecurityException) { /* ignore */ }
                                return@launch
                            }
                            // SECURITY: the server has now proven it knows the shared secret AND its
                            // claimed identity (deviceId/partnerName are bound into the HMAC it just
                            // produced, so neither could have been tampered with in transit). Now the
                            // identity-and-consent gate, BEFORE this device ever sends anything real - see
                            // checkPinOrRecordPending's own doc for why this must happen here, not
                            // after-the-fact inside applyPayload as it used to (that check remains too, as
                            // defence-in-depth, but this is the one that actually stops this device from
                            // ever handing its own data to a rejected identity in the first place).
                            val serverDeviceId = String(decoded.deviceId, Charsets.UTF_8)
                            val serverPartnerName = String(decoded.partnerName, Charsets.UTF_8)
                            val identityOk = checkPinOrRecordPending(serverDeviceId, device, serverPartnerName)
                            // SECURITY (test-code-allmodels round 4, Opus): checkPinOrRecordPending above
                            // suspends (DataStore I/O) - re-check staleness, see the identical comment
                            // after the earlier await() above.
                            if (gatt !== clientGatt) return@launch
                            if (!identityOk) {
                                finish(false)
                                try { gatt.disconnect() } catch (e: SecurityException) { /* ignore */ }
                                return@launch
                            }
                            // SECURITY (test-code-allmodels round 1, Opus): mutual auth + identity check
                            // BOTH genuinely passed - only now does onCharacteristicChanged's fallback
                            // routing start accepting incoming bytes as real payload/photo data. See
                            // clientHandshakeVerified's own doc for the race this closes.
                            synchronized(clientLock) {
                                clientHandshakeVerified = true
                                serverHandshakeVerifiedDeviceId = serverDeviceId
                            }
                            // Mutual auth + identity check both passed - proceed exactly as the
                            // pre-existing post-handshake flow (stall watchdog + build/send real payload).
                            // BUG fix: see metadataResponseSignal's own doc - without this watchdog, a
                            // peer that authenticated correctly but then never sent back a metadata
                            // response (or sent one that never completed with a LAST flag) left this
                            // device waiting forever, holding its single-sync-attempt guard open with no
                            // way to recover except killing the app. Stall-based, not a fixed deadline -
                            // see metadataResponseSignal's own "round 2" doc for why. Polls rather than a
                            // single withTimeoutOrNull so it can compare against a deadline that keeps
                            // moving forward as long as genuine progress (metadataLastActivityAtMillis)
                            // keeps happening.
                            val signal = CompletableDeferred<Unit>()
                            metadataResponseSignal = signal
                            metadataLastActivityAtMillis = System.currentTimeMillis()
                            val phaseStartedAtMillis = metadataLastActivityAtMillis
                            scope.launch {
                                while (!signal.isCompleted) {
                                    delay(METADATA_STALL_CHECK_INTERVAL_MILLIS)
                                    if (signal.isCompleted) break
                                    // BUG fix: a second independent review round pointed out this closure
                                    // reads the SHARED metadataLastActivityAtMillis, not one scoped to its
                                    // own `signal` - so if this exact attempt were ever abandoned without
                                    // its `signal` being completed (disconnectClient() closes the gatt
                                    // without going through the normal onConnectionStateChange
                                    // DISCONNECTED path, which is what would otherwise complete it), this
                                    // watchdog could linger up to the full ceiling and then call
                                    // finish(false) on behalf of a sync that's no longer this device's
                                    // current attempt - a stale finish() is a harmless no-op by itself, but
                                    // is one guard too many to rely on alone. Bailing out the moment
                                    // metadataResponseSignal has been reassigned to a NEWER attempt's
                                    // signal means this watchdog only ever acts on its own, still-current
                                    // attempt.
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
                            val payload = buildPayload()
                            // SECURITY (test-code-allmodels round 4, Opus): buildPayload above suspends
                            // (Room I/O) - re-check staleness before overwriting the shared clientOutQueue
                            // a NEWER attempt may already be pumping from.
                            if (gatt !== clientGatt) return@launch
                            synchronized(clientLock) { clientOutQueue = toChunks(payload, clientChunkPayload).toMutableList() }
                            sendNextClientChunk(gatt, characteristic)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to complete mutual handshake / build outgoing sync payload as client", e)
                            // SECURITY (test-code-allmodels round 4, Opus): this catch can be reached after
                            // any of the suspension points above - don't call finish()/disconnect() on
                            // behalf of an attempt that's no longer current.
                            if (gatt !== clientGatt) return@launch
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
                // SECURITY (test-code-allmodels round 3, Sonnet, clean-room): see clientGatt's own doc -
                // this is the most important of the three guards, since this callback is the one that
                // writes into every shared reassembly buffer (clientHandshakeIncoming/clientIncoming/
                // clientPhotoIncoming) and completes metadataResponseSignal.
                if (gatt !== clientGatt) return
                val value = characteristic.value ?: return
                if (value.isEmpty()) return

                if (characteristic.uuid == BleConstants.SYNC_CHARACTERISTIC_UUID && !nonceDeferred.isCompleted) {
                    // The very first notification the server ever sends on the sync characteristic is
                    // always its handshake nonce (see startServer's onDescriptorWriteRequest) - raw bytes,
                    // no chunk-flag framing, since the server won't send this device anything else until
                    // AFTER it has both authenticated this device's message 2 AND accepted its identity
                    // (see checkPinOrRecordPending's own doc).
                    // MINOR fix (test-code-allmodels round 3, Opus, clean-room): a wrong-length value here
                    // used to be accepted as-is - harmless in practice (computeMutualHandshakeResponse
                    // still derives freshness from the verifier's OWN nonce regardless of the prover's
                    // length, so this was never actually exploitable), but S1's doc claims fixed-length
                    // nonces, and this is what makes that claim true by construction rather than by
                    // accident. A wrong-length value is simply ignored (not completed) - the existing
                    // HANDSHAKE_NONCE_TIMEOUT_MILLIS wait handles a server that never sends a valid one.
                    if (value.size != BleConstants.HANDSHAKE_NONCE_BYTES) return
                    nonceDeferred.complete(value)
                    return
                }

                // SECURITY (mutual-handshake follow-up): message 3 (the server's own proof+identity
                // reply, HandshakeReplyMessage) arrives on this same characteristic next, flag-framed
                // exactly like everything past the raw nonce - reassembled into a buffer SEPARATE from
                // the post-auth JSON one (clientIncoming), so a handshake-phase notification can never be
                // misread as JSON or vice versa. Gated on !handshakeReplyDeferred.isCompleted so this
                // branch only ever runs once per connection, strictly before real data exchange begins.
                if (characteristic.uuid == BleConstants.SYNC_CHARACTERISTIC_UUID && !handshakeReplyDeferred.isCompleted) {
                    val flag = value[0]
                    if (value.size > 1) {
                        if (clientHandshakeIncoming.size() + value.size - 1 > MAX_HANDSHAKE_MESSAGE_BYTES) {
                            Log.w(TAG, "Incoming handshake reply from server exceeded sanity cap - dropping")
                            clientHandshakeIncoming.reset()
                        } else {
                            clientHandshakeIncoming.write(value, 1, value.size - 1)
                        }
                    }
                    if (flag == BleConstants.CHUNK_FLAG_LAST) {
                        val raw = clientHandshakeIncoming.toByteArray()
                        clientHandshakeIncoming.reset()
                        handshakeReplyDeferred.complete(raw)
                    }
                    return
                }

                // SECURITY (test-code-allmodels round 1, Opus): `handshakeReplyDeferred` being completed
                // (checked above) only means SOME bytes arrived claiming to be message 3 - verification of
                // those bytes happens asynchronously in a separate coroutine (see clientHandshakeVerified's
                // own doc). Without this gate, a peer that sends garbage as message 3 and then immediately
                // follows with a fabricated JSON/photo notification could get it processed before that
                // coroutine has even resumed, let alone rejected and disconnected it - bypassing the mutual
                // proof entirely. Anything arriving before verification has genuinely succeeded is dropped
                // here, silently, on both the photo and JSON paths - there is nothing legitimate this could
                // ever be, since the server (per this file's own protocol) never sends anything else until
                // after it has authenticated and accepted this device.
                if (!synchronized(clientLock) { clientHandshakeVerified }) return

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
                    val handshakeVerifiedDeviceId = synchronized(clientLock) { serverHandshakeVerifiedDeviceId } ?: ""
                    scope.launch {
                        val remoteMomentInfo = try {
                            applyPayload(raw, remoteDevice = gatt.device, handshakeVerifiedDeviceId = handshakeVerifiedDeviceId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse incoming sync payload - reporting failure instead of a silent empty merge", e)
                            // SECURITY (test-code-allmodels round 4, Opus): applyPayload above suspends
                            // (Room I/O) - don't call finish()/disconnect() on behalf of an attempt a
                            // newer connectAsClient() call may have already superseded. See clientGatt's
                            // own doc.
                            if (gatt !== clientGatt) return@launch
                            finish(false)
                            try { gatt.disconnect() } catch (e2: SecurityException) { /* ignore */ }
                            return@launch
                        }
                        // SECURITY (test-code-allmodels round 4, Opus): re-check staleness after
                        // applyPayload's suspension before touching finish()/the shared
                        // clientSyncAttemptInProgress guard below.
                        if (gatt !== clientGatt) return@launch
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
                        // SECURITY (test-code-allmodels round 4, Opus): runPhotoPhaseAsClient above also
                        // suspends extensively (network I/O) - re-check once more before the final
                        // finish(true)/disconnect.
                        if (gatt !== clientGatt) return@launch
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
        // SECURITY (test-code-allmodels round 5, Opus): getAll() above suspends (Room I/O) - a NEWER
        // connectAsClient() attempt could have superseded this one while it was suspended. Re-check
        // before touching any shared client photo-phase state below - same discipline as every other
        // suspension point in the handshake/payload coroutines. See clientGatt's own doc.
        if (gatt !== clientGatt) return
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

            // SECURITY (test-code-allmodels round 5, Opus): the per-file withContext(Dispatchers.IO)
            // read above suspends once per photo - re-check staleness before overwriting shared
            // clientPhotoSendComplete/clientPhotoOutQueue that a NEWER attempt may already be using.
            // The unconditional "clear leftover transferring markers" cleanup below this whole
            // withTimeoutOrNull block still runs either way, so bailing here can't strand the UI.
            if (gatt !== clientGatt) return@withTimeoutOrNull

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

        /** SECURITY (mutual-handshake follow-up): sanity ceiling on the reassembled handshake proof
         * message (nonce + response + length-prefixed deviceId/partnerName, both directions) - mirrors
         * MAX_SYNC_JSON_BYTES's own reasoning but sized for what this message actually is: at most
         * 16 + 16 + 1 + 255 + 1 + 255 = 544 bytes even at the absolute field-length ceiling
         * (BleConstants.MAX_HANDSHAKE_FIELD_BYTES). Generous headroom over that, purely defensive
         * against a misbehaving/adversarial peer that never sends a LAST flag. */
        private const val MAX_HANDSHAKE_MESSAGE_BYTES = 4096

        /** BLOCKER fix: an independent testing round found that every LWW merge (dateIdeas,
         * listCategories, milestones, momentNotes) trusted the peer's `updatedAt` absolutely, with no
         * bound - a peer with a wrong clock (or a forged payload) could stamp a value far in the future,
         * after which no local edit could ever win again since local writes stamp real wall-clock time.
         *
         * MINOR fix (test-code-allmodels, Fable - doc drift): this doc used to describe a CLAMP-and-accept
         * design ("clamping incoming updatedAt to now + this tolerance... a poisoned/forged row can still
         * win once, but any subsequent real local edit wins back"). That design was replaced with
         * reject-outright ([isPlausibleWireUpdatedAt] below simply drops a row failing this check, never
         * clamps and accepts it) after a later round found clamping doesn't self-heal under REPEATED
         * replay of the same poisoned value - each replay gets clamped to a fresh, later ceiling relative
         * to its own "now," letting a repeatedly-resent poisoned row keep beating legitimate edits made in
         * between instead of losing once. This constant is still the tolerance window for that reject
         * check (and the sibling backup-restore reject checks in BackupManager), just no longer a clamp
         * ceiling - kept the doc accurate so a future maintainer doesn't reintroduce clamping here by
         * reading stale reasoning. */
        const val MAX_CLOCK_SKEW_TOLERANCE_MILLIS = 5 * 60_000L

        /** Couple-level settings sync: typo/forgery guardrail on an incoming
         * sessionGraceMinutes/reunionThresholdMinutes value, same "generous but sane" reasoning as
         * TimeCapsuleRepository.MAX_UNLOCK_AT_HOURS - 30 days comfortably covers any real relationship
         * configuration (a reunion threshold of "we were apart a month" is already an extreme edge case)
         * while still rejecting an obviously-forged or corrupted value rather than silently accepting it. */
        const val MAX_SETTINGS_MINUTES = 30 * 24 * 60

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
