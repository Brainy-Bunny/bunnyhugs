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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.backup.BackupManager
import kotlinx.coroutines.launch

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
 */
@Composable
fun RestoreBackupButton(trigger: @Composable (onClick: () -> Unit) -> Unit) {
    val context = LocalContext.current
    var restoreCandidate by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    var restoreResult by remember { mutableStateOf<BackupManager.BackupResult?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreCandidate = uri to queryDisplayName(context, uri)
    }

    trigger { pickerLauncher.launch(arrayOf("*/*")) }

    restoreCandidate?.let { (uri, displayName) ->
        AlertDialog(
            onDismissRequest = { restoreCandidate = null },
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
                    restoreCandidate = null
                    isRestoring = true
                    ServiceLocator.applicationScope.launch {
                        // Durable across process death, not just UI teardown - see restoreBackupDurable's
                        // doc. If the process dies before this returns, TwogetherApp.onCreate's own
                        // pending-restore check picks it back up on next launch automatically.
                        val result = BackupManager.restoreBackupDurable(context.applicationContext, uri)
                        isRestoring = false
                        restoreResult = result
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreCandidate = null }) { Text("Cancel") } }
        )
    }

    if (isRestoring) {
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

    restoreResult?.let { result ->
        AlertDialog(
            onDismissRequest = { if (!result.success) restoreResult = null },
            title = { Text(if (result.success) "Restore complete" else "Restore failed") },
            text = { Text(result.message) },
            confirmButton = {
                if (result.success) {
                    TextButton(onClick = { BackupManager.restartApp(context) }) { Text("Restart now") }
                } else {
                    TextButton(onClick = { restoreResult = null }) { Text("OK") }
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
