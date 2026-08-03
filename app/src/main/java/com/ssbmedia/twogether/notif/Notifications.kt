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
import java.io.File

object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_MILESTONES = "milestones"
    const val CHANNEL_UPDATES = "updates"

    const val STATUS_NOTIFICATION_ID = 1001
    const val REMINDER_NOTIFICATION_ID = 1002
    /** Base id for a milestone's yearly notification - offset by a stable per-milestone hash so
     * different milestones never clobber each other's notification (see MilestoneAlarmScheduler). */
    const val MILESTONE_NOTIFICATION_ID_BASE = 2000
    const val UPDATE_NOTIFICATION_ID = 3000

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
            CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Lets you know when a newer version of Twogether is ready to install"
        }
        manager.createNotificationChannel(statusChannel)
        manager.createNotificationChannel(reminderChannel)
        manager.createNotificationChannel(milestoneChannel)
        manager.createNotificationChannel(updatesChannel)
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
}
