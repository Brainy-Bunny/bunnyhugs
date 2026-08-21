package com.ssbmedia.twogether.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbmedia.twogether.BuildConfig
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.ble.ProximityStateMachine
import com.ssbmedia.twogether.stats.StatsCalculator
import com.ssbmedia.twogether.data.backup.BackupManager
import com.ssbmedia.twogether.data.datastore.AppSettings
import com.ssbmedia.twogether.data.datastore.ThemeMode
import com.ssbmedia.twogether.data.update.UpdateChecker
import com.ssbmedia.twogether.events.AppEvents
import com.ssbmedia.twogether.lock.AppLockManager
import com.ssbmedia.twogether.lock.BiometricGate
import com.ssbmedia.twogether.lock.PinUtil
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import com.ssbmedia.twogether.ui.onboarding.emojiOptions
import com.ssbmedia.twogether.ui.update.UpdateInstallActivity
import com.ssbmedia.twogether.util.DateFormats
import com.ssbmedia.twogether.util.RelativeTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId

private const val SETTINGS_TAG = "SettingsViewModel"

class SettingsViewModel : ViewModel() {
    val settings = ServiceLocator.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setSnoozeMinutes(min: Int) {
        viewModelScope.launch { ServiceLocator.settingsStore.setDefaultSnoozeMinutes(min) }
    }

    /** Item 24 (UX-FIX-PLAN.md): the "Photo reminder interval" row's Change dialog calls this - mirrors
     * setSnoozeMinutes' own pattern exactly. */
    fun setPhotoReminderMinutes(minutes: Int) {
        viewModelScope.launch { ServiceLocator.settingsStore.setPhotoReminderMinutes(minutes) }
    }

    /** Reunion-count non-retroactivity feature: the "Together-timer grace window" row's Change dialog
     * calls this - mirrors setPhotoReminderMinutes' own pattern exactly. See
     * AppSettings.sessionGraceMinutes' own doc: purely a live setting, no retroactive-recomputation
     * concern. */
    fun setSessionGraceMinutes(minutes: Int) {
        viewModelScope.launch {
            ServiceLocator.settingsStore.setSessionGraceMinutes(minutes)
            // MAJOR fix (ultimate-app-review round 1, B4): every OTHER local settings/data write in this
            // app nudges an immediate sync while together (see CalendarScreen.addManualSession/
            // deleteManualSession/updateManualSession/DayNoteSection, CameraScreen, CapsulesScreen,
            // OurListsScreen, HomeScreen, MilestonesScreen, GalleryImportFlow - all gated on the exact same
            // `ServiceLocator.proximityStateStore.current().isTogether` check) so the change reaches the
            // partner's phone right away instead of waiting for the next natural reconnect or the 15-minute
            // periodic catch-all. This setter (and setReunionThresholdMinutes below) was the one write path
            // in the app that didn't - live-measured, a reunion-threshold change took 486s to reach the
            // partner phone without this nudge.
            if (ServiceLocator.proximityStateStore.current().isTogether) {
                AppEvents.requestManualSync()
            }
        }
    }

