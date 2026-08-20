package com.ssbmedia.twogether.badges

import com.ssbmedia.twogether.stats.Trend
import com.ssbmedia.twogether.stats.TogetherStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-FIX-PLAN.md Phase 3 item 21: coverage for BadgeCatalog.progressRows/closestToNextBadge - the pure
 * threshold/fraction/caption logic shared by BadgesScreen's full 4-bar section AND Home's compact
 * "closest to your next badge" card. A regression here would silently break BOTH surfaces at once.
 */
class BadgeCatalogProgressTest {

    /** Every TogetherStats field BadgeCatalog.progressRows doesn't care about is just filler - only
     * totalHoursAllTime/longestDailyStreak/longestWeeklyStreak/reunionCount are read. */
    private fun stats(
        hours: Double = 0.0,
        dailyStreak: Int = 0,
        weeklyStreak: Int = 0,
        reunions: Int = 0
    ) = TogetherStats(
        totalHoursAllTime = hours,
        totalHoursThisWeek = 0.0,
        totalHoursThisMonth = 0.0,
        currentDailyStreak = dailyStreak,
        longestDailyStreak = dailyStreak,
        currentWeeklyStreak = weeklyStreak,
        longestWeeklyStreak = weeklyStreak,
        longestSessionMinutes = 0L,
        reunionCount = reunions,
        perfectWeekCount = 0,
        favoriteDayOfWeek = null,
        totalDaysTogether = 0,
        mostMetMonth = null,
        mostHoursMonth = null,
        longestSingleDay = null,
        togetherSince = null,
        monthTrend = Trend.FLAT,
        monthTrendDeltaDays = 0,
        avgDaysBetweenMeetups = null,
        longestApart = null
    )

    // ---- progressRows ----

    @Test
    fun `progressRows returns exactly the 4 headline categories in Hours,Days,Week Streak,Reunions order`() {
        val rows = BadgeCatalog.progressRows(stats())
        assertEquals(
            listOf(BadgeType.HOURS, BadgeType.DAILY_STREAK, BadgeType.WEEKLY_STREAK, BadgeType.REUNIONS),
            rows.map { it.type }
        )
    }

    @Test
    fun `progressRows hours row uses 1-decimal caption`() {
        val row = BadgeCatalog.progressRows(stats(hours = 7.5)).first { it.type == BadgeType.HOURS }
        assertEquals("2.5h to your next badge", row.caption)
        assertEquals(10, row.nextThreshold)
        assertEquals(1, row.prevThreshold)
    }

    @Test
    fun `progressRows daily streak singular caption at exactly 1 day remaining`() {
        // Seed thresholds are 7/30/100 - 6 days in means 1 more day (to 7) completes the badge.
        val row = BadgeCatalog.progressRows(stats(dailyStreak = 6)).first { it.type == BadgeType.DAILY_STREAK }
        assertEquals("1 day to your next badge", row.caption)
    }

    @Test
    fun `progressRows plural caption when more than 1 unit remains`() {
        val row = BadgeCatalog.progressRows(stats(weeklyStreak = 1)).first { it.type == BadgeType.WEEKLY_STREAK }
        // Weekly seed thresholds are 4/12/52 - at 1, 3 more weeks remain to reach 4.
        assertEquals("3 weeks to your next badge", row.caption)
    }

    @Test
    fun `progressRows fraction is 0 at a just-passed threshold and climbs toward 1`() {
        val justPassed = BadgeCatalog.progressRows(stats(reunions = 10)).first { it.type == BadgeType.REUNIONS }
        assertEquals(0f, justPassed.fraction, 0.001f)
        val almostThere = BadgeCatalog.progressRows(stats(reunions = 49)).first { it.type == BadgeType.REUNIONS }
        assertTrue("expected a high fraction just before the next threshold, was ${almostThere.fraction}", almostThere.fraction > 0.9f)
    }

    @Test
    fun `progressRows marks a maxed-out category with fraction 1 and the celebration caption`() {
        val row = BadgeCatalog.progressRows(stats(reunions = 5_000)).first { it.type == BadgeType.REUNIONS }
        assertTrue(row.maxed)
        assertEquals(1f, row.fraction, 0.001f)
        assertTrue(row.caption.contains("5,000 Reunions"))
    }

    // ---- closestToNextBadge ----

    @Test
    fun `closestToNextBadge picks the highest-fraction non-maxed row`() {
        val rows = BadgeCatalog.progressRows(stats(hours = 0.0, dailyStreak = 0, weeklyStreak = 0, reunions = 9))
        // Reunions at 9/10 is by far the closest (90%) vs the other 3 categories starting from 0.
        val closest = BadgeCatalog.closestToNextBadge(rows)
        assertNotNull(closest)
        assertEquals(BadgeType.REUNIONS, closest!!.type)
    }

    @Test
    fun `closestToNextBadge ties break toward the first row in list order (Hours)`() {
        // All 4 categories sitting at fraction 0 (nothing progressed yet) - Hours is first in the list.
        val rows = BadgeCatalog.progressRows(stats())
        val closest = BadgeCatalog.closestToNextBadge(rows)
        assertEquals(BadgeType.HOURS, closest!!.type)
    }

    @Test
    fun `closestToNextBadge returns null once every category is maxed`() {
        val allMaxed = listOf(
            BadgeCatalog.progressRows(stats(hours = 100_000.0)).first { it.type == BadgeType.HOURS },
            BadgeCatalog.progressRows(stats(dailyStreak = 10_000)).first { it.type == BadgeType.DAILY_STREAK },
            BadgeCatalog.progressRows(stats(weeklyStreak = 1_000)).first { it.type == BadgeType.WEEKLY_STREAK },
            BadgeCatalog.progressRows(stats(reunions = 5_000)).first { it.type == BadgeType.REUNIONS }
        )
        assertTrue(allMaxed.all { it.maxed })
        assertNull(BadgeCatalog.closestToNextBadge(allMaxed))
    }
}
