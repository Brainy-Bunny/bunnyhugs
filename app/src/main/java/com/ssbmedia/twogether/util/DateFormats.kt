package com.ssbmedia.twogether.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * UX-FIX-PLAN.md Phase 2 item 11: one shared `dd MM yyyy` date display format, so Time Capsule's new
 * "Sealed on"/"Opened on" timeline text doesn't invent its own ad-hoc pattern. Kept intentionally small
 * (this codebase snapshot doesn't yet have Phase 1's full app-wide formatter sweep) - just the one
 * pattern item 11 actually needs, plus a couple of obvious companions for anything else in Phase 2's
 * scope that wants a display date/time.
 */
object DateFormats {

    /** The shared display date pattern: "20 08 2026". */
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MM yyyy")

    /** Same as [DATE] but with the weekday name prefixed - "Thursday, 20 08 2026". */
    val DATE_WITH_WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, dd MM yyyy")

    /** 12-hour clock, matching this app's existing "h:mm a" convention. */
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

    fun formatDate(date: LocalDate): String = date.format(DATE)

    fun formatDateWithWeekday(date: LocalDate): String = date.format(DATE_WITH_WEEKDAY)

    fun formatTime(time: LocalTime): String = time.format(TIME)

    fun formatDateTime(dateTime: LocalDateTime): String =
        "${dateTime.toLocalDate().format(DATE)}, ${dateTime.toLocalTime().format(TIME)}"
}