    /** Reunion-count non-retroactivity feature: the "Reunion threshold" row's Change dialog calls this -
     * mirrors setPhotoReminderMinutes' own pattern exactly. See AppSettings.reunionThresholdMinutes' own
     * doc for the full non-retroactivity guarantee this deliberately does NOT (and must never) touch:
     * this setter only ever changes the live value future transitions get evaluated against - it never
     * rescans or rewrites ProximityPersistedState.reunionCount. */
    fun setReunionThresholdMinutes(minutes: Int) {
        viewModelScope.launch {
            ServiceLocator.settingsStore.setReunionThresholdMinutes(minutes)
            // MAJOR fix (ultimate-app-review round 1, B4): see setSessionGraceMinutes' matching fix just
            // above for the full reasoning - same missing nudge, same fix.
            if (ServiceLocator.proximityStateStore.current().isTogether) {
                AppEvents.requestManualSync()
            }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { ServiceLocator.settingsStore.setNotificationsEnabled(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { ServiceLocator.settingsStore.setThemeMode(mode) }
    }

    fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        viewModelScope.launch { ServiceLocator.settingsStore.setAutoUpdateCheckEnabled(enabled) }
    }

    /** Item 1 (deferred UX fix, 4-model advisory audit): lets the "Paired with" row's edit dialog persist
     * a changed partner name/emoji AFTER pairing - see PairingStore.updatePartnerInfo's own doc for why
     * this is safe to write straight through with no other side effects (purely local display data). */
    fun updatePartnerInfo(name: String, emoji: String) {
        viewModelScope.launch { ServiceLocator.pairingStore.updatePartnerInfo(name, emoji) }
    }

    /** Item 5 (deferred UX fix, 4-model advisory audit): persists the opt-in/opt-out for the optional
     * biometric-unlock alternative to the PIN screen - see AppSettings.biometricUnlockEnabled's own doc. */
    fun setBiometricUnlockEnabled(enabled: Boolean) {
        viewModelScope.launch { ServiceLocator.settingsStore.setBiometricUnlockEnabled(enabled) }
    }

    /** Launched on the app-scoped coroutine (same reasoning as backupNow() below - a mid-check screen
     * navigation must not cancel a download that's already in flight) rather than viewModelScope.
     * Bypasses both the auto-check toggle and the throttle window: an explicit tap on "Check for
     * updates now" is its own consent, independent of the "automatically" setting. */
    fun checkForUpdatesNow(context: Context, onResult: (UpdateChecker.CheckOutcome) -> Unit) {
        ServiceLocator.applicationScope.launch {
            val outcome = UpdateChecker.checkAndNotify(context.applicationContext)
            onResult(outcome)
        }
    }

    /** Clears a stale pending-update indicator whose cached APK has gone missing - see the Updates
     * section's own comment for why this exists. */
    fun clearPendingUpdate() {
        viewModelScope.launch { ServiceLocator.settingsStore.clearPendingUpdate() }
    }

    fun setPin(pin: String, onDone: (success: Boolean) -> Unit) {
        // MINOR fix: launched on the app-scoped coroutine, NOT viewModelScope - same reasoning as
        // unpair() below. PinUtil.hash() now does real, visible work (600k-round PBKDF2, up to ~1-3s on
        // a mid-range phone); navigating out of Settings within that window used to cancel viewModelScope
        // and silently drop the PIN save entirely (the dialog would close as if it succeeded, but
        // pinHash/pinEnabled were never actually written). At the old 20k-round cost (~tens of ms) this
        // was never realistically reachable.
        ServiceLocator.applicationScope.launch {
            // BUG fix: an independent review round pointed out this had no error handling at all -
            // applicationScope has no CoroutineExceptionHandler, so a genuine DataStore IOException
            // (disk full, I/O failure - documented as a real possibility of DataStore's edit{}, not a
            // "can't happen" case) would crash the whole app uncaught. Separately, even if it hadn't
            // crashed, onDone() would never have fired, leaving SetPinDialog's isSaving spinner stuck
            // true forever (fields+buttons disabled, no way to dismiss). onDone now always fires exactly
            // once, carrying whether the save actually succeeded so the dialog can show an error and let
            // the user retry instead of silently closing as if the PIN had been set.
            try {
                // AppLockManager.isLocked defaults to true and is otherwise only cleared by successfully
                // entering the PIN on PinLockScreen. Without this, turning PIN lock on for the first time
                // (isLocked has never been flipped false yet) immediately re-shows the lock screen right
                // after the user just typed the same PIN into the "set PIN" dialog - forcing them to
                // enter it twice in a row for no reason. The user is already authenticated in this
                // session (they're sitting in Settings), so unlock immediately.
                //
                // BUG fix: unlock() now runs BEFORE the DataStore write below, not after - an independent
                // testing round live-reproduced a race where the settings Flow's pinEnabled=true emission
                // (collected separately by MainActivity) could land before this unlock() call did, so
                // MainActivity's `when` briefly evaluated pinEnabled=true && isLocked=true (still) ->
                // swapped to PinLockScreen -> then immediately back once unlock() landed - but
                // TwogetherNavHost is a fresh composable instance each time that branch is (re)entered, so
                // the app lost its back stack and bounced to Home, with BatteryOptimizationGate re-firing
                // as a visible side effect. Unlocking first means isLocked is already false by the time
                // pinEnabled's write is ever observed, so that brief "both true" window can't occur at all
                // regardless of dispatch timing - not just a narrower window, a structurally closed one.
                AppLockManager.unlock()
                ServiceLocator.settingsStore.setPin(PinUtil.hash(pin))
                // BUG fix: onDone used to be called synchronously right after launching this coroutine
                // (i.e. the dialog closed and the "App lock (PIN)" switch was shown immediately), NOT after
                // the write above actually completed. Since the hash alone can take 1-3s, the switch would
                // legitimately still read the OLD value for that whole window - not a stale-recomposition
                // bug, just zero feedback that the save was still in flight. Calling onDone here instead,
                // after the real write, means the dialog only closes once the switch is guaranteed correct.
                onDone(true)
            } catch (e: Exception) {
                Log.w(SETTINGS_TAG, "Failed to save PIN", e)
                onDone(false)
            }
        }
    }

    fun clearPin() {
        // MINOR fix: matches setPin()'s own applicationScope reasoning above for consistency - this
        // write is fast (no PBKDF2 involved), so the cancellation window was always narrow, but there's
        // no reason this one PIN-mutating action should be the odd one out still exposed to it.
        ServiceLocator.applicationScope.launch { ServiceLocator.settingsStore.clearPin() }
    }

    fun unpair(onDone: () -> Unit) {
        // Deliberately launched on the app-scoped coroutine, NOT viewModelScope: the pairingStore.unpair()
        // write below flips MainActivity's pairing state, which disposes SettingsScreen (and cancels its
        // viewModelScope) as soon as that recomposition lands - which can race ahead of this same
        // coroutine resuming to run its remaining lines (AppEvents.emitUnpaired() / onDone(), which is
        // what actually calls stopProximityService()). If viewModelScope had won that race, the proximity
        // foreground service (and its persistent notification) would be left running orphaned - BLE
        // itself would self-heal within ~5s via the ticker's own pairing check, but the notification would
        // linger until the process was killed. ServiceLocator.applicationScope outlives the screen, so
        // this always runs to completion regardless of how fast the navigation change disposes the screen.
        ServiceLocator.applicationScope.launch {
            // Unpairing while together used to leave any open TogetherSession row open forever (nothing
            // else would ever close it, since the service tears down BLE/GATT for this pairing right
            // after), which made Home's "together" status and all-time-hours stats grow unbounded
            // forever. Close it first, clamped the same way the service itself clamps a normal
            // apart-transition (never later than the last real sighting + the absence timeout).
            val openSession = ServiceLocator.sessionRepository.getOpenSession()
            if (openSession != null) {
                val persisted = ServiceLocator.proximityStateStore.current()
                val now = System.currentTimeMillis()
                // Bounded by the newest CONFIRMED sighting (maxOf(lastSeenAt, startedAt)) + the absence
                // timeout, never by a bare "now" - see StatsCalculator.effectiveOpenSessionEnd's doc for
                // why the old lastSeenAt<=0 fallback to "now" was an unbounded-inflation hole here too.
                val clampedEnd = StatsCalculator.effectiveOpenSessionEnd(
                    startedAt = openSession.startedAt,
                    now = now,
                    lastSeenAt = persisted.lastSeenAt
                )
                ServiceLocator.sessionRepository.endSession(openSession, clampedEnd.coerceAtLeast(openSession.startedAt))
            }
            ServiceLocator.proximityStateStore.update {
                it.copy(isTogether = false, continuousTogetherSince = 0L, currentSessionId = -1L, pendingReunionCelebration = false)
            }
            ServiceLocator.pairingStore.unpair()
            // SECURITY fix: see PairingSessionGeneration's own doc - without this, the PairingViewModel
            // instance retained since the original pairing flow could resurface with stale mid-flow
            // state (step/pendingCode) the next time MainActivity swaps back to PairingScreen, letting
            // "Skip for now" silently re-pair with the OLD secret instead of landing cleanly on LANDING.
            com.ssbmedia.twogether.ui.onboarding.PairingSessionGeneration.value++
            AppEvents.emitUnpaired()
            onDone()
        }
    }

    /** Launched on applicationScope for consistency with unpair() above, so a mid-backup screen
     * navigation can't truncate a backup either. (Restore itself - which DOES have the same
     * dispose-mid-flight landmine as unpair() - is handled by the shared RestoreBackupButton composable,
     * used here and from PairingScreen's onboarding landing; see its doc comment.) */
    fun backupNow(context: Context, onResult: (BackupManager.BackupResult) -> Unit) {
        ServiceLocator.applicationScope.launch {
            val result = BackupManager.createBackup(context.applicationContext)
            onResult(result)
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onUnpaired: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = SimpleViewModelFactory { SettingsViewModel() })
    val settings by vm.settings.collectAsState()
    var partnerName by remember { mutableStateOf("") }
    // Item 1 (deferred UX fix, 4-model advisory audit): tracked alongside partnerName now that the "Paired
    // with" row also displays and edits the emoji, not just the name - see EditPartnerInfoDialog below.
    var partnerEmoji by remember { mutableStateOf("💕") }
    var pairingCode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val info = ServiceLocator.pairingStore.current()
        partnerName = info.partnerName
        partnerEmoji = info.partnerEmoji
        pairingCode = info.pairPlainCode.orEmpty()
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    // Item 5 (deferred UX fix, 4-model advisory audit): live hardware/enrollment check, gating whether
    // Settings even offers the biometric-unlock toggle at all - see BiometricGate.shouldShowToggle's own
    // doc for why a device with no usable biometric enrollment never sees it (not even disabled).
    val biometricCanAuthenticateResult = remember {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    }

    var showPinDialog by remember { mutableStateOf(false) }
    // Gates BOTH "Change PIN" and "turn PIN off" behind re-entering the CURRENT PIN first - closes the
    // gap where anyone holding an already-unlocked phone could silently hijack or remove the app lock
    // with zero friction, without needing to know the existing PIN at all. See PinVerifyPurpose's doc.
    var pinVerifyPurpose by remember { mutableStateOf<PinVerifyPurpose?>(null) }
    var showUnpairConfirm by remember { mutableStateOf(false) }
    var showSnoozeDialog by remember { mutableStateOf(false) }
    var showPhotoReminderDialog by remember { mutableStateOf(false) }
    var showSessionGraceDialog by remember { mutableStateOf(false) }
    var showReunionThresholdDialog by remember { mutableStateOf(false) }
    // Item 1 (deferred UX fix, 4-model advisory audit): "Paired with" row's edit dialog toggle.
    var showEditPartnerDialog by remember { mutableStateOf(false) }

    var isBackingUp by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingForUpdate by remember { mutableStateOf(false) }
    var updateCheckMessage by remember { mutableStateOf<String?>(null) }
    // Manual-download fallback: only offered for the two outcomes where the automatic path genuinely
    // couldn't reach/finish talking to GitHub (DownloadFailed, CheckFailed) - not shown for
    // UpdateAvailable/UpToDate, where there's nothing to fall back to.
    var updateCheckOfferManualDownload by remember { mutableStateOf(false) }

    // "Sync now" - same mechanism OurListsScreen's own button uses (AppEvents.requestManualSync), just
    // surfaced here too since a full sync is convenient to trigger without having to go into Our Lists
    // first. See OurListsScreen's identical block for why each piece of state below exists.
    var lastSyncAt by remember { mutableStateOf(0L) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var isListeningRole by remember { mutableStateOf(false) }
    val syncCoroutineScope = rememberCoroutineScope()
    // Phase 1 item 2 of UX-FIX-PLAN.md: "Last synced Xm ago" used to be computed once at composition
    // time with no ticker, so it visibly froze the whole time this screen stayed open - matches
    // HomeScreen's own 30s ticker pattern.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    LaunchedEffect(Unit) {
        lastSyncAt = ServiceLocator.settingsStore.current().lastSyncAt
    }
    LaunchedEffect(Unit) {
        AppEvents.syncListening.collect {
            isListeningRole = true
            syncing = false
            syncMessage = "Listening for your partner's phone…"
        }
    }
    LaunchedEffect(Unit) {
        AppEvents.syncCompleted.collect { success ->
            syncing = false
            isListeningRole = false
            val dropped = AppEvents.lastSyncDroppedCount.value
            // MAJOR fix (ultimate-app-review round 1, Opus+Sonnet) - same reasoning as OurListsScreen's
            // matching fix: distinguish a pinned-partner mismatch from the generic "not together" failure,
            // since it's the one failure reason with an actual fix the user can take.
            syncMessage = when {
                !success && AppEvents.lastSyncFailedDueToPartnerMismatch.value ->
                    "Couldn't sync — this phone doesn't match your paired partner. Unpair, then create a new pairing code to reconnect."
                !success -> "Couldn't sync — make sure you're together"
                dropped > 0 -> "Synced, but $dropped item${if (dropped == 1) "" else "s"} skipped — check both phones' clocks"
                else -> "Synced! 💛"
            }
            if (success) lastSyncAt = ServiceLocator.settingsStore.current().lastSyncAt
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(title = "Appearance") {
                Text(
                    "Theme",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    "Follows your phone by default",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeModeChip(label = "System", selected = settings.themeMode == ThemeMode.SYSTEM, modifier = Modifier.weight(1f)) {
                        vm.setThemeMode(ThemeMode.SYSTEM)
                    }
                    ThemeModeChip(label = "Light", selected = settings.themeMode == ThemeMode.LIGHT, modifier = Modifier.weight(1f)) {
                        vm.setThemeMode(ThemeMode.LIGHT)
                    }
                    ThemeModeChip(label = "Dark", selected = settings.themeMode == ThemeMode.DARK, modifier = Modifier.weight(1f)) {
                        vm.setThemeMode(ThemeMode.DARK)
                    }
                }
            }

            SettingsSection(title = "Notifications") {
                // BUG fix: subtitle used to say "...and updates", but settings.notificationsEnabled is
                // only ever read in one place (ProximityForegroundService.checkPhotoReminder) - it has
                // never actually gated the app-update notification, milestone notifications, or anything
                // else. Fixed the copy to describe what this switch actually controls rather than change
                // its scope to match the old (aspirational, never-implemented) copy.
                SettingsRow(label = "Notifications enabled", subtitle = "${settings.photoReminderMinutes}-minute photo nudges") {
                    Switch(checked = settings.notificationsEnabled, onCheckedChange = { vm.setNotificationsEnabled(it) })
                }
                // Item 24 (UX-FIX-PLAN.md): was hardcoded at 15 minutes - now configurable, same UI
                // pattern as "Default snooze length" right below it.
                SettingsRow(label = "Photo reminder interval", subtitle = "${settings.photoReminderMinutes} minutes") {
                    TextButton(onClick = { showPhotoReminderDialog = true }) { Text("Change") }
                }
                SettingsRow(label = "Default snooze length", subtitle = "${settings.defaultSnoozeMinutes} minutes") {
                    TextButton(onClick = { showSnoozeDialog = true }) { Text("Change") }
                }
            }

            // Reunion-count non-retroactivity feature: both rows below were previously hardcoded
            // constants (ProximityForegroundService.SESSION_GRACE_MILLIS / StatsCalculator.
            // REUNION_GAP_MILLIS) - now user-configurable, same "Change" dialog pattern as the
            // Notifications section above.
            SettingsSection(title = "Together timer & Reunions") {
                SettingsRow(
                    label = "Together-timer grace window",
                    subtitle = "A brief BLE gap under ${settings.sessionGraceMinutes} min won't reset your together timer"
                ) {
                    TextButton(onClick = { showSessionGraceDialog = true }) { Text("Change") }
                }
                SettingsRow(
                    label = "Reunion threshold",
                    subtitle = "Apart ${settings.reunionThresholdMinutes}+ min counts as a real reunion. Changing this only affects future reunions, never past ones."
                ) {
                    TextButton(onClick = { showReunionThresholdDialog = true }) { Text("Change") }
                }
            }

            SettingsSection(title = "Privacy") {
                SettingsRow(label = "App lock (PIN)", subtitle = if (settings.pinEnabled) "On — required to open the app" else "Off") {
                    Switch(
                        checked = settings.pinEnabled,
                        onCheckedChange = { enabled ->
                            // Turning ON for the first time (or after clearPin() fully wiped the old
                            // hash - see its own doc) has no existing PIN to verify against, so it goes
                            // straight to setting a fresh one. Turning OFF is exactly as sensitive as
                            // changing it - it removes protection entirely - so it goes through the same
                            // current-PIN check below rather than being one frictionless tap.
                            if (enabled) showPinDialog = true else pinVerifyPurpose = PinVerifyPurpose.DISABLE
                        }
                    )
                }
                if (settings.pinEnabled) {
                    SettingsRow(label = "Change PIN", subtitle = "Update your app lock code") {
                        TextButton(onClick = { pinVerifyPurpose = PinVerifyPurpose.CHANGE }) { Text("Change") }
                    }
                }
                // Item 5 (deferred UX fix, 4-model advisory audit): only ever shown when PIN lock is
                // already on AND this device genuinely has usable biometric enrollment - see
                // BiometricGate.shouldShowToggle's own doc. Default OFF (AppSettings.biometricUnlockEnabled),
                // and PIN entry on the lock screen is never affected by this either way - see
                // PinLockScreen's own doc for the always-available-fallback guarantee.
                if (BiometricGate.shouldShowToggle(settings.pinEnabled, biometricCanAuthenticateResult)) {
                    SettingsRow(
                        label = "Unlock with fingerprint/face",
                        subtitle = if (settings.biometricUnlockEnabled) {
                            "On — offered as a faster alternative to your PIN"
                        } else {
                            "Off — PIN only"
                        }
                    ) {
                        Switch(
                            checked = settings.biometricUnlockEnabled,
                            onCheckedChange = { vm.setBiometricUnlockEnabled(it) }
                        )
                    }
                }
            }

            SettingsSection(title = "Pairing") {
                // Item 1 (deferred UX fix, 4-model advisory audit): partner name/emoji were set once during
                // onboarding and read-only forever after - the edit icon opens EditPartnerInfoDialog below,
                // which writes through PairingStore.updatePartnerInfo (purely local display data - see its
                // own doc).
                SettingsRow(
                    label = "Paired with",
                    subtitle = if (partnerName.isBlank()) "—" else "$partnerEmoji $partnerName"
                ) {
                    IconButton(onClick = { showEditPartnerDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit partner name and emoji")
                    }
                }
                if (pairingCode.isNotBlank()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("Your pairing code", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Share this with your partner's phone if they ever need to rejoin (lost data, factory reset, reinstall).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Card(
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(
                                text = pairingCode.chunked(3).joinToString("  "),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                            )
                        }
                        // Item 2 (deferred UX fix, 4-model advisory audit): copy/share for the pairing code
                        // redisplayed here - same pattern as PairingScreen's own ShowCodeContent (the
                        // original onboarding display), so a partner who lost their copy (reinstall,
                        // factory reset) doesn't have to read this aloud or retype it by hand either.
                        var justCopied by remember { mutableStateOf(false) }
                        LaunchedEffect(justCopied) {
                            if (justCopied) {
                                delay(1500)
                                justCopied = false
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(pairingCode))
                                justCopied = true
                            }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = if (justCopied) "Copied" else "Copy pairing code"
                                )
                            }
                            IconButton(onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Here's my Twogether pairing code: $pairingCode")
                                }
                                context.startActivity(Intent.createChooser(sendIntent, null))
                            }) {
                                Icon(Icons.Filled.Share, contentDescription = "Share pairing code")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                SettingsRow(
                    label = "Sync now",
                    subtitle = syncMessage
                        ?: if (lastSyncAt > 0) "Last synced ${RelativeTime.relativeAgo((now - lastSyncAt).coerceAtLeast(0L))}" else "Not synced yet"
                ) {
                    TextButton(
                        onClick = {
                            syncing = true
                            isListeningRole = false
                            syncMessage = null
                            AppEvents.requestManualSync()
                            // Same belt-and-suspenders timeout as OurListsScreen's button - see its own
                            // doc for why this can't just wait on syncCompleted forever.
                            syncCoroutineScope.launch {
                                kotlinx.coroutines.delay(8_000)
                                if (syncing && !isListeningRole) {
                                    syncing = false
                                    syncMessage = "Couldn't sync — make sure you're together"
                                }
                            }
                        },
                        enabled = !syncing
                    ) { Text(if (syncing) "Syncing…" else "Sync now") }
                }
                Spacer(modifier = Modifier.height(4.dp))
                SettingsRow(label = "Unpair this phone", subtitle = "Disconnects from your partner locally") {
                    TextButton(onClick = { showUnpairConfirm = true }) { Text("Unpair") }
                }
            }

            SettingsSection(title = "Backup & Restore") {
                val lastBackupSubtitle = when {
                    isBackingUp -> "Backing up…"
                    settings.lastBackupAt <= 0L -> "Never backed up yet — a weekly backup runs automatically"
                    else -> {
                        val whenText = DateFormats.formatDateTime(
                            Instant.ofEpochMilli(settings.lastBackupAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        )
                        if (settings.lastBackupOk) "Last backup: $whenText" else "Last backup FAILED: $whenText"
                    }
                }
                SettingsRow(label = "Back up now", subtitle = lastBackupSubtitle) {
                    if (isBackingUp) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        TextButton(onClick = {
                            isBackingUp = true
                            vm.backupNow(context) { result ->
                                isBackingUp = false
                                backupMessage = result.message
                            }
                        }) { Text("Back up now") }
                    }
                }
                SettingsRow(label = "Restore from backup", subtitle = "Loads sessions, photos, and settings from a saved backup file - you'll need to re-pair afterward") {
                    com.ssbmedia.twogether.ui.backup.RestoreBackupButton { onClick ->
                        TextButton(onClick = onClick) { Text("Restore") }
                    }
                }
            }

            SettingsSection(title = "Updates") {
                // BUG fix: a downloaded update used to be discoverable ONLY via the dismissible "Update
                // available" OS notification - swipe it away, or have notifications off entirely
                // (app-level or OS-permission-level), and there was no way back to it short of manually
                // tapping "Check for updates now" again. This persists regardless of notification state,
                // reading the same durable flag UpdateChecker sets the moment it finishes downloading.
                if (UpdateChecker.isPendingUpdateActionable(settings.pendingUpdateVersionCode, BuildConfig.VERSION_CODE)) {
                    val pendingName = settings.pendingUpdateVersionName ?: "update"
                    val pendingPath = settings.pendingUpdateApkPath
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Update $pendingName ready", fontWeight = FontWeight.SemiBold)
                                Text("Tap Install to update now", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                val apkFile = pendingPath?.let { File(it) }
                                if (apkFile != null && apkFile.isFile) {
                                    context.startActivity(
                                        Intent(context, UpdateInstallActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            putExtra(UpdateInstallActivity.EXTRA_APK_PATH, apkFile.absolutePath)
                                        }
                                    )
                                } else {
                                    // The cached APK is gone (cleared storage, cache eviction, etc.) -
                                    // clear the stale flag rather than leaving a permanently-broken
                                    // "Install" button; the user can re-check to re-download it.
                                    vm.clearPendingUpdate()
                                    updateCheckMessage = "That update file is no longer available - tap \"Check now\" to re-download it."
                                }
                            }) { Text("Install") }
                        }
                    }
                }
                SettingsRow(
                    label = "Check for updates automatically",
                    subtitle = "Uses the internet just for this — everything else in Twogether stays fully offline"
                ) {
                    Switch(
                        checked = settings.autoUpdateCheckEnabled,
                        onCheckedChange = { vm.setAutoUpdateCheckEnabled(it) }
                    )
                }
                SettingsRow(label = "Check for updates now", subtitle = "Current version: ${BuildConfig.VERSION_NAME}") {
                    if (isCheckingForUpdate) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        TextButton(onClick = {
                            isCheckingForUpdate = true
                            vm.checkForUpdatesNow(context) { outcome ->
                                isCheckingForUpdate = false
                                updateCheckOfferManualDownload = outcome is UpdateChecker.CheckOutcome.DownloadFailed ||
                                    outcome is UpdateChecker.CheckOutcome.CheckFailed
                                updateCheckMessage = when (outcome) {
                                    is UpdateChecker.CheckOutcome.UpdateAvailable ->
                                        "Update ${outcome.info.versionName} downloaded — check your notifications to install it."
                                    UpdateChecker.CheckOutcome.UpToDate -> "You're up to date."
                                    UpdateChecker.CheckOutcome.DownloadFailed ->
                                        "Found a newer version, but the download failed. Check your connection and try again, or download it manually below."
                                    UpdateChecker.CheckOutcome.CheckFailed ->
                                        "Couldn't check for updates. Check your connection and try again, or download it manually below."
                                }
                            }
                        }) { Text("Check now") }
                    }
                }
            }

