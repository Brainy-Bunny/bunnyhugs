package com.ssbmedia.twogether.data.datastore

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// A torn/corrupted preferences file (e.g. a write interrupted by a crash or power loss) throws
// CorruptionException on every future read forever without a corruptionHandler - which, for the
// service-critical proximityDs in particular, would permanently break restoreState() and thus this
// service's whole startup path (see ProximityForegroundService.onCreate's try/catch around
// restoreState() for the crash-loop this combination used to cause). Resetting to empty preferences
// on corruption is a safe, self-healing default: it's equivalent to the state a fresh install starts
// from, not silent data loss of anything that could otherwise still be read.
private val corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }

private val Context.pairingDs by preferencesDataStore(name = "pairing", corruptionHandler = corruptionHandler)
private val Context.settingsDs by preferencesDataStore(name = "settings", corruptionHandler = corruptionHandler)
private val Context.proximityDs by preferencesDataStore(name = "proximity_state", corruptionHandler = corruptionHandler)
private val Context.badgeUnlocksDs by preferencesDataStore(name = "badge_unlocks", corruptionHandler = corruptionHandler)

data class PairingInfo(
    val pairSecretHash: String? = null,
    val pairPlainCode: String? = null,
    val partnerName: String = "Your Person",
    val partnerEmoji: String = "💕",
    val pairedAt: Long = 0L,
    /** SECURITY: trust-on-first-use device pinning - see PairingStore.pinPartnerDeviceIdIfAbsent's doc
     * and GattSyncManager.applyPayload's use of this. Null until the first sync after pairing completes;
     * from then on, GattSyncManager refuses to merge a payload whose sender doesn't match this id. Closes
     * the gap documented in BleConstants' file doc where the pairing code's 10^6 keyspace alone let any
     * device that coincidentally lands on the same code be treated as "the partner" for every future
     * connection, not just once. */
    val pinnedPartnerDeviceId: String? = null,
    /** SECURITY (user-designed follow-up to the TOFU pinning above, refined per Fable's design review):
     * once this device is pinned to a partner, a sync attempt from any OTHER device presenting the same
     * pairing code is rejected as before (GattSyncManager.applyPayload) - but rather than silently
     * discarding that rejection forever (which is what let a partner's stale pin go unnoticed and
     * unrecoverable in the original design), the rejecting device now remembers EACH distinct device
     * that tries, here, and surfaces them as explicit "is this actually your partner?" prompts instead of
     * resolving automatically either way. A LIST, not a single slot: Fable's review flagged that a single
     * overwritable slot lets a second (stranger, or just a second legitimate retry racing a real one)
     * request silently replace the one the user actually saw and meant to approve. Approving/dismissing
     * always targets one specific entry's deviceId from this list - if that entry is no longer present
     * (expired, already resolved) the action simply no-ops rather than ever acting on a DIFFERENT entry
     * than the one the user looked at. See PairingStore.recordPendingResyncRequest/approvePendingResync/
     * dismissPendingResync/PENDING_RESYNC_REQUEST_TTL_MILLIS. */
    val pendingResyncRequests: List<PendingResyncRequest> = emptyList()
) {
    val isPaired: Boolean get() = !pairSecretHash.isNullOrBlank()
}

/** One distinct "a device I don't recognize tried to sync using my pairing code" event - see
 * [PairingInfo.pendingResyncRequests]'s doc. [bluetoothName] and [theirPartnerName] are both
 * best-effort identifying context (may be null - a permission denial, an older peer build, or a
 * peer that simply has no partner name set yet), shown so the user can actually tell a genuine
 * partner apart from a stranger/collision instead of approving blind:
 * - [bluetoothName] is the OS-level Bluetooth name of the connecting device (usually the phone's
 *   own name, e.g. "Sahil's Galaxy S23") - hardware-level, not something either app controls, so
 *   much harder for an unrelated collision to coincidentally match than [theirPartnerName].
 * - [theirPartnerName] is literally the requesting device's OWN "partner_name" field (what IT calls
 *   whoever IT thinks its partner is) - carried in the sync payload envelope (see
 *   GattSyncManager.buildPayload/applyPayload's "senderPartnerName" field). If this really is your
 *   partner's phone, this should read back whatever nickname you gave yourself when you two paired -
 *   a mismatch here is a real red flag, a match is real (if weak) corroboration. */
data class PendingResyncRequest(
    val deviceId: String,
    val bluetoothName: String? = null,
    val theirPartnerName: String? = null,
    val requestedAt: Long = 0L
)

/** Snapshot of the most recently torn-down pairing, kept around after unpair() so PairingScreen can
 * offer a one-tap "Reconnect to X" that restores it without re-entering the code - see
 * PairingStore.unpair()/reconnectToLast(). Only meaningful while [secretHash] is non-null. */
data class LastConnectionInfo(
    val secretHash: String? = null,
    val plainCode: String? = null,
    val partnerName: String = "",
    val partnerEmoji: String = "💕",
    val unpairedAt: Long = 0L
) {
    val exists: Boolean get() = !secretHash.isNullOrBlank()
}

class PairingStore(private val context: Context) {
    private object Keys {
        val SECRET_HASH = stringPreferencesKey("pair_secret_hash")
        val PLAIN_CODE = stringPreferencesKey("pair_plain_code")
        val PARTNER_NAME = stringPreferencesKey("partner_name")
        val PARTNER_EMOJI = stringPreferencesKey("partner_emoji")
        val PAIRED_AT = longPreferencesKey("paired_at")
        val PINNED_PARTNER_DEVICE_ID = stringPreferencesKey("pinned_partner_device_id")
        val LAST_PINNED_PARTNER_DEVICE_ID = stringPreferencesKey("last_pinned_partner_device_id")
        // JSON-encoded array of PendingResyncRequest, same org.json serialization convention already
        // used throughout GattSyncManager/BackupManager - Preferences DataStore has no native list-of-
        // objects type, and a handful of records at a time doesn't justify a whole new Room table/
        // migration for this.
        val PENDING_RESYNC_REQUESTS_JSON = stringPreferencesKey("pending_resync_requests_json")

        // "Last connection" snapshot - deliberately stored under different key names in the same
        // pairingDs file (rather than a separate DataStore) so unpair() can move the active-> last
        // values over inside one atomic edit{} transaction.
        val LAST_SECRET_HASH = stringPreferencesKey("last_pair_secret_hash")
        val LAST_PLAIN_CODE = stringPreferencesKey("last_pair_plain_code")
        val LAST_PARTNER_NAME = stringPreferencesKey("last_partner_name")
        val LAST_PARTNER_EMOJI = stringPreferencesKey("last_partner_emoji")
        val LAST_UNPAIRED_AT = longPreferencesKey("last_unpaired_at")
    }

    val info: Flow<PairingInfo> = context.pairingDs.data.map { p ->
        PairingInfo(
            pairSecretHash = p[Keys.SECRET_HASH],
            pairPlainCode = p[Keys.PLAIN_CODE],
            partnerName = p[Keys.PARTNER_NAME] ?: "Your Person",
            partnerEmoji = p[Keys.PARTNER_EMOJI] ?: "💕",
            pairedAt = p[Keys.PAIRED_AT] ?: 0L,
            pinnedPartnerDeviceId = p[Keys.PINNED_PARTNER_DEVICE_ID],
            // Fable review: expired entries are filtered at READ time (here), not just pruned lazily on
            // the next write - so a request that's aged out disappears from the UI/notification the
            // moment it's stale, not only the next time recordPendingResyncRequest happens to run.
            pendingResyncRequests = decodePendingResyncRequests(p[Keys.PENDING_RESYNC_REQUESTS_JSON])
                .filter { System.currentTimeMillis() - it.requestedAt < PENDING_RESYNC_REQUEST_TTL_MILLIS }
        )
    }

    val lastConnection: Flow<LastConnectionInfo> = context.pairingDs.data.map { p ->
        LastConnectionInfo(
            secretHash = p[Keys.LAST_SECRET_HASH],
            plainCode = p[Keys.LAST_PLAIN_CODE],
            partnerName = p[Keys.LAST_PARTNER_NAME] ?: "",
            partnerEmoji = p[Keys.LAST_PARTNER_EMOJI] ?: "💕",
            unpairedAt = p[Keys.LAST_UNPAIRED_AT] ?: 0L
        )
    }

    suspend fun current(): PairingInfo = info.first()
    suspend fun currentLastConnection(): LastConnectionInfo = lastConnection.first()

