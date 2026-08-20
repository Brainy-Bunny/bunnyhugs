package com.ssbmedia.twogether.util

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * UX-FIX-PLAN.md Phase 3 item 23: `NotificationChannel.setBypassDnd(true)` (see notif/Notifications.kt's
 * CHANNEL_REMINDERS) is a no-op unless the user has ALSO separately granted this app "Do Not Disturb
 * access" (`ACCESS_NOTIFICATION_POLICY`) - a special access settings screen, not a runtime permission
 * dialog, exactly like [BatteryOptimization]'s battery-exemption flow this file deliberately mirrors the
 * shape of (same "isGranted/requestIntent" pair) so both call sites - the item-22 notification-inbox panel
 * and any future Home banner - agree on exactly what "granted" means and how to ask for it.
 */
object DndAccess {

    /** True once the user has granted Do Not Disturb access for this app, so CHANNEL_REMINDERS'
     * `setBypassDnd(true)` can actually take effect. NotificationManager being unavailable is treated as
     * "nothing to warn about" (matches [BatteryOptimization.isIgnoring]'s same fallback choice). */
    fun isGranted(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        return manager.isNotificationPolicyAccessGranted
    }

    /** The standard, policy-compliant system settings screen that lets the user grant (or revoke) DND
     * access for this app - never silently granted by the app itself. */
    fun requestIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
}
