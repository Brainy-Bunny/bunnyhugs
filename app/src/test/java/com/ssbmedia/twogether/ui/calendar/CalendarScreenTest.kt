package com.ssbmedia.twogether.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

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