    /** [plainCode] is the actual 6-digit code (not just its hash) - both the "create" and "join" flows
     * know it locally (the creator generated it, the joiner typed it in), and persisting it lets
     * Settings redisplay it later so a partner who lost their own copy (reinstall, factory reset) can
     * still be given it again. It never leaves this device on its own. Also clears any stale "last
     * connection" snapshot from a previous relationship, so Feature 3's reconnect option always reflects
     * the most recent prior pairing, never an arbitrarily old one. */
    suspend fun savePairing(secretHash: String, plainCode: String, partnerName: String, partnerEmoji: String) {
        context.pairingDs.edit { p ->
            // Fix #6 (Fable review): if this is the SAME code this device was last paired with, carry the
            // old pin forward instead of starting unpinned - this is exactly the user's originally-reported
            // scenario (unpair, then re-pair with the same code): both sides end up pinned to the same
            // device id they always were, symmetrically, with no popup ever needed. Only applies when the
            // hash matches (a genuinely different code means a genuinely different/reset partner pairing,
            // where inheriting the old pin would be wrong) and must be read BEFORE the block below clears
            // LAST_SECRET_HASH/LAST_PINNED_PARTNER_DEVICE_ID.
            val isSameAsLastCode = !p[Keys.LAST_SECRET_HASH].isNullOrBlank() && p[Keys.LAST_SECRET_HASH] == secretHash
            val carriedPin = if (isSameAsLastCode) p[Keys.LAST_PINNED_PARTNER_DEVICE_ID] else null
            p[Keys.SECRET_HASH] = secretHash
            p[Keys.PLAIN_CODE] = plainCode
            p[Keys.PARTNER_NAME] = partnerName.trim().ifBlank { "Your Person" }
            p[Keys.PARTNER_EMOJI] = partnerEmoji.ifBlank { "💕" }
            p[Keys.PAIRED_AT] = System.currentTimeMillis()
            // A brand new pairing (even re-pairing with the same code by coincidence) must start with no
            // pin, so the first real sync under THIS pairing is what does the pinning - never inherit a
            // pin left over from whatever pairing (if any) was active before. EXCEPT when carriedPin says
            // this is a same-code rejoin, per Fix #6 above.
            if (!carriedPin.isNullOrBlank()) p[Keys.PINNED_PARTNER_DEVICE_ID] = carriedPin else p.remove(Keys.PINNED_PARTNER_DEVICE_ID)
            p.remove(Keys.LAST_SECRET_HASH)
            p.remove(Keys.LAST_PLAIN_CODE)
            p.remove(Keys.LAST_PARTNER_NAME)
            p.remove(Keys.LAST_PARTNER_EMOJI)
            p.remove(Keys.LAST_UNPAIRED_AT)
            p.remove(Keys.LAST_PINNED_PARTNER_DEVICE_ID)
            // A pending resync request only ever means something against the pairing that received it -
            // starting a brand new one makes any old request meaningless (it was about a device trying to
            // reach the PREVIOUS pairing, not this one).
            p.remove(Keys.PENDING_RESYNC_REQUESTS_JSON)
        }
    }

    /** Clears the ACTIVE pairing (so isPaired flips false and the app shows onboarding / stops the
     * proximity service, exactly as before) but - unlike the old `it.clear()` - snapshots the secret/
     * code/partner info into the "last connection" keys first instead of discarding it, so
     * reconnectToLast() can restore it later. Deliberately does NOT touch Room data (sessions/moments/
     * date ideas/capsules) or any other DataStore - only this pairingDs file. */
    suspend fun unpair() {
        context.pairingDs.edit { p ->
            val secretHash = p[Keys.SECRET_HASH]
            if (!secretHash.isNullOrBlank()) {
                p[Keys.LAST_SECRET_HASH] = secretHash
                p[Keys.LAST_PLAIN_CODE] = p[Keys.PLAIN_CODE] ?: ""
                p[Keys.LAST_PARTNER_NAME] = p[Keys.PARTNER_NAME] ?: "Your Person"
                p[Keys.LAST_PARTNER_EMOJI] = p[Keys.PARTNER_EMOJI] ?: "💕"
                p[Keys.LAST_UNPAIRED_AT] = System.currentTimeMillis()
                // Snapshotted alongside the rest of the "last connection" info (rather than dropped) so
                // reconnectToLast() below can restore it too - it's still valid against that exact same
                // secretHash, and re-pinning it means a quick "Reconnect to X" doesn't reopen the
                // first-sync collision window this pin exists to close.
                val pinned = p[Keys.PINNED_PARTNER_DEVICE_ID]
                if (!pinned.isNullOrBlank()) p[Keys.LAST_PINNED_PARTNER_DEVICE_ID] = pinned else p.remove(Keys.LAST_PINNED_PARTNER_DEVICE_ID)
            }
            p.remove(Keys.SECRET_HASH)
            p.remove(Keys.PLAIN_CODE)
            p.remove(Keys.PARTNER_NAME)
            p.remove(Keys.PARTNER_EMOJI)
            p.remove(Keys.PAIRED_AT)
            p.remove(Keys.PINNED_PARTNER_DEVICE_ID)
            // Same reasoning as savePairing() - a pending request belongs to the pairing being torn down.
            p.remove(Keys.PENDING_RESYNC_REQUESTS_JSON)
        }
    }

    /** Restores the previously-unpaired connection instantly, with no code re-entry: writes the saved
     * secret hash/plain code/partner info back into the ACTIVE pairing keys. Only works if this phone's
     * own local "last connection" snapshot survived (it doesn't help after a factory reset / fresh
     * install of THIS phone - that's what the Feature 4 backup/restore is for). No-ops if there is no
     * last-connection snapshot. */
    suspend fun reconnectToLast() {
        context.pairingDs.edit { p ->
            val secretHash = p[Keys.LAST_SECRET_HASH] ?: return@edit
            p[Keys.SECRET_HASH] = secretHash
            p[Keys.PLAIN_CODE] = p[Keys.LAST_PLAIN_CODE] ?: ""
            p[Keys.PARTNER_NAME] = p[Keys.LAST_PARTNER_NAME] ?: "Your Person"
            p[Keys.PARTNER_EMOJI] = p[Keys.LAST_PARTNER_EMOJI] ?: "💕"
            p[Keys.PAIRED_AT] = System.currentTimeMillis()
            val lastPinned = p[Keys.LAST_PINNED_PARTNER_DEVICE_ID]
            if (!lastPinned.isNullOrBlank()) p[Keys.PINNED_PARTNER_DEVICE_ID] = lastPinned else p.remove(Keys.PINNED_PARTNER_DEVICE_ID)
            p.remove(Keys.PENDING_RESYNC_REQUESTS_JSON)
        }
    }

    /** Item 1 (deferred UX fix, 4-model advisory audit): lets Settings edit the locally-displayed partner
     * name/emoji AFTER pairing - previously set once during onboarding (savePairing above) and read-only
     * forever after. Local display data only, not synced/security-relevant (see [PairingInfo.partnerName]/
     * [PairingInfo.partnerEmoji]'s own doc - each side's copy of "what I call my partner" is purely a
     * per-device label, never exchanged over BLE), so this deliberately touches ONLY those two fields -
     * secret hash, plain code, pairedAt, and the pinned partner device id are all left completely
     * untouched. Mirrors savePairing()'s own trim/blank-fallback normalization for these two fields. */
    suspend fun updatePartnerInfo(partnerName: String, partnerEmoji: String) {
        context.pairingDs.edit { p ->
            p[Keys.PARTNER_NAME] = partnerName.trim().ifBlank { "Your Person" }
            p[Keys.PARTNER_EMOJI] = partnerEmoji.ifBlank { "💕" }
        }
    }

    /** SECURITY: trust-on-first-use device pinning - see [PairingInfo.pinnedPartnerDeviceId]'s doc for
     * why this exists. Called by GattSyncManager.applyPayload right after the FIRST sync under the
     * current pairing completes successfully; a no-op (first writer wins) on every call after that, so a
     * later same-code collision can never overwrite an already-established pin. */
    suspend fun pinPartnerDeviceIdIfAbsent(deviceId: String) {
        if (deviceId.isBlank()) return
        context.pairingDs.edit { p ->
            if (p[Keys.SECRET_HASH].isNullOrBlank()) return@edit
            if (p[Keys.PINNED_PARTNER_DEVICE_ID].isNullOrBlank()) {
                p[Keys.PINNED_PARTNER_DEVICE_ID] = deviceId
            }
        }
    }

