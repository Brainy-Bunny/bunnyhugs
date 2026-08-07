package com.ssbmedia.twogether.util

/**
 * BLOCKER fix (ultimate-app-review, post-restart full-scope round, Opus): a together-session's
 * (startedAt, endedAt) bounds were validated independently in two places - GattSyncManager's wire
 * path (upper-bound-only, and the subtraction for the duration check could silently integer-overflow
 * for a startedAt near Long.MIN_VALUE, defeating the ceiling entirely) and BackupManager's restore
 * path (which validated NOTHING at all). Both gaps were live-reproduced: an authenticated partner
 * device sending `startedAt = Long.MIN_VALUE` over BLE, or a hand-crafted backup zip containing the
 * same row, both reached `StatsCalculator.buildDailyMinuteMap` (which walks the interval one
 * calendar day at a time) and threw an unrecoverable `OutOfMemoryError` - a crash-loop escaping the
 * app's own exception hardening (S25/B90), since `OutOfMemoryError` is an `Error`, not an
 * `Exception`, and un-fixable in-app (only `pm clear`, which wipes all local data). This is the
 * single shared validator both call sites now use - Opus's own proposal #3 ("consolidate the three
 * timestamp-validation policies into one shared validator") - so the two paths can never drift back
 * out of sync the way they did before this fix.
 *
 * A lower bound (this app's own possible existence window) is checked BEFORE the duration
 * subtraction, which is what makes the subtraction itself overflow-safe: both operands are already
 * constrained to within a few years of `nowMillis`, so their difference can never approach Long's
 * range limits, regardless of what a malicious/corrupted payload originally claimed.
 */
object SessionBoundsValidator {
    // 2020-01-01 UTC - safely before this app's first commit, generous enough that a genuine device
    // clock misconfiguration (wrong year, not wrong century) still passes, while Long.MIN_VALUE-style
    // attack payloads and any pre-app-existence date do not.
    const val MIN_PLAUSIBLE_TIMESTAMP_MILLIS = 1_577_836_800_000L

    fun isPlausible(
        startedAt: Long,
        endedAt: Long,
        nowMillis: Long,
        maxSkewMillis: Long,
        maxDurationMillis: Long
    ): Boolean {
        val maxPlausibleBound = nowMillis + maxSkewMillis
        return startedAt >= MIN_PLAUSIBLE_TIMESTAMP_MILLIS &&
            endedAt >= MIN_PLAUSIBLE_TIMESTAMP_MILLIS &&
            endedAt >= startedAt &&
            startedAt <= maxPlausibleBound &&
            endedAt <= maxPlausibleBound &&
            (endedAt - startedAt) <= maxDurationMillis
    }
}