            SettingsSection(title = "About") {
                SettingsRow(label = "Version", subtitle = BuildConfig.VERSION_NAME) {}
                SettingsRow(label = "Twogether", subtitle = "Made for the two of you 💕 — fully offline, no accounts, no servers.") {}
                // Quiet, permanent book-dedication-style personal credit - not tied to any account or
                // couple, purely a personal note from the app's maker. Never gates or restricts anything.
                Text(
                    text = "Inspired by My Cutie Kachvii 💚",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }

    if (showSnoozeDialog) {
        SnoozeDefaultDialog(
            current = settings.defaultSnoozeMinutes,
            onDismiss = { showSnoozeDialog = false },
            onSave = { vm.setSnoozeMinutes(it); showSnoozeDialog = false }
        )
    }

    if (showPhotoReminderDialog) {
        PhotoReminderDialog(
            current = settings.photoReminderMinutes,
            onDismiss = { showPhotoReminderDialog = false },
            onSave = { vm.setPhotoReminderMinutes(it); showPhotoReminderDialog = false }
        )
    }

    if (showSessionGraceDialog) {
        SessionGraceDialog(
            current = settings.sessionGraceMinutes,
            onDismiss = { showSessionGraceDialog = false },
            onSave = { vm.setSessionGraceMinutes(it); showSessionGraceDialog = false }
        )
    }

    if (showReunionThresholdDialog) {
        ReunionThresholdDialog(
            current = settings.reunionThresholdMinutes,
            onDismiss = { showReunionThresholdDialog = false },
            onSave = { vm.setReunionThresholdMinutes(it); showReunionThresholdDialog = false }
        )
    }

    if (showPinDialog) {
        SetPinDialog(onDismiss = { showPinDialog = false }, onSave = { pin, onResult -> vm.setPin(pin, onResult) })
    }

    // Item 1 (deferred UX fix, 4-model advisory audit): saves optimistically into partnerName/partnerEmoji
    // right here (rather than waiting on a Flow re-collection) so the "Paired with" row reflects the edit
    // the instant the dialog closes.
    if (showEditPartnerDialog) {
        EditPartnerInfoDialog(
            currentName = partnerName,
            currentEmoji = partnerEmoji,
            onDismiss = { showEditPartnerDialog = false },
            onSave = { name, emoji ->
                partnerName = name
                partnerEmoji = emoji
                vm.updatePartnerInfo(name, emoji)
                showEditPartnerDialog = false
            }
        )
    }

    pinVerifyPurpose?.let { purpose ->
        VerifyCurrentPinDialog(
            currentPinHash = settings.pinHash,
            onDismiss = { pinVerifyPurpose = null },
            onVerified = {
                pinVerifyPurpose = null
                when (purpose) {
                    PinVerifyPurpose.CHANGE -> showPinDialog = true
                    PinVerifyPurpose.DISABLE -> vm.clearPin()
                }
            }
        )
    }

    if (showUnpairConfirm) {
        val displayName = partnerName.trim().ifBlank { "your partner's" }
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            title = { Text("Unpair this phone?") },
            text = {
                Text(
                    "This won't delete your history, photos, or stats — they stay on this phone. " +
                        "You'll just need to reconnect with $displayName's phone to resume tracking time together."
                )
            },
            confirmButton = {
                TextButton(onClick = { showUnpairConfirm = false; vm.unpair(onUnpaired) }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { showUnpairConfirm = false }) { Text("Cancel") } }
        )
    }