    /** SECURITY (user-designed follow-up, refined per Fable's design review): called by
     * GattSyncManager.applyPayload the moment a payload is rejected for a mismatched sender WHILE this
     * device is genuinely pinned to someone - i.e. the "I'm already locked to a specific partner and
     * something else just tried my code" case, not a brand-new first-time pairing (that path never has a
     * pin yet, so this is never reached for it). Upserts by deviceId (dedupes a peer's own retry loop
     * into a refreshed timestamp on the SAME entry rather than growing unboundedly) and caps the list at
     * [MAX_PENDING_RESYNC_REQUESTS], dropping the oldest first - a genuinely large number of distinct
     * devices hitting this at once is itself a signal something's wrong, not a case to build unbounded
     * storage for. Returns true only when [deviceId] is a genuinely NEW entry (not previously pending) -
     * Fix #5 (Fable review, notification throttle): the caller uses this to decide whether to fire a
     * notification, so a peer's own retry loop (which calls this every few seconds) refreshes its
     * timestamp silently instead of re-notifying the user on every single attempt. */
    suspend fun recordPendingResyncRequest(deviceId: String, bluetoothName: String?, theirPartnerName: String?): Boolean {
        if (deviceId.isBlank()) return false
        var isNewDevice = false
        context.pairingDs.edit { p ->
            val now = System.currentTimeMillis()
            val existing = decodePendingResyncRequests(p[Keys.PENDING_RESYNC_REQUESTS_JSON])
            isNewDevice = existing.none { it.deviceId == deviceId }
            val updated = existing.filterNot { it.deviceId == deviceId } +
                PendingResyncRequest(deviceId, bluetoothName, theirPartnerName, now)
            val capped = updated.sortedByDescending { it.requestedAt }.take(MAX_PENDING_RESYNC_REQUESTS)
            p[Keys.PENDING_RESYNC_REQUESTS_JSON] = encodePendingResyncRequests(capped)
        }
        return isNewDevice
    }

    /** SECURITY (user-designed follow-up, refined per Fable's design review): the user's explicit "yes,
     * that's my partner" response to ONE SPECIFIC pending request, identified by [deviceId] - the id the
     * user actually saw and chose from the rendered list, never "whatever's currently pending" (that
     * ambiguity is exactly the race Fable's review flagged: a second request silently replacing the one
     * the user meant to approve). If [deviceId] is no longer in the list (expired, already resolved, or
     * simply never existed), this is a deliberate no-op rather than falling back to approving something
     * else. Pins DIRECTLY to the requesting device's id - deliberately NOT a bare
     * `p.remove(PINNED_PARTNER_DEVICE_ID)` that reopens a plain pinPartnerDeviceIdIfAbsent-style
     * trust-on-first-use window, since we already know exactly which device asked. Returns true if a pin
     * change actually happened (so the caller knows whether to kick off an immediate retry sync). */
    suspend fun approvePendingResync(deviceId: String): Boolean {
        var approved = false
        context.pairingDs.edit { p ->
            val existing = decodePendingResyncRequests(p[Keys.PENDING_RESYNC_REQUESTS_JSON])
            if (existing.none { it.deviceId == deviceId }) return@edit
            p[Keys.PINNED_PARTNER_DEVICE_ID] = deviceId
            p[Keys.PENDING_RESYNC_REQUESTS_JSON] = encodePendingResyncRequests(existing.filterNot { it.deviceId == deviceId })
            approved = true
        }
        return approved
    }

    /** SECURITY (user-designed follow-up): the user's explicit "no, that's not my partner" response (or
     * simply dismissing that one entry) - removes just this one request with no change to the pin, so the
     * next attempt from that same unrecognized device is rejected (and re-recorded) exactly as before.
     * Other still-pending requests, if any, are untouched. */
    suspend fun dismissPendingResync(deviceId: String) {
        context.pairingDs.edit { p ->
            val existing = decodePendingResyncRequests(p[Keys.PENDING_RESYNC_REQUESTS_JSON])
            p[Keys.PENDING_RESYNC_REQUESTS_JSON] = encodePendingResyncRequests(existing.filterNot { it.deviceId == deviceId })
        }
    }

    /** Fable review: the strongest available signal that a pending request is NOT the real partner is the
     * real partner demonstrably still being alive - if a sync with the CURRENTLY pinned partner succeeds
     * after a request was recorded, every pending entry recorded before that point is now stale (the real
     * partner never lost their pin, so whoever's still asking almost certainly isn't them) and gets
     * cleared automatically, without waiting for the TTL or a manual dismiss. Called from
     * GattSyncManager.applyPayload right after a successful (non-rejected) merge. Entries recorded AFTER
     * the successful sync are left alone - they're a separate, still-unresolved event. */
    suspend fun clearPendingResyncRequestsOlderThan(cutoffMillis: Long) {
        context.pairingDs.edit { p ->
            val existing = decodePendingResyncRequests(p[Keys.PENDING_RESYNC_REQUESTS_JSON])
            if (existing.none { it.requestedAt <= cutoffMillis }) return@edit
            p[Keys.PENDING_RESYNC_REQUESTS_JSON] = encodePendingResyncRequests(existing.filter { it.requestedAt > cutoffMillis })
        }
    }

    private fun decodePendingResyncRequests(json: String?): List<PendingResyncRequest> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val deviceId = o.optString("deviceId", "")
                if (deviceId.isBlank()) return@mapNotNull null
                PendingResyncRequest(
                    deviceId = deviceId,
                    bluetoothName = if (o.isNull("bluetoothName")) null else o.optString("bluetoothName"),
                    theirPartnerName = if (o.isNull("theirPartnerName")) null else o.optString("theirPartnerName"),
                    requestedAt = o.optLong("requestedAt", 0L)
                )
            }
        } catch (e: Exception) {
            // A torn/corrupt list here must never crash the sync path that reads it - worst case, prior
            // pending requests are silently lost, same self-healing philosophy as corruptionHandler above.
            emptyList()
        }
    }

    private fun encodePendingResyncRequests(list: List<PendingResyncRequest>): String {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("deviceId", r.deviceId)
                put("bluetoothName", r.bluetoothName ?: JSONObject.NULL)
                put("theirPartnerName", r.theirPartnerName ?: JSONObject.NULL)
                put("requestedAt", r.requestedAt)
            })
        }
        return arr.toString()
    }

    companion object {
        /** Fable review: an unanswered pending request from weeks ago risks being approved on faith by a
         * user who no longer remembers the context - expiring it means the only thing left to approve is
         * recent enough to plausibly still be reasoned about, and a genuinely still-trying partner simply
         * gets re-recorded by their own retry loop, so nothing real is lost by expiring. */
        const val PENDING_RESYNC_REQUEST_TTL_MILLIS = 72 * 60 * 60 * 1000L // 72 hours
        const val MAX_PENDING_RESYNC_REQUESTS = 5
    }

    /** Full raw restore used by Feature 4's backup restore flow - writes every field (including the
     * "last connection" snapshot) verbatim from a backup manifest, bypassing the savePairing()/unpair()
     * business rules above (e.g. it must NOT clear last-connection the way savePairing() does). */
    suspend fun restoreRaw(
        secretHash: String?, plainCode: String?, partnerName: String?, partnerEmoji: String?, pairedAt: Long,
        lastSecretHash: String?, lastPlainCode: String?, lastPartnerName: String?, lastPartnerEmoji: String?, lastUnpairedAt: Long
    ) {
        context.pairingDs.edit { p ->
            p.clear()
            if (!secretHash.isNullOrBlank()) {
                p[Keys.SECRET_HASH] = secretHash
                p[Keys.PLAIN_CODE] = plainCode ?: ""
                p[Keys.PARTNER_NAME] = partnerName ?: "Your Person"
                p[Keys.PARTNER_EMOJI] = partnerEmoji ?: "💕"
                p[Keys.PAIRED_AT] = pairedAt
            }
            if (!lastSecretHash.isNullOrBlank()) {
                p[Keys.LAST_SECRET_HASH] = lastSecretHash
                p[Keys.LAST_PLAIN_CODE] = lastPlainCode ?: ""
                p[Keys.LAST_PARTNER_NAME] = lastPartnerName ?: "Your Person"
                p[Keys.LAST_PARTNER_EMOJI] = lastPartnerEmoji ?: "💕"
                p[Keys.LAST_UNPAIRED_AT] = lastUnpairedAt
            }
        }
    }
}

