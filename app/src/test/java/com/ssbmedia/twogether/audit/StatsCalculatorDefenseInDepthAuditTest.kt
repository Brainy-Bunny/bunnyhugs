package com.ssbmedia.twogether.audit

import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.stats.StatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * ultimate-app-review spec-test (round-2 re-verification, Sonnet finding, 2026-08-08) -
 * StatsCalculator.mergedIntervals reads whatever is already in the local sessions table with no floor
 * of its own, unlike the two ingestion paths (GattSyncManager, BackupManager) which both validate
 * through SessionBoundsValidator before a row ever reaches storage. Live-reproduced during round-2
 * testing: a session row reaching the local table some other way (on-device root/`run-as` tampering,
 * or a future bug in either ingestion path) with a `startedAt` near `Long.MIN_VALUE` crashed
 * `buildDailyMinuteMap`'s one-calendar-day-at-a-time walk with an unrecoverable `OutOfMemoryError` -
 * the exact crash class the ingestion-side fixes exist to eliminate, reached from a different angle.
 */
class StatsCalculatorDefenseInDepthAuditTest {

    private fun session(startedAt: Long, endedAt: Long?): TogetherSession = TogetherSession(
        id = 0, startedAt = startedAt, endedAt = endedAt, isManual = false,
        syncId = UUID.randomUUID().toString(), updatedAt = startedAt, deleted = false
    )

    @Test
    fun `a startedAt near Long-MIN_VALUE is excluded from stats, not fed into the day-walking loop`() {
        val now = System.currentTimeMillis()
        val legit = session(now - 3_600_000L, now - 1_800_000L) // 30 min, real
        val poisoned = session(Long.MIN_VALUE, 0L)
        // If the poisoned row weren't filtered, this call would either throw or (worse) attempt to walk
        // ~10^11 calendar days - the actual OutOfMemoryError this test guards against. Completing at
        // all, with only the legit row's 0.5h counted, is the assertion.
        val stats = StatsCalculator.compute(listOf(legit, poisoned), now = now)
        assertEquals(0.5, stats.totalHoursAllTime, 0.01)
    }

    @Test
    fun `an implausibly long duration already in local storage is excluded from stats`() {
        val now = System.currentTimeMillis()
        val legit = session(now - 3_600_000L, now - 1_800_000L)
        val marathon = session(now - 40L * 24 * 3_600_000L, now)
        val stats = StatsCalculator.compute(listOf(legit, marathon), now = now)
        assertEquals(0.5, stats.totalHoursAllTime, 0.01)
    }

    @Test
    fun `a session slightly ahead of a stale now parameter is still counted - not a regression of the rollover fix`() {
        // This is the exact scenario "today is derived from the now parameter..." (StatsCalculatorAuditTest)
        // depends on: `now` can legitimately be a few seconds/minutes stale relative to a real session's
        // own timestamp. The defensive filter above must not reject this - only overflow-class garbage.
        val now = System.currentTimeMillis()
        val staleNow = now - 5_000L
        val recentSession = session(now - 3_600_000L, now - 1_800_000L)
        val stats = StatsCalculator.compute(listOf(recentSession), now = staleNow)
        assertEquals(0.5, stats.totalHoursAllTime, 0.01)
    }
}
