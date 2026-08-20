package com.ssbmedia.twogether.stats

import com.ssbmedia.twogether.ble.ProximityStateMachine
import com.ssbmedia.twogether.data.db.TogetherSession
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields

/** A calendar month plus a metric value for it - used by [TogetherStats.mostMetMonth] (value = distinct
 * together-days that month) and [TogetherStats.mostHoursMonth] (value = together-hours that month). */
data class MonthStat(val yearMonth: YearMonth, val value: Double)

/** A single calendar date plus a metric value - used by [TogetherStats.longestSingleDay] (hours). */
data class DayStat(val date: LocalDate, val hours: Double)

/** Simple up/down/flat trend signal - see [TogetherStats.monthTrend]'s doc for exactly what's compared. */
enum class Trend { UP, DOWN, FLAT }

/** A gap between two consecutive "meetups" - i.e. distinct together-days, the same qualifying-day
 * concept [TogetherStats.totalDaysTogether]/the streak stats use (see [StatsCalculator.dayGapsFromQualifyingDays]) -
 * with the actual millis range it spans (midnight of each day, in the caller's zone) so the UI can
 * render e.g. "12 days apart, March 3 – March 15, 2026". */
data class GapInfo(val days: Double, val startMillis: Long, val endMillis: Long)

/** An inclusive calendar-date span - used by [StatsCalculator.longestDailyStreakRange] /
 * [StatsCalculator.longestWeeklyStreakRange] so the Calendar screen can highlight exactly which days
 * made up a given streak, distinct from the plain streak LENGTH already in [TogetherStats]. */
data class DateRange(val start: LocalDate, val end: LocalDate)

/** One calendar month's activity - used by the Stats screen's monthly drill-down chart (Feature 1) so
 * it can render a continuous month-by-month timeline (including zero-activity months in between),
 * unlike [TogetherStats.mostMetMonth]/[TogetherStats.mostHoursMonth] which only expose the single best
 * month. */
data class MonthlyBreakdown(val yearMonth: YearMonth, val daysMet: Int, val hours: Double)

/** Together-time on today's exact calendar date (same month+day) in a previous year - e.g. "on this
 * day in 2025, you spent 3.2 hours together." Used by Home's "on this day" callback card. */
data class OnThisDayInfo(val year: Int, val hours: Double)

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
    /** Count of ISO weeks where ALL 7 days were qualifying days (see [buildDailyMinuteMap]) - stricter
     * than the daily/weekly streak stats above, which only need a consecutive RUN of days/weeks: a
     * perfect week demands literally every single day of that week have together-time, not just enough
     * consecutive days to form a streak. */
    val perfectWeekCount: Int,
    val favoriteDayOfWeek: DayOfWeek?,
    /** Total distinct calendar days with any together-time at all (BLE-detected or manually backfilled).
     * Same qualifying-day set the Calendar screen highlights - computed once here off the same
     * buildDailyMinuteMap() so the two screens can never drift apart. */
    val totalDaysTogether: Int,
    // ---- Feature E: more stats, all derived from the same merged/deduped session data above ----
    /** The calendar month with the most distinct together-days. */
    val mostMetMonth: MonthStat?,
    /** The calendar month with the most cumulative together-hours. */
    val mostHoursMonth: MonthStat?,
    /** The single calendar day with the most together-hours. */
    val longestSingleDay: DayStat?,
    /** The date of the very first-ever recorded session (BLE-detected or manual), across all history -
     * computed from raw session start times, not the merged/clamped timeline, so it's never affected by
     * how open sessions get clamped. */
    val togetherSince: LocalDate?,
    /** This month's hours-so-far vs the SAME elapsed window of last month (e.g. "first 9 days of this
     * month" vs "first 9 days of last month") - not this-month-partial vs last-month-total, which would
     * unfairly always trend down until the month is almost over. UP/DOWN uses a 5% deadband either side
     * so tiny noise doesn't flip-flop the label. */
    val monthTrend: Trend,
    /** Phase 1 item 5 of UX-FIX-PLAN.md: the actual day-count delta behind [monthTrend], in the user's
     * own explicitly-requested unit (DAYS, not hours) - "this month's distinct together-days so far" vs
     * "the same elapsed window last month" (the exact same day-count comparison [monthTrend] makes in
     * hours, just counted in qualifying days instead). Positive = more days met so far this month than
     * the same point last month, negative = fewer, 0 = the same. Independent of [monthTrend]'s own
     * hours-based 5%-deadband UP/DOWN/FLAT classification - the two CAN disagree at the margins (e.g.
     * fewer, but longer, meetups this month), which is expected since they measure different things. */
    val monthTrendDeltaDays: Int,
    /** Average calendar-day gap between consecutive distinct together-days (null if fewer than 2
     * together-days exist yet). A "meetup" is one distinct calendar day with ANY together-time - the
     * exact same qualifying-day set [totalDaysTogether]/the streak stats use - so meeting twice in one
     * day (e.g. a morning session and an evening session, however far apart) always counts as a single
     * meetup/day, never two. */
    val avgDaysBetweenMeetups: Double?,
    /** The single longest gap, in calendar days, between two consecutive together-days, with the actual
     * date range it spans. */
    val longestApart: GapInfo?
)


