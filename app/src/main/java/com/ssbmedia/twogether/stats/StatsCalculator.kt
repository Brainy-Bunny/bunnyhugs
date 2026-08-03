package com.ssbmedia.twogether.stats

import com.ssbmedia.twogether.ble.ProximityStateMachine
import com.ssbmedia.twogether.data.db.TogetherSession
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.WeekFields

data class TogetherStats(
    val totalHoursAllTime: Double,
    val totalHoursThisWeek: Double,
    val totalHoursThisMonth: Double,
    val currentDailyStreak: Int,
    val longestDailyStreak: Int,
    val currentWeeklyStreak: Int,
    val longestWeeklyStreak: Int,
    val longestSessionMinutes: Long,
    val reunionCount: Int,
    val favoriteDayOfWeek: DayOfWeek?,
    /** Total distinct calendar days with any together-time at all (BLE-detected or manually backfilled).
     * Same qualifying-day set the Calendar screen highlights - computed once here off the same
     * buildDailyMinuteMap() so the two screens can never drift apart. */
    val totalDaysTogether: Int
)

private const val REUNION_GAP_MILLIS = 30 * 60 * 1000L

object StatsCalculator {

    /**
     * The effective "as of now" cutoff to use in place of an open (endedAt == null) session's live
     * duration. Mirrors the SAME clamp every WRITE path already applies when it actually closes a
     * session (see ProximityForegroundService.handleBecameApart / selfHealOrphanedSession /
     * SettingsScreen.unpair): an open session's credited duration must never run past the last
     * confirmed sighting of the partner (lastSeenAt) plus the absence timeout.
     *
     * Without this, any READ of an open session (stats totals, capsule-unlock eligibility, badge
     * progress, calendar day totals) that naively substitutes "now" for a missing endedAt would let a
     * stale/orphaned open row (service killed, BLE permission revoked so it never restarts to self-heal,
     * a reboot, etc) silently "grow" forever every single time anything reads it - which is especially
     * dangerous for Time Capsules, since crossing a threshold there performs a real, irreversible write
     * (unlockedAt). [lastSeenAt] <= 0 means "no sighting info available" (e.g. a screen that hasn't
     * loaded proximity state yet) and intentionally falls back to the old unclamped "now" behavior rather
     * than always reporting a zero-length open session.
     */
    fun effectiveOpenSessionCutoff(
        now: Long,
        lastSeenAt: Long,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): Long = if (lastSeenAt > 0L) minOf(now, lastSeenAt + absenceTimeoutMillis) else now

