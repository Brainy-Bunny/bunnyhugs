package com.ssbmedia.twogether.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rollover-boundary coverage for [RelativeTime] - this utility replaced ~5 duplicated, buggy ad-hoc
 * implementations app-wide (see UX-FIX-PLAN.md Phase 1 item 2), so a regression here has a wide blast
 * radius. Focuses on the exact boundaries called out in that plan: 59s/60s, 59min/60min, 23h/24h.
 */
class RelativeTimeTest {

    // ---- relativeAgo ----

    @Test
    fun `relativeAgo under one minute reads just now`() {
        assertEquals("just now", RelativeTime.relativeAgo(0L))
        assertEquals("just now", RelativeTime.relativeAgo(59_000L))
    }

    @Test
    fun `relativeAgo crosses from just now to minutes at exactly 60s`() {
        assertEquals("1m ago", RelativeTime.relativeAgo(60_000L))
    }

    @Test
    fun `relativeAgo stays in minutes up to 59m59s`() {
        assertEquals("59m ago", RelativeTime.relativeAgo(59 * 60_000L))
        assertEquals("59m ago", RelativeTime.relativeAgo(59 * 60_000L + 59_000L))
    }

    @Test
    fun `relativeAgo crosses from minutes to hours at exactly 60 minutes`() {
        assertEquals("1h ago", RelativeTime.relativeAgo(60 * 60_000L))
    }

    @Test
    fun `relativeAgo stays in hours up to 23h59m`() {
        assertEquals("23h ago", RelativeTime.relativeAgo(23 * 3_600_000L))
        assertEquals("23h ago", RelativeTime.relativeAgo(23 * 3_600_000L + 59 * 60_000L))
    }

    @Test
    fun `relativeAgo crosses from hours to days at exactly 24 hours`() {
        assertEquals("1d ago", RelativeTime.relativeAgo(24 * 3_600_000L))
    }

    @Test
    fun `relativeAgo never regresses to a giant raw minute count - the 972m ago bug`() {
        // 972 minutes = 16.2 hours - the literal reported bug: this must read in hours, not "972m ago".
        assertEquals("16h ago", RelativeTime.relativeAgo(972 * 60_000L))
    }

    @Test
    fun `relativeAgo handles multi-day gaps - the 96h ago notification bug`() {
        // 96h = 4 days - must roll all the way to days, not stall out at hours.
        assertEquals("4d ago", RelativeTime.relativeAgo(96 * 3_600_000L))
    }

    @Test
    fun `relativeAgo clamps a negative (clock-skew) input to just now`() {
        assertEquals("just now", RelativeTime.relativeAgo(-5_000L))
    }

    // ---- formatDuration ----

    @Test
    fun `formatDuration under one hour reads minutes only`() {
        assertEquals("0m", RelativeTime.formatDuration(0L))
        assertEquals("0m", RelativeTime.formatDuration(59_000L))
        assertEquals("1m", RelativeTime.formatDuration(60_000L))
        assertEquals("59m", RelativeTime.formatDuration(59 * 60_000L))
    }

    @Test
    fun `formatDuration crosses from minutes to hours at exactly 60 minutes`() {
        assertEquals("1h 0m", RelativeTime.formatDuration(60 * 60_000L))
    }

    @Test
    fun `formatDuration stays in hours up to 23h59m`() {
        assertEquals("23h 0m", RelativeTime.formatDuration(23 * 3_600_000L))
        assertEquals("23h 59m", RelativeTime.formatDuration(23 * 3_600_000L + 59 * 60_000L))
    }

    @Test
    fun `formatDuration crosses from hours to days at exactly 24 hours`() {
        assertEquals("1d 0h", RelativeTime.formatDuration(24 * 3_600_000L))
    }

    @Test
    fun `formatDuration keeps rolling correctly across multiple days`() {
        // 50 hours = 2d 2h.
        assertEquals("2d 2h", RelativeTime.formatDuration(50 * 3_600_000L))
    }

    @Test
    fun `formatDuration clamps a negative input to zero`() {
        assertEquals("0m", RelativeTime.formatDuration(-1_000L))
    }
}
