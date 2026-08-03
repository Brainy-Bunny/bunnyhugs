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

object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_REMINDERS = "reminders"

    const val STATUS_NOTIFICATION_ID = 1001
    const val REMINDER_NOTIFICATION_ID = 1002

    const val EXTRA_OPEN_CAMERA = "open_camera"

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
        manager.createNotificationChannel(statusChannel)
        manager.createNotificationChannel(reminderChannel)
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
}
