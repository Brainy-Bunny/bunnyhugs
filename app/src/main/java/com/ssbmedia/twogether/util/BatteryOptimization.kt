package com.ssbmedia.twogether.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Small shared helper around Android's Doze/App Standby battery-optimization exemption, used both by
 * the UI (the one-time "please allow this" dialog after pairing) and by ProximityForegroundService (the
 * persistent nag notification while the app is NOT exempted). Kept as one object so both call sites
 * agree exactly on what "exempted" means and how to ask for it - see ProximityForegroundService's tick()
 * and ui/battery/BatteryOptimizationDialog.kt.
 */
object BatteryOptimization {

    /** True once the user has granted the exemption (or the OEM/AOSP build doesn't enforce it at all -
     * PowerManager being unavailable is treated as "nothing to warn about" rather than a false alarm). */
    fun isIgnoring(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** The standard, policy-compliant system intent that pops Android's own confirmation dialog asking
     * to exempt this app from battery optimization - never silently grants it ourselves. */
    fun requestIgnoreIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
}