/** Manual override for light/dark appearance, independent of the OS setting. SYSTEM (the default) keeps
 * today's existing behavior (TwogetherTheme's own isSystemInDarkTheme() default) - LIGHT/DARK force one
 * regardless of the device's own setting. Persisted by name (stringPreferencesKey), not ordinal, so
 * reordering this enum later can never silently reinterpret an already-saved choice as a different mode. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val defaultSnoozeMinutes: Int = 15,
    val notificationsEnabled: Boolean = true,
    val pinHash: String? = null,
    val pinEnabled: Boolean = false,
    val lastSyncAt: Long = 0L,
    val deviceTieBreakByte: Int? = null,
    val lastBackupAt: Long = 0L,
    val lastBackupOk: Boolean = false,
    /** Feature D: this install's stable identity, used to tag which side authored a MomentNote (see
     * MomentNoteRepository). Generated once via getOrCreateLocalDeviceId(); deliberately included in
     * backup/restore (BackupManager) so restoring onto a replacement phone after a loss keeps this
     * person's own notes recognized as "mine" rather than misattributed as the partner's. */
    val localDeviceId: String? = null,
    /** Auto-update: whether app start / the daily UpdateWorker are allowed to hit the GitHub Releases
     * API at all. Defaults on, but is a real opt-out - this is the one deliberate exception to this
     * app's otherwise fully-offline design (see the INTERNET permission's manifest comment), so it
     * must stay under the user's control. The manual "Check for updates now" button in Settings
     * ignores this (an explicit tap is its own consent) - see UpdateChecker/SettingsScreen. */
    val autoUpdateCheckEnabled: Boolean = true,
    /** Auto-update: wall-clock time of the last *attempted* update check (success or failure),
     * whichever of app-start or UpdateWorker ran it - throttles app-start's own check so reopening
     * the app repeatedly can't re-hit the network every time; see UpdateChecker.MIN_CHECK_INTERVAL_MS. */
    val lastUpdateCheckAt: Long = 0L,
    /** BUG fix: a downloaded update used to be discoverable ONLY via the dismissible "Update available"
     * OS notification - swipe it away (or have notifications disabled entirely, app-level or
     * OS-permission-level) and there was no way to find out an update was ready short of manually
     * tapping "Check for updates now" again. These three durably record the last update UpdateChecker
     * actually downloaded, so Settings can show a persistent "Update X ready to install" row regardless
     * of notification state. [pendingUpdateVersionCode] is 0 when nothing is pending; a UI reader should
     * also treat it as stale (and ignore it) once it's <= BuildConfig.VERSION_CODE - covers the update
     * having already been installed via the notification without a fresh check ever running to clear
     * these explicitly. */
    val pendingUpdateVersionCode: Int = 0,
    val pendingUpdateVersionName: String? = null,
    val pendingUpdateApkPath: String? = null,
    /** Drag-and-drop reordering of Home's "Quick links" grid - deliberately per-device, NOT synced
     * between partners (each person may want their own layout), so this lives in local settingsDs
     * rather than anywhere that gets shared/paired. Comma-joined list of the stable quick-link ids
     * (see QuickLinkIds in HomeScreen.kt) in the user's preferred order. Null means "never reordered" -
     * HomeScreen falls back to its DEFAULT_QUICK_LINK_ORDER in that case, so a fresh install (or an
     * install that predates this feature) keeps showing the original hardcoded order unchanged. */
    val quickLinksOrder: String? = null,
    /** See [ThemeMode]'s own doc. Deliberately per-device (like quickLinksOrder above), not synced
     * between partners - each person's phone can independently follow-system/force-light/force-dark. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Non-null while a backup restore is in flight or was interrupted before completing (process death,
     * force-close) - the app-private path of the cached backup zip BackupManager.restoreBackupDurable
     * copied the user's picked file to before touching anything live. Checked on every app start
     * (TwogetherApp.onCreate) so an interrupted restore automatically resumes and retries until it fully
     * succeeds, rather than silently leaving the phone in a half-restored state with no recovery path -
     * see restoreBackupDurable's own doc for why true cross-storage-engine atomicity isn't achievable
     * here (Room and DataStore are separate engines with no shared transaction), so "resume until it
     * actually finishes" is the real fix instead. Cleared the moment a restore from this path succeeds. */
    val pendingRestorePath: String? = null,
    /** Count of consecutive NON-permanent failures for the restore at [pendingRestorePath] (see
     * BackupManager.BackupResult.permanent's doc for permanent-vs-not). MAJOR fix: without a cap, a
     * deterministic-but-not-detected-as-permanent failure (e.g. the device is out of storage, so the
     * live Room tables get wiped+repopulated but the trailing photo-copy step throws) would otherwise
     * retry - and re-wipe the DB - on every single app launch forever, destroying any real data created
     * since. Reset to 0 whenever a NEW restore starts (restoreBackupDurable); once it exceeds
     * BackupManager's retry cap, the attempt is treated as permanent and the pending state is given up
     * on, same as a validation failure. */
    val pendingRestoreAttempts: Int = 0,
    /** Item 24 (UX-FIX-PLAN.md): how many minutes of continuous together-time trigger the "snap a
     * photo?" nudge - was previously hardcoded (ProximityForegroundService.FIFTEEN_MINUTES_MILLIS,
     * Notifications.showPhotoReminder's title text). Settings' "Photo reminder interval" row lets the
     * user change this; the notification's own title text is now built from this value too, rather than
     * a hardcoded "15". */
    val photoReminderMinutes: Int = 15,
    /** Item 5 (deferred UX fix, 4-model advisory audit): OPTIONAL biometric (fingerprint/face) unlock as a
     * faster alternative alongside the PIN screen - see [com.ssbmedia.twogether.lock.BiometricGate]'s own
     * doc for the gating logic and [com.ssbmedia.twogether.ui.lock.PinLockScreen] for where this is
     * actually offered. Defaults OFF: this is a genuinely new permission-adjacent surface, so it must be
     * an explicit opt-in, never silently on. Never a REPLACEMENT for the PIN - PIN entry stays available
     * unconditionally regardless of this setting, since biometric auth can always fail (wet fingers, no
     * enrollment, hardware removed) and there must always be a way in. */
    val biometricUnlockEnabled: Boolean = false,
    /** Reunion-count non-retroactivity feature: replaces the old hardcoded
     * ProximityForegroundService.SESSION_GRACE_MILLIS (10 min) constant. How long a brief BLE gap can
     * last without resetting the continuous "together" timer/session - see
     * ProximityForegroundService.withinGraceWindow/graceWindowExpired, both of which now read this live
     * setting instead of a compile-time constant. Purely a real-time session-continuation knob - nothing
     * displayed anywhere is ever DERIVED from this setting's history, so (unlike
     * [reunionThresholdMinutes] below) changing it has no retroactive-recomputation concern at all: it
     * only ever affects the CURRENT apart/together decision, never a past one. */
    val sessionGraceMinutes: Int = 10,
    /** Couple-level settings sync: wall-clock time [sessionGraceMinutes] was last changed, on EITHER
     * phone - lets GattSyncManager do a plain per-field last-write-wins merge (adopt the remote's value
     * only if its timestamp is newer than this phone's own) the same way every synced entity's `updatedAt`
     * already works, without needing a new merge concept just for settings. 0L (never changed locally)
     * always loses to any real remote timestamp, so a fresh install correctly adopts its partner's already-
     * configured values on first sync rather than fighting them with an untouched default. */
    val sessionGraceMinutesUpdatedAt: Long = 0L,
    /** Reunion-count non-retroactivity feature: the CURRENT threshold used to detect a NEW reunion going
     * forward - replaces the old hardcoded StatsCalculator.REUNION_GAP_MILLIS (60 min) constant for all
     * LIVE (post-migration) reunion detection. Read live, at the exact moment of each apart->together
     * transition, by ProximityForegroundService.handleBecameTogether - see its own doc and
     * [com.ssbmedia.twogether.data.datastore.ProximityPersistedState.reunionCount]'s doc for why this is
     * a genuinely one-time, point-in-time decision per reunion rather than a value that could later be
     * recomputed differently. CRITICAL: changing this must NEVER retroactively reinterpret history -
     * already-recorded reunions stay counted forever exactly as they happened, under whatever threshold
     * was in effect the moment each one occurred. That guarantee is what makes reunionCount a persisted,
     * live-incrementing counter instead of a value derived by rescanning session history (the old
     * StatsCalculator.countReunions design, which is fundamentally incompatible with a user-editable
     * threshold - the moment the constant becomes editable, "rescan everything with today's value" starts
     * silently reinterpreting history the user already celebrated under a different rule). */
    val reunionThresholdMinutes: Int = 60,
    /** Couple-level settings sync: same role as [sessionGraceMinutesUpdatedAt] above, for
     * [reunionThresholdMinutes]. Only the live threshold value/timestamp are ever synced this way -
     * [com.ssbmedia.twogether.data.datastore.ProximityPersistedState.reunionCount] itself (the actual
     * count) is NEVER synced or merged between phones; each phone keeps counting its own independently
     * detected reunions, exactly as it already did before this settings-sync feature existed. Syncing the
     * THRESHOLD just means both phones agree on which future gaps qualify - it has no bearing on the
     * non-retroactivity guarantee, since a synced threshold change is still only ever applied to each
     * phone's own subsequent apart->together transitions, never rescanned into either phone's past. */
    val reunionThresholdMinutesUpdatedAt: Long = 0L
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val SNOOZE_MIN = intPreferencesKey("default_snooze_minutes")
        val NOTIFS = booleanPreferencesKey("notifications_enabled")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_ENABLED = booleanPreferencesKey("pin_enabled")
        val LAST_SYNC = longPreferencesKey("last_sync_at")
        val TIE_BREAK = intPreferencesKey("device_tie_break_byte")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val LAST_BACKUP_OK = booleanPreferencesKey("last_backup_ok")
        val LOCAL_DEVICE_ID = stringPreferencesKey("local_device_id")
        val AUTO_UPDATE_ENABLED = booleanPreferencesKey("auto_update_check_enabled")
        val LAST_UPDATE_CHECK_AT = longPreferencesKey("last_update_check_at")
        val QUICK_LINKS_ORDER = stringPreferencesKey("quick_links_order")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PENDING_RESTORE_PATH = stringPreferencesKey("pending_restore_path")
        val PENDING_RESTORE_ATTEMPTS = intPreferencesKey("pending_restore_attempts")
        val PENDING_UPDATE_VERSION_CODE = intPreferencesKey("pending_update_version_code")
        val PENDING_UPDATE_VERSION_NAME = stringPreferencesKey("pending_update_version_name")
        val PENDING_UPDATE_APK_PATH = stringPreferencesKey("pending_update_apk_path")
        val PHOTO_REMINDER_MINUTES = intPreferencesKey("photo_reminder_minutes")
        val BIOMETRIC_UNLOCK_ENABLED = booleanPreferencesKey("biometric_unlock_enabled")
        val SESSION_GRACE_MINUTES = intPreferencesKey("session_grace_minutes")
        val REUNION_THRESHOLD_MINUTES = intPreferencesKey("reunion_threshold_minutes")
        val SESSION_GRACE_MINUTES_UPDATED_AT = longPreferencesKey("session_grace_minutes_updated_at")
        val REUNION_THRESHOLD_MINUTES_UPDATED_AT = longPreferencesKey("reunion_threshold_minutes_updated_at")
    }

    val settings: Flow<AppSettings> = context.settingsDs.data.map { p -> fromPreferences(p) }

    companion object {
        /** Pure mapping from a raw DataStore [Preferences] snapshot to [AppSettings] - extracted out of
         * the [settings] Flow's map{} lambda so it's unit-testable directly against a plain in-memory
         * Preferences instance (androidx.datastore.preferences.core.Preferences/MutablePreferences are
         * ordinary JVM classes with no Android Context dependency, unlike the DataStore file itself),
         * without needing Robolectric or an instrumented test. Item 24 (UX-FIX-PLAN.md) added
         * photoReminderMinutes; see PhotoReminderSettingsAuditTest for the round-trip coverage this
         * enables. */
        internal fun fromPreferences(p: Preferences): AppSettings = AppSettings(
            defaultSnoozeMinutes = p[Keys.SNOOZE_MIN] ?: 15,
            notificationsEnabled = p[Keys.NOTIFS] ?: true,
            pinHash = p[Keys.PIN_HASH],
            pinEnabled = p[Keys.PIN_ENABLED] ?: false,
            lastSyncAt = p[Keys.LAST_SYNC] ?: 0L,
            deviceTieBreakByte = p[Keys.TIE_BREAK],
            lastBackupAt = p[Keys.LAST_BACKUP_AT] ?: 0L,
            lastBackupOk = p[Keys.LAST_BACKUP_OK] ?: false,
            localDeviceId = p[Keys.LOCAL_DEVICE_ID],
            autoUpdateCheckEnabled = p[Keys.AUTO_UPDATE_ENABLED] ?: true,
            lastUpdateCheckAt = p[Keys.LAST_UPDATE_CHECK_AT] ?: 0L,
            quickLinksOrder = p[Keys.QUICK_LINKS_ORDER],
            // Defensive against a value from a future app version this build doesn't recognize (an
            // enum name Room/DataStore can't map back) - falls back to SYSTEM rather than crashing.
            themeMode = p[Keys.THEME_MODE]?.let { raw -> runCatching { ThemeMode.valueOf(raw) }.getOrNull() } ?: ThemeMode.SYSTEM,
            pendingRestorePath = p[Keys.PENDING_RESTORE_PATH],
            pendingRestoreAttempts = p[Keys.PENDING_RESTORE_ATTEMPTS] ?: 0,
            pendingUpdateVersionCode = p[Keys.PENDING_UPDATE_VERSION_CODE] ?: 0,
            pendingUpdateVersionName = p[Keys.PENDING_UPDATE_VERSION_NAME],
            pendingUpdateApkPath = p[Keys.PENDING_UPDATE_APK_PATH],
            photoReminderMinutes = p[Keys.PHOTO_REMINDER_MINUTES] ?: 15,
            biometricUnlockEnabled = p[Keys.BIOMETRIC_UNLOCK_ENABLED] ?: false,
            sessionGraceMinutes = p[Keys.SESSION_GRACE_MINUTES] ?: 10,
            sessionGraceMinutesUpdatedAt = p[Keys.SESSION_GRACE_MINUTES_UPDATED_AT] ?: 0L,
            reunionThresholdMinutes = p[Keys.REUNION_THRESHOLD_MINUTES] ?: 60,
            reunionThresholdMinutesUpdatedAt = p[Keys.REUNION_THRESHOLD_MINUTES_UPDATED_AT] ?: 0L
        )
    }

    /** Returns this install's persistent random device identity (Feature D), generating it once if
     * missing - same read-then-write-inside-one-edit{} pattern as getOrCreateTieBreakByte() below, for
     * the same reason (two concurrent callers must not each generate and persist a different id). */
    suspend fun getOrCreateLocalDeviceId(): String {
        var result = ""
        context.settingsDs.edit { p ->
            val existing = p[Keys.LOCAL_DEVICE_ID]
            // SECURITY (test-code-allmodels round 5, Opus): a present-but-BLANK value (e.g. a
            // hand-edited or corrupted backup file - BackupManager's own JSON reader can yield "" for a
            // key that's present but empty, and the restore path only null-checks) used to be accepted
            // as "already generated" forever, since `existing != null` is true for "". Now that
            // GattSyncManager's handshake gate rejects a blank deviceId outright (it can never be
            // pinned, see checkPinOrRecordPending's own doc), that would have permanently locked this
            // install out of ever pairing again with no in-app recovery. Treat blank the same as
            // missing - regenerate a real id.
            result = if (!existing.isNullOrBlank()) existing else UUID.randomUUID().toString().also { p[Keys.LOCAL_DEVICE_ID] = it }
        }
        return result
    }

    /** Returns this install's persistent random role tie-break byte, generating it once if missing.
     * Reads and (if needed) writes inside the same edit{} transaction so two concurrent callers can't
     * each generate and persist a different random byte and race on which one "wins". */
    suspend fun getOrCreateTieBreakByte(): Int {
        var result = 0
        context.settingsDs.edit { p ->
            val existing = p[Keys.TIE_BREAK]
            result = if (existing != null) {
                existing
            } else {
                (0..255).random().also { p[Keys.TIE_BREAK] = it }
            }
        }
        return result
    }

    /** Re-rolls this install's tie-break byte to a fresh random value (see getOrCreateTieBreakByte)
     * and persists it. Used to break a rare (~1/256) tie-break collision with the partner's byte,
     * which would otherwise permanently disable date-ideas sync for that couple forever - since both
     * bytes are drawn once at install and never revisited, `ownTieBreak == partnerTieBreak` would stay
     * true on every sync attempt indefinitely with no self-correction. Re-rolling on the LOCAL side
     * only is enough: it's a fresh independent random draw each time a collision is hit, so it
     * converges (to inequality) with the very next attempt in all but a vanishingly rare
     * repeat-collision case, and never needs the other device to also change anything. */
    suspend fun regenerateTieBreakByte(): Int {
        val fresh = (0..255).random()
        context.settingsDs.edit { it[Keys.TIE_BREAK] = fresh }
        return fresh
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDefaultSnoozeMinutes(min: Int) {
        context.settingsDs.edit { it[Keys.SNOOZE_MIN] = min }
    }

    /** Item 24 (UX-FIX-PLAN.md): persists the user's chosen photo-reminder interval - see
     * AppSettings.photoReminderMinutes' own doc. Same "trust the caller's own bound-checked input"
     * pattern as setDefaultSnoozeMinutes above (the Settings dialog itself does the coerceIn), rather
     * than duplicating validation here too. */
    suspend fun setPhotoReminderMinutes(minutes: Int) {
        context.settingsDs.edit { it[Keys.PHOTO_REMINDER_MINUTES] = minutes }
    }

    /** Reunion-count non-retroactivity feature: persists the user's chosen together-timer grace window -
     * see [AppSettings.sessionGraceMinutes]'s own doc. Same "trust the caller's own bound-checked input"
     * pattern as setPhotoReminderMinutes/setDefaultSnoozeMinutes above. Purely a live setting - no
     * historical data is ever derived from it, so unlike [setReunionThresholdMinutes] there is no
     * retroactive-recomputation concern to document here. */
    suspend fun setSessionGraceMinutes(minutes: Int) {
        context.settingsDs.edit {
            it[Keys.SESSION_GRACE_MINUTES] = minutes
            it[Keys.SESSION_GRACE_MINUTES_UPDATED_AT] = System.currentTimeMillis()
        }
    }

    /** Couple-level settings sync: applies a value that WON a last-write-wins merge against the partner's
     * phone (see GattSyncManager's settings-sync wiring) - writes [remoteUpdatedAt] verbatim rather than
     * stamping "now" the way [setSessionGraceMinutes] (a genuine local user edit) does, so this phone's own
     * notion of "when was this last changed" stays correctly anchored to whichever phone actually changed
     * it, not to whenever the sync happened to run. Never call this for a local user edit - use
     * [setSessionGraceMinutes] for that. */
    suspend fun applySyncedSessionGraceMinutes(minutes: Int, remoteUpdatedAt: Long) {
        context.settingsDs.edit {
            it[Keys.SESSION_GRACE_MINUTES] = minutes
            it[Keys.SESSION_GRACE_MINUTES_UPDATED_AT] = remoteUpdatedAt
        }
    }

    /** Reunion-count non-retroactivity feature: persists the user's chosen reunion threshold - see
     * [AppSettings.reunionThresholdMinutes]'s own doc for the full non-retroactivity guarantee. This
     * setter does nothing beyond writing the new live value: it deliberately does NOT touch
     * [ProximityPersistedState.reunionCount] or trigger any rescan of history. Changing this value only
     * ever changes which threshold ProximityForegroundService.handleBecameTogether applies to the VERY
     * NEXT apart->together transition it evaluates - every reunion already counted before this call stays
     * counted exactly as it was, forever. */
    suspend fun setReunionThresholdMinutes(minutes: Int) {
        context.settingsDs.edit {
            it[Keys.REUNION_THRESHOLD_MINUTES] = minutes
            it[Keys.REUNION_THRESHOLD_MINUTES_UPDATED_AT] = System.currentTimeMillis()
        }
    }

    /** Couple-level settings sync: same role as [applySyncedSessionGraceMinutes] above, for
     * [AppSettings.reunionThresholdMinutes]. Writes [remoteUpdatedAt] verbatim (never "now"). Never call
     * this for a local user edit - use [setReunionThresholdMinutes] for that. Does NOT touch
     * [ProximityPersistedState.reunionCount] - see that field's own doc and
     * [AppSettings.reunionThresholdMinutesUpdatedAt]'s doc for why the count itself is never synced,
     * only the threshold going forward. */
    suspend fun applySyncedReunionThresholdMinutes(minutes: Int, remoteUpdatedAt: Long) {
        context.settingsDs.edit {
            it[Keys.REUNION_THRESHOLD_MINUTES] = minutes
            it[Keys.REUNION_THRESHOLD_MINUTES_UPDATED_AT] = remoteUpdatedAt
        }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.settingsDs.edit { it[Keys.NOTIFS] = enabled }
    }

    suspend fun setPin(hash: String) {
        context.settingsDs.edit {
            it[Keys.PIN_HASH] = hash
            it[Keys.PIN_ENABLED] = true
        }
    }

    suspend fun clearPin() {
        context.settingsDs.edit {
            it.remove(Keys.PIN_HASH)
            it[Keys.PIN_ENABLED] = false
            // Item 5: biometric unlock is only ever meaningful as a PIN-screen alternative - turning PIN
            // lock off entirely must also turn it off, so it can't linger enabled+orphaned (no PIN screen
            // left for it to ever be offered on) only to silently reappear if PIN lock is turned back on
            // later without the user having consciously re-opted-in.
            it[Keys.BIOMETRIC_UNLOCK_ENABLED] = false
        }
    }

    /** Item 5 (deferred UX fix, 4-model advisory audit): persists the user's opt-in/opt-out for the
     * optional biometric-unlock alternative to the PIN screen - see [AppSettings.biometricUnlockEnabled]'s
     * own doc. Settings' toggle for this only ever shows when [com.ssbmedia.twogether.lock.BiometricGate]
     * confirms the device actually has usable biometric enrollment, but this setter itself does no such
     * check - it trusts the caller the same way setDefaultSnoozeMinutes/setPhotoReminderMinutes do. */
    suspend fun setBiometricUnlockEnabled(enabled: Boolean) {
        context.settingsDs.edit { it[Keys.BIOMETRIC_UNLOCK_ENABLED] = enabled }
    }

    suspend fun setLastSyncAt(time: Long) {
        context.settingsDs.edit { it[Keys.LAST_SYNC] = time }
    }

    suspend fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        context.settingsDs.edit { it[Keys.AUTO_UPDATE_ENABLED] = enabled }
    }

    suspend fun setLastUpdateCheckAt(time: Long) {
        context.settingsDs.edit { it[Keys.LAST_UPDATE_CHECK_AT] = time }
    }

    /** See [AppSettings.pendingUpdateVersionCode]'s doc. Called once by UpdateChecker right after it
     * downloads a confirmed-newer release, so Settings can show a persistent "Update ready" indicator
     * independent of the dismissible OS notification. */
    suspend fun setPendingUpdate(versionCode: Int, versionName: String, apkPath: String) {
        context.settingsDs.edit { p ->
            p[Keys.PENDING_UPDATE_VERSION_CODE] = versionCode
            p[Keys.PENDING_UPDATE_VERSION_NAME] = versionName
            p[Keys.PENDING_UPDATE_APK_PATH] = apkPath
        }
    }

    /** Clears the pending-update indicator - called once UpdateChecker confirms this build is already
     * up to date (the pending update must have been installed already), or by Settings defensively if
     * the cached APK it points at has gone missing. */
    suspend fun clearPendingUpdate() {
        context.settingsDs.edit { p ->
            p.remove(Keys.PENDING_UPDATE_VERSION_CODE)
            p.remove(Keys.PENDING_UPDATE_VERSION_NAME)
            p.remove(Keys.PENDING_UPDATE_APK_PATH)
        }
    }

    /** Persists the user's drag-and-drop reordering of Home's "Quick links" grid. [orderedIds] is
     * stored comma-joined verbatim - deliberately not validated/deduped here, since HomeScreen's own
     * reconciliation (filtering to currently-known ids + appending any missing ones) is what stays
     * resilient to a future quick link being added or removed; this store just persists whatever it's
     * handed. */
    suspend fun setQuickLinksOrder(orderedIds: List<String>) {
        context.settingsDs.edit { it[Keys.QUICK_LINKS_ORDER] = orderedIds.joinToString(",") }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDs.edit { it[Keys.THEME_MODE] = mode.name }
    }

    /** See [AppSettings.pendingRestorePath]'s doc. [path] null clears it (a restore either fully
     * succeeded or was never attempted); non-null marks one as in-flight/needing resume. Also
     * resets/clears [AppSettings.pendingRestoreAttempts] in the same edit, since a null path always means
     * "no attempt in progress to count" and a fresh non-null path always means a brand new restore whose
     * attempt count must start over at 0, never inherit a previous restore's count. */
    suspend fun setPendingRestorePath(path: String?) {
        context.settingsDs.edit { p ->
            if (path != null) p[Keys.PENDING_RESTORE_PATH] = path else p.remove(Keys.PENDING_RESTORE_PATH)
            p.remove(Keys.PENDING_RESTORE_ATTEMPTS)
        }
    }

    /** Atomically increments [AppSettings.pendingRestoreAttempts] and returns the new count - see its
     * own doc for why this exists. Read-modify-write inside one edit{} so two near-simultaneous callers
     * (which shouldn't happen given BackupManager's restoreMutex, but this is cheap insurance) can't lose
     * an increment to each other. */
    suspend fun incrementPendingRestoreAttempts(): Int {
        var newCount = 0
        context.settingsDs.edit { p ->
            newCount = (p[Keys.PENDING_RESTORE_ATTEMPTS] ?: 0) + 1
            p[Keys.PENDING_RESTORE_ATTEMPTS] = newCount
        }
        return newCount
    }

    /** Records the outcome of the most recent backup attempt (manual "Back up now" or the weekly
     * WorkManager job), so Settings can show "Last backup: ..." / "failed" without the caller having to
     * plumb a one-shot result through separately. */
    suspend fun setLastBackupResult(atMillis: Long, ok: Boolean) {
        context.settingsDs.edit {
            it[Keys.LAST_BACKUP_AT] = atMillis
            it[Keys.LAST_BACKUP_OK] = ok
        }
    }

    /** Full raw restore used by Feature 4's backup restore flow - overwrites every settings field
     * verbatim from a backup manifest (deliberately preserves whatever lastBackupAt/lastBackupOk already
     * exist on THIS install rather than the backed-up ones, since those describe the backup file itself,
     * not the couple's data, and get set again right after this call completes anyway).
     *
     * MINOR fix (ultimate-app-review round 1, item A3): sessionGraceMinutes/reunionThresholdMinutes
     * (+ their *UpdatedAt companions), photoReminderMinutes, themeMode, quickLinksOrder, and
     * autoUpdateCheckEnabled were completely absent from this round-trip (confirmed: zero occurrences
     * anywhere in this function or in BackupManager's manifest before this fix) - restoring a backup
     * silently reset all six to their AppSettings defaults instead of the values the couple had actually
     * configured. Added below, all with plain default parameter values matching AppSettings' own defaults
     * (mirroring defaultSnoozeMinutes/notificationsEnabled's existing non-nullable-with-default shape
     * above) so a backup made by an OLDER version of this app - one that predates this fix and simply has
     * no JSON keys for these six fields - restores cleanly via BackupManager's own optInt/optLong/
     * optBoolean/optStringOrNull defaults at the JSON-read call site, without this function needing any
     * special-cased null-handling for them. quickLinksOrder is the one deliberately nullable exception
     * (matching deviceTieBreakByte's/localDeviceId's own optional shape above) - null is a genuinely
     * meaningful "never reordered" state HomeScreen already falls back on, not just "value absent".
     * biometricUnlockEnabled is DELIBERATELY excluded from both this function and the backup manifest -
     * see AppSettings.biometricUnlockEnabled's own doc for why that one field must always require a fresh,
     * explicit opt-in rather than being silently restored. */
    suspend fun restoreRaw(
        defaultSnoozeMinutes: Int, notificationsEnabled: Boolean, pinHash: String?, pinEnabled: Boolean,
        lastSyncAt: Long, deviceTieBreakByte: Int?, localDeviceId: String? = null,
        sessionGraceMinutes: Int = 10, sessionGraceMinutesUpdatedAt: Long = 0L,
        reunionThresholdMinutes: Int = 60, reunionThresholdMinutesUpdatedAt: Long = 0L,
        photoReminderMinutes: Int = 15, themeMode: String = ThemeMode.SYSTEM.name,
        quickLinksOrder: String? = null, autoUpdateCheckEnabled: Boolean = true
    ) {
        context.settingsDs.edit { p ->
            p[Keys.SNOOZE_MIN] = defaultSnoozeMinutes
            p[Keys.NOTIFS] = notificationsEnabled
            if (pinHash != null) p[Keys.PIN_HASH] = pinHash else p.remove(Keys.PIN_HASH)
            p[Keys.PIN_ENABLED] = pinEnabled
            p[Keys.LAST_SYNC] = lastSyncAt
            if (deviceTieBreakByte != null) p[Keys.TIE_BREAK] = deviceTieBreakByte else p.remove(Keys.TIE_BREAK)
            // Deliberately NOT cleared when null (unlike the other fields above) - an older backup made
            // before Feature D existed simply won't have this key, and this device's own already-generated
            // id (if any) must survive that restore rather than being wiped, or a fresh
            // getOrCreateLocalDeviceId() call right after would mint a new one anyway; either way there's
            // no "restore to blank" case worth supporting here.
            if (localDeviceId != null) p[Keys.LOCAL_DEVICE_ID] = localDeviceId
            p[Keys.SESSION_GRACE_MINUTES] = sessionGraceMinutes
            p[Keys.SESSION_GRACE_MINUTES_UPDATED_AT] = sessionGraceMinutesUpdatedAt
            p[Keys.REUNION_THRESHOLD_MINUTES] = reunionThresholdMinutes
            p[Keys.REUNION_THRESHOLD_MINUTES_UPDATED_AT] = reunionThresholdMinutesUpdatedAt
            p[Keys.PHOTO_REMINDER_MINUTES] = photoReminderMinutes
            p[Keys.THEME_MODE] = themeMode
            // Nullable, matching localDeviceId's shape above - see this function's own doc for why.
            if (quickLinksOrder != null) p[Keys.QUICK_LINKS_ORDER] = quickLinksOrder else p.remove(Keys.QUICK_LINKS_ORDER)
            p[Keys.AUTO_UPDATE_ENABLED] = autoUpdateCheckEnabled
        }
    }
}

