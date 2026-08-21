package com.ssbmedia.twogether.ui.calendar

import com.ssbmedia.twogether.data.db.Milestone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * UX-FIX-PLAN.md Phase 2 item 13: the Calendar day-cell photo marker logic, extracted as a pure function
 * so it's unit-testable without Compose UI test infra. The bug: the marker used to be nested inside
 * `hasTogetherTime` and rendered when `!hasPhoto` - a "no photo yet" icon on together-days WITHOUT a
 * photo, and NOTHING AT ALL on any day that actually had a photo (the primary bug - includes every
 * gallery-imported photo on an apart-day).
 */
class CalendarScreenTest {

    @Test
    fun `day with a photo always shows the solid HAS_PHOTO marker, regardless of together status`() {
        assertEquals(PhotoMarkerState.HAS_PHOTO, photoMarkerState(hasPhoto = true, hasTogetherTime = true))
        // This is the primary bug under test: a photo-day with no together-time used to render nothing.
        assertEquals(PhotoMarkerState.HAS_PHOTO, photoMarkerState(hasPhoto = true, hasTogetherTime = false))
    }

    @Test
    fun `together-day with no photo yet keeps the faded prompt`() {
        assertEquals(PhotoMarkerState.PROMPT_NO_PHOTO, photoMarkerState(hasPhoto = false, hasTogetherTime = true))
    }

    @Test
    fun `apart-day with no photo shows nothing`() {
        assertEquals(PhotoMarkerState.NONE, photoMarkerState(hasPhoto = false, hasTogetherTime = false))
    }
}

/**
 * BLOCKER fix (independent audit, live-reproducible crash): [milestoneMatchesDay] replaced a
 * `MonthDay.of(month, day)` approach that THREW `DateTimeException` for a day invalid for that month
 * (e.g. month=2, day=31) - reachable via a partner sync or backup restore, both of which clamp month/day
 * independently rather than jointly. These tests prove the crash-prone inputs no longer throw, AND that
 * the Feb-29-falls-back-to-Feb-28-in-a-non-leap-year behavior (matching the yearly notification's own
 * established clamp) actually holds.
 */
class MilestoneMatchesDayTest {

    private fun milestone(month: Int, day: Int) = Milestone(
        id = "m1", label = "Test", month = month, day = day, createdAt = 0L, updatedAt = 0L
    )

    @Test
    fun `matches the same month and day in any year`() {
        val m = milestone(month = 12, day = 20)
        assertTrue(milestoneMatchesDay(m, LocalDate.of(2025, 12, 20)))
        assertTrue(milestoneMatchesDay(m, LocalDate.of(2030, 12, 20)))
        assertFalse(milestoneMatchesDay(m, LocalDate.of(2025, 12, 21)))
    }

    @Test
    // MINOR fix (advisory review): renamed - the assertions correctly check the clamped match (Feb 28),
    // but the old name claimed "matches nothing", which was never what this test actually asserted.
    fun `a corrupted day-for-month milestone does not throw, and matches its clamped-to-valid day instead`() {
        // month=2, day=31 - MonthDay.of(2, 31) would throw DateTimeException; safeDateForYear clamps
        // day=31 down to February's real max (28 or 29), so this becomes an ordinary Feb 28/29 milestone
        // rather than crashing the screen.
        val corrupted = milestone(month = 2, day = 31)
        assertTrue(milestoneMatchesDay(corrupted, LocalDate.of(2026, 2, 28)))
        assertFalse(milestoneMatchesDay(corrupted, LocalDate.of(2026, 2, 27)))
    }

    @Test
    fun `a Feb 29th milestone falls back to Feb 28th in a non-leap year`() {
        val m = milestone(month = 2, day = 29)
        assertFalse(2026 % 4 == 0) // sanity: 2026 is not a leap year
        assertTrue(milestoneMatchesDay(m, LocalDate.of(2026, 2, 28)))
        assertFalse(milestoneMatchesDay(m, LocalDate.of(2026, 2, 27)))
    }

    @Test
    fun `a Feb 29th milestone matches Feb 29th exactly in a leap year`() {
        val m = milestone(month = 2, day = 29)
        assertTrue(2028 % 4 == 0) // sanity: 2028 is a leap year
        assertTrue(milestoneMatchesDay(m, LocalDate.of(2028, 2, 29)))
        assertFalse(milestoneMatchesDay(m, LocalDate.of(2028, 2, 28)))
    }
}

/**
 * Item 4 (deferred UX fix, 4-model advisory audit): the month header's "Today" jump-back button - only
 * shown once the user has paged away from the real current month. Extracted as a plain pure function (see
 * [shouldShowBackToTodayButton]'s own doc) for the same "no Compose UI test infra needed" reasoning as
 * [CalendarScreenTest] above.
 */
class BackToTodayButtonTest {

    @Test
    fun `hidden while viewing the real current month`() {
        val currentMonth = YearMonth.of(2026, 8)
        assertFalse(shouldShowBackToTodayButton(currentMonth, currentMonth))
    }

    @Test
    fun `shown after paging forward a month`() {
        val currentMonth = YearMonth.of(2026, 8)
        assertTrue(shouldShowBackToTodayButton(currentMonth.plusMonths(1), currentMonth))
    }

    @Test
    fun `shown after paging backward a month`() {
        val currentMonth = YearMonth.of(2026, 8)
        assertTrue(shouldShowBackToTodayButton(currentMonth.minusMonths(1), currentMonth))
    }

    @Test
    fun `shown for a month many years away, not just an adjacent one`() {
        val currentMonth = YearMonth.of(2026, 8)
        assertTrue(shouldShowBackToTodayButton(currentMonth.minusYears(3), currentMonth))
    }
}
