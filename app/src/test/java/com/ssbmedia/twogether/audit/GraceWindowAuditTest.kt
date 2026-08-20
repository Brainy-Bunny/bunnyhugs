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
 * ~100s isTogether flip happens - it persists pendingApartSince and leaves the row open for a grace
 * window (~10 min by default). A reconnect inside that window resumes the SAME session
 * (handleBecameTogether's resume path); a reconnect after it expires (or no reconnect at all,
 * checkGraceExpiry) gets/produces a genuinely new one. The actual decision of "resume vs new session" /
 * "close it yet or not" is delegated to two small pure private functions on the companion object
 * (withinGraceWindow/graceWindowExpired) - this is what's unit-tested directly here via reflection,
 * mirroring BackupOpenSessionAuditTest's pattern for testing a private implementation detail without
 * standing up the full Android Service (this codebase's unit tests have no Robolectric/Context
 * available, so the Service itself can't be instantiated in a local JVM test).
 *
 * Reunion-count non-retroactivity feature: the grace window used to be a hardcoded constant
 * (ProximityForegroundService.SESSION_GRACE_MILLIS) baked directly into withinGraceWindow/
 * graceWindowExpired - it's now a live, user-configurable setting (AppSettings.sessionGraceMinutes,
 * same 10-minute default) that the SERVICE reads and passes in as an explicit graceMillis parameter, so
 * these two pure helpers now take it as a third argument rather than reading a constant internally. This
 * test drives that parameter directly rather than depending on any particular default.
 *
 * Item 10 (reunion same-day requirement removed): StatsCalculator.countReunions used to ALSO require
 * the apart-start and the reunion to fall on the same calendar day - an overnight or multi-day apart
 * gap never counted as a reunion no matter how long it was. That's tested directly through the public
 * StatsCalculator.compute() API below, no reflection needed.
 */
class GraceWindowAuditTest {

    // ---------- Item 9: pure grace-window decision helpers ----------