/** Arbitrary fixed date used only as a field-template base in computeWeeklyStreaks' previousWeekKey -
 * see its doc for why this must NOT be LocalDate.now(). Any date works; this one is a plain, safely
 * mid-week/mid-year Monday. */
private val FIXED_WEEK_ANCHOR: LocalDate = LocalDate.of(2000, 1, 3)

object StatsCalculator {

    /** LEGACY-ONLY as of the user-configurable reunion threshold feature (see
     * [com.ssbmedia.twogether.data.datastore.AppSettings.reunionThresholdMinutes]'s own doc). This used to
     * be the single live threshold ProximityForegroundService's celebration check AND [countReunions]'s
     * full-history rescan both read - now it is ONLY ever used by [legacyReunionCountForBackfill], the
     * one-time migration that seeds an existing install's persisted reunionCount (see
     * ProximityPersistedState.reunionCount/reunionCountBackfilled's docs). It must stay a fixed constant,
     * never itself become user-editable - it exists specifically to represent "the one threshold that
     * genuinely applied to 100% of this app's history before the live threshold became configurable",
     * which is what makes that one-time rescan unambiguous. All LIVE (post-migration) reunion detection
     * now reads AppSettings.reunionThresholdMinutes instead - see
     * ProximityForegroundService.handleBecameTogether.
     *
     * Item 10 (UX-FIX-PLAN.md): this used to ALSO require the apart-start and the reunion itself to fall
     * on the same calendar day (see the now-removed isSameCalendarDay check in [countReunions]), which
     * meant a couple apart overnight (goodnight -> next morning) or across several days never counted as
     * a reunion at all, however long the real gap was - clearly wrong. The gap threshold alone is now the
     * only requirement. */
    const val REUNION_GAP_MILLIS = 60 * 60 * 1000L

    /**
     * The effective end timestamp to credit an open (endedAt == null) session up to. Mirrors the SAME
     * clamp every WRITE path applies when it actually closes a session (see
     * ProximityForegroundService.handleBecameApart / selfHealOrphanedSession / SettingsScreen.unpair):
     * an open session's credited duration must never run past the newest CONFIRMED sighting of the
     * partner plus the absence timeout.
     *
     * Without this, any READ of an open session (stats totals, capsule-unlock eligibility, badge
     * progress, calendar day totals) that naively substitutes "now" for a missing endedAt would let a
     * stale/orphaned open row (service killed, BLE permission revoked so it never restarts to self-heal,
     * a reboot, etc) silently "grow" forever every single time anything reads it - which is especially
     * dangerous for Time Capsules, since crossing a threshold there performs a real, irreversible write
     * (unlockedAt).
     *
     * "Newest confirmed sighting" is `maxOf(lastSeenAt, startedAt)`, NOT lastSeenAt alone. [startedAt]
     * is itself a confirmed sighting timestamp: a BLE session row only ever gets created from
     * ProximityForegroundService.handleBecameTogether(now), which runs immediately after
     * ProximityStateMachine.onBeaconSeen(now) confirmed the partner's beacon at that exact instant. That
     * matters because [lastSeenAt] can legitimately be 0/absent while an open session row still exists -
     * most importantly right after BackupManager.restoreBackup() writes a backed-up open session onto a
     * phone whose proximity DataStore is still at its defaults (restore deliberately does not restore
     * proximity state, and a replacement phone has none), and equally after DataStores.kt's
     * ReplaceFileCorruptionHandler resets a torn proximity_state file to emptyPreferences(). This used to
     * fall back to a completely UNCLAMPED "now", which credited the entire wall-clock gap between when
     * the backup was taken and when it was restored as real together-time (verified: a 30-day-old open
     * session reported 723.5h and irreversibly unlocked every time capsule up to 500h). Falling back to
     * startedAt keeps the fallback bounded by actual evidence instead.
     */
    fun effectiveOpenSessionEnd(
        startedAt: Long,
        now: Long,
        lastSeenAt: Long,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): Long = minOf(now, maxOf(lastSeenAt, startedAt) + absenceTimeoutMillis)

