package com.ssbmedia.twogether.ui.battery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ssbmedia.twogether.util.BatteryOptimization

/**
 * Feature 1: a one-time-per-app-open friendly explainer for why Twogether wants an exemption from
 * Android's battery optimization (Doze/App Standby), which otherwise silently kills exactly this kind
 * of always-on background BLE service on aggressive OEMs (Xiaomi, Samsung, OnePlus, ...) regardless of
 * correct API usage on our end.
 *
 * Called once from TwogetherNavHost (which itself only ever composes once the couple is actually
 * paired) - so this naturally covers both halves of the spec: "on first successful pairing" (the very
 * first time NavHost composes after pairing) and "on app launch if paired but not yet resolved" (every
 * later cold start where NavHost composes fresh again while the exemption is still missing). Dismissing
 * with "Not now" does NOT permanently suppress this - there's no persisted "don't ask again" flag - so
 * it simply asks again the next time the app is opened fresh, which is a deliberately light-touch way to
 * "check again later" without nagging mid-session or over-engineering a snooze schedule.
 *
 * The persistent notification (see ProximityForegroundService.updateBatteryOptimizationNotification) is
 * the actual durable reminder while this is unresolved; this dialog is just the friendlier one-time
 * explainer alongside it.
 */
@Composable
fun BatteryOptimizationGate() {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // No result handling needed here - ProximityForegroundService's own ticker independently
        // re-checks PowerManager.isIgnoringBatteryOptimizations on a ~5s cadence and shows/cancels the
        // persistent notification accordingly, regardless of what the user actually chose in the system
        // dialog this launched.
    }

    LaunchedEffect(Unit) {
        if (!BatteryOptimization.isIgnoring(context)) {
            showDialog = true
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Keep tracking reliable in the background") },
            text = {
                Column {
                    Text(
                        "For Twogether to reliably track time together in the background, please allow " +
                            "it to skip battery optimization - otherwise Android may stop the connection " +
                            "while your phone is in your pocket."
                    )
                    Text(
                        "Note: some phone brands (Xiaomi, Samsung, OnePlus, and others) have their own " +
                            "extra \"auto-start\" or \"protected apps\" battery settings beyond what this " +
                            "screen controls. If background tracking still seems unreliable after allowing " +
                            "this, it's worth checking your phone's battery/app-launch settings too.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    try {
                        settingsLauncher.launch(BatteryOptimization.requestIgnoreIntent(context))
                    } catch (e: Exception) {
                        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is a standard AOSP intent available
                        // on every real device, but some heavily-modified OEM builds or emulator images
                        // can lack a handler for it - fail quietly rather than crashing; the persistent
                        // notification (and this same dialog next app open) remains as the fallback.
                    }
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Not now") }
            }
        )
    }
}
