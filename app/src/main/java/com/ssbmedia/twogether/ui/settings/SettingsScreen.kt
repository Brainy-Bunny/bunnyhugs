package com.ssbmedia.twogether.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import com.ssbmedia.twogether.lock.PinUtil
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel : ViewModel() {
    val settings = ServiceLocator.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setSnoozeMinutes(min: Int) {
        viewModelScope.launch { ServiceLocator.settingsStore.setDefaultSnoozeMinutes(min) }
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

    fun setPin(pin: String) {
        // MINOR fix: launched on the app-scoped coroutine, NOT viewModelScope - same reasoning as
        // unpair() below. PinUtil.hash() now does real, visible work (600k-round PBKDF2, up to ~1-3s on
        // a mid-range phone); navigating out of Settings within that window used to cancel viewModelScope
        // and silently drop the PIN save entirely (the dialog would close as if it succeeded, but
        // pinHash/pinEnabled were never actually written). At the old 20k-round cost (~tens of ms) this
        // was never realistically reachable.
        ServiceLocator.applicationScope.launch {
            ServiceLocator.settingsStore.setPin(PinUtil.hash(pin))
            // AppLockManager.isLocked defaults to true and is otherwise only cleared by successfully
            // entering the PIN on PinLockScreen. Without this, turning PIN lock on for the first time
            // (isLocked has never been flipped false yet) immediately re-shows the lock screen right
            // after the user just typed the same PIN into the "set PIN" dialog - forcing them to
            // enter it twice in a row for no reason. The user is already authenticated in this
            // session (they're sitting in Settings), so unlock immediately.
            AppLockManager.unlock()
        }
    }

    fun clearPin() {
        viewModelScope.launch { ServiceLocator.settingsStore.clearPin() }
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
    var pairingCode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val info = ServiceLocator.pairingStore.current()
        partnerName = info.partnerName
        pairingCode = info.pairPlainCode.orEmpty()
    }

    var showPinDialog by remember { mutableStateOf(false) }
    // Gates BOTH "Change PIN" and "turn PIN off" behind re-entering the CURRENT PIN first - closes the
    // gap where anyone holding an already-unlocked phone could silently hijack or remove the app lock
    // with zero friction, without needing to know the existing PIN at all. See PinVerifyPurpose's doc.
    var pinVerifyPurpose by remember { mutableStateOf<PinVerifyPurpose?>(null) }
    var showUnpairConfirm by remember { mutableStateOf(false) }
    var showSnoozeDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var isBackingUp by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingForUpdate by remember { mutableStateOf(false) }
    var updateCheckMessage by remember { mutableStateOf<String?>(null) }

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
                SettingsRow(label = "Notifications enabled", subtitle = "15-minute photo nudges and updates") {
                    Switch(checked = settings.notificationsEnabled, onCheckedChange = { vm.setNotificationsEnabled(it) })
                }
                SettingsRow(label = "Default snooze length", subtitle = "${settings.defaultSnoozeMinutes} minutes") {
                    TextButton(onClick = { showSnoozeDialog = true }) { Text("Change") }
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
            }

            SettingsSection(title = "Pairing") {
                SettingsRow(label = "Paired with", subtitle = partnerName.ifBlank { "—" }) {}
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
                    }
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
                        val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
                        val whenText = fmt.format(Date(settings.lastBackupAt))
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
                                updateCheckMessage = when (outcome) {
                                    is UpdateChecker.CheckOutcome.UpdateAvailable ->
                                        "Update ${outcome.info.versionName} downloaded — check your notifications to install it."
                                    UpdateChecker.CheckOutcome.UpToDate -> "You're up to date."
                                    UpdateChecker.CheckOutcome.DownloadFailed ->
                                        "Found a newer version, but the download failed. Check your connection and try again."
                                    UpdateChecker.CheckOutcome.CheckFailed ->
                                        "Couldn't check for updates. Check your connection and try again."
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

    if (showPinDialog) {
        SetPinDialog(onDismiss = { showPinDialog = false }, onSave = { pin -> vm.setPin(pin); showPinDialog = false })
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
                        "You'll just need to reconnect with $displayName phone to resume tracking time together."
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
            confirmButton = { TextButton(onClick = { updateCheckMessage = null }) { Text("OK") } }
        )
    }

}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
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
private fun SetPinDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN") },
        text = {
            Column {
                Text("Choose a 4-6 digit PIN.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("PIN") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("Confirm PIN") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length < 4 -> error = "PIN must be at least 4 digits"
                    pin != confirm -> error = "PINs don't match"
                    else -> onSave(pin)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
