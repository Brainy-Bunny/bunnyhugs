package com.ssbmedia.twogether.ui.backup

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.backup.BackupManager
import kotlinx.coroutines.launch

/** See RestoreBackupButton's own doc for why this state deliberately does NOT live in a composable-local
 * `remember{}` - a plain top-level singleton with Compose `mutableStateOf` fields (the same pattern
 * AppLockManager already uses) survives MainActivity swapping its whole displayed screen (PinLockScreen
 * / PairingScreen / TwogetherNavHost) out from under whichever composable happens to be hosting
 * RestoreBackupButton at the time, since it isn't scoped to that composable's position in the tree at
 * all - reading/writing these fields from ANY composable still triggers normal Compose recomposition. */
private object RestoreFlowState {
    var restoreCandidate by mutableStateOf<Pair<Uri, String>?>(null)
    /** Wall-clock time [restoreCandidate] was picked - see the expiry check in RestoreBackupButton for
     * why this exists. */
    var candidatePickedAtMillis by mutableStateOf(0L)
    var isRestoring by mutableStateOf(false)
    var restoreResult by mutableStateOf<BackupManager.BackupResult?>(null)
}

/** BUG fix: surviving indefinitely (the whole point of moving this state into a singleton - see its own
 * doc) turned an edge case into a worse one - an independent review round found that a candidate picked
 * during the SAF-picker/PIN-relock scenario this was built for, but never actually confirmed/dismissed
 * (e.g. the user got distracted and never reopened a screen hosting this button), would sit there
 * FOREVER, then ambush the user with a "This will REPLACE everything currently on this phone... can't be
 * undone" destructive confirm dialog completely out of context whenever they next happened to open
 * Settings or the pairing screen - possibly hours or days later, with no memory of having picked a file.
 * Bounding how stale a picked-but-unconfirmed candidate can be before it's silently discarded turns that
 * back into the original (much safer) "silently forgotten" behavior once it's no longer plausible the
 * user is still mid-flow, while still covering the actual relock-during-picker scenario this exists for
 * (which resolves within seconds of the user re-entering their PIN, not minutes). */
private const val RESTORE_CANDIDATE_EXPIRY_MILLIS = 3 * 60_000L

/**
 * Self-contained "Restore from backup" trigger + its whole dialog flow (pick file -> confirm
 * overwrite -> progress -> result/restart), shared between SettingsScreen (reachable while paired) and
 * PairingScreen's onboarding landing (reachable while UNPAIRED, e.g. right after a factory reset /
 * reinstall / `pm clear`).
 *
 * That second entry point matters: a restore is disaster recovery, and the most common disaster - this
 * phone's own local data getting wiped - is exactly the scenario where MainActivity shows PairingScreen,
 * not the paired-only TwogetherNavHost that SettingsScreen lives inside. Without a restore entry point
 * that works from the UNPAIRED state too, "wipe the phone and restore from backup" (the whole point of
 * Feature 4) would have no UI path at all.
 *
 * File selection uses the system Storage Access Framework picker (ActivityResultContracts.OpenDocument)
 * rather than an in-app list of "backups I made" - see BackupManager's class doc for why an in-app
 * MediaStore-query-based list was tried first and found unreliable exactly across a `pm clear`/reinstall,
 * the main scenario this feature serves. SAF needs no storage permission on any API level and lets the
 * user browse to Downloads/"Twogether Backups" (or wherever they moved the file) directly.
 *
 * [trigger] renders whatever button/row content the caller wants; calling its `onClick` opens the
 * picker. Restore itself is deliberately launched on ServiceLocator.applicationScope (not any
 * ViewModel's viewModelScope), for the same reason SettingsViewModel.unpair() is: a successful restore
 * rewrites the pairing DataStore, which can flip MainActivity's paired/unpaired branch and dispose
 * whichever screen hosts this composable mid-flight - that must not cancel the restore partway through.
 *
 * BUG fix: this whole flow's state (picked-file confirmation, in-progress, result) lives in
 * [RestoreFlowState], NOT a plain `remember{}` here - see that object's own doc for why a
 * composable-local `remember` isn't enough. Two real, live-reproduced bugs came from this: (1) the SAF
 * picker below briefly backgrounds the whole app, which re-arms PIN lock if it's enabled, swapping
 * MainActivity to PinLockScreen BEFORE the user ever saw the "Restore this backup?" confirm dialog -
 * unlocking then dropped them on Home with the picked file silently forgotten. (2) A SUCCESSFUL restore
 * clears pairing, which (exactly as this file's own comment above already predicted for the restore
 * OPERATION itself) flips MainActivity to PairingScreen - but that same flip was ALSO disposing the
 * composable meant to show the "Restore complete / Restart now" dialog right as the result arrived,
 * so a successful restore could silently finish with no confirmation and no restart ever triggered.
 */