    fun compute(
        sessions: List<TogetherSession>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        /** Latest confirmed partner sighting (ProximityPersistedState.lastSeenAt), used to clamp an
         * open session's live duration - see [effectiveOpenSessionEnd]. Pass 0L only when this
         * information genuinely isn't available yet; that case is still bounded by the session's own
         * startedAt rather than running unclamped to [now]. */
        lastSeenAt: Long = 0L,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS,
        /** Reunion-count non-retroactivity feature: the couple's lifetime reunion count, taken VERBATIM
         * from the caller's own ProximityPersistedState.reunionCount (a persisted, live-incrementing
         * counter - see its own doc) rather than computed here. This used to be `countReunions(merged)`,
         * rescanning the ENTIRE session history on every single call with whatever the single global
         * threshold constant happened to be - fundamentally incompatible with a user-editable threshold,
         * since a later threshold change would silently reinterpret (and change the count of) reunions
         * that already happened and were already celebrated under a DIFFERENT threshold. Defaults to 0
         * only for callers that don't care about this field (e.g. [manualHoursCredit]'s two internal
         * `compute()` calls, which only ever read totalHoursAllTime) - every real UI call site must pass
         * its own ProximityPersistedState's reunionCount explicitly. */
        reunionCount: Int = 0
    ): TogetherStats {

        // Computed from raw session start times (not the merged/clamped timeline below) so it reflects
        // the true first-ever recorded moment regardless of how open sessions get clamped elsewhere.
        val togetherSince = sessions.minByOrNull { it.startedAt }
            ?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.startedAt), zone).toLocalDate() }

        // Merges possibly-overlapping session intervals (e.g. a manually backfilled "today, 2h" entry
        // stacked on top of BLE-detected time already logged that day) into a normalized,
        // non-overlapping timeline before any duration math happens - so overlapping rows count real
        // elapsed time once, not twice, no matter how the overlap got there. This is read-side only;
        // nothing about how sessions are validated/inserted needs to change for this to be correct.
        val merged = mergedIntervals(sessions, now, lastSeenAt, absenceTimeoutMillis)
        if (merged.isEmpty()) {
            return TogetherStats(
                totalHoursAllTime = 0.0, totalHoursThisWeek = 0.0, totalHoursThisMonth = 0.0,
                currentDailyStreak = 0, longestDailyStreak = 0, currentWeeklyStreak = 0, longestWeeklyStreak = 0,
                // reunionCount is still the passed-in persisted counter here, not 0 - see the parameter's
                // own doc: it's never derived from `merged`, so an empty session list (nothing left after
                // filtering/clamping) must not force it to 0 either.
                longestSessionMinutes = 0, reunionCount = reunionCount, perfectWeekCount = 0, favoriteDayOfWeek = null, totalDaysTogether = 0,
                mostMetMonth = null, mostHoursMonth = null, longestSingleDay = null, togetherSince = togetherSince,
                monthTrend = Trend.FLAT, monthTrendDeltaDays = 0, avgDaysBetweenMeetups = null, longestApart = null
            )
        }

        val totalHoursAllTime = merged.sumOf { it.end - it.start } / 3_600_000.0

        // Deliberately derived from the `now` parameter (not LocalDate.now(zone), the real wall clock)
        // so this whole function is a pure function of its inputs and "today" can never race ahead of
        // `now` itself. A caller (e.g. HomeScreen.kt) may pass a `now` that's a few/tens of seconds stale
        // versus the real clock (its ticker only refreshes every 30s) - if `today` were computed from the
        // TRUE wall clock instead, then right after any ISO-week or month boundary tick, startOfWeek/
        // startOfMonth would already reflect the new week/month while `now` (the clip range's upper
        // bound below) still reflected the old one, making rangeEnd < rangeStart for every interval and
        // silently reporting 0.0 for totalHoursThisWeek/totalHoursThisMonth even though the couple has
        // real together-time already inside the new week/month - a real, reproducible under-count on
        // every single week rollover. See StatsCalculatorAuditTest's "BUG - totalHoursThisWeek..." test.
        val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate()
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

        // Stricter than the weekly streak above: a week only counts here if EVERY one of its 7 days is
        // a qualifying day, not merely that the week itself has some together-time. weekKeyToMonday is
        // the same Monday-anchoring longestWeeklyStreakRange already uses, so this can't drift from how
        // a week's real calendar dates are resolved elsewhere.
        val perfectWeekCount = qualifyingWeeks.count { weekKey ->
            val monday = weekKeyToMonday(weekKey)
            (0..6).all { monday.plusDays(it.toLong()) in qualifyingDays }
        }

        val longestSessionMinutes = merged.maxOf { it.end - it.start } / 60_000L

        // reunionCount is NOT computed here (was `countReunions(merged)` - a full-history rescan run on
        // EVERY call) - it's the `reunionCount` parameter, passed straight through from the caller's own
        // ProximityPersistedState.reunionCount. See that parameter's own doc for why this function must
        // never derive it from `merged` any more.

        val favoriteDayOfWeek = minutesPerDay.entries
            .groupBy { it.key.dayOfWeek }
            .mapValues { (_, entries) -> entries.sumOf { it.value } }
            .maxByOrNull { it.value }
            ?.takeIf { it.value > 0 }
            ?.key

        // ---- Feature E ----

        val monthlyStats = monthlyBreakdownFromDailyMap(minutesPerDay)
        val mostMetMonth = monthlyStats.maxByOrNull { it.daysMet }?.let { MonthStat(it.yearMonth, it.daysMet.toDouble()) }
        val mostHoursMonth = monthlyStats.maxByOrNull { it.hours }?.let { MonthStat(it.yearMonth, it.hours) }

        val longestSingleDay = minutesPerDay.maxByOrNull { it.value }?.let { DayStat(it.key, it.value / 60.0) }

        val elapsedIntoMonth = (now - startOfMonth).coerceAtLeast(0L)
        val lastMonthStart = today.minusMonths(1).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val hoursThisMonthSoFar = merged.sumOf { clippedIntervalMillis(it, startOfMonth, startOfMonth + elapsedIntoMonth) } / 3_600_000.0
        // Cap the "last month" window's end at startOfMonth (this month's start, i.e. last month's real
        // exclusive end) - NEVER let it run past into this month. Without this cap, on any day-of-month
        // that's further in than the previous (shorter) month's total length - e.g. "now" = March 30
        // (elapsedIntoMonth ~29.5 days) compared against February (only 28 days) - lastMonthStart +
        // elapsedIntoMonth spills past Feb's real end and back into March itself, double-attributing
        // THIS month's own hours into the "last month" figure too and silently hiding a genuine increase
        // (or fabricating a decrease) behind a wrongly-inflated "last month" number.
        val lastMonthWindowEnd = minOf(lastMonthStart + elapsedIntoMonth, startOfMonth)
        val hoursLastMonthSameWindow = merged.sumOf { clippedIntervalMillis(it, lastMonthStart, lastMonthWindowEnd) } / 3_600_000.0
        val monthTrend = when {
            hoursThisMonthSoFar > hoursLastMonthSameWindow * 1.05 -> Trend.UP
            hoursThisMonthSoFar < hoursLastMonthSameWindow * 0.95 -> Trend.DOWN
            else -> Trend.FLAT
        }

        // Same "this month so far" vs "the same elapsed window last month" comparison as monthTrend
        // above, just counted in distinct qualifying DAYS instead of hours (user's own explicit ask -
        // see monthTrendDeltaDays' own doc). Deliberately worked out directly in whole calendar days
        // (not by converting the millis-based hours window above back into dates) - the elapsed-hours
        // window's boundaries fall mid-day, and re-deriving day counts from a mid-day millis cutoff would
        // need its own off-by-one reasoning; counting calendar days directly is both simpler and exactly
        // matches what "N days met" means to begin with.
        val thisMonthWindowStartDate = today.withDayOfMonth(1)
        val daysElapsedThisMonth = ChronoUnit.DAYS.between(thisMonthWindowStartDate, today) + 1
        val lastMonthWindowStartDate = today.minusMonths(1).withDayOfMonth(1)
        val lastMonthLengthDays = YearMonth.from(lastMonthWindowStartDate).lengthOfMonth().toLong()
        // Never let the "last month" window run longer than last month actually was - same overrun
        // guard as hoursLastMonthSameWindow's own lastMonthWindowEnd cap above, just in day units.
        val lastMonthWindowLengthDays = daysElapsedThisMonth.coerceAtMost(lastMonthLengthDays)
        val lastMonthWindowEndDateInclusive = lastMonthWindowStartDate.plusDays(lastMonthWindowLengthDays - 1)
        val daysThisMonthSoFar = qualifyingDays.count { !it.isBefore(thisMonthWindowStartDate) && !it.isAfter(today) }
        val daysLastMonthSameWindow = qualifyingDays.count {
            !it.isBefore(lastMonthWindowStartDate) && !it.isAfter(lastMonthWindowEndDateInclusive)
        }
        val monthTrendDeltaDays = daysThisMonthSoFar - daysLastMonthSameWindow

        val gaps = dayGapsFromQualifyingDays(qualifyingDays.toList(), zone)
        val avgDaysBetweenMeetups = if (gaps.isNotEmpty()) gaps.map { it.days }.average() else null
        val longestApart = gaps.maxByOrNull { it.days }

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
            perfectWeekCount = perfectWeekCount,
            favoriteDayOfWeek = favoriteDayOfWeek,
            totalDaysTogether = qualifyingDays.size,
            mostMetMonth = mostMetMonth,
            mostHoursMonth = mostHoursMonth,
            longestSingleDay = longestSingleDay,
            togetherSince = togetherSince,
            monthTrend = monthTrend,
            monthTrendDeltaDays = monthTrendDeltaDays,
            avgDaysBetweenMeetups = avgDaysBetweenMeetups,
            longestApart = longestApart
        )
    }

    /** How many of the couple's cumulative together-hours are attributable to manually-backfilled
     * (isManual) sessions rather than genuine BLE detections - i.e. totalHoursAllTime(everything) minus
     * totalHoursAllTime(BLE-detected only). Computed as a difference of two already-interval-merged
     * totals (not a raw sum of manual sessions' own durations) so a manual entry that happens to overlap
     * a real BLE-detected session covering the same moment is correctly NOT double-credited - both
     * totals already dedupe overlap the same way, so only the genuinely "extra" manual contribution
     * survives the subtraction. Used exclusively by Time Capsules' auto-adjusting anti-cheat threshold -
     * see TimeCapsuleRepository.unlockEligible's doc for why this exact quantity is what keeps manual
     * backfill from ever being able to accelerate an unlock. */
    fun manualHoursCredit(
        sessions: List<TogetherSession>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        lastSeenAt: Long = 0L,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): Float {
        val all = compute(sessions, now, zone, lastSeenAt, absenceTimeoutMillis).totalHoursAllTime
        val verifiedOnly = compute(sessions.filter { !it.isManual }, now, zone, lastSeenAt, absenceTimeoutMillis).totalHoursAllTime
        return (all - verifiedOnly).toFloat()
    }

    private data class Interval(val start: Long, val end: Long)

    /** Sorts sessions by start, clamps any open one's end via [effectiveOpenSessionEnd] (per row, since
     * the fallback bound depends on that row's own startedAt), drops degenerate non-positive-duration
     * rows, then merges overlapping/touching intervals into a normalized non-overlapping timeline.
     * Shared by [compute] and [buildDailyMinuteMap] so both can never drift apart on how overlap is
     * resolved. */
    private fun mergedIntervals(
        sessions: List<TogetherSession>,
        now: Long,
        lastSeenAt: Long,
        absenceTimeoutMillis: Long
    ): List<Interval> {
        // MINOR fix, defense-in-depth (ultimate-app-review, round-2 re-verification, Sonnet): every
        // ingestion path (GattSyncManager's wire sync, BackupManager's restore) already validates a
        // session's bounds through SessionBoundsValidator before it ever reaches local storage - but
        // this function reads whatever is ALREADY in the local table, with no floor of its own. A row
        // that reached the table some other way (a future bug in either ingestion path, or literal
        // on-device root/`run-as` tampering with this app's own database - live-reproduced during
        // round-2 testing, requires already having root on your own phone, same threat-model boundary
        // this app already accepts elsewhere) still reached buildDailyMinuteMap's one-calendar-day-at-a-
        // time walk unfiltered, reproducing the exact unrecoverable OutOfMemoryError crash-loop the
        // ingestion-side fixes were meant to eliminate. This is the last line of defense, not the
        // primary fix - a row failing this filter is silently excluded from stats rather than being
        // "fixed" (there's no correct value to guess at this layer), same fail-safe direction as an
        // ingestion path rejecting an implausible row outright.
        //
        // Deliberately does NOT also reject a start/end merely ahead of the `now` parameter (unlike the
        // ingestion-side validators) - `now` here is caller-supplied and can be legitimately stale by
        // more than a few minutes relative to real wall-clock time (see "today is derived from the now
        // parameter..." below, an intentional B75-class rollover fix that depends on real sessions being
        // allowed to sit ahead of a stale `now`). Only catches the OVERFLOW-CLASS garbage this layer
        // exists to stop: a startedAt/endedAt so far outside any real calendar date, or a duration so
        // long, that no legitimate together-session could ever produce it - not "unusually recent."
        val raw = sessions
            .map { Interval(it.startedAt, it.endedAt ?: effectiveOpenSessionEnd(it.startedAt, now, lastSeenAt, absenceTimeoutMillis)) }
            .filter { it.end > it.start }
            .filter {
                it.start >= com.ssbmedia.twogether.util.SessionBoundsValidator.MIN_PLAUSIBLE_TIMESTAMP_MILLIS &&
                    it.end >= com.ssbmedia.twogether.util.SessionBoundsValidator.MIN_PLAUSIBLE_TIMESTAMP_MILLIS &&
                    (it.end - it.start) <= com.ssbmedia.twogether.ble.GattSyncManager.MAX_PLAUSIBLE_SESSION_DURATION_MILLIS
            }
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
        /** See [compute]'s doc - forwarded to [effectiveOpenSessionEnd] so an open session's minutes
         * here can't grow unbounded off a stale/orphaned row either. */
        lastSeenAt: Long = 0L,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): Map<LocalDate, Long> {
        val merged = mergedIntervals(sessions, now, lastSeenAt, absenceTimeoutMillis)
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

    /** UX-FIX-PLAN.md Phase 2 item 12: finds a [TogetherSession] whose window contains [at] (inclusive
     * start, exclusive end - same convention [buildDailyMinuteMap]'s day-splitting already uses), so a
     * gallery-imported photo can be auto-detected as "taken while together" instead of always defaulting
     * to apart. An open session's end is clamped via [effectiveOpenSessionEnd], same as every other read
     * of an open session's duration elsewhere in this file - so a stale/orphaned open row can't claim an
     * arbitrarily-far-future photo as "together" either. Returns the first match; overlapping sessions
     * covering the same instant are a real (if rare) possibility this app already tolerates elsewhere
     * (see [mergedIntervals]'s own doc), and any one of them is an equally correct answer to "was there
     * together-time at this instant". */
    fun sessionContaining(
        sessions: List<TogetherSession>,
        at: Long,
        now: Long = System.currentTimeMillis(),
        lastSeenAt: Long = 0L,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): TogetherSession? = sessions.firstOrNull { s ->
        val end = s.endedAt ?: effectiveOpenSessionEnd(s.startedAt, now, lastSeenAt, absenceTimeoutMillis)
        at >= s.startedAt && at < end
    }

    /** Finds the most recent PAST year in which today's month+day had any together-time, using the same
     * per-day minute map the Calendar screen and [compute] both already use - see [buildDailyMinuteMap].
     * Returns null if there's no matching day in any earlier year (including the common case of a couple
     * who's simply not used the app long enough yet). */
    fun onThisDayPreviousYear(
        sessions: List<TogetherSession>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        lastSeenAt: Long = 0L,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): OnThisDayInfo? {
        val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate()
        val map = buildDailyMinuteMap(sessions, now, zone, lastSeenAt, absenceTimeoutMillis)
        return map.entries
            .filter { it.key.year < today.year && it.key.monthValue == today.monthValue && it.key.dayOfMonth == today.dayOfMonth && it.value > 0L }
            .maxByOrNull { it.key.year }
            ?.let { OnThisDayInfo(it.key.year, it.value / 60.0) }
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

    // Step back 7 days from the Monday of that ISO week. Anchored on a FIXED reference date (not
    // LocalDate.now()/today) so this is a pure function of `key` alone - using the real wall clock as a
    // throwaway "template" date here was harmless in practice (the weekBasedYear/weekOfWeekBasedYear/
    // dayOfWeek fields set below fully determine the result) but needlessly tied this to the system
    // default zone, which could differ from the `zone` this whole StatsCalculator.compute() call was
    // asked to use. Hoisted to a top-level function (rather than local to computeWeeklyStreaks) so
    // [longestWeeklyStreakRange] can walk the same week-key arithmetic when locating a streak's actual
    // calendar dates.
    private fun previousWeekKey(key: Pair<Int, Int>): Pair<Int, Int> {
        val approxDate = FIXED_WEEK_ANCHOR.with(WeekFields.ISO.weekBasedYear(), key.first.toLong())
            .with(WeekFields.ISO.weekOfWeekBasedYear(), key.second.toLong())
            .with(WeekFields.ISO.dayOfWeek(), 1L)
        val prev = approxDate.minusWeeks(1)
        return WeekFields.ISO.let { wf -> prev.get(wf.weekBasedYear()) to prev.get(wf.weekOfWeekBasedYear()) }
    }

    /** The Monday of the given ISO week key, as a real calendar date - see [previousWeekKey]'s doc for
     * why [FIXED_WEEK_ANCHOR] is a safe, zone-independent template date to derive it from. */
    private fun weekKeyToMonday(key: Pair<Int, Int>): LocalDate =
        FIXED_WEEK_ANCHOR.with(WeekFields.ISO.weekBasedYear(), key.first.toLong())
            .with(WeekFields.ISO.weekOfWeekBasedYear(), key.second.toLong())
            .with(WeekFields.ISO.dayOfWeek(), 1L)

    private fun computeWeeklyStreaks(qualifyingWeeks: Set<Pair<Int, Int>>, currentWeekKey: Pair<Int, Int>): Pair<Int, Int> {
        if (qualifyingWeeks.isEmpty()) return 0 to 0

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

    /** A reunion is an apart-gap of at least [REUNION_GAP_MILLIS] between the apart-start (previous
     * session's end) and the reunion itself (next session's start) - e.g. apart for a 2-hour lunch
     * break, back together that afternoon. Meeting multiple times in one day (morning + afternoon +
     * evening, each separated by a real gap) counts a reunion for each such gap.
     *
     * Item 10 (UX-FIX-PLAN.md) fix: this used to ALSO require the gap to fall within a single calendar
     * day, which meant an overnight gap (goodnight -> next morning) or a multi-day apart stretch never
     * counted as a reunion no matter how long the real gap was - clearly wrong, since the whole point of
     * the gap threshold is to detect a genuine apart-then-back-together event. The same-day check (and
     * the [zone] it needed) is removed entirely; only the gap threshold remains.
     *
     * LEGACY-ONLY as of the user-configurable reunion threshold feature: [compute] no longer calls this on
     * its normal path (it now takes reunionCount as a plain parameter - see its own doc) - this full-
     * history rescan survives ONLY as [legacyReunionCountForBackfill]'s implementation, for the one-time
     * migration that seeds an existing install's persisted counter. Never call this directly from any new
     * code path; a rescan against the CURRENT live threshold is exactly the retroactive recomputation this
     * whole feature exists to prevent. */
    private fun countReunions(mergedSorted: List<Interval>): Int {
        var count = 0
        for (i in 1 until mergedSorted.size) {
            val gap = mergedSorted[i].start - mergedSorted[i - 1].end
            if (gap >= REUNION_GAP_MILLIS) count++
        }
        return count
    }

    /** One-time backfill helper for an EXISTING install adopting the user-configurable reunion threshold
     * feature - see [ProximityPersistedState][com.ssbmedia.twogether.data.datastore.ProximityPersistedState]
     * .reunionCount/.reunionCountBackfilled's own docs, and
     * ProximityForegroundService.backfillReunionCountIfNeeded (the sole caller). Rescans the couple's ENTIRE
     * session history exactly once, against the fixed legacy [REUNION_GAP_MILLIS] (60 min) - the only
     * threshold that has EVER applied to any of this app's history before this feature shipped, so this one
     * rescan is genuinely unambiguous, unlike a rescan against whatever the live, user-editable threshold
     * happens to be today. Must never be called again after the caller's backfill flag is set - repurposes
     * the same [mergedIntervals]/[countReunions] machinery [compute] used to call on every single
     * invocation, rather than reimplementing equivalent gap-counting logic a second time. */
    fun legacyReunionCountForBackfill(
        sessions: List<TogetherSession>,
        now: Long = System.currentTimeMillis(),
        lastSeenAt: Long = 0L,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): Int = countReunions(mergedIntervals(sessions, now, lastSeenAt, absenceTimeoutMillis))

    /** Turns a sorted list of distinct together-days (the same qualifying-day concept used for
     * [totalDaysTogether]/streaks - see [buildDailyMinuteMap]) into the gap list between each
     * consecutive pair, as whole calendar days (via [ChronoUnit.DAYS], never a sub-day fraction, since a
     * "meetup" is now purely a calendar day and not a session-gap cluster). [startMillis]/[endMillis]
     * are each day's local midnight so the UI can render the exact date range. Shared by [compute]
     * (which already has qualifyingDays in hand) and the public [computeMeetupGaps] below - both must
     * resolve to the exact same gap list, never two independently re-derived ones. */
    private fun dayGapsFromQualifyingDays(sortedDays: List<LocalDate>, zone: ZoneId): List<GapInfo> {
        if (sortedDays.size < 2) return emptyList()
        return (1 until sortedDays.size).map {
            val prevDay = sortedDays[it - 1]
            val nextDay = sortedDays[it]
            GapInfo(
                ChronoUnit.DAYS.between(prevDay, nextDay).toDouble(),
                prevDay.atStartOfDay(zone).toInstant().toEpochMilli(),
                nextDay.atStartOfDay(zone).toInstant().toEpochMilli()
            )
        }
    }

    /** Every gap between consecutive distinct together-days (see [dayGapsFromQualifyingDays]'s doc), not
     * just the single longest one [TogetherStats.longestApart] already exposes - powers the Stats
     * screen's "Longest apart" / "Avg. days between meetups" drill-down timeline. */
    fun computeMeetupGaps(
        sessions: List<TogetherSession>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        lastSeenAt: Long = 0L,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): List<GapInfo> = dayGapsFromQualifyingDays(
        buildDailyMinuteMap(sessions, now, zone, lastSeenAt, absenceTimeoutMillis).keys.sorted(),
        zone
    )

    /** Shared by [compute]'s mostMetMonth/mostHoursMonth and the public [computeMonthlyBreakdown] below -
     * just the per-month grouping, no gap-filling (that's [computeMonthlyBreakdown]'s job), so both stay
     * derived from the exact same per-day map. */
    private fun monthlyBreakdownFromDailyMap(minutesPerDay: Map<LocalDate, Long>): List<MonthlyBreakdown> {
        val hoursByMonth = HashMap<YearMonth, Double>()
        val daysByMonth = HashMap<YearMonth, Int>()
        minutesPerDay.forEach { (date, minutes) ->
            val ym = YearMonth.from(date)
            hoursByMonth[ym] = (hoursByMonth[ym] ?: 0.0) + minutes / 60.0
            daysByMonth[ym] = (daysByMonth[ym] ?: 0) + 1
        }
        return hoursByMonth.keys.map { MonthlyBreakdown(it, daysByMonth[it] ?: 0, hoursByMonth[it] ?: 0.0) }
    }

    /** Every calendar month from the couple's very first together-day through the current month
     * (inclusive), with zero-activity months filled in so the Stats screen's monthly drill-down chart can
     * render a continuous timeline rather than only the single best month. Sorted oldest-first. */
    fun computeMonthlyBreakdown(
        sessions: List<TogetherSession>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        lastSeenAt: Long = 0L,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): List<MonthlyBreakdown> {
        val minutesPerDay = buildDailyMinuteMap(sessions, now, zone, lastSeenAt, absenceTimeoutMillis)
        if (minutesPerDay.isEmpty()) return emptyList()
        val byMonth = monthlyBreakdownFromDailyMap(minutesPerDay).associateBy { it.yearMonth }
        val firstMonth = minutesPerDay.keys.minOf { YearMonth.from(it) }
        val lastMonth = YearMonth.from(LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate())
        val out = mutableListOf<MonthlyBreakdown>()
        var cursor = firstMonth
        while (!cursor.isAfter(lastMonth)) {
            out.add(byMonth[cursor] ?: MonthlyBreakdown(cursor, 0, 0.0))
            cursor = cursor.plusMonths(1)
        }
        return out
    }

    /** The actual calendar-date span of [TogetherStats.longestDailyStreak] (not just its length), so the
     * Calendar screen can jump to and highlight exactly those days. Ties (multiple runs sharing the same
     * max length) resolve to the MOST RECENT run. */
    fun longestDailyStreakRange(
        sessions: List<TogetherSession>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        lastSeenAt: Long = 0L,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): DateRange? {
        val qualifyingDays = buildDailyMinuteMap(sessions, now, zone, lastSeenAt, absenceTimeoutMillis).keys.sorted()
        if (qualifyingDays.isEmpty()) return null
        var bestStart = qualifyingDays[0]
        var bestEnd = qualifyingDays[0]
        var bestLen = 1
        var curStart = qualifyingDays[0]
        var curLen = 1
        for (i in 1 until qualifyingDays.size) {
            if (qualifyingDays[i] == qualifyingDays[i - 1].plusDays(1)) curLen++ else { curStart = qualifyingDays[i]; curLen = 1 }
            if (curLen >= bestLen) { bestLen = curLen; bestStart = curStart; bestEnd = qualifyingDays[i] }
        }
        return DateRange(bestStart, bestEnd)
    }

    /** Same idea as [longestDailyStreakRange] but for [TogetherStats.longestWeeklyStreak] - resolves the
     * winning run of consecutive ISO weeks back to real calendar dates (Monday of its first week through
     * Sunday of its last). Ties resolve to the MOST RECENT run. */
    fun longestWeeklyStreakRange(
        sessions: List<TogetherSession>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        lastSeenAt: Long = 0L,
        absenceTimeoutMillis: Long = ProximityStateMachine.DEFAULT_ABSENCE_TIMEOUT_MILLIS
    ): DateRange? {
        val qualifyingDays = buildDailyMinuteMap(sessions, now, zone, lastSeenAt, absenceTimeoutMillis).keys
        if (qualifyingDays.isEmpty()) return null
        val sortedWeeks = qualifyingDays.map { weekKeyOf(it) }.toSortedSet(compareBy({ it.first }, { it.second })).toList()
        var bestStart = sortedWeeks[0]
        var bestEnd = sortedWeeks[0]
        var bestLen = 1
        var curStart = sortedWeeks[0]
        var curLen = 1
        for (i in 1 until sortedWeeks.size) {
            if (previousWeekKey(sortedWeeks[i]) == sortedWeeks[i - 1]) curLen++ else { curStart = sortedWeeks[i]; curLen = 1 }
            if (curLen >= bestLen) { bestLen = curLen; bestStart = curStart; bestEnd = sortedWeeks[i] }
        }
        return DateRange(weekKeyToMonday(bestStart), weekKeyToMonday(bestEnd).plusDays(6))
    }
}