/** Persisted proximity state so the foreground service can resume correctly after process death / reboot. */
data class ProximityPersistedState(
    val isTogether: Boolean = false,
    val lastSeenAt: Long = 0L,
    val continuousTogetherSince: Long = 0L,
    val lastApartSince: Long = 0L,
    val reminderFiredForSession: Boolean = false,
    val snoozeUntil: Long = 0L,
    val currentSessionId: Long = -1L,
    val pendingReunionCelebration: Boolean = false,
    // Mirrors ProximityStateMachine.lastSeenElapsedRealtime (SystemClock.elapsedRealtime(), NOT wall
    // clock) so HomeScreen's own defensive staleness check can also be immune to a backwards system
    // clock change, matching the service's own checkAbsence() logic - see ProximityStateMachine's doc
    // for the full reasoning. Only meaningful within the current boot session; do not use for anything
    // user-facing (persisted session times / "last saw them X ago" text must keep using lastSeenAt).
    val lastSeenElapsedRealtime: Long = 0L,
    /** Item 9 (UX-FIX-PLAN.md): wall-clock instant the fast ~100s isTogether apart-flip happened, set by
     * ProximityForegroundService.handleBecameApart the moment it flips - NOT when the underlying
     * TogetherSession row actually gets closed, which now waits out
     * ProximityForegroundService.SESSION_GRACE_MILLIS (~10 min) first so a brief reconnect can resume
     * the SAME session/timer instead of restarting it. 0L means "no apart transition pending a
     * session-close decision" (currently together, or the grace window already resolved one way or the
     * other). Deliberately a SEPARATE field from [lastApartSince] (which anchors the reunion-gap check
     * and must keep reflecting the CLAMPED estimated-real-apart instant, not this raw transition
     * timestamp) - see ProximityForegroundService.handleBecameApart's doc for why both exist. */
    val pendingApartSince: Long = 0L,
    /** Item 24 (UX-FIX-PLAN.md): "fired once per together-session" tracking for the list/list-item
     * reminder feature - mirrors [reminderFiredForSession]'s pattern for the photo reminder, just keyed
     * (rather than a single bool) since many distinct ideas/lists can each have their own reminder.
     * Entries look like "idea:<DateIdea.id>" or "list:<ListCategory.id>" - see
     * ProximityForegroundService.checkListReminders' doc. Reset to empty at exactly the same two points
     * reminderFiredForSession resets to false (a fresh together-transition, and an apart transition), so
     * a new continuous-together session always gets a clean slate of reminders to re-fire. */
    val remindersFiredForSession: Set<String> = emptySet(),
    /** Reunion-count non-retroactivity feature (see [AppSettings.reunionThresholdMinutes]'s own doc for
     * the full design rationale). This is NOT derived/recomputable - it is a persisted counter that
     * ProximityForegroundService.handleBecameTogether increments by exactly 1, live, at the moment it
     * determines a reunion just happened (evaluated against whatever [AppSettings.reunionThresholdMinutes]
     * was in effect at that exact instant). StatsCalculator.compute() now takes this as a plain parameter
     * instead of recomputing it by rescanning session history - see StatsCalculator.compute's own doc and
     * the now-legacy-only StatsCalculator.countReunions/legacyReunionCountForBackfill. Starts at 0 for a
     * brand new install; see [reunionCountBackfilled] for how an EXISTING install's already-accumulated
     * history gets seeded into this exactly once, without ever being rescanned again after that. */
    val reunionCount: Int = 0,
    /** Reunion-count non-retroactivity feature: true once the one-time legacy backfill (see
     * ProximityForegroundService.backfillReunionCountIfNeeded) has run for this install. Existing installs
     * from before this feature shipped have real history that was ALWAYS evaluated under the single
     * hardcoded StatsCalculator.REUNION_GAP_MILLIS (60 min) - genuinely unambiguous, since that's the only
     * threshold that has ever applied to any of this app's history - so it's seeded into [reunionCount]
     * exactly once by rescanning with that fixed legacy value. This flag is what makes that a ONE-TIME
     * event: once true, it must never flip back or be re-evaluated, so a later live change to
     * [AppSettings.reunionThresholdMinutes] can never re-trigger a rescan of history under the new value -
     * that is exactly the retroactive-recomputation bug this whole feature exists to prevent. False on a
     * brand new install too, but harmlessly so: a fresh install has no pre-existing history to backfill,
     * the rescan finds 0 reunions, and the flag flips true on the very first restoreState() either way. */
    val reunionCountBackfilled: Boolean = false
)