    fun compute(
        sessions: List<TogetherSession>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        /** Latest confirmed partner sighting (ProximityPersistedState.lastSeenAt), used to clamp an
         * open session's live duration - see [effectiveOpenSessionCutoff]. Pass 0L only when this
         * information genuinely isn't available yet. */
        lastSeenAt: Long = 0L,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): TogetherStats {
        val openCutoff = effectiveOpenSessionCutoff(now, lastSeenAt, absenceTimeoutMillis)

        // Merges possibly-overlapping session intervals (e.g. a manually backfilled "today, 2h" entry
        // stacked on top of BLE-detected time already logged that day) into a normalized,
        // non-overlapping timeline before any duration math happens - so overlapping rows count real
        // elapsed time once, not twice, no matter how the overlap got there. This is read-side only;
        // nothing about how sessions are validated/inserted needs to change for this to be correct.
        val merged = mergedIntervals(sessions, openCutoff)
        if (merged.isEmpty()) {
            return TogetherStats(0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, null, 0)
        }

        val totalHoursAllTime = merged.sumOf { it.end - it.start } / 3_600_000.0

        val today = LocalDate.now(zone)
        val startOfWeek = today.with(java.time.DayOfWeek.MONDAY).atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfMonth = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val totalHoursThisWeek = merged.sumOf { clippedIntervalMillis(it, startOfWeek, now) } / 3_600_000.0
        val totalHoursThisMonth = merged.sumOf { clippedIntervalMillis(it, startOfMonth, now) } / 3_600_000.0

        val minutesPerDay = buildDailyMinuteMap(sessions, now, zone, lastSeenAt, absenceTimeoutMillis)

        // Every key buildDailyMinuteMap() inserts already corresponds to a day with at least one
        // genuine positive-duration segment (segmentEnd > cursor is guaranteed inside its loop), even if
        // that segment's floor-divided minute contribution is 0 (e.g. a session, or a day-spanning
        // tail, under 60 seconds). Qualifying on key membership - rather than requiring the summed
        // minutes to floor-round up to >= 1 - is what lets a real sub-minute-together day still count
        // toward the streak instead of silently breaking it.
        val qualifyingDays = minutesPerDay.keys.toSortedSet()
        val (currentDailyStreak, longestDailyStreak) = computeDailyStreaks(qualifyingDays, today)

        // Derived from qualifyingDays (not a separate floor-summed minutesPerWeek >= 1 check) for the
        // same reason as above: a week made up only of sub-minute-together days would otherwise floor
        // to a 0-minute week total and get wrongly excluded from the weekly streak too.
        val qualifyingWeeks = qualifyingDays.map { weekKeyOf(it) }.toSortedSet(compareBy({ it.first }, { it.second }))
        val currentWeekKey = weekKeyOf(today)
        val (currentWeeklyStreak, longestWeeklyStreak) = computeWeeklyStreaks(qualifyingWeeks, currentWeekKey)

        val longestSessionMinutes = merged.maxOf { it.end - it.start } / 60_000L

        val reunionCount = countReunions(merged)

        val favoriteDayOfWeek = minutesPerDay.entries
            .groupBy { it.key.dayOfWeek }
            .mapValues { (_, entries) -> entries.sumOf { it.value } }
            .maxByOrNull { it.value }
            ?.takeIf { it.value > 0 }
            ?.key

        return TogetherStats(
            totalHoursAllTime = totalHoursAllTime,
            totalHoursThisWeek = totalHoursThisWeek,
            totalHoursThisMonth = totalHoursThisMonth,
            currentDailyStreak = currentDailyStreak,
            longestDailyStreak = longestDailyStreak,
            currentWeeklyStreak = currentWeeklyStreak,
            longestWeeklyStreak = longestWeeklyStreak,
            longestSessionMinutes = longestSessionMinutes,
            reunionCount = reunionCount,
            favoriteDayOfWeek = favoriteDayOfWeek,
            totalDaysTogether = qualifyingDays.size
        )
    }

    private data class Interval(val start: Long, val end: Long)

    /** Sorts sessions by start, clamps any open one's end via [effectiveOpenSessionCutoff]-derived
     * [openCutoff], drops degenerate non-positive-duration rows, then merges overlapping/touching
     * intervals into a normalized non-overlapping timeline. Shared by [compute] and
     * [buildDailyMinuteMap] so both can never drift apart on how overlap is resolved. */
    private fun mergedIntervals(sessions: List<TogetherSession>, openCutoff: Long): List<Interval> {
        val raw = sessions
            .map { Interval(it.startedAt, it.endedAt ?: openCutoff) }
            .filter { it.end > it.start }
            .sortedBy { it.start }
        if (raw.isEmpty()) return emptyList()

        val merged = mutableListOf(raw.first())
        for (interval in raw.drop(1)) {
            val last = merged.last()
            if (interval.start <= last.end) {
                merged[merged.lastIndex] = last.copy(end = maxOf(last.end, interval.end))
            } else {
                merged.add(interval)
            }
        }
        return merged
    }

    private fun clippedIntervalMillis(interval: Interval, rangeStart: Long, rangeEnd: Long): Long {
        val start = maxOf(interval.start, rangeStart)
        val end = minOf(interval.end, rangeEnd)
        return (end - start).coerceAtLeast(0L)
    }

