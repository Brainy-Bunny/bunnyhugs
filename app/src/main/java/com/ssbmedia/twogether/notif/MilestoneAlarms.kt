package com.ssbmedia.twogether.notif

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.db.Milestone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Feature F: schedules a yearly local reminder for each milestone's month/day. AlarmManager has no true
 * "repeat once a year" primitive (setRepeating's interval is a fixed millisecond period, which drifts
 * across leap years), so instead this computes the NEXT concrete occurrence, schedules a single alarm
 * for it, and MilestoneAlarmReceiver reschedules the year-after-next occurrence itself once it fires -
 * the standard pattern for "yearly" reminders on Android.
 *
 * Deliberately uses setAndAllowWhileIdle (inexact-but-Doze-aware) rather than
 * setExactAndAllowWhileIdle: an exact alarm on API 31+ needs the user to separately grant
 * SCHEDULE_EXACT_ALARM in system settings, which is a lot of permission-flow complexity for a
 * once-a-year reminder where being off by even a few hours is completely fine. No special permission is
 * needed for the inexact variant.
 */
object MilestoneAlarmScheduler {
    private const val EXTRA_MILESTONE_ID = "milestone_id"
    private const val EXTRA_LABEL = "label"
    private const val EXTRA_MONTH = "month"
    private const val EXTRA_DAY = "day"
    private const val NOTIFY_HOUR = 9

    fun scheduleAll(context: Context, milestones: List<Milestone>) {
        milestones.forEach { scheduleOne(context, it) }
    }

    fun scheduleOne(context: Context, milestone: Milestone) {
        val next = nextOccurrenceMillis(milestone.month, milestone.day)
        schedule(context, milestone.id, milestone.label, milestone.month, milestone.day, next)
    }

    fun cancel(context: Context, milestoneId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = pendingIntentFor(context, milestoneId, "", 0, 0)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun schedule(context: Context, milestoneId: String, label: String, month: Int, day: Int, atMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = pendingIntentFor(context, milestoneId, label, month, day)
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
        } catch (e: SecurityException) {
            // Extremely unlikely for the inexact variant, but never let a scheduling failure crash the
            // caller (add-milestone flow, boot rescheduling, or the receiver's own reschedule-for-next-year).
        }
    }

    private fun pendingIntentFor(context: Context, milestoneId: String, label: String, month: Int, day: Int): PendingIntent {
        val intent = Intent(context, MilestoneAlarmReceiver::class.java).apply {
            putExtra(EXTRA_MILESTONE_ID, milestoneId)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_MONTH, month)
            putExtra(EXTRA_DAY, day)
        }
        // Stable per-milestone request code so re-scheduling the SAME milestone (e.g. app restart, boot)
        // updates the existing alarm rather than stacking a duplicate one, and cancel() can find it again.
        return PendingIntent.getBroadcast(
            context, milestoneId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Next wall-clock occurrence of [month]/[day] at [NOTIFY_HOUR]:00 local time, today if it hasn't
     * happened yet today, otherwise next year. Clamps day-of-month for a Feb 29 milestone in a
     * non-leap year down to Feb 28 rather than crashing/throwing on an invalid date. */
    private fun nextOccurrenceMillis(month: Int, day: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = LocalDate.now(zone)
        var year = now.year
        var date = safeDate(year, month, day)
        val todayAtHour = now.atTime(NOTIFY_HOUR, 0)
        if (date.isBefore(now) || (date == now && java.time.LocalDateTime.now(zone).isAfter(todayAtHour))) {
            year += 1
            date = safeDate(year, month, day)
        }
        return date.atTime(NOTIFY_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate {
        val maxDay = YearMonth.of(year, month).lengthOfMonth()
        return LocalDate.of(year, month, minOf(day, maxDay))
    }

    internal fun rescheduleForNextYear(context: Context, milestoneId: String, label: String, month: Int, day: Int) {
        val zone = ZoneId.systemDefault()
        val nextYear = LocalDate.now(zone).year + 1
        val date = safeDate(nextYear, month, day)
        schedule(context, milestoneId, label, month, day, date.atTime(NOTIFY_HOUR, 0).atZone(zone).toInstant().toEpochMilli())
    }
}

/** Fires once a year for a given milestone: shows the notification, then immediately re-arms itself for
 * next year (see MilestoneAlarmScheduler's class doc for why this reschedule-on-fire pattern is used
 * instead of a single repeating alarm). Exported=false - only this app's own scheduled PendingIntents
 * can trigger it. */
class MilestoneAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val milestoneId = intent.getStringExtra("milestone_id") ?: return
        val label = intent.getStringExtra("label") ?: "your milestone"
        val month = intent.getIntExtra("month", 1)
        val day = intent.getIntExtra("day", 1)

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Confirm the milestone (and its label, in case it was edited) still exists before
                // notifying - a deleted milestone's alarm that fires before Settings/Milestones had a
                // chance to cancel it should just silently no-op rather than notify about something
                // that's gone.
                ServiceLocator.init(appContext)
                val current = ServiceLocator.milestoneRepository.getAll().firstOrNull { it.id == milestoneId && !it.deleted }
                if (current != null) {
                    Notifications.ensureChannels(appContext)
                    Notifications.showMilestoneNotification(appContext, milestoneId, current.label)
                    MilestoneAlarmScheduler.rescheduleForNextYear(appContext, milestoneId, current.label, current.month, current.day)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
