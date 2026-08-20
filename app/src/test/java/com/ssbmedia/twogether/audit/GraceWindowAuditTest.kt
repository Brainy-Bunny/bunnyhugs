package com.ssbmedia.twogether.audit

import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.service.ProximityForegroundService
import com.ssbmedia.twogether.stats.StatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-FIX-PLAN.md Phase 2, items 9 & 10.
 *
 * Item 9 (together-timer grace-window fix): ProximityForegroundService.handleBecameApart no longer
 * closes the open TogetherSession row (or resets the continuous-together timer) the instant the fast
 * ~100s isTogether flip happens - it persists pendingApartSince and leaves the row open for
 * SESSION_GRACE_MILLIS (~10 min). A reconnect inside that window resumes the SAME session
 * (handleBecameTogether's resume path); a reconnect after it expires (or no reconnect at all,
 * checkGraceExpiry) gets/produces a genuinely new one. The actual decision of "resume vs new session" /
 * "close it yet or not" is delegated to two small pure private functions on the companion object
 * (withinGraceWindow/graceWindowExpired) - this is what's unit-tested directly here via reflection,
 * mirroring BackupOpenSessionAuditTest's pattern for testing a private implementation detail without
 * standing up the full Android Service (this codebase's unit tests have no Robolectric/Context
 * available, so the Service itself can't be instantiated in a local JVM test).
 *
 * Item 10 (reunion same-day requirement removed): StatsCalculator.countReunions used to ALSO require
 * the apart-start and the reunion to fall on the same calendar day - an overnight or multi-day apart
 * gap never counted as a reunion no matter how long it was. That's tested directly through the public
 * StatsCalculator.compute() API below, no reflection needed.
 */
class GraceWindowAuditTest {

    // ---------- Item 9: pure grace-window decision helpers ----------

    private fun invokeGraceHelper(name: String, pendingApartSince: Long, now: Long): Boolean {
        val companion = ProximityForegroundService.Companion
        val method = companion.javaClass.getDeclaredMethod(
            name, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(companion, pendingApartSince, now) as Boolean
    }

    private fun withinGraceWindow(pendingApartSince: Long, now: Long) =
        invokeGraceHelper("withinGraceWindow", pendingApartSince, now)

    private fun graceWindowExpired(pendingApartSince: Long, now: Long) =
        invokeGraceHelper("graceWindowExpired", pendingApartSince, now)

    private val graceMillis = ProximityForegroundService.SESSION_GRACE_MILLIS

    @Test
    fun `SESSION_GRACE_MILLIS is exactly 10 minutes`() {
        assertEquals(10 * 60 * 1000L, graceMillis)
    }

    @Test
    fun `a brief under-10-minute gap is within the grace window - should resume, not restart`() {
        val pendingApartSince = 1_000_000L
        val now = pendingApartSince + 5 * 60 * 1000L // 5 minutes later
        assertTrue("a 5-minute gap must be within the grace window", withinGraceWindow(pendingApartSince, now))
        assertFalse("a 5-minute gap must NOT be reported as expired", graceWindowExpired(pendingApartSince, now))
    }

    @Test
    fun `a gap of exactly the grace window is treated as expired, not within (half-open interval)`() {
        val pendingApartSince = 1_000_000L
        val now = pendingApartSince + graceMillis // exactly 10 minutes later
        assertFalse("exactly 10 minutes must no longer count as 'within'", withinGraceWindow(pendingApartSince, now))
        assertTrue("exactly 10 minutes must count as expired", graceWindowExpired(pendingApartSince, now))
    }

    @Test
    fun `a gap longer than the grace window is expired - session should close for real, timer resets`() {
        val pendingApartSince = 1_000_000L
        val now = pendingApartSince + 45 * 60 * 1000L // 45 minutes later - long disconnect
        assertFalse(withinGraceWindow(pendingApartSince, now))
        assertTrue(graceWindowExpired(pendingApartSince, now))
    }

    @Test
    fun `no pending apart (0L) is neither within nor expired - nothing to act on`() {
        val now = 5_000_000L
        assertFalse("0L means no apart transition is pending a session-close decision", withinGraceWindow(0L, now))
        assertFalse(graceWindowExpired(0L, now))
    }

    @Test
    fun `withinGraceWindow and graceWindowExpired are exact complements for any positive pendingApartSince`() {
        val pendingApartSince = 2_000_000L
        // Sweep gaps from just after the transition through well past the grace window.
        val gapsToCheck = listOf(0L, 1L, 60_000L, graceMillis - 1, graceMillis, graceMillis + 1, graceMillis * 3)
        for (gap in gapsToCheck) {
            val now = pendingApartSince + gap
            val within = withinGraceWindow(pendingApartSince, now)
            val expired = graceWindowExpired(pendingApartSince, now)
            assertTrue(
                "for gap=$gap, exactly one of within/expired must be true (within=$within, expired=$expired)",
                within != expired
            )
        }
    }

    // ---------- Item 9/10 interaction note ----------

    @Test
    fun `the grace window is far shorter than the reunion gap - a resume can never look like a reunion`() {
        // Structural guarantee the plan's interaction note relies on: since a grace-window "resume"
        // reconnect happens at most SESSION_GRACE_MILLIS after the apart flip, and a reunion requires a
        // gap of at least REUNION_GAP_MILLIS, the two thresholds must never overlap.
        assertTrue(
            "SESSION_GRACE_MILLIS must stay well under REUNION_GAP_MILLIS for item 9/10 to never collide",
            graceMillis < StatsCalculator.REUNION_GAP_MILLIS
        )
    }

    // ---------- Item 10: same-calendar-day requirement removed from reunion counting ----------

    private fun session(start: Long, end: Long) = TogetherSession(startedAt = start, endedAt = end)

    @Test
    fun `an overnight reunion now counts - apart 11pm, together again 1am, over 60min gap`() {
        // Day 1 22:00-23:00, apart, Day 2 01:00-02:00 - a 2-hour overnight gap spanning midnight.
        val day1Start = 1_700_000_000_000L // arbitrary fixed epoch anchor
        val eveningEnd = day1Start + 60 * 60 * 1000L // 1h session
        val nextMorningStart = eveningEnd + 2 * 60 * 60 * 1000L // apart 2h, crossing midnight
        val nextMorningEnd = nextMorningStart + 60 * 60 * 1000L

        val sessions = listOf(
            session(day1Start, eveningEnd),
            session(nextMorningStart, nextMorningEnd)
        )
        val stats = StatsCalculator.compute(sessions, now = nextMorningEnd + 1000L)
        assertEquals(
            "an overnight gap over the 60-minute threshold must now count as a reunion",
            1, stats.reunionCount
        )
    }

    @Test
    fun `a multi-day apart reunion now counts too`() {
        val start = 1_700_000_000_000L
        val firstEnd = start + 60 * 60 * 1000L
        val threeDaysLater = firstEnd + 3L * 24 * 60 * 60 * 1000L
        val secondEnd = threeDaysLater + 60 * 60 * 1000L

        val sessions = listOf(session(start, firstEnd), session(threeDaysLater, secondEnd))
        val stats = StatsCalculator.compute(sessions, now = secondEnd + 1000L)
        assertEquals("a multi-day apart gap must count as a reunion", 1, stats.reunionCount)
    }

    @Test
    fun `a same-day short gap under 60min still does NOT count as a reunion`() {
        val start = 1_700_000_000_000L
        val firstEnd = start + 60 * 60 * 1000L
        val tenMinLater = firstEnd + 10 * 60 * 1000L // well under the 60-minute gap threshold
        val secondEnd = tenMinLater + 60 * 60 * 1000L

        val sessions = listOf(session(start, firstEnd), session(tenMinLater, secondEnd))
        val stats = StatsCalculator.compute(sessions, now = secondEnd + 1000L)
        assertEquals("a sub-threshold gap must never count as a reunion, same-day or not", 0, stats.reunionCount)
    }

    @Test
    fun `multiple overnight and same-day reunions across several days all count`() {
        val start = 1_700_000_000_000L
        val s1End = start + 60 * 60 * 1000L
        // Reunion 1: overnight gap (2h, crosses into "next day" conceptually via the millis gap).
        val s2Start = s1End + 2 * 60 * 60 * 1000L
        val s2End = s2Start + 60 * 60 * 1000L
        // Reunion 2: another >60min gap a few days later.
        val s3Start = s2End + 4L * 24 * 60 * 60 * 1000L
        val s3End = s3Start + 60 * 60 * 1000L

        val sessions = listOf(session(start, s1End), session(s2Start, s2End), session(s3Start, s3End))
        val stats = StatsCalculator.compute(sessions, now = s3End + 1000L)
        assertEquals(2, stats.reunionCount)
    }
}
