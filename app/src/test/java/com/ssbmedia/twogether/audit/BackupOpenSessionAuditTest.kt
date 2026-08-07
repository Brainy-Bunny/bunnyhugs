package com.ssbmedia.twogether.audit

import com.ssbmedia.twogether.data.backup.BackupManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ultimate-app-review spec-test (Step 4, Fable F-2, 2026-08-07) - independently derived from
 * checklist.md's S18 ("no open TogetherSession may ever be credited past
 * maxOf(lastSeenAt, startedAt) + absenceTimeout, on any read or write path") and S20 (capsule unlock
 * is irreversible). Fable live-reproduced the exact B63 disaster through the BACKUP path: restoring a
 * backup containing a 30-day-old open session while genuinely together with a partner
 * (lastSeenAt ~ now) credited the whole 30-day gap and irreversibly unlocked capsules up to 500h -
 * because effectiveOpenSessionEnd bounds an open session against the RESTORING device's own live
 * lastSeenAt, which is a valid witness for "how long have we been apart" but not for "how long ago
 * was this backup taken."
 *
 * BackupManager.buildManifest no longer serializes an open session at all (mirrors
 * GattSyncManager.buildPayload's existing S19 policy), and parseSessions closes any that still arrive
 * (an older build's backup, or a hand-crafted one) using the BACKUP's OWN createdAt - a timestamp from
 * the same snapshot as startedAt, never the restoring device's current moment. This file tests
 * parseSessions directly (the defense-in-depth leg reachable regardless of what wrote the zip); the
 * buildManifest leg is a one-line filter verified by inspection (constructing BackupManager's full
 * manifest call graph is Room/DataStore/MediaStore-heavy relative to what it would add here).
 */
class BackupOpenSessionAuditTest {

    private fun parseSessions(arr: JSONArray, backupCreatedAt: Long): List<*> {
        val method = BackupManager.javaClass.getDeclaredMethod(
            "parseSessions", JSONArray::class.java, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(BackupManager, arr, backupCreatedAt) as List<*>
    }

    private fun openSessionJson(id: Long, startedAt: Long): JSONArray = JSONArray().put(
        JSONObject().apply {
            put("id", id)
            put("startedAt", startedAt)
            put("endedAt", JSONObject.NULL)
            put("isManual", false)
            put("syncId", "s-$id")
            put("updatedAt", startedAt)
            put("deleted", false)
        }
    )

    @Test
    fun `an open session from an older-build backup is closed using the backups own createdAt, not left open`() {
        val backupCreatedAt = System.currentTimeMillis() - 30L * 24 * 3_600_000L // "backup taken 30 days ago"
        val startedAt = backupCreatedAt - 3_600_000L // opened 1h before the backup was taken
        val result = parseSessions(openSessionJson(1L, startedAt), backupCreatedAt)
        val session = result.first() as com.ssbmedia.twogether.data.db.TogetherSession
        assertTrue("a restored open session must never be left open", session.endedAt != null)
        assertEquals(
            "must close at the backup's own createdAt, not the current moment",
            backupCreatedAt, session.endedAt
        )
    }

    @Test
    fun `an open session never credits time past what the backup itself represents, even if restored while together today`() {
        // The exact live-reproduced disaster: a 30-day-old open session must credit ~1h (its own
        // startedAt-to-backupCreatedAt span), never the 30-day gap to "now" (today, restore time).
        val today = System.currentTimeMillis()
        val backupCreatedAt = today - 30L * 24 * 3_600_000L
        val startedAt = backupCreatedAt - 3_600_000L
        val result = parseSessions(openSessionJson(1L, startedAt), backupCreatedAt)
        val session = result.first() as com.ssbmedia.twogether.data.db.TogetherSession
        val creditedMillis = session.endedAt!! - session.startedAt
        assertTrue(
            "credited duration must be ~1h (the backup's own span), not ~30 days to today",
            creditedMillis in 0..3_600_000L
        )
    }

    @Test
    fun `a startedAt after the backups own createdAt credits zero, never negative`() {
        val backupCreatedAt = System.currentTimeMillis() - 1_000L
        val startedAt = backupCreatedAt + 3_600_000L // clock/ordering anomaly: "started" after the backup snapshot
        val result = parseSessions(openSessionJson(1L, startedAt), backupCreatedAt)
        val session = result.first() as com.ssbmedia.twogether.data.db.TogetherSession
        assertTrue("must never credit a negative duration", session.endedAt!! >= session.startedAt)
        assertEquals("must credit exactly zero, not wrap or go negative", session.startedAt, session.endedAt)
    }

    @Test
    fun `a closed session passes through with its bounds untouched`() {
        val now = System.currentTimeMillis()
        val closed = JSONArray().put(
            JSONObject().apply {
                put("id", 1L); put("startedAt", now - 7_200_000L); put("endedAt", now - 3_600_000L)
                put("isManual", false); put("syncId", "s-closed"); put("updatedAt", now); put("deleted", false)
            }
        )
        val result = parseSessions(closed, now)
        val session = result.first() as com.ssbmedia.twogether.data.db.TogetherSession
        assertEquals(now - 7_200_000L, session.startedAt)
        assertEquals(now - 3_600_000L, session.endedAt)
    }
}