class ProximityStateStore(private val context: Context) {
    private object Keys {
        val IS_TOGETHER = booleanPreferencesKey("is_together")
        val LAST_SEEN_AT = longPreferencesKey("last_seen_at")
        val CONTINUOUS_SINCE = longPreferencesKey("continuous_together_since")
        val LAST_APART_SINCE = longPreferencesKey("last_apart_since")
        val REMINDER_FIRED = booleanPreferencesKey("reminder_fired_for_session")
        val SNOOZE_UNTIL = longPreferencesKey("snooze_until")
        val SESSION_ID = longPreferencesKey("current_session_id")
        val PENDING_CELEBRATION = booleanPreferencesKey("pending_reunion_celebration")
        val LAST_SEEN_ELAPSED_REALTIME = longPreferencesKey("last_seen_elapsed_realtime")
        val PENDING_APART_SINCE = longPreferencesKey("pending_apart_since")
        val LIST_REMINDERS_FIRED = stringSetPreferencesKey("list_reminders_fired_for_session")
        val REUNION_COUNT = intPreferencesKey("reunion_count")
        val REUNION_COUNT_BACKFILLED = booleanPreferencesKey("reunion_count_backfilled")
    }

    val state: Flow<ProximityPersistedState> = context.proximityDs.data.map { p ->
        ProximityPersistedState(
            isTogether = p[Keys.IS_TOGETHER] ?: false,
            lastSeenAt = p[Keys.LAST_SEEN_AT] ?: 0L,
            continuousTogetherSince = p[Keys.CONTINUOUS_SINCE] ?: 0L,
            lastApartSince = p[Keys.LAST_APART_SINCE] ?: 0L,
            reminderFiredForSession = p[Keys.REMINDER_FIRED] ?: false,
            snoozeUntil = p[Keys.SNOOZE_UNTIL] ?: 0L,
            currentSessionId = p[Keys.SESSION_ID] ?: -1L,
            pendingReunionCelebration = p[Keys.PENDING_CELEBRATION] ?: false,
            lastSeenElapsedRealtime = p[Keys.LAST_SEEN_ELAPSED_REALTIME] ?: 0L,
            pendingApartSince = p[Keys.PENDING_APART_SINCE] ?: 0L,
            remindersFiredForSession = p[Keys.LIST_REMINDERS_FIRED] ?: emptySet(),
            reunionCount = p[Keys.REUNION_COUNT] ?: 0,
            reunionCountBackfilled = p[Keys.REUNION_COUNT_BACKFILLED] ?: false
        )
    }

