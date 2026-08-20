package com.ssbmedia.twogether.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
