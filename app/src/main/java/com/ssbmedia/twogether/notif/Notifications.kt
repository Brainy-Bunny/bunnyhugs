package com.ssbmedia.twogether.notif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ssbmedia.twogether.MainActivity
import com.ssbmedia.twogether.R
import com.ssbmedia.twogether.ble.BlePermissions
import com.ssbmedia.twogether.ui.snooze.SnoozeActivity
import com.ssbmedia.twogether.ui.update.UpdateInstallActivity
import com.ssbmedia.twogether.util.BatteryOptimization
import java.io.File

object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_MILESTONES = "milestones"
    /** Item 14 (update-nag reach fix): bumped from IMPORTANCE_DEFAULT to IMPORTANCE_HIGH so a granted-
     * permission "Update available" notification actually heads-up-pops instead of sitting quietly in
     * the shade - a real user shipped v2.7 (a genuine data-loss bug fix) and went days without noticing
     * it was ready. Android notification channel importance is IMMUTABLE once a channel is created on a
     * real device - simply changing IMPORTANCE_DEFAULT to IMPORTANCE_HIGH in ensureChannels' own
     * NotificationChannel(...) call below does nothing at all for anyone who already has the OLD
     * "updates" channel from a prior install (their channel stays stuck at whatever importance it was
     * first created with, forever, regardless of what this code says from here on). A new channel id is
     * the only way to actually take effect for upgrading users - see CHANNEL_UPDATES_LEGACY below, which
     * ensureChannels explicitly deletes so the old channel doesn't linger as a dead, still-visible entry
     * in system notification settings. */
    const val CHANNEL_UPDATES = "updates_v2"
    /** The pre-item-14 channel id (IMPORTANCE_DEFAULT) - see CHANNEL_UPDATES' own doc for why this can't
     * just be upgraded in place. Deleted in ensureChannels() on every app start; deleteNotificationChannel
     * is a harmless no-op for an install that never had this channel (fresh installs, or one already
     * upgraded past this point). */
    private const val CHANNEL_UPDATES_LEGACY = "updates"
    /** Separate (and deliberately LOW-importance, silent) channel from CHANNEL_STATUS, so the battery
     * nag is clearly distinguishable from the normal always-on "together/apart" status notification
     * rather than folded into its text - see buildBatteryWarningNotification's doc. */
    const val CHANNEL_BATTERY_WARNING = "battery_warning"
    /** MAJOR fix: a backup restore that gave up after repeated failed attempts (see
     * BackupManager.resumePendingRestoreIfAny's doc) used to leave the user unpaired/PIN-less with a
     * partially-restored phone and zero indication anything had even been attempted, since
     * TwogetherApp.onCreate silently discarded that call's result. */
    const val CHANNEL_BACKUP = "backup"

    const val STATUS_NOTIFICATION_ID = 1001
    const val REMINDER_NOTIFICATION_ID = 1002
    // BUG fix: these three used to be 3000/4000/5000 - well inside the range MILESTONE_NOTIFICATION_ID_BASE
    // + (hash and 0x0FFFFFFF) can actually produce (up to ~268 million), so some real milestone's hash
    // was always capable of landing exactly on one of them. Two IDs colliding means whichever notification
    // posts second silently replaces the first on screen, and manager.cancel() on one of these fixed IDs
    // could unintentionally cancel an unrelated milestone's still-relevant notification. Moved below 2000
    // instead - alongside STATUS/REMINDER - so the milestone range (2000 and up) is now exclusively
    // reserved for milestones and can never collide with any fixed-ID notification again.
    const val UPDATE_NOTIFICATION_ID = 1003
    const val BATTERY_WARNING_NOTIFICATION_ID = 1004
    const val RESTORE_GAVE_UP_NOTIFICATION_ID = 1005
    /** Base id for a milestone's yearly notification - offset by a stable per-milestone hash so
     * different milestones never clobber each other's notification (see MilestoneAlarmScheduler). Always
     * >= 2000 and (per the hash mask) always < 2000 + 0x0FFFFFFF - see the fixed IDs above for why nothing
     * else may ever be assigned an ID in that range. */
    const val MILESTONE_NOTIFICATION_ID_BASE = 2000

    const val EXTRA_OPEN_CAMERA = "open_camera"
    /** Feature F: carries which milestone to open the "throughout the years" retrospective for, when the
     * user taps a milestone's yearly notification - mirrors EXTRA_OPEN_CAMERA's pattern. */
    const val EXTRA_OPEN_MILESTONE_ID = "open_milestone_id"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val statusChannel = NotificationChannel(
            CHANNEL_STATUS, "Together status", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows whether you two are currently together"
            setShowBadge(false)
        }
        val reminderChannel = NotificationChannel(
            CHANNEL_REMINDERS, "Photo reminders", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "A gentle nudge to snap a photo when you've been together a while"
        }
        val milestoneChannel = NotificationChannel(
            CHANNEL_MILESTONES, "Anniversaries & milestones", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Yearly reminders for the dates you two have marked as milestones"
        }
        val updatesChannel = NotificationChannel(
            CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Lets you know when a newer version of Twogether is ready to install"
        }
        val batteryChannel = NotificationChannel(
            CHANNEL_BATTERY_WARNING, "Battery optimization warning", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Warns you if Android's battery optimization might interrupt background tracking"
            setShowBadge(false)
        }
        val backupChannel = NotificationChannel(
            CHANNEL_BACKUP, "Backup & restore", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Lets you know if a backup restore couldn't be completed"
        }
        manager.createNotificationChannel(statusChannel)
        manager.createNotificationChannel(reminderChannel)
        manager.createNotificationChannel(milestoneChannel)
        manager.createNotificationChannel(updatesChannel)
        manager.createNotificationChannel(batteryChannel)
        manager.createNotificationChannel(backupChannel)
        // See CHANNEL_UPDATES_LEGACY's doc - removes the old lower-importance "updates" channel so
        // upgrading users don't end up with a dead duplicate sitting in system notification settings
        // alongside the new CHANNEL_UPDATES one. No-op if it was never created (fresh install) or was
        // already deleted on a previous app start.
        manager.deleteNotificationChannel(CHANNEL_UPDATES_LEGACY)
    }

    fun buildStatusNotification(context: Context, contentText: String): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle("Twogether")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Returns true iff the reminder was actually posted, so callers don't mark a one-per-session
     * reminder as "fired" when nothing was shown (e.g. notification permission denied on API 33+). */
    fun showPhotoReminder(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false

        val cameraIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CAMERA, true)
        }
        val cameraPendingIntent = PendingIntent.getActivity(
            context, 1, cameraIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(context, SnoozeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val snoozePendingIntent = PendingIntent.getActivity(
            context, 2, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle("You've been together for 15 minutes 💛")
            .setContentText("Snap a photo?")
            .setAutoCancel(true)
            .setContentIntent(cameraPendingIntent)
            .addAction(0, "Snooze", snoozePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // On API 33+, notify() without POST_NOTIFICATIONS granted just silently never shows anything -
        // matching the check ProximityForegroundService.updateNotification already does before its own
        // notify() call. Returns whether it actually posted so the caller can avoid marking this
        // session's one-shot 15-minute reminder as "fired" when nothing was actually shown.
        if (!BlePermissions.hasNotificationPermission(context)) return false
        manager.notify(REMINDER_NOTIFICATION_ID, notification)
        return true
    }

    fun cancelPhotoReminder(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(REMINDER_NOTIFICATION_ID)
    }

    /** Feature F: the yearly "it's [label] today!" notification. Tapping it opens the app straight into
     * that milestone's "throughout the years" photo retrospective, same EXTRA-on-intent pattern as the
     * photo reminder's camera shortcut above. */
    fun showMilestoneNotification(context: Context, milestoneId: String, label: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!BlePermissions.hasNotificationPermission(context)) return false

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_MILESTONE_ID, milestoneId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, milestoneId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle("Today is $label 💛")
            .setContentText("Tap to relive your photos from this day over the years")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(MILESTONE_NOTIFICATION_ID_BASE + (milestoneId.hashCode() and 0x0FFFFFFF), notification)
        return true
    }

    /** MAJOR fix: posted when BackupManager.resumePendingRestoreIfAny gives up on a restore after
     * repeated failed silent attempts - without this, the user could be left unpaired/PIN-less with a
     * partially-restored phone and no indication a restore had even been attempted, since its result was
     * previously discarded silently by TwogetherApp.onCreate. */
    fun showRestoreGaveUpNotification(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!BlePermissions.hasNotificationPermission(context)) return false

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, RESTORE_GAVE_UP_NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle("Restore couldn't be completed")
            .setContentText("Open Twogether to check your pairing and PIN, then try restoring again.")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(RESTORE_GAVE_UP_NOTIFICATION_ID, notification)
        return true
    }

    /** Auto-update: posted once UpdateChecker has already downloaded [apkFile] for a confirmed-newer
     * release. Tapping it goes through UpdateInstallActivity (NOT a raw install Intent directly)
     * so a device that hasn't yet granted "install unknown apps" for Twogether gets routed to that
     * settings screen instead of the tap silently doing nothing - see that activity's doc comment. */
    fun showUpdateAvailableNotification(context: Context, apkFile: File, versionName: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!BlePermissions.hasNotificationPermission(context)) return false

        val installIntent = Intent(context, UpdateInstallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(UpdateInstallActivity.EXTRA_APK_PATH, apkFile.absolutePath)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, UPDATE_NOTIFICATION_ID, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle("Update available")
            .setContentText("Twogether $versionName is ready — tap to install")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(UPDATE_NOTIFICATION_ID, notification)
        return true
    }

    /**
     * Battery optimization nag: shown for as long as the app is NOT exempted from Doze/App Standby (see
     * BatteryOptimization.isIgnoring), so the couple understands *why* background tracking might be
     * unreliable rather than just silently missing detections. Deliberately setOngoing(true) (persists
     * through a swipe, matching the always-on status notification's own pattern) but - unlike the status
     * notification - this one is expected to actually go away entirely (cancel(), not just update its
     * text) the moment the exemption is granted; see ProximityForegroundService's per-tick check.
     * Tapping it goes straight to the same system settings screen the onboarding dialog's "Allow" button
     * uses, so there's exactly one way to resolve this app-wide.
     */
    fun buildBatteryWarningNotification(context: Context): Notification {
        val settingsIntent = BatteryOptimization.requestIgnoreIntent(context)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_BATTERY_WARNING)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle("Background tracking may be unreliable")
            .setContentText("Tap to let Twogether skip battery optimization")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Returns true iff the warning was actually posted, so callers don't latch "already nagged for this
     * state" when nothing was shown (e.g. notification permission denied) - mirrors showPhotoReminder's
     * pattern. See ProximityForegroundService.updateBatteryOptimizationNotification's doc. */
    fun showBatteryWarning(context: Context): Boolean {
        if (!BlePermissions.hasNotificationPermission(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.notify(BATTERY_WARNING_NOTIFICATION_ID, buildBatteryWarningNotification(context))
        return true
    }

    fun cancelBatteryWarning(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(BATTERY_WARNING_NOTIFICATION_ID)
    }
}
