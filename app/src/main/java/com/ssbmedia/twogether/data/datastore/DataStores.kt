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
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    val pairedAt: Long = 0L
) {
    val isPaired: Boolean get() = !pairSecretHash.isNullOrBlank()
}

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
            pairedAt = p[Keys.PAIRED_AT] ?: 0L
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
            p[Keys.SECRET_HASH] = secretHash
            p[Keys.PLAIN_CODE] = plainCode
            p[Keys.PARTNER_NAME] = partnerName.trim().ifBlank { "Your Person" }
            p[Keys.PARTNER_EMOJI] = partnerEmoji.ifBlank { "💕" }
            p[Keys.PAIRED_AT] = System.currentTimeMillis()
            p.remove(Keys.LAST_SECRET_HASH)
            p.remove(Keys.LAST_PLAIN_CODE)
            p.remove(Keys.LAST_PARTNER_NAME)
            p.remove(Keys.LAST_PARTNER_EMOJI)
            p.remove(Keys.LAST_UNPAIRED_AT)
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
            }
            p.remove(Keys.SECRET_HASH)
            p.remove(Keys.PLAIN_CODE)
            p.remove(Keys.PARTNER_NAME)
            p.remove(Keys.PARTNER_EMOJI)
            p.remove(Keys.PAIRED_AT)
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
        }
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
    val pendingRestoreAttempts: Int = 0
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
    }

    val settings: Flow<AppSettings> = context.settingsDs.data.map { p ->
        AppSettings(
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
            pendingUpdateApkPath = p[Keys.PENDING_UPDATE_APK_PATH]
        )
    }

    /** Returns this install's persistent random device identity (Feature D), generating it once if
     * missing - same read-then-write-inside-one-edit{} pattern as getOrCreateTieBreakByte() below, for
     * the same reason (two concurrent callers must not each generate and persist a different id). */
    suspend fun getOrCreateLocalDeviceId(): String {
        var result = ""
        context.settingsDs.edit { p ->
            val existing = p[Keys.LOCAL_DEVICE_ID]
            result = if (existing != null) existing else UUID.randomUUID().toString().also { p[Keys.LOCAL_DEVICE_ID] = it }
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
        }
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
     * not the couple's data, and get set again right after this call completes anyway). */
    suspend fun restoreRaw(
        defaultSnoozeMinutes: Int, notificationsEnabled: Boolean, pinHash: String?, pinEnabled: Boolean,
        lastSyncAt: Long, deviceTieBreakByte: Int?, localDeviceId: String? = null
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
    val pendingApartSince: Long = 0L
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
            pendingApartSince = p[Keys.PENDING_APART_SINCE] ?: 0L
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
                pendingApartSince = p[Keys.PENDING_APART_SINCE] ?: 0L
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