    /** Splits every (overlap-merged) together-interval across the calendar days it spans and sums
     * minutes-together per local day. Public so the Calendar screen can reuse it. */
    fun buildDailyMinuteMap(
        sessions: List<TogetherSession>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        /** See [compute]'s doc - forwarded to [effectiveOpenSessionCutoff] so an open session's minutes
         * here can't grow unbounded off a stale/orphaned row either. */
        lastSeenAt: Long = 0L,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): Map<LocalDate, Long> {
        val openCutoff = effectiveOpenSessionCutoff(now, lastSeenAt, absenceTimeoutMillis)
        val merged = mergedIntervals(sessions, openCutoff)
        val map = HashMap<LocalDate, Long>()
        for (interval in merged) {
            var cursor = interval.start
            while (cursor < interval.end) {
                val cursorDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor), zone)
                val day = cursorDateTime.toLocalDate()
                val dayEndMillis = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val segmentEnd = minOf(interval.end, dayEndMillis)
                val minutes = (segmentEnd - cursor) / 60_000L
                map[day] = (map[day] ?: 0L) + minutes
                cursor = segmentEnd
            }
        }
        return map
    }

    private fun computeDailyStreaks(qualifyingDays: Set<LocalDate>, today: LocalDate): Pair<Int, Int> {
        if (qualifyingDays.isEmpty()) return 0 to 0

        // Current streak: walk back from today, allowing "today" to still be in progress.
        var anchor = today
        if (anchor !in qualifyingDays) anchor = anchor.minusDays(1)
        var current = 0
        var cursor = anchor
        while (cursor in qualifyingDays) {
            current++
            cursor = cursor.minusDays(1)
        }

        // Longest streak ever: scan sorted days for the longest consecutive run.
        val sortedDays = qualifyingDays.sorted()
        var longest = 1
        var run = 1
        for (i in 1 until sortedDays.size) {
            run = if (sortedDays[i] == sortedDays[i - 1].plusDays(1)) run + 1 else 1
            if (run > longest) longest = run
        }
        longest = maxOf(longest, current)
        return current to longest
    }

    /** Key = (weekBasedYear, weekOfYear) using ISO week fields, so week boundaries are timezone/locale-stable. */
    private fun weekKeyOf(date: LocalDate): Pair<Int, Int> {
        val weekFields = WeekFields.ISO
        return date.get(weekFields.weekBasedYear()) to date.get(weekFields.weekOfWeekBasedYear())
    }

    private fun computeWeeklyStreaks(qualifyingWeeks: Set<Pair<Int, Int>>, currentWeekKey: Pair<Int, Int>): Pair<Int, Int> {
        if (qualifyingWeeks.isEmpty()) return 0 to 0

        fun previousWeekKey(key: Pair<Int, Int>): Pair<Int, Int> {
            // Approximate: step back 7 days from the first day of that ISO week.
            val approxDate = LocalDate.now().with(WeekFields.ISO.weekBasedYear(), key.first.toLong())
                .with(WeekFields.ISO.weekOfWeekBasedYear(), key.second.toLong())
                .with(WeekFields.ISO.dayOfWeek(), 1L)
            val prev = approxDate.minusWeeks(1)
            return WeekFields.ISO.let { wf -> prev.get(wf.weekBasedYear()) to prev.get(wf.weekOfWeekBasedYear()) }
        }

        var anchor = currentWeekKey
        if (anchor !in qualifyingWeeks) anchor = previousWeekKey(anchor)
        var current = 0
        var cursor = anchor
        while (cursor in qualifyingWeeks) {
            current++
            cursor = previousWeekKey(cursor)
        }

        val sortedWeeks = qualifyingWeeks.sortedWith(compareBy({ it.first }, { it.second }))
        var longest = 1
        var run = 1
        for (i in 1 until sortedWeeks.size) {
            val expectedPrev = previousWeekKey(sortedWeeks[i])
            run = if (expectedPrev == sortedWeeks[i - 1]) run + 1 else 1
            if (run > longest) longest = run
        }
        longest = maxOf(longest, current)
        return current to longest
    }

    private fun countReunions(mergedSorted: List<Interval>): Int {
        var count = 0
        for (i in 1 until mergedSorted.size) {
            val gap = mergedSorted[i].start - mergedSorted[i - 1].end
            if (gap >= REUNION_GAP_MILLIS) count++
        }
        return count
    }
}