    if (backupMessage != null) {
        AlertDialog(
            onDismissRequest = { backupMessage = null },
            title = { Text("Backup") },
            text = { Text(backupMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { backupMessage = null }) { Text("OK") } }
        )
    }

    if (updateCheckMessage != null) {
        AlertDialog(
            onDismissRequest = { updateCheckMessage = null },
            title = { Text("Check for updates") },
            text = { Text(updateCheckMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { updateCheckMessage = null }) { Text("OK") } },
            dismissButton = if (updateCheckOfferManualDownload) {
                {
                    TextButton(onClick = {
                        updateCheckMessage = null
                        // Manual fallback for the exact case the automatic path can't recover from on
                        // its own: this app's own HttpURLConnection call to the GitHub API/CDN failed,
                        // but a normal browser reaching github.com is a genuinely different network path
                        // (different DNS/TLS stack, different host in some blocked/throttled-app
                        // scenarios) and may well succeed where the in-app request didn't.
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_PAGE_URL)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                            )
                        } catch (e: Exception) {
                            // No browser available to handle ACTION_VIEW - vanishingly rare on a real
                            // Android device, but this button must never crash Settings if it happens.
                        }
                    }) { Text("Download manually") }
                }
            } else null
        )
    }

}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    // BUG fix (user-reported "faded text" + ultimate-app-review Round 2 visual inspection, live-
    // confirmed): CardDefaults.cardColors(containerColor = ...) with no explicit contentColor defaults
    // content color to contentColorFor(containerColor), which for surfaceVariant resolves to
    // onSurfaceVariant - the deliberately MUTED text role (see onSurfaceVariant's own doc in Theme.kt).
    // Every Text() below with no explicit color of its own (the section title here, and every
    // SettingsRow label) silently inherited that muted color too, reading as faded/low-hierarchy
    // primary text - not a contrast-tuning issue, a wrong ambient role. subtitle text (SettingsRow's
    // second line, already explicitly onSurfaceVariant) was correctly muted and is unaffected.
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingsRow(label: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun ThemeModeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier
    )
}