    suspend fun current(): ProximityPersistedState = state.first()

    /**
     * Applies [transform] atomically inside a single edit{} transaction, reading the "current" value it
     * receives from the in-transaction snapshot [p] itself - never from a value read before the
     * transaction started. That matters here because several independent callers (the ticker,
     * handleBecameTogether/handleBecameApart, SnoozeActivity.applySnooze, HomeScreen clearing
     * pendingReunionCelebration) all call this concurrently; DataStore's edit{} only guarantees the
     * write itself is atomic - if we read "current" via a separate pre-fetch (as this used to do) and
     * then wrote a value computed from that stale snapshot, a concurrent writer's edit{} landing in
     * between could get silently clobbered, losing fields like currentSessionId or
     * pendingReunionCelebration.
     */
    suspend fun update(transform: (ProximityPersistedState) -> ProximityPersistedState) {
        context.proximityDs.edit { p ->
            val currentState = ProximityPersistedState(
                isTogether = p[Keys.IS_TOGETHER] ?: false,
                lastSeenAt = p[Keys.LAST_SEEN_AT] ?: 0L,
                continuousTogetherSince = p[Keys.CONTINUOUS_SINCE] ?: 0L,
                lastApartSince = p[Keys.LAST_APART_SINCE] ?: 0L,
                reminderFiredForSession = p[Keys.REMINDER_FIRED] ?: false,
                snoozeUntil = p[Keys.SNOOZE_UNTIL] ?: 0L,
                currentSessionId = p[Keys.SESSION_ID] ?: -1L,
                pendingReunionCelebration = p[Keys.PENDING_CELEBRATION] ?: false,
                lastSeenElapsedRealtime = p[Keys.LAST_SEEN_ELAPSED_REALTIME] ?: 0L,
                pendingApartSince = p[Keys.PENDING_APART_SINCE] ?: 0L,
                remindersFiredForSession = p[Keys.LIST_REMINDERS_FIRED] ?: emptySet(),
                reunionCount = p[Keys.REUNION_COUNT] ?: 0,
                reunionCountBackfilled = p[Keys.REUNION_COUNT_BACKFILLED] ?: false
            )
            val updated = transform(currentState)
            p[Keys.IS_TOGETHER] = updated.isTogether
            p[Keys.LAST_SEEN_AT] = updated.lastSeenAt
            p[Keys.CONTINUOUS_SINCE] = updated.continuousTogetherSince
            p[Keys.LAST_APART_SINCE] = updated.lastApartSince
            p[Keys.REMINDER_FIRED] = updated.reminderFiredForSession
            p[Keys.SNOOZE_UNTIL] = updated.snoozeUntil
            p[Keys.SESSION_ID] = updated.currentSessionId
            p[Keys.PENDING_CELEBRATION] = updated.pendingReunionCelebration
            p[Keys.LAST_SEEN_ELAPSED_REALTIME] = updated.lastSeenElapsedRealtime
            p[Keys.PENDING_APART_SINCE] = updated.pendingApartSince
            p[Keys.LIST_REMINDERS_FIRED] = updated.remindersFiredForSession
            p[Keys.REUNION_COUNT] = updated.reunionCount
            p[Keys.REUNION_COUNT_BACKFILLED] = updated.reunionCountBackfilled
        }
    }
}

