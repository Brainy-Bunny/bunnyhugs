package com.ssbmedia.twogether.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** Basic pattern coverage for the shared [DateFormats] object (UX-FIX-PLAN.md Phase 1 item 1).
 * [DateFormats] deliberately formats using the device's default locale (matching every ad-hoc formatter
 * it replaced) rather than a hardcoded one, so this test pins the JVM's default locale to en-US for the
 * duration of the run - AM/PM casing genuinely differs by default locale/JDK (e.g. lowercase "pm" was
 * observed locally), which is a test-environment variable, not a production bug. */
class DateFormatsTest {

    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `formatDate uses dd MM yyyy`() {
        assertEquals("20 08 2026", DateFormats.formatDate(LocalDate.of(2026, 8, 20)))
    }

    @Test
    fun `formatDate zero-pads single-digit day and month`() {
        assertEquals("05 01 2026", DateFormats.formatDate(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `formatDateWithWeekday includes the weekday name and full date`() {
        // 2026-08-20 is a Thursday.
        assertEquals("Thursday, 20 08 2026", DateFormats.formatDateWithWeekday(LocalDate.of(2026, 8, 20)))
    }

    @Test
    fun `formatTime uses 12-hour clock with AM PM`() {
        assertEquals("6:45 PM", DateFormats.formatTime(LocalTime.of(18, 45)))
        assertEquals("12:00 AM", DateFormats.formatTime(LocalTime.of(0, 0)))
        assertEquals("12:00 PM", DateFormats.formatTime(LocalTime.of(12, 0)))
    }

    @Test
    fun `formatDateTime combines date and time`() {
        assertEquals(
            "20 08 2026, 6:45 PM",
            DateFormats.formatDateTime(LocalDateTime.of(2026, 8, 20, 18, 45))
        )
    }
}
