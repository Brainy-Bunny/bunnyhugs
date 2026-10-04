package com.ssbmedia.twogether.stats

import com.ssbmedia.twogether.data.db.TogetherSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Day start (AppSettings.dayStartHour, 4 AM default): a session that runs past midnight but not past the
 * day start must count as ONE day, not two. Covers the logical-day mapping, the per-day minute split, and
 * the Home "together today" window.
 */
class DayStartHourTest {
    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun t(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ist).toInstant().toEpochMilli()

    private fun session(start: Long, end: Long) = TogetherSession(startedAt = start, endedAt = end)

    @Test
    fun logicalDay_oneAmBelongsToPreviousDay_whenStartIs4Am() {
        // 1:00 AM on 2026-10-04 is still the day that started 2026-10-03 at 4 AM.
        assertEquals(LocalDate.of(2026, 10, 3), StatsCalculator.logicalDayOf(t(2026, 10, 4, 1, 0), ist, 4))
        // 4:30 AM is already the new day.
        assertEquals(LocalDate.of(2026, 10, 4), StatsCalculator.logicalDayOf(t(2026, 10, 4, 4, 30), ist, 4))
    }

    @Test
    fun logicalDay_startZero_isCalendarMidnight() {
        assertEquals(LocalDate.of(2026, 10, 4), StatsCalculator.logicalDayOf(t(2026, 10, 4, 1, 0), ist, 0))
    }

    @Test
    fun logicalDayStart_isTheConfiguredHourOfThatDay() {
        val expected = t(2026, 10, 3, 4, 0)
        assertEquals(expected, StatsCalculator.logicalDayStartMillis(LocalDate.of(2026, 10, 3), ist, 4))
    }

    @Test
    fun lateNightDate_pastMidnightButBeforeDayStart_isOneDay() {
        // Friday 11 PM to Saturday 1:30 AM: one date night, one day together with the 4 AM start.
        val date = session(t(2026, 10, 2, 23, 0), t(2026, 10, 3, 1, 30))
        val map = StatsCalculator.buildDailyMinuteMap(listOf(date), zone = ist, dayStartHour = 4)
        assertEquals(setOf(LocalDate.of(2026, 10, 2)), map.keys)
        assertEquals(150L, map[LocalDate.of(2026, 10, 2)])
    }

    @Test
    fun lateNightDate_withMidnightStart_splitsIntoTwoDays() {
        // Same session counted the old calendar-midnight way: two days, which is the bug being fixed.
        val date = session(t(2026, 10, 2, 23, 0), t(2026, 10, 3, 1, 30))
        val map = StatsCalculator.buildDailyMinuteMap(listOf(date), zone = ist, dayStartHour = 0)
        assertEquals(setOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3)), map.keys)
    }

    @Test
    fun compute_countsLateNightDateAsSingleTogetherDay() {
        val date = session(t(2026, 10, 2, 23, 0), t(2026, 10, 3, 1, 30))
        val stats = StatsCalculator.compute(listOf(date), now = t(2026, 10, 3, 12, 0), zone = ist, dayStartHour = 4)
        assertEquals(1, stats.totalDaysTogether)
    }

    @Test
    fun togetherToday_apartNowAfterMeetingEarlierInTheDay_reportsWindow() {
        // Met 10:00-11:30 AM today (Sat), now is 3 PM and they are apart. Today's window starts Sat 4 AM.
        val s = session(t(2026, 10, 3, 10, 0), t(2026, 10, 3, 11, 30))
        val today = StatsCalculator.togetherToday(listOf(s), now = t(2026, 10, 3, 15, 0), zone = ist, dayStartHour = 4)
        assertNotNull(today)
        assertEquals(90L * 60_000L, today!!.totalMillis)
        assertEquals(t(2026, 10, 3, 10, 0), today.firstStartMillis)
        assertEquals(t(2026, 10, 3, 11, 30), today.lastEndMillis)
    }

    @Test
    fun togetherToday_nightSessionBeforeDayStart_stillBelongsToPreviousDay() {
        // 1:00-2:30 AM Sat is part of Friday's day (4 AM boundary). At 3 PM Sat there's no together-time today.
        val s = session(t(2026, 10, 3, 1, 0), t(2026, 10, 3, 2, 30))
        assertNull(StatsCalculator.togetherToday(listOf(s), now = t(2026, 10, 3, 15, 0), zone = ist, dayStartHour = 4))
    }

    @Test
    fun togetherToday_clipsToNowAndDayStart() {
        // Session 3:00 AM to 6:00 AM Sat, now 5:00 AM: only 4:00-5:00 AM is inside the day that started at 4 AM.
        val s = session(t(2026, 10, 3, 3, 0), t(2026, 10, 3, 6, 0))
        val today = StatsCalculator.togetherToday(listOf(s), now = t(2026, 10, 3, 5, 0), zone = ist, dayStartHour = 4)
        assertNotNull(today)
        assertEquals(60L * 60_000L, today!!.totalMillis)
        assertEquals(t(2026, 10, 3, 4, 0), today.firstStartMillis)
        assertEquals(t(2026, 10, 3, 5, 0), today.lastEndMillis)
    }
}
