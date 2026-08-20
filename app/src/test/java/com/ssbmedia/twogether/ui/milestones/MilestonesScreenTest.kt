package com.ssbmedia.twogether.ui.milestones

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UX-FIX-PLAN.md Phase 3 item 20: coverage for the pure date-math helpers behind Milestone -> Calendar
 * navigation, extracted so they're unit-testable without any Compose test infra - see
 * MilestoneRetrospective's own doc for how these two feed its year-header/subtitle taps.
 */
class MilestonesScreenTest {

    @Test
    fun `safeDateForYear builds a real date for an ordinary month and day`() {
        assertEquals(LocalDate.of(2024, 6, 15), safeDateForYear(2024, 6, 15))
    }

    @Test
    fun `safeDateForYear clamps a Feb 29 milestone down to Feb 28 in a non-leap year`() {
        // Defense-in-depth, same reasoning as MilestoneAlarmScheduler.safeDate/monthDayLabel's own clamp -
        // a pre-existing corrupted or synced-from-an-older-build row must never crash this screen.
        assertEquals(LocalDate.of(2025, 2, 28), safeDateForYear(2025, 2, 29))
    }

    @Test
    fun `safeDateForYear clamps an out-of-range month`() {
        assertEquals(LocalDate.of(2024, 12, 1), safeDateForYear(2024, 13, 1))
        assertEquals(LocalDate.of(2024, 1, 1), safeDateForYear(2024, 0, 1))
    }

    @Test
    fun `nearestApplicableYear always uses the milestone's own recorded year when set`() {
        val today = LocalDate.of(2026, 8, 20)
        assertEquals(2019, nearestApplicableYear(month = 3, day = 1, milestoneYear = 2019, today = today))
    }

    @Test
    fun `nearestApplicableYear falls back to this year when the date has already happened`() {
        val today = LocalDate.of(2026, 8, 20)
        // August 1st has already passed this year as of August 20th.
        assertEquals(2026, nearestApplicableYear(month = 8, day = 1, milestoneYear = null, today = today))
    }

    @Test
    fun `nearestApplicableYear falls back to last year when this year's occurrence hasn't happened yet`() {
        val today = LocalDate.of(2026, 8, 20)
        // December 25th hasn't happened yet this year - the most recent REAL occurrence was last year.
        assertEquals(2025, nearestApplicableYear(month = 12, day = 25, milestoneYear = null, today = today))
    }

    @Test
    fun `nearestApplicableYear treats today itself as already happened`() {
        val today = LocalDate.of(2026, 8, 20)
        assertEquals(2026, nearestApplicableYear(month = 8, day = 20, milestoneYear = null, today = today))
    }
}