/** Persists the first-unlocked-at timestamp for each badge (keyed by Badge.id), so the Badges screen
 * can show "locked vs unlocked with unlock date" instead of just "Unlocked" with no date - BadgeCatalog
 * itself is a pure function of the current stats snapshot and has no notion of "when" a badge first
 * crossed its threshold, so that has to be recorded the first time it's observed. One dynamic
 * longPreferencesKey per badge id, rather than a fixed key set, since BadgeCatalog.badgesFor(stats) can grow. */
class BadgeUnlocksStore(private val context: Context) {
    val unlockedAtByBadgeId: Flow<Map<String, Long>> = context.badgeUnlocksDs.data.map { prefs ->
        prefs.asMap().entries.associate { (key, value) -> key.name to (value as Long) }
    }

    suspend fun current(): Map<String, Long> = unlockedAtByBadgeId.first()

    /** Records [unlockedAt] for [badgeId] only if nothing is recorded for it yet - so re-observing an
     * already-unlocked badge on a later stats recomputation never overwrites its true first-unlock time. */
    suspend fun recordUnlockIfNeeded(badgeId: String, unlockedAt: Long) {
        val key = longPreferencesKey(badgeId)
        context.badgeUnlocksDs.edit { prefs ->
            if (prefs[key] == null) prefs[key] = unlockedAt
        }
    }

    /** Full raw restore used by Feature 4's backup restore flow - replaces the entire unlocked-badges
     * map verbatim from a backup manifest. */
    suspend fun restoreRaw(unlockedAtByBadgeId: Map<String, Long>) {
        context.badgeUnlocksDs.edit { prefs ->
            prefs.clear()
            unlockedAtByBadgeId.forEach { (badgeId, unlockedAt) -> prefs[longPreferencesKey(badgeId)] = unlockedAt }
        }
    }
}