@Composable
fun RestoreBackupButton(trigger: @Composable (onClick: () -> Unit) -> Unit) {
    val context = LocalContext.current

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            RestoreFlowState.restoreCandidate = uri to queryDisplayName(context, uri)
            RestoreFlowState.candidatePickedAtMillis = System.currentTimeMillis()
        }
    }

    trigger { pickerLauncher.launch(arrayOf("*/*")) }

    // BUG fix: see RESTORE_CANDIDATE_EXPIRY_MILLIS' own doc - discards a stale unconfirmed candidate
    // before it can ambush the user, checked every time this composable (re)enters composition (i.e.
    // every time the user navigates to a screen that hosts this button).
    LaunchedEffect(Unit) {
        val pickedAt = RestoreFlowState.candidatePickedAtMillis
        if (RestoreFlowState.restoreCandidate != null && pickedAt != 0L &&
            System.currentTimeMillis() - pickedAt > RESTORE_CANDIDATE_EXPIRY_MILLIS
        ) {
            RestoreFlowState.restoreCandidate = null
        }
    }

    RestoreFlowState.restoreCandidate?.let { (uri, displayName) ->
        AlertDialog(
            onDismissRequest = { RestoreFlowState.restoreCandidate = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "This will REPLACE everything currently on this phone — sessions, photos, date ideas, " +
                        "time capsules, and settings — with what's saved in \"$displayName\". " +
                        "Your pairing and PIN lock are NOT restored (for security, they're never saved in a " +
                        "backup) — you'll need to pair with your partner again afterward. " +
                        "This can't be undone. The app will restart when it's done."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    RestoreFlowState.restoreCandidate = null
                    RestoreFlowState.isRestoring = true
                    ServiceLocator.applicationScope.launch {
                        // BUG fix: wrapped in try/finally - restoreBackupDurable is documented as
                        // graceful-failure-returning, not throwing, but that's an invariant of ITS
                        // implementation, not something the compiler enforces here. Without this, any
                        // future exception escaping it (or a bug introduced there later) would leave
                        // isRestoring stuck true forever - and unlike the old remember-scoped version,
                        // this singleton's "Restoring..." modal (non-dismissible, onDismissRequest = {})
                        // would then follow the user to every single screen that hosts this button, for
                        // the rest of the process, not just this one composable.
                        try {
                            // Durable across process death, not just UI teardown - see restoreBackupDurable's
                            // doc. If the process dies before this returns, TwogetherApp.onCreate's own
                            // pending-restore check picks it back up on next launch automatically.
                            val result = BackupManager.restoreBackupDurable(context.applicationContext, uri)
                            RestoreFlowState.restoreResult = result
                        } finally {
                            RestoreFlowState.isRestoring = false
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { RestoreFlowState.restoreCandidate = null }) { Text("Cancel") } }
        )
    }

    if (RestoreFlowState.isRestoring) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Restoring…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Please don't close the app.")
                }
            },
            confirmButton = {}
        )
    }

    RestoreFlowState.restoreResult?.let { result ->
        AlertDialog(
            onDismissRequest = { if (!result.success) RestoreFlowState.restoreResult = null },
            title = { Text(if (result.success) "Restore complete" else "Restore failed") },
            text = { Text(result.message) },
            confirmButton = {
                if (result.success) {
                    TextButton(onClick = {
                        RestoreFlowState.restoreResult = null
                        BackupManager.restartApp(context)
                    }) { Text("Restart now") }
                } else {
                    TextButton(onClick = { RestoreFlowState.restoreResult = null }) { Text("OK") }
                }
            }
        )
    }
}

/** Best-effort human-readable file name for a SAF-picked Uri, falling back to its last path segment
 * (never blank/crashing) if the DocumentsProvider doesn't return OpenableColumns.DISPLAY_NAME. */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) {
            val name = c.getString(idx)
            if (!name.isNullOrBlank()) return name
        }
    }
    return uri.lastPathSegment ?: "backup file"
}
