package com.ssbmedia.twogether.audit

import com.ssbmedia.twogether.data.backup.BackupManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ultimate-app-review spec-test (Step 4 Fable F-2, then post-restart full-scope round Opus, 2026-08-07/08)
 * - independently derived from checklist.md's S18 ("no open TogetherSession may ever be credited past
 * maxOf(lastSeenAt, startedAt) + absenceTimeout, on any read or write path") and S20 (capsule unlock
 * is irreversible), and Opus's post-restart finding that BackupManager.parseSessions performed NO
 * bounds validation at all (unlike GattSyncManager's wire path) - live-reproduced as an unrecoverable
 * OutOfMemoryError crash-loop from a crafted `startedAt = Long.MIN_VALUE` backup.
 *
 * BackupManager.buildManifest now always CLOSES an open session before writing it (using the same
 * effectiveOpenSessionEnd formula the live Stats/Home screens already trust, anchored to THIS
 * device's real lastSeenAt at backup time - see its own doc); parseSessions' fallback for an
 * older-build/hand-crafted backup that still carries `endedAt == null` caps the credited duration at
 * the same absence-timeout window every other "how open was this really" answer in the app already
 * uses (conservative - may under-credit a session that really was fresh at backup time, which is the
 * safe direction here), and every row - closed by the wire, closed by this fallback, or already
 * closed in the JSON - is validated through the same SessionBoundsValidator GattSyncManager uses.
 * This file tests parseSessions directly (the defense-in-depth leg reachable regardless of what wrote
 * the zip); buildManifest's own closing logic is a thin wrapper around
 * StatsCalculator.effectiveOpenSessionEnd, which StatsCalculatorAuditTest already covers extensively.
 */
class BackupOpenSessionAuditTest {

    private fun parseSessions(arr: JSONArray, backupCreatedAt: Long): List<*> {
        val method = BackupManager.javaClass.getDeclaredMethod(
            "parseSessions", JSONArray::class.java, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(BackupManager, arr, backupCreatedAt) as List<*>
    }

    private fun sessionJson(
        id: Long, startedAt: Long, endedAt: Long?, updatedAt: Long = startedAt
    ): JSONArray = JSONArray().put(
        JSONObject().apply {
            put("id", id)
            put("startedAt", startedAt)
            put("endedAt", endedAt ?: JSONObject.NULL)
            put("isManual", false)
            put("syncId", "s-$id")
            put("updatedAt", updatedAt)
            put("deleted", false)
        }
    )

    @Test
    fun `an open session from an older-build backup is closed, capped at the absence-timeout window, not left open`() {
        val backupCreatedAt = System.currentTimeMillis() - 30L * 24 * 3_600_000L // "backup taken 30 days ago"
        val startedAt = backupCreatedAt - 3_600_000L // opened 1h before the backup was taken
        val result = parseSessions(sessionJson(1L, startedAt, null), backupCreatedAt)
        val session = result.first() as com.ssbmedia.twogether.data.db.TogetherSession
        assertTrue("a restored open session must never be left open", session.endedAt != null)
        assertEquals(
            "must cap at startedAt + the absence-timeout window (100s), not the full span to backupCreatedAt",
            startedAt + 100_000L, session.endedAt
        )
    }

    @Test
    fun `an open session never credits time past the absence-timeout window, even if restored while together today`() {
        // The exact live-reproduced disaster: a 30-day-old open session must credit ~100s (the same
        // absence-timeout cap every other "how open was this really" answer in the app uses), never
        // the 30-day gap to "now" (today, restore time).
        val today = System.currentTimeMillis()
        val backupCreatedAt = today - 30L * 24 * 3_600_000L
        val startedAt = backupCreatedAt - 3_600_000L
        val result = parseSessions(sessionJson(1L, startedAt, null), backupCreatedAt)
        val session = result.first() as com.ssbmedia.twogether.data.db.TogetherSession
        val creditedMillis = session.endedAt!! - session.startedAt
        assertEquals(
            "credited duration must be capped at exactly the 100s absence-timeout window, not ~30 days to today",
            100_000L, creditedMillis
        )
    }

    @Test
    fun `a startedAt after the backups own createdAt (small clock-ordering anomaly) credits zero, never negative`() {
        // Small offsets (seconds, not hours) so this stays within SessionBoundsValidator's own skew
        // tolerance and isn't rejected upstream for an unrelated reason - this test is specifically
        // about the zero-floor, not about far-future rejection (see the Blocker-2 tests below for that).
        val backupCreatedAt = System.currentTimeMillis() - 10_000L
        val startedAt = backupCreatedAt + 2_000L
        val result = parseSessions(sessionJson(1L, startedAt, null), backupCreatedAt)
        val session = result.first() as com.ssbmedia.twogether.data.db.TogetherSession
        assertTrue("must never credit a negative duration", session.endedAt!! >= session.startedAt)
        assertEquals("must credit exactly zero, not wrap or go negative", session.startedAt, session.endedAt)
    }

    @Test
    fun `a closed session passes through with its bounds untouched`() {
        val now = System.currentTimeMillis()
        val result = parseSessions(sessionJson(1L, now - 7_200_000L, now - 3_600_000L, now), now)
        val session = result.first() as com.ssbmedia.twogether.data.db.TogetherSession
        assertEquals(now - 7_200_000L, session.startedAt)
        assertEquals(now - 3_600_000L, session.endedAt)
    }

    // Below: Blocker 2 (ultimate-app-review, post-restart full-scope round, Opus) - parseSessions
    // performed NO bounds validation at all, unlike GattSyncManager's wire path. Live-verified: a
    // crafted backup with `startedAt = Long.MIN_VALUE` restored, then crashed the app in an
    // unrecoverable OutOfMemoryError crash-loop (only `pm clear` escaped) the moment StatsCalculator
    // tried to compute stats from it.

    @Test
    fun `a startedAt near Long-MIN_VALUE is rejected, not accepted and later OOM-crashing StatsCalculator`() {
        val now = System.currentTimeMillis()
        val result = parseSessions(sessionJson(1L, Long.MIN_VALUE, 0L, now), now)
        assertTrue("an overflow-attack startedAt must never merge in", result.isEmpty())
    }

    @Test
    fun `a duration that would integer-overflow the old subtraction is rejected`() {
        // The original bug: (endedAt - startedAt) with startedAt near Long.MIN_VALUE and endedAt near
        // 0 overflows to a small/negative number, defeating the 30-day duration ceiling entirely.
        val now = System.currentTimeMillis()
        val result = parseSessions(sessionJson(1L, Long.MIN_VALUE + 1000, -1000L, now), now)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a far-future startedAt from a backup is rejected`() {
        val now = System.currentTimeMillis()
        val result = parseSessions(sessionJson(1L, now + 999_999_999_999L, now + 999_999_999_999L + 1000, now), now)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an implausibly long duration from a backup is rejected`() {
        val now = System.currentTimeMillis()
        val result = parseSessions(sessionJson(1L, now - 40L * 24 * 3_600_000L, now, now), now)
        assertTrue("a 40-day 'session' must never merge in from a backup either", result.isEmpty())
    }

    @Test
    fun `a legitimate closed session from a backup still passes bounds validation`() {
        val now = System.currentTimeMillis()
        val result = parseSessions(sessionJson(1L, now - 3_600_000L, now - 1_800_000L, now), now)
        assertEquals(1, result.size)
    }
}
