package com.ssbmedia.twogether.notif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
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
    /** UX-FIX-PLAN.md Phase 3 item 23: bumped from IMPORTANCE_DEFAULT (no DND bypass, default sound) to a
     * genuinely loud, `setBypassDnd(true)` channel - the whole point of a "you've been together Xh, snap a
     * photo?" or list/item reminder is to actually be noticed, not sit silently in the shade the way a
     * DEFAULT-importance notification can under a phone's Do Not Disturb schedule. Exactly the same
     * "channel importance is immutable once created on a real device, so the id itself must change" problem
     * CHANNEL_UPDATES already solved below - see its own doc for the full explanation. CHANNEL_REMINDERS_
     * LEGACY (the old "reminders" id) is deleted in ensureChannels() below so upgrading users don't end up
     * with a dead duplicate channel sitting in system notification settings. Note: `setBypassDnd(true)` on
     * the channel is only actually honored once the user has ALSO separately granted this app Do Not
     * Disturb access (ACCESS_NOTIFICATION_POLICY / NotificationManager.isNotificationPolicyAccessGranted) -
     * see util/DndAccess.kt and the item-22 notification-inbox panel, which explicitly asks for that grant
     * rather than silently relying on this flag alone. */
    const val CHANNEL_REMINDERS = "reminders_v2"
    /** The pre-item-23 channel id (IMPORTANCE_DEFAULT, no DND bypass) - see CHANNEL_REMINDERS' own doc for
     * why this can't just be upgraded in place. Deleted in ensureChannels() on every app start;
     * deleteNotificationChannel is a harmless no-op for an install that never had this channel. */
    private const val CHANNEL_REMINDERS_LEGACY = "reminders"
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
    /** SECURITY (user-designed follow-up to the TOFU device-ID pinning): a device this phone is already
     * locked to a partner rejected a sync from a DIFFERENT device presenting the same pairing code - see
     * PairingStore.recordPendingResyncRequest's doc. Worth a real (not silent) notification since this is
     * exactly the "your pin is now permanently stale and nothing will ever tell you" gap the whole
     * device-ID pinning feature was already trying to close - a rejection nobody notices is no better
     * than not rejecting at all. */
    const val CHANNEL_PAIRING = "pairing"

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
    const val RESYNC_REQUEST_NOTIFICATION_ID = 1006
    /** Base id for a milestone's yearly notification - offset by a stable per-milestone hash so
     * different milestones never clobber each other's notification (see MilestoneAlarmScheduler). Always
     * >= 2000 and (per the hash mask) always < 2000 + 0x0FFFFFFF - see the fixed IDs above for why nothing
     * else may ever be assigned an ID in that range. */
    const val MILESTONE_NOTIFICATION_ID_BASE = 2000

    /** Item 24 (UX-FIX-PLAN.md): base id for a list/list-item reminder notification, offset by a stable
     * per-key hash (see showListItemReminder/showListReminder) - mirrors MILESTONE_NOTIFICATION_ID_BASE's
     * own pattern one level up, deliberately in its OWN separate part of the id space rather than
     * reusing 2000: MILESTONE_NOTIFICATION_ID_BASE's own hash mask (0x0FFFFFFF, ~268 million) already
     * spans most of the usable positive-Int range starting at 2000, so a second hash-based range sharing
     * that same base would meaningfully raise the chance of a real collision with an unrelated
     * milestone's id. Starting this range at 300,000,000 instead (with the same 0x0FFFFFFF mask, so it
     * spans up to ~568 million) keeps it clear of both the fixed IDs above (1001-1006) and the entire
     * milestone range, while staying safely inside Int's positive range (~2.1 billion). */
    const val LIST_REMINDER_NOTIFICATION_ID_BASE = 300_000_000

    const val EXTRA_OPEN_CAMERA = "open_camera"
    /** Feature F: carries which milestone to open the "throughout the years" retrospective for, when the
     * user taps a milestone's yearly notification - mirrors EXTRA_OPEN_CAMERA's pattern. */
    const val EXTRA_OPEN_MILESTONE_ID = "open_milestone_id"
    /** Item 24 (UX-FIX-PLAN.md): carries which ListCategory to open (and auto-expand) in "Our Lists" when
     * the user taps a list/list-item reminder notification - mirrors EXTRA_OPEN_MILESTONE_ID's pattern.
     * Used for BOTH showListItemReminder (opens the ITEM's owning list) and showListReminder (opens the
     * list itself), since "Our Lists" has no separate per-item destination to deep-link to. */
    const val EXTRA_OPEN_LIST_ID = "open_list_id"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val statusChannel = NotificationChannel(
            CHANNEL_STATUS, "Together status", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows whether you two are currently together"
            setShowBadge(false)
        }
        // UX-FIX-PLAN.md Phase 3 item 23: IMPORTANCE_HIGH + setBypassDnd(true) + a real vibration pattern +
        // explicit AudioAttributes, so this channel actually heads-up-pops and can ring through Do Not
        // Disturb (once the user has separately granted DND access - see CHANNEL_REMINDERS' own doc) -
        // instead of the old IMPORTANCE_DEFAULT channel that could sit silently unnoticed in the shade.
        val reminderChannel = NotificationChannel(
            CHANNEL_REMINDERS, "Photo & list reminders", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "A gentle but LOUD nudge to snap a photo or check a reminder when you've been " +
                "together a while - rings even during Do Not Disturb if you've granted that access"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 250, 250)
            setBypassDnd(true)
            setSound(
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
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
        val pairingChannel = NotificationChannel(
            CHANNEL_PAIRING, "Pairing requests", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Lets you know if a device tries to sync with your paired phone that doesn't match your partner"
        }
        manager.createNotificationChannel(statusChannel)
        manager.createNotificationChannel(reminderChannel)
        manager.createNotificationChannel(milestoneChannel)
        manager.createNotificationChannel(updatesChannel)
        manager.createNotificationChannel(batteryChannel)
        manager.createNotificationChannel(backupChannel)
        manager.createNotificationChannel(pairingChannel)
        // See CHANNEL_UPDATES_LEGACY's doc - removes the old lower-importance "updates" channel so
        // upgrading users don't end up with a dead duplicate sitting in system notification settings
        // alongside the new CHANNEL_UPDATES one. No-op if it was never created (fresh install) or was
        // already deleted on a previous app start.
        manager.deleteNotificationChannel(CHANNEL_UPDATES_LEGACY)
        // See CHANNEL_REMINDERS_LEGACY's doc - same reasoning, for the old DEFAULT-importance "reminders"
        // channel this item-23 loud/DND-bypassing channel replaces.
        manager.deleteNotificationChannel(CHANNEL_REMINDERS_LEGACY)
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

    /** Item 24 (UX-FIX-PLAN.md): [minutes] is the user-configured interval (AppSettings.
     * photoReminderMinutes, default 15) - the title text is now built from the actual value rather than
     * a hardcoded "15 minutes". Returns true iff the reminder was actually posted, so callers don't mark
     * a one-per-session reminder as "fired" when nothing was shown (e.g. notification permission denied
     * on API 33+). */
    fun showPhotoReminder(context: Context, minutes: Int): Boolean {
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

        val minutesLabel = if (minutes == 1) "1 minute" else "$minutes minutes"
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle("You've been together for $minutesLabel 💛")
            .setContentText("Snap a photo?")
            .setAutoCancel(true)
            .setContentIntent(cameraPendingIntent)
            .addAction(0, "Snooze", snoozePendingIntent)
            // UX-FIX-PLAN.md Phase 3 item 23: CATEGORY_REMINDER (tells the system/OEM what KIND of
            // notification this is, independent of the channel's own importance) + PRIORITY_HIGH (the
            // pre-O fallback NotificationCompat still reads on API < 26, harmless no-op on this app's real
            // minSdk 26+ where the channel's own IMPORTANCE_HIGH governs instead).
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // On API 33+, notify() without POST_NOTIFICATIONS granted just silently never shows anything -
        // matching the check ProximityForegroundService.updateNotification already does before its own
        // notify() call. Returns whether it actually posted so the caller can avoid marking this
        // session's one-shot reminder as "fired" when nothing was actually shown.
        if (!BlePermissions.hasNotificationPermission(context)) return false
        manager.notify(REMINDER_NOTIFICATION_ID, notification)
        return true
    }

    fun cancelPhotoReminder(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(REMINDER_NOTIFICATION_ID)
    }

    /** Item 24 (UX-FIX-PLAN.md): fired once per together-session for a single DateIdea whose own
     * [com.ssbmedia.twogether.data.db.DateIdea.remindAfterTogetherMinutes] threshold has been crossed -
     * see ProximityForegroundService.checkListReminders. Reuses CHANNEL_REMINDERS (same channel as the
     * photo reminder). Tapping it opens "Our Lists" with the idea's OWNING list expanded (the idea's own
     * text is in the notification body so it's clear what the reminder is about, even without a
     * per-item destination to deep-link to). [listId] is the idea's [ListCategory][
     * com.ssbmedia.twogether.data.db.ListCategory].id, used both for the deep-link and for the stable
     * per-idea notification id below. */
    fun showListItemReminder(context: Context, ideaId: String, listId: String, listName: String, ideaText: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!BlePermissions.hasNotificationPermission(context)) return false

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_LIST_ID, listId)
        }
        val notificationId = LIST_REMINDER_NOTIFICATION_ID_BASE + ("idea:$ideaId".hashCode() and 0x0FFFFFFF)
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle("Idea time! 💡")
            .setContentText("\"$ideaText\" — from your $listName list")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(notificationId, notification)
        return true
    }

    /** Item 24 (UX-FIX-PLAN.md): fired once per together-session for a whole ListCategory whose
     * [com.ssbmedia.twogether.data.db.ListCategory.defaultRemindAfterTogetherMinutes] threshold has been
     * crossed (e.g. "remind me about our bucket list every time we've been together 2+ hours") - see
     * ProximityForegroundService.checkListReminders. One notification about the LIST as a whole,
     * independent of any per-item reminders also firing for ideas inside it (showListItemReminder
     * above). Reuses CHANNEL_REMINDERS. Tapping it opens "Our Lists" with this list expanded. */
    fun showListReminder(context: Context, listId: String, listName: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!BlePermissions.hasNotificationPermission(context)) return false

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_LIST_ID, listId)
        }
        val notificationId = LIST_REMINDER_NOTIFICATION_ID_BASE + ("list:$listId".hashCode() and 0x0FFFFFFF)
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle("Check your $listName list 💌")
            .setContentText("You've been together a while — take a look?")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(notificationId, notification)
        return true
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

    /** SECURITY (user-designed follow-up): posted when GattSyncManager.applyPayload records a genuinely
     * NEW pending resync request (see PairingStore.recordPendingResyncRequest's isNewDevice return and
     * this call's throttle at the GattSyncManager call site) - i.e. a device presenting this pairing's
     * code that doesn't match who this device is already pinned to. Deliberately generic content with NO
     * action buttons and no specific device identity in the text itself (per Fable's design review):
     * approving a resync is a real security decision (it re-opens who this device trusts), so it must
     * only ever happen after the user has actually looked at the full context - Bluetooth name, claimed
     * partner name, request age - on the in-app review screen this deep-links to, never from a notification
     * shade tap alone. */
    fun showResyncRequestNotification(context: Context, partnerName: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!BlePermissions.hasNotificationPermission(context)) return false

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, RESYNC_REQUEST_NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setContentTitle("A device tried to use your pairing code")
            .setContentText("Tap to check if it was $partnerName")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(RESYNC_REQUEST_NOTIFICATION_ID, notification)
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
