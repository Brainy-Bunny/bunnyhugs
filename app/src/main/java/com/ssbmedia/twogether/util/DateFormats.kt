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

    /** BUG fix (user-reported): "20th August 2026" - a narrative/storytelling read, originally added
     * just for StatsScreen's "Together since" card, then (per an explicit follow-up user request to use
     * this format "everywhere") rolled out to every other display date site in the app too. [DATE]
     * itself is kept, not deleted, as the underlying day/month/year pattern [formatDateLong] is built
     * from - see [formatDateLong]. */
    private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

    /** The 12-hour clock convention already used by Calendar's day-detail dialog before this fix
     * (`"h:mm a"`) - kept as the one shared time format rather than switching the app to 24-hour, since
     * that was the existing user-facing convention. */
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun formatDate(date: LocalDate): String = date.format(DATE)

    /** "20th August 2026" - see [MONTH_YEAR]'s own doc for where/why this exists alongside [DATE]
     * rather than replacing it. Ordinal suffix computed by hand ("st"/"nd"/"rd"/"th") since
     * DateTimeFormatter has no built-in ordinal-day pattern letter; the 11th/12th/13th exception (all
     * "th", not "st"/"nd"/"rd") is the one irregular case in the otherwise mod-10 rule. */
    fun formatDateLong(date: LocalDate): String {
        val day = date.dayOfMonth
        val suffix = if (day in 11..13) "th" else when (day % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$day$suffix ${date.format(MONTH_YEAR)}"
    }

    fun formatDateWithWeekday(date: LocalDate): String = date.format(DATE_WITH_WEEKDAY)

    /** "Thursday, 20th August 2026" - [formatDateWithWeekday]'s long-form counterpart, for the same
     * user-requested "everywhere" sweep that added [formatDateLong] itself. */
    fun formatDateWithWeekdayLong(date: LocalDate): String {
        val weekday = date.format(DateTimeFormatter.ofPattern("EEEE", Locale.US))
        return "$weekday, ${formatDateLong(date)}"
    }

    fun formatTime(time: LocalTime): String = time.format(TIME)

    /** "20 08 2026, 6:45 PM" - date + time together, for contexts that previously showed a combined
     * localized date-time (e.g. a Moment's full-screen capture timestamp). */
    fun formatDateTime(dateTime: LocalDateTime): String =
        "${dateTime.toLocalDate().format(DATE)}, ${dateTime.toLocalTime().format(TIME)}"

    /** "20th August 2026, 6:45 PM" - [formatDateTime]'s long-form counterpart. */
    fun formatDateTimeLong(dateTime: LocalDateTime): String =
        "${formatDateLong(dateTime.toLocalDate())}, ${dateTime.toLocalTime().format(TIME)}"
}
