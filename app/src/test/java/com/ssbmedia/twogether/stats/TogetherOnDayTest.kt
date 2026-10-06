package com.ssbmedia.twogether.stats

import com.ssbmedia.twogether.data.db.TogetherSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The photo-backfill dialog offers the together time the app already tracked on the chosen day. */
class TogetherOnDayTest {

    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun t(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ist).toInstant().toEpochMilli()

    private fun session(start: Long, end: Long) = TogetherSession(startedAt = start, endedAt = end)

    private val day = LocalDate.of(2026, 9, 29)

    @Test fun noSessionsMeansNothingToOffer() {
        assertNull(StatsCalculator.togetherOnDay(emptyList(), day, ist, dayStartHour = 4))
    }

    @Test fun trackedSessionOnTheDayIsReturnedWithItsWindow() {
        val s = session(t(2026, 9, 29, 19, 0), t(2026, 9, 29, 21, 30))
        val tt = StatsCalculator.togetherOnDay(listOf(s), day, ist, dayStartHour = 4)
        assertEquals(150L * 60_000L, tt!!.totalMillis)
        assertEquals(t(2026, 9, 29, 19, 0), tt.firstStartMillis)
        assertEquals(t(2026, 9, 29, 21, 30), tt.lastEndMillis)
    }

    @Test fun sessionOnAnotherDayIsNotOffered() {
        val s = session(t(2026, 9, 28, 19, 0), t(2026, 9, 28, 21, 0))
        assertNull(StatsCalculator.togetherOnDay(listOf(s), day, ist, dayStartHour = 4))
    }

    /** A late-night session after midnight belongs to the same logical day when the day starts at 4 AM. */
    @Test fun lateNightSessionCountsForThePreviousLogicalDay() {
        val s = session(t(2026, 9, 29, 23, 0), t(2026, 9, 30, 1, 30))
        val tt = StatsCalculator.togetherOnDay(listOf(s), day, ist, dayStartHour = 4)
        assertEquals(150L * 60_000L, tt!!.totalMillis)
    }
}
