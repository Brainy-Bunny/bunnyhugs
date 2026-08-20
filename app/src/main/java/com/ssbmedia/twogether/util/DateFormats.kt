package com.ssbmedia.twogether.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phase 1 item 1 of UX-FIX-PLAN.md: one shared date/time formatting convention, replacing the ~7
 * independent ad-hoc formatters that had drifted across Moments/Stats/Badges/Settings/Calendar
 * ("MMM d, yyyy", "MMM d", DateFormat.getDateInstance(MEDIUM), ofLocalizedDate(MEDIUM), ...).
 *
 * Deliberately does NOT touch the raw ISO `yyyy-MM-dd` TEXT INPUT fields in GalleryImportFlow.kt /
 * CalendarScreen.AddManualSessionDialog - those are parse targets for a free-text field, not display
 * formatting, and get replaced by a native date/time picker in a later phase instead.
 *
 * Every pattern below is built with an explicit [Locale.US] rather than `ofPattern(String)`'s implicit
 * default locale - found the hard way during Phase 2 integration: without a pinned locale, the "a"
 * (AM/PM) marker's case and the "EEEE" weekday name both silently follow whatever locale the JVM/device
 * happens to default to (CLDR data has shipped both "PM" and "pm" across Java/Android versions), which
 * would have made this "one shared convention" formatter itself inconsistent depending on the user's
 * device settings - exactly the class of bug this file exists to eliminate.
 */
object DateFormats {

    /** The one shared display date pattern app-wide: "20 08 2026". */
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MM yyyy", Locale.US)

    /** Same as [DATE] but with the weekday name prefixed - for contexts (like Calendar's day-detail
     * dialog title) that want "Thursday, 20 08 2026" rather than the bare date. */
    val DATE_WITH_WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, dd MM yyyy", Locale.US)

    /** The 12-hour clock convention already used by Calendar's day-detail dialog before this fix
     * (`"h:mm a"`) - kept as the one shared time format rather than switching the app to 24-hour, since
     * that was the existing user-facing convention. */
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun formatDate(date: LocalDate): String = date.format(DATE)

    fun formatDateWithWeekday(date: LocalDate): String = date.format(DATE_WITH_WEEKDAY)

    fun formatTime(time: LocalTime): String = time.format(TIME)

    /** "20 08 2026, 6:45 PM" - date + time together, for contexts that previously showed a combined
     * localized date-time (e.g. a Moment's full-screen capture timestamp). */
    fun formatDateTime(dateTime: LocalDateTime): String =
        "${dateTime.toLocalDate().format(DATE)}, ${dateTime.toLocalTime().format(TIME)}"
}