    private fun invokeGraceHelper(name: String, pendingApartSince: Long, now: Long, graceMillis: Long): Boolean {
        val companion = ProximityForegroundService.Companion
        val method = companion.javaClass.getDeclaredMethod(
            name, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(companion, pendingApartSince, now, graceMillis) as Boolean
    }

    private fun withinGraceWindow(pendingApartSince: Long, now: Long, graceMillis: Long) =
        invokeGraceHelper("withinGraceWindow", pendingApartSince, now, graceMillis)

    private fun graceWindowExpired(pendingApartSince: Long, now: Long, graceMillis: Long) =
        invokeGraceHelper("graceWindowExpired", pendingApartSince, now, graceMillis)

    // Matches AppSettings.sessionGraceMinutes' default (10 minutes) - this test drives the helpers'
    // explicit graceMillis parameter directly, it's no longer reading a compile-time constant off the
    // Service.
    private val graceMillis = 10 * 60 * 1000L

    @Test
    fun `a brief under-10-minute gap is within the grace window - should resume, not restart`() {
        val pendingApartSince = 1_000_000L
        val now = pendingApartSince + 5 * 60 * 1000L // 5 minutes later
        assertTrue("a 5-minute gap must be within the grace window", withinGraceWindow(pendingApartSince, now, graceMillis))
        assertFalse("a 5-minute gap must NOT be reported as expired", graceWindowExpired(pendingApartSince, now, graceMillis))
    }

    @Test
    fun `a gap of exactly the grace window is treated as expired, not within (half-open interval)`() {
        val pendingApartSince = 1_000_000L
        val now = pendingApartSince + graceMillis // exactly 10 minutes later
        assertFalse("exactly 10 minutes must no longer count as 'within'", withinGraceWindow(pendingApartSince, now, graceMillis))
        assertTrue("exactly 10 minutes must count as expired", graceWindowExpired(pendingApartSince, now, graceMillis))
    }

    @Test
    fun `a gap longer than the grace window is expired - session should close for real, timer resets`() {
        val pendingApartSince = 1_000_000L
        val now = pendingApartSince + 45 * 60 * 1000L // 45 minutes later - long disconnect
        assertFalse(withinGraceWindow(pendingApartSince, now, graceMillis))
        assertTrue(graceWindowExpired(pendingApartSince, now, graceMillis))
    }

    @Test
    fun `no pending apart (0L) is neither within nor expired - nothing to act on`() {
        val now = 5_000_000L
        assertFalse("0L means no apart transition is pending a session-close decision", withinGraceWindow(0L, now, graceMillis))
        assertFalse(graceWindowExpired(0L, now, graceMillis))
    }

    @Test
    fun `withinGraceWindow and graceWindowExpired are exact complements for any positive pendingApartSince`() {
        val pendingApartSince = 2_000_000L
        // Sweep gaps from just after the transition through well past the grace window.
        val gapsToCheck = listOf(0L, 1L, 60_000L, graceMillis - 1, graceMillis, graceMillis + 1, graceMillis * 3)
        for (gap in gapsToCheck) {
            val now = pendingApartSince + gap
            val within = withinGraceWindow(pendingApartSince, now, graceMillis)
            val expired = graceWindowExpired(pendingApartSince, now, graceMillis)
            assertTrue(
                "for gap=$gap, exactly one of within/expired must be true (within=$within, expired=$expired)",
                within != expired
            )
        }
    }

    @Test
    fun `withinGraceWindow and graceWindowExpired correctly honor a non-default graceMillis argument`() {
        // Reunion-count non-retroactivity feature: graceMillis is now a caller-supplied live setting, not
        // a compile-time constant baked into these functions - prove they actually use the ARGUMENT, not
        // some leftover hardcoded 10-minute value.
        val pendingApartSince = 1_000_000L
        val customGraceMillis = 2 * 60 * 1000L // 2 minutes, deliberately far from the 10-minute default
        val within1min = pendingApartSince + 60 * 1000L // 1 minute later
        val within5min = pendingApartSince + 5 * 60 * 1000L // 5 minutes later - within the 10min default, but NOT within a 2min custom window

        assertTrue(withinGraceWindow(pendingApartSince, within1min, customGraceMillis))
        assertFalse(graceWindowExpired(pendingApartSince, within1min, customGraceMillis))

        assertFalse(
            "a 5-minute gap must NOT be within a 2-minute custom grace window, even though it would be within the 10-minute default",
            withinGraceWindow(pendingApartSince, within5min, customGraceMillis)
        )
        assertTrue(graceWindowExpired(pendingApartSince, within5min, customGraceMillis))
    }

    // ---------- Item 9/10 interaction note ----------

    @Test
    fun `the DEFAULT grace window is far shorter than the DEFAULT reunion gap - a resume can never look like a reunion out of the box`() {
        // This used to be a structural, compiler-enforced guarantee (two hardcoded constants). Now that
        // AppSettings.sessionGraceMinutes and AppSettings.reunionThresholdMinutes are both independently
        // user-configurable, that guarantee no longer holds universally - a user CAN set
        // reunionThresholdMinutes below sessionGraceMinutes, in which case a grace-window "resume" can
        // also be flagged as a reunion (see handleBecameTogether's own doc - this is an accepted edge
        // case of the two settings being independent, not a bug). What still holds, and is worth locking
        // in here, is that the DEFAULTS preserve the original relationship out of the box.
        assertTrue(
            "the default grace window (10min) must stay under the default reunion threshold (60min) so a fresh install never sees this edge case",
            graceMillis < StatsCalculator.REUNION_GAP_MILLIS
        )
    }

    // ---------- Item 10: same-calendar-day requirement removed from reunion counting ----------
    //
    // Reunion-count non-retroactivity feature: StatsCalculator.compute()'s reunionCount is no longer
    // derived by rescanning session history (that's the whole point of this feature - see
    // ProximityPersistedState.reunionCount's own doc) - it's now just a pass-through parameter. The
    // gap-counting rescan logic these tests exercise (Item 10's same-calendar-day fix) still exists, but
    // only as StatsCalculator.legacyReunionCountForBackfill - the one-time migration helper - so these
    // tests now call that directly instead of reading StatsCalculator.compute(...).reunionCount.

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
        val legacyCount = StatsCalculator.legacyReunionCountForBackfill(sessions, now = nextMorningEnd + 1000L)
        assertEquals(
            "an overnight gap over the 60-minute threshold must now count as a reunion",
            1, legacyCount
        )
    }

    @Test
    fun `a multi-day apart reunion now counts too`() {
        val start = 1_700_000_000_000L
        val firstEnd = start + 60 * 60 * 1000L
        val threeDaysLater = firstEnd + 3L * 24 * 60 * 60 * 1000L
        val secondEnd = threeDaysLater + 60 * 60 * 1000L

        val sessions = listOf(session(start, firstEnd), session(threeDaysLater, secondEnd))
        val legacyCount = StatsCalculator.legacyReunionCountForBackfill(sessions, now = secondEnd + 1000L)
        assertEquals("a multi-day apart gap must count as a reunion", 1, legacyCount)
    }

    @Test
    fun `a same-day short gap under 60min still does NOT count as a reunion`() {
        val start = 1_700_000_000_000L
        val firstEnd = start + 60 * 60 * 1000L
        val tenMinLater = firstEnd + 10 * 60 * 1000L // well under the 60-minute gap threshold
        val secondEnd = tenMinLater + 60 * 60 * 1000L

        val sessions = listOf(session(start, firstEnd), session(tenMinLater, secondEnd))
        val legacyCount = StatsCalculator.legacyReunionCountForBackfill(sessions, now = secondEnd + 1000L)
        assertEquals("a sub-threshold gap must never count as a reunion, same-day or not", 0, legacyCount)
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
        val legacyCount = StatsCalculator.legacyReunionCountForBackfill(sessions, now = s3End + 1000L)
        assertEquals(2, legacyCount)
    }
}
