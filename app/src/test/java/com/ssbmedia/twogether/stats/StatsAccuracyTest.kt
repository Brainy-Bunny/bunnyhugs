package com.ssbmedia.twogether.stats

import com.ssbmedia.twogether.data.db.TogetherSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Accuracy audit for the one number the whole app is about: total together-time.
 * Every expected value below is hand-calculated in the comment above it.
 */
class StatsAccuracyTest {

    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    private val ny: ZoneId = ZoneId.of("America/New_York")
    private val absence = 100_000L

    private fun t(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun s(start: Long, end: Long?, manual: Boolean = false, id: Long = 0) =
        TogetherSession(id = id, startedAt = start, endedAt = end, isManual = manual)

    private fun hours(
        sessions: List<TogetherSession>,
        now: Long = t(ist, 2026, 6, 1, 23, 59),
        zone: ZoneId = ist,
        lastSeenAt: Long = 0L
    ) = StatsCalculator.compute(sessions, now = now, zone = zone, lastSeenAt = lastSeenAt, absenceTimeoutMillis = absence)
        .totalHoursAllTime

    // ---------- 1. Overlap handling ----------

    /** A 10:00-14:00 (4h), B 11:00-12:00 fully contained. Expected union = 4.000h */
    @Test fun fullyContainedOverlapCountsOnce() {
        val list = listOf(
            s(t(ist, 2026, 5, 10, 10, 0), t(ist, 2026, 5, 10, 14, 0)),
            s(t(ist, 2026, 5, 10, 11, 0), t(ist, 2026, 5, 10, 12, 0))
        )
        assertEquals(4.0, hours(list), 1e-9)
    }

    /** A 10:00-12:00, B 11:00-13:30 -> union 10:00-13:30 = 3.500h */
    @Test fun partialOverlapCountsUnionOnly() {
        val list = listOf(
            s(t(ist, 2026, 5, 10, 10, 0), t(ist, 2026, 5, 10, 12, 0)),
            s(t(ist, 2026, 5, 10, 11, 0), t(ist, 2026, 5, 10, 13, 30))
        )
        assertEquals(3.5, hours(list), 1e-9)
    }

    /** A 10:00-12:00 touching B 12:00-13:00 -> 3.000h (no gap, no overlap, nothing lost) */
    @Test fun exactlyAdjacentSessionsSumExactly() {
        val list = listOf(
            s(t(ist, 2026, 5, 10, 10, 0), t(ist, 2026, 5, 10, 12, 0)),
            s(t(ist, 2026, 5, 10, 12, 0), t(ist, 2026, 5, 10, 13, 0))
        )
        assertEquals(3.0, hours(list), 1e-9)
    }

    /** Disjoint 10:00-11:00 + 12:00-13:00 = 2.000h (merge must NOT swallow the gap) */
    @Test fun disjointSessionsAreNotMerged() {
        val list = listOf(
            s(t(ist, 2026, 5, 10, 10, 0), t(ist, 2026, 5, 10, 11, 0)),
            s(t(ist, 2026, 5, 10, 12, 0), t(ist, 2026, 5, 10, 13, 0))
        )
        assertEquals(2.0, hours(list), 1e-9)
    }

    /**
     * Data-loss regression guard: a long interval followed by a fully-contained short one.
     * Unsorted input: 12:00-13:00, 10:00-15:00, 14:00-14:30, 16:00-17:00.
     * Correct union = (10:00-15:00 = 5h) + (16:00-17:00 = 1h) = 6.000h.
     * A merge that assigned `end = interval.end` instead of max() would SHRINK to 4.5h+1h.
     */
    @Test fun mergeNeverShrinksAnInterval() {
        val list = listOf(
            s(t(ist, 2026, 5, 10, 12, 0), t(ist, 2026, 5, 10, 13, 0)),
            s(t(ist, 2026, 5, 10, 10, 0), t(ist, 2026, 5, 10, 15, 0)),
            s(t(ist, 2026, 5, 10, 14, 0), t(ist, 2026, 5, 10, 14, 30)),
            s(t(ist, 2026, 5, 10, 16, 0), t(ist, 2026, 5, 10, 17, 0))
        )
        assertEquals(6.0, hours(list), 1e-9)
    }

    /** Manual entry EXACTLY duplicating a BLE session 09:00-11:00 -> 2.000h, never 4.0 */
    @Test fun manualEntryDuplicatingBleSessionDoesNotDouble() {
        val list = listOf(
            s(t(ist, 2026, 5, 10, 9, 0), t(ist, 2026, 5, 10, 11, 0), manual = false),
            s(t(ist, 2026, 5, 10, 9, 0), t(ist, 2026, 5, 10, 11, 0), manual = true)
        )
        assertEquals(2.0, hours(list), 1e-9)
    }

    /** Same session row present 5x (worst-case sync duplication) -> still 2.000h */
    @Test fun fiveIdenticalRowsStillCountOnce() {
        val a = t(ist, 2026, 5, 10, 9, 0)
        val b = t(ist, 2026, 5, 10, 11, 0)
        val list = (1..5).map { s(a, b, id = it.toLong()) }
        assertEquals(2.0, hours(list), 1e-9)
    }

    // ---------- 2. Open-session clamping ----------

    /**
     * Orphaned open session: started 5 days ago, last sighting 2h after it started, never closed.
     * Clamp -> lastSeenAt + 100s. Expected credited = 2h + 100s = 7300s = 2.0277777...h
     */
    @Test fun staleOpenSessionIsClampedNotGrownToNow() {
        val started = t(ist, 2026, 5, 27, 10, 0)
        val lastSeen = started + 2 * 3_600_000L
        val now = t(ist, 2026, 6, 1, 12, 0)
        val list = listOf(s(started, null))
        assertEquals(7_300_000.0 / 3_600_000.0, hours(list, now = now, lastSeenAt = lastSeen), 1e-9)
    }

    /**
     * Genuinely LIVE open session: started 30 min ago, partner seen 5s ago.
     * cutoff = min(now, now-5s+100s) = now -> full 0.5h credited, NOT cut short.
     */
    @Test fun liveOpenSessionIsNotCutShort() {
        val now = t(ist, 2026, 6, 1, 12, 0)
        val started = now - 30 * 60_000L
        val lastSeen = now - 5_000L
        assertEquals(0.5, hours(listOf(s(started, null)), now = now, lastSeenAt = lastSeen), 1e-9)
    }

    /** Boundary: partner last seen exactly 100s ago -> cutoff == now exactly, full duration kept. */
    @Test fun openSessionAtExactTimeoutBoundaryKeepsFullDuration() {
        val now = t(ist, 2026, 6, 1, 12, 0)
        val started = now - 60 * 60_000L
        assertEquals(1.0, hours(listOf(s(started, null)), now = now, lastSeenAt = now - absence), 1e-9)
    }

    /**
     * REGRESSION (real bug, found and fixed during this audit): lastSeenAt unavailable (0) - exactly the
     * state after BackupManager restores a backed-up OPEN session onto a replacement phone, or after
     * DataStores' ReplaceFileCorruptionHandler resets a torn proximity_state file. This used to fall back
     * to a fully UNCLAMPED "now" and credit the whole gap (measured live on-device: 723.5h from a 30-day
     * -old open row, which irreversibly unlocked every time capsule up to 500h).
     * startedAt is itself a confirmed sighting, so the correct bound is startedAt + 100s = 0.02777...h.
     */
    @Test fun openSessionWithNoSightingInfoIsBoundedByItsOwnStart() {
        val now = t(ist, 2026, 6, 1, 12, 0)
        assertEquals(100_000.0 / 3_600_000.0, hours(listOf(s(now - 3_600_000L, null)), now = now, lastSeenAt = 0L), 1e-9)
    }

    /** Same, at 30-day scale: a 30-day-old orphan with no sighting info still credits only 100s. */
    @Test fun thirtyDayOldOrphanWithNoSightingInfoCreditsOnlyTheTimeout() {
        val now = t(ist, 2026, 8, 3, 11, 0)
        val started = t(ist, 2026, 7, 4, 8, 0)
        assertEquals(100_000.0 / 3_600_000.0, hours(listOf(s(started, null)), now = now, lastSeenAt = 0L), 1e-9)
    }

    /**
     * The evidence bound is maxOf(lastSeenAt, startedAt): a PERSISTED lastSeenAt older than the open
     * session's own start (stale carry-over) must not shrink it below what its own start proves.
     * Open row started now-60s, lastSeenAt 10 min ago. Expected: min(now, started+100s) = now -> 60s.
     * Plus an untouched closed 2h session. Total = 2h + 60s = 2.01666...h
     */
    @Test fun boundUsesNewestOfLastSeenAndStart() {
        val now = t(ist, 2026, 6, 1, 12, 0)
        val list = listOf(
            s(t(ist, 2026, 5, 30, 10, 0), t(ist, 2026, 5, 30, 12, 0)),
            s(now - 60_000L, null)
        )
        assertEquals((2 * 3_600_000L + 60_000L) / 3_600_000.0, hours(list, now = now, lastSeenAt = now - 10 * 60_000L), 1e-9)
    }

    /** The fix must never let an open row's credited end exceed `now`. */
    @Test fun openSessionNeverCreditedPastNow() {
        val now = t(ist, 2026, 6, 1, 12, 0)
        assertEquals(30_000.0 / 3_600_000.0, hours(listOf(s(now - 30_000L, null)), now = now, lastSeenAt = now), 1e-9)
    }

    // ---------- 3. Midnight split ----------

    /** 23:00 -> 02:00 next day. Total 3.000h; day1 = 60 min, day2 = 120 min, sum = 180 min. */
    @Test fun midnightSpanSplitsExactly() {
        val list = listOf(s(t(ist, 2026, 5, 10, 23, 0), t(ist, 2026, 5, 11, 2, 0)))
        assertEquals(3.0, hours(list), 1e-9)
        val map = StatsCalculator.buildDailyMinuteMap(list, now = t(ist, 2026, 6, 1, 23, 59), zone = ist)
        assertEquals(60L, map[LocalDate.of(2026, 5, 10)])
        assertEquals(120L, map[LocalDate.of(2026, 5, 11)])
        assertEquals(180L, map.values.sum())
        assertEquals(2, map.size)
    }

    /** Multi-day: 2026-05-10 22:00 -> 2026-05-13 03:00 = 53h; days 10(120m) 11(1440m) 12(1440m) 13(180m) */
    @Test fun multiDaySpanSplitsExactly() {
        val list = listOf(s(t(ist, 2026, 5, 10, 22, 0), t(ist, 2026, 5, 13, 3, 0)))
        assertEquals(53.0, hours(list), 1e-9)
        val map = StatsCalculator.buildDailyMinuteMap(list, now = t(ist, 2026, 6, 1, 23, 59), zone = ist)
        assertEquals(120L, map[LocalDate.of(2026, 5, 10)])
        assertEquals(1440L, map[LocalDate.of(2026, 5, 11)])
        assertEquals(1440L, map[LocalDate.of(2026, 5, 12)])
        assertEquals(180L, map[LocalDate.of(2026, 5, 13)])
        assertEquals(53L * 60, map.values.sum())
    }

    // ---------- 4. DST ----------

    /**
     * Spring forward, America/New_York, 2026-03-08 (02:00 EST -> 03:00 EDT).
     * Session 00:30 EST -> 04:30 EDT. Wall-clock label difference is 4h but REAL elapsed is 3h.
     * Expected total = 3.000h, and the whole thing lands on Mar 8 = 180 min.
     */
    @Test fun springForwardCountsRealElapsedTime() {
        val list = listOf(s(t(ny, 2026, 3, 8, 0, 30), t(ny, 2026, 3, 8, 4, 30)))
        assertEquals(3.0, hours(list, now = t(ny, 2026, 6, 1, 12, 0), zone = ny), 1e-9)
        val map = StatsCalculator.buildDailyMinuteMap(list, now = t(ny, 2026, 6, 1, 12, 0), zone = ny)
        assertEquals(180L, map[LocalDate.of(2026, 3, 8)])
        assertEquals(1, map.size)
    }

    /**
     * Fall back, America/New_York, 2026-11-01 (02:00 EDT -> 01:00 EST).
     * Session 00:30 EDT -> 03:30 EST. Wall labels say 3h; REAL elapsed is 4h.
     * java.time resolves an ambiguous local time to the EARLIER offset, so 00:30 = EDT and
     * 03:30 = EST (unambiguous). Expected total = 4.000h, Nov 1 = 240 min.
     */
    @Test fun fallBackCountsRealElapsedTime() {
        val list = listOf(s(t(ny, 2026, 11, 1, 0, 30), t(ny, 2026, 11, 1, 3, 30)))
        assertEquals(4.0, hours(list, now = t(ny, 2026, 12, 1, 12, 0), zone = ny), 1e-9)
        val map = StatsCalculator.buildDailyMinuteMap(list, now = t(ny, 2026, 12, 1, 12, 0), zone = ny)
        assertEquals(240L, map[LocalDate.of(2026, 11, 1)])
    }

    /**
     * DST transition AND midnight in the same session: 2026-03-07 23:00 EST -> 2026-03-08 04:00 EDT.
     * Real elapsed = 4h (the 02:00 hour never exists). Day Mar 7 = 60 min (23:00-24:00),
     * Day Mar 8 = 180 min (00:00-04:00 wall, minus the skipped hour). Sum = 240 min = 4h.
     */
    @Test fun dstPlusMidnightSplitsExactly() {
        val list = listOf(s(t(ny, 2026, 3, 7, 23, 0), t(ny, 2026, 3, 8, 4, 0)))
        assertEquals(4.0, hours(list, now = t(ny, 2026, 6, 1, 12, 0), zone = ny), 1e-9)
        val map = StatsCalculator.buildDailyMinuteMap(list, now = t(ny, 2026, 6, 1, 12, 0), zone = ny)
        assertEquals(60L, map[LocalDate.of(2026, 3, 7)])
        assertEquals(180L, map[LocalDate.of(2026, 3, 8)])
        assertEquals(240L, map.values.sum())
    }

    /** Fall-back day is 25h long: full local day 2026-11-01 00:00 -> 2026-11-02 00:00 = 25.000h */
    @Test fun fallBackFullDayIs25Hours() {
        val list = listOf(s(t(ny, 2026, 11, 1, 0, 0), t(ny, 2026, 11, 2, 0, 0)))
        assertEquals(25.0, hours(list, now = t(ny, 2026, 12, 1, 12, 0), zone = ny), 1e-9)
        val map = StatsCalculator.buildDailyMinuteMap(list, now = t(ny, 2026, 12, 1, 12, 0), zone = ny)
        assertEquals(1500L, map[LocalDate.of(2026, 11, 1)])
        assertEquals(1, map.size)
    }

    // ---------- 5. Order independence / determinism (divergence guard) ----------

    /**
     * The SAME session set in a different row order (what two synced phones inevitably have, since
     * each assigns its own local auto-increment ids) must produce byte-identical stats.
     */
    @Test fun statsAreIndependentOfRowOrderAndLocalIds() {
        val base = listOf(
            s(t(ist, 2026, 5, 1, 9, 0), t(ist, 2026, 5, 1, 11, 0), id = 1),
            s(t(ist, 2026, 5, 2, 9, 0), t(ist, 2026, 5, 2, 10, 30), id = 2),
            s(t(ist, 2026, 5, 2, 10, 0), t(ist, 2026, 5, 2, 12, 0), id = 3),
            s(t(ist, 2026, 5, 3, 23, 0), t(ist, 2026, 5, 4, 1, 0), id = 4, manual = true),
            s(t(ist, 2026, 5, 4, 0, 30), t(ist, 2026, 5, 4, 2, 0), id = 5)
        )
        val now = t(ist, 2026, 5, 10, 12, 0)
        val shuffled = base.reversed().mapIndexed { i, x -> x.copy(id = 100L + i) }
        val a = StatsCalculator.compute(base, now = now, zone = ist, lastSeenAt = now - 1000, absenceTimeoutMillis = absence)
        val b = StatsCalculator.compute(shuffled, now = now, zone = ist, lastSeenAt = now - 1000, absenceTimeoutMillis = absence)
        assertEquals(a, b)
        // Hand check: 5/1 = 2h; 5/2 union 09:00-12:00 = 3h; 5/3 23:00 -> 5/4 02:00 union = 3h. Total 8.000h
        assertEquals(8.0, a.totalHoursAllTime, 1e-9)
        // Days: 5/1, 5/2, 5/3, 5/4 = 4
        assertEquals(4, a.totalDaysTogether)
    }

    // ---------- 6. Derived stats consistency ----------

    /** Per-day minutes must never exceed the merged total (no double count leaking into day map). */
    @Test fun dailyMapNeverExceedsTotal() {
        val list = listOf(
            s(t(ist, 2026, 5, 10, 9, 0), t(ist, 2026, 5, 10, 17, 0)),
            s(t(ist, 2026, 5, 10, 10, 0), t(ist, 2026, 5, 10, 11, 0), manual = true),
            s(t(ist, 2026, 5, 10, 16, 0), t(ist, 2026, 5, 11, 3, 0))
        )
        // union = 09:00 5/10 -> 03:00 5/11 = 18h
        val stats = StatsCalculator.compute(list, now = t(ist, 2026, 6, 1, 12, 0), zone = ist, absenceTimeoutMillis = absence)
        assertEquals(18.0, stats.totalHoursAllTime, 1e-9)
        val map = StatsCalculator.buildDailyMinuteMap(list, now = t(ist, 2026, 6, 1, 12, 0), zone = ist)
        assertEquals(900L, map[LocalDate.of(2026, 5, 10)])  // 09:00-24:00 = 15h
        assertEquals(180L, map[LocalDate.of(2026, 5, 11)])  // 00:00-03:00 = 3h
        assertEquals(18L * 60, map.values.sum())
        assertEquals(2, stats.totalDaysTogether)
    }

    /** longestSessionMinutes off overlapping rows must be the merged span, not a raw row. */
    @Test fun longestSessionUsesMergedSpan() {
        val list = listOf(
            s(t(ist, 2026, 5, 10, 9, 0), t(ist, 2026, 5, 10, 11, 0)),
            s(t(ist, 2026, 5, 10, 10, 0), t(ist, 2026, 5, 10, 13, 0))
        )
        val stats = StatsCalculator.compute(list, now = t(ist, 2026, 6, 1, 12, 0), zone = ist, absenceTimeoutMillis = absence)
        assertEquals(240L, stats.longestSessionMinutes) // 09:00-13:00 = 4h
    }
}