/** Item 1 (deferred UX fix, 4-model advisory audit): the post-pairing "edit partner name/emoji" dialog -
 * reuses [emojiOptions] (onboarding's own picker list, made `internal` for exactly this reuse - see its
 * own doc) and mirrors PairingScreen's PartnerInfoContent's FlowRow-picker shape almost line-for-line,
 * rather than inventing a second, potentially-drifting picker UI for structurally the same choice. */
@Composable
private fun EditPartnerInfoDialog(
    currentName: String,
    currentEmoji: String,
    onDismiss: () -> Unit,
    onSave: (name: String, emoji: String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    var emoji by remember { mutableStateOf(currentEmoji) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit partner info") },
        text = {
            Column {
                Text(
                    "This is just for your phone — it's never sent to your partner's.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Their name") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    emojiOptions.forEach { option ->
                        val selected = option == emoji
                        Card(
                            onClick = { emoji = option },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(option, fontSize = 22.sp, modifier = Modifier.padding(10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, emoji) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SnoozeDefaultDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default snooze length") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() }.take(4) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onSave((text.toIntOrNull() ?: current).coerceIn(1, 720)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Item 24 (UX-FIX-PLAN.md): "Photo reminder interval" - the configurable replacement for the old
 * hardcoded 15-minute photo nudge. Same input pattern as [SnoozeDefaultDialog] right above it. */
@Composable
private fun PhotoReminderDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Photo reminder interval") },
        text = {
            Column {
                Text(
                    "How long you've been together before Twogether nudges you to snap a photo.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(4) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave((text.toIntOrNull() ?: current).coerceIn(1, 720)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Reunion-count non-retroactivity feature: "Together-timer grace window" - the configurable replacement
 * for the old hardcoded ProximityForegroundService.SESSION_GRACE_MILLIS (10 min). Same input pattern as
 * [PhotoReminderDialog]/[SnoozeDefaultDialog] above it. Purely a live setting (see
 * AppSettings.sessionGraceMinutes' own doc) - no non-retroactivity copy needed here, unlike
 * [ReunionThresholdDialog] below. */
@Composable
private fun SessionGraceDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Together-timer grace window") },
        text = {
            Column {
                Text(
                    "If your phones briefly lose Bluetooth contact for less than this, your \"together\" timer keeps running instead of resetting.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(4) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave((text.toIntOrNull() ?: current).coerceIn(1, 720)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Reunion-count non-retroactivity feature: "Reunion threshold" - the configurable replacement for the
 * old hardcoded StatsCalculator.REUNION_GAP_MILLIS (60 min). Same input pattern as [PhotoReminderDialog]/
 * [SnoozeDefaultDialog] above it, but with an explicit non-retroactivity disclosure in the body copy -
 * this is the one setting in the app where a user could reasonably (and wrongly) expect their whole
 * history to be reinterpreted under the new value, so the UI says outright that it won't be. See
 * AppSettings.reunionThresholdMinutes' own doc for the full guarantee this copy is describing. */
@Composable
private fun ReunionThresholdDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reunion threshold") },
        text = {
            Column {
                Text(
                    "How long you need to be apart before getting back together counts as a real reunion (and adds to your lifetime Reunions count).",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "This only changes future reunions — it never rewrites reunions you've already had, no matter how you change it.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(4) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave((text.toIntOrNull() ?: current).coerceIn(1, 720)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Which action a successful [VerifyCurrentPinDialog] should unlock - see its call site's doc for why
 * both of these (not just changing) need the current PIN re-entered first. */
private enum class PinVerifyPurpose { CHANGE, DISABLE }

/** Requires the CURRENT PIN before allowing [PinVerifyPurpose.CHANGE]/[DISABLE] - closes the gap where
 * anyone holding an already-unlocked phone could silently take over or remove the app lock without ever
 * needing to know the existing PIN. [currentPinHash] is null only if PIN lock was somehow already off
 * when this got triggered (shouldn't normally happen, both call sites are gated on settings.pinEnabled),
 * treated as "can't verify, refuse" rather than silently succeeding. */
@Composable
private fun VerifyCurrentPinDialog(currentPinHash: String?, onDismiss: () -> Unit, onVerified: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }

    // MINOR fix: shares the SAME lockout state PinLockScreen's own unlock flow uses
    // (AppLockManager.recordFailedPinAttempt/isPinLockedOut) - without this, this dialog was the one PIN
    // gate in the app with unlimited guesses, even though it protects exactly the "someone else picked up
    // an already-unlocked phone" scenario this whole feature exists for. A single shared counter across
    // both screens is also the more correct threat model: it's still a brute-force attempt against the
    // same PIN regardless of which screen it's typed into.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val lockedOut = AppLockManager.isPinLockedOut(now)
    LaunchedEffect(lockedOut) {
        while (AppLockManager.isPinLockedOut(System.currentTimeMillis())) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter your current PIN") },
        text = {
            Column {
                Text("Confirm it's you before changing this.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6); error = null },
                    label = { Text("Current PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !lockedOut
                )
                val displayError = if (lockedOut) {
                    "Too many wrong attempts - try again in ${((AppLockManager.pinLockedOutUntilMillis - now) / 1000L).coerceAtLeast(0L) + 1}s"
                } else error
                displayError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // MINOR fix: guards against a double-tap spawning a second concurrent 600k-round
                    // PBKDF2 job (and, on a wrong PIN, recording two failed attempts for one user
                    // intent) - at the old 20k-round cost this was fast enough not to matter, but a
                    // verify can now visibly take over a second on a mid-range phone.
                    if (isVerifying) return@TextButton
                    isVerifying = true
                    // BLOCKER fix: PinUtil.matches now suspends and internally moves the 600k-round
                    // PBKDF2 work off Main - launching here (rather than calling it directly) is what
                    // actually keeps this click handler from blocking the UI thread.
                    scope.launch {
                        // MINOR fix: try/finally - without it, an exception from PinUtil.matches (none
                        // currently throws, but this is a coroutine calling into crypto APIs) would leave
                        // isVerifying stuck true forever, permanently disabling this button.
                        try {
                            if (PinUtil.matches(pin, currentPinHash)) {
                                // MINOR fix: resets the shared failed-attempt counter on success, same as
                                // AppLockManager.unlock() does - otherwise wrong guesses typed into THIS
                                // dialog would linger and could trigger an immediate lockout on the next
                                // wrong PIN typed anywhere, including later on the main lock screen.
                                AppLockManager.resetFailedPinAttempts()
                                onVerified()
                            } else {
                                AppLockManager.recordFailedPinAttempt()
                                error = "That's not the right PIN"
                                pin = ""
                            }
                        } finally {
                            isVerifying = false
                        }
                    }
                },
                enabled = !lockedOut && !isVerifying
            ) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SetPinDialog(onDismiss: () -> Unit, onSave: (String, onResult: (success: Boolean) -> Unit) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // BUG fix: the dialog used to close (and the caller's "App lock (PIN)" switch render) IMMEDIATELY
    // on tapping Save, before the 600k-round PBKDF2 hash + actual DataStore write had finished (up to
    // ~1-3s) - so the switch briefly, legitimately showed the OLD value with zero indication a save was
    // still in progress. isSaving disables the button and shows a spinner for that real window instead.
    var isSaving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Set a PIN") },
        text = {
            Column {
                Text("Choose a 4-6 digit PIN.", style = MaterialTheme.typography.bodySmall)
                // BUG fix: KeyboardType.NumberPassword only picks the numeric-password KEYBOARD LAYOUT -
                // it doesn't mask the displayed characters on its own. Only PinLockScreen's own field had
                // visualTransformation = PasswordVisualTransformation() applied; this one and Confirm PIN
                // below, plus VerifyCurrentPinDialog's field above, rendered the digits in clear text.
                OutlinedTextField(
                    // BUG fix: error was only ever recomputed inside Save's onClick, never cleared as the
                    // user typed - so e.g. "PINs don't match" stayed on screen even after editing one
                    // field to actually match, until Save was tapped again.
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6); error = null },
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !isSaving
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter { c -> c.isDigit() }.take(6); error = null },
                    label = { Text("Confirm PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !isSaving
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                TextButton(onClick = {
                    when {
                        pin.length < 4 -> error = "PIN must be at least 4 digits"
                        pin != confirm -> error = "PINs don't match"
                        else -> {
                            isSaving = true
                            // BUG fix: onResult now carries whether the save actually succeeded - a
                            // failure (e.g. a real DataStore I/O error) used to be indistinguishable from
                            // success at this call site (there was no failure path at all), so the dialog
                            // would just hang with isSaving stuck true if setPin ever threw. On failure
                            // this now un-sticks the spinner AND surfaces an error instead of silently
                            // closing as if the PIN had been set.
                            onSave(pin) { success ->
                                isSaving = false
                                if (success) onDismiss() else error = "Couldn't save PIN - try again"
                            }
                        }
                    }
                }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") } }
    )
}
