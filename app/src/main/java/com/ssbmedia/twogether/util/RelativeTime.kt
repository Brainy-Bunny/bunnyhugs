package com.ssbmedia.twogether.util

/**
 * Phase 1 item 2 of UX-FIX-PLAN.md: one shared "time ago" / "elapsed duration" formatter, replacing the
 * ~5 duplicated ad-hoc implementations that had drifted across OurListsScreen, SettingsScreen, HomeScreen
 * and ProximityForegroundService - most of which never rolled over past minutes at all (the literal
 * "972m ago" / "96h 12m ago" bugs).
 */
object RelativeTime {

    /**
     * "just now" / "Xm ago" / "Xh ago" / "Xd ago" - for "how long ago did X happen" phrasing (last
     * synced, last seen, etc). Rolls over at 60 minutes and 24 hours, and never grows past a single
     * day-granularity number the way a raw "972m ago" minute count would. Negative input (a clock that's
     * briefly out of sync) is clamped to "just now" rather than showing a negative number.
     */
    fun relativeAgo(millis: Long): String {
        val elapsed = millis.coerceAtLeast(0L)
        val minutes = elapsed / 60_000L
        val hours = elapsed / 3_600_000L
        val days = elapsed / 86_400_000L
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "${minutes}m ago"
            hours < 24L -> "${hours}h ago"
            else -> "${days}d ago"
        }
    }

    /**
     * "Xm" / "Xh Ym" / "Xd Yh" - for an elapsed/together-duration reading (e.g. "Together for 3h 12m"),
     * distinct from [relativeAgo]'s "ago" phrasing. Rolls over at 60 minutes and 24 hours, same
     * boundaries as [relativeAgo], so the two never disagree about when a duration "becomes" hours or
     * days. Negative input is clamped to zero.
     */
    fun formatDuration(millis: Long): String {
        val elapsed = millis.coerceAtLeast(0L)
        val totalMinutes = elapsed / 60_000L
        val totalHours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        val hours = totalHours % 24L
        val days = totalHours / 24L
        return when {
            totalHours < 1L -> "${minutes}m"
            totalHours < 24L -> "${hours}h ${minutes}m"
            else -> "${days}d ${hours}h"
        }
    }
}
