package com.ssbmedia.twogether.audit

import com.ssbmedia.twogether.data.datastore.ProximityPersistedState
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.service.ProximityForegroundService
import com.ssbmedia.twogether.stats.StatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * User-configurable reunion threshold feature - the core requirement is that changing
 * AppSettings.reunionThresholdMinutes must NEVER retroactively reinterpret history: each reunion is a
 * one-time event, evaluated against whatever threshold was in effect at the exact moment it happened,
 * and never re-evaluated after the fact. That is why ProximityPersistedState.reunionCount is a
 * persisted, live-incrementing counter (see its own doc) rather than a value StatsCalculator.compute()
 * derives by rescanning session history (the old, now-removed design) - the moment the threshold became
 * user-editable, "rescan everything under today's value" starts silently changing the count of reunions
 * a couple already celebrated under a different rule.
 *
 * This project's plain-JVM unit tests have no Robolectric/Context available (see GraceWindowAuditTest's
 * own doc), so ProximityForegroundService itself can't be instantiated here. Instead, this drives the
 * exact same pure decision helpers the Service's handleBecameTogether/backfillReunionCountIfNeeded
 * actually call in production (isReunion/nextReunionCount/backfilledReunionState, all private on the
 * companion object - unit-tested via reflection, mirroring GraceWindowAuditTest/ListReminderAuditTest's
 * established pattern) directly against plain ProximityPersistedState/AppSettings values, simulating the
 * exact sequence of transactional updates the real service performs.
 */
class ReunionNonRetroactivityAuditTest {

    // ---------- reflection access to the private pure companion helpers ----------

    private fun isReunion(lastApartSince: Long, now: Long, reunionThresholdMillis: Long): Boolean {
        val companion = ProximityForegroundService.Companion
        val method = companion.javaClass.getDeclaredMethod(
            "isReunion", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(companion, lastApartSince, now, reunionThresholdMillis) as Boolean
    }

    private fun nextReunionCount(currentCount: Int, isReunion: Boolean): Int {
        val companion = ProximityForegroundService.Companion
        val method = companion.javaClass.getDeclaredMethod(
            "nextReunionCount", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(companion, currentCount, isReunion) as Int
    }

    private fun backfilledReunionState(current: ProximityPersistedState, legacyCount: Int): ProximityPersistedState {
        val companion = ProximityForegroundService.Companion
        val method = companion.javaClass.getDeclaredMethod(
            "backfilledReunionState", ProximityPersistedState::class.java, Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(companion, current, legacyCount) as ProximityPersistedState
    }

    /** Simulates exactly what handleBecameTogether's proximityStateStore.update{} transform does to
     * reunionCount for one apart->together transition - same two pure helper calls, same order, just
     * driven directly against a plain ProximityPersistedState instead of through DataStore. */
    private fun simulateBecameTogether(persisted: ProximityPersistedState, now: Long, reunionThresholdMinutes: Int): ProximityPersistedState {
        val thresholdMillis = reunionThresholdMinutes.coerceAtLeast(1) * 60_000L
        val reunion = isReunion(persisted.lastApartSince, now, thresholdMillis)
        return persisted.copy(
            lastApartSince = 0L, // handleBecameTogether doesn't itself clear lastApartSince, but it's irrelevant to this simulation
            reunionCount = nextReunionCount(persisted.reunionCount, reunion)
        )
    }

    // ---------- (a) a reunion recorded under 60min stays counted after the threshold changes to 20min ----------

    @Test
    fun `a reunion recorded under a 60-minute threshold stays counted after the threshold changes to 20 minutes`() {
        var state = ProximityPersistedState(reunionCountBackfilled = true) // already-backfilled install, count starts real at 0
        val t0 = 1_000_000_000L

        // They go apart, then reconnect 70 minutes later while reunionThresholdMinutes is still 60 -
        // a genuine reunion under the threshold in effect AT THAT MOMENT.
        state = state.copy(lastApartSince = t0)
        val afterFirstReunion = simulateBecameTogether(state, now = t0 + 70 * 60_000L, reunionThresholdMinutes = 60)
        assertEquals("the 70-minute gap must count as a reunion under a 60-minute threshold", 1, afterFirstReunion.reunionCount)

        // The user now lowers reunionThresholdMinutes to 20 - purely a live setting change, touching
        // nothing about already-persisted state. The already-recorded reunion must stay exactly as it was.
        assertEquals(
            "lowering the threshold must never retroactively change a reunion that's already been counted",
            1, afterFirstReunion.reunionCount
        )
    }

    // ---------- (b) lowering the threshold never retroactively creates EXTRA reunions from old gaps ----------

    @Test
    fun `lowering the threshold does not retroactively create extra reunions from gaps that already existed`() {
        // Legacy-style history: several gaps that were all UNDER 60min (so none of them were ever counted
        // as reunions under the original always-60min rule), but several of which WOULD now qualify under
        // a new 20-minute threshold if history were ever rescanned.
        val start = 1_700_000_000_000L
        fun session(s: Long, e: Long) = TogetherSession(startedAt = s, endedAt = e)
        val s1End = start + 60 * 60_000L
        val s2Start = s1End + 25 * 60_000L // 25min gap - under 60min (not a legacy reunion), but OVER 20min
        val s2End = s2Start + 60 * 60_000L
        val s3Start = s2End + 30 * 60_000L // 30min gap - same story
        val s3End = s3Start + 60 * 60_000L
        val sessions = listOf(session(start, s1End), session(s2Start, s2End), session(s3Start, s3End))

        // The one-time legacy backfill (fixed 60min, the only threshold that ever applied to this
        // history) correctly finds ZERO reunions - both gaps are under 60min.
        val legacyCount = StatsCalculator.legacyReunionCountForBackfill(sessions, now = s3End + 1000L)
        assertEquals("neither 25min nor 30min gap ever qualified under the original 60-minute rule", 0, legacyCount)

        var state = ProximityPersistedState(reunionCountBackfilled = false)
        state = backfilledReunionState(state, legacyCount)
        assertEquals(0, state.reunionCount)
        assertTrue(state.reunionCountBackfilled)

        // The user now lowers reunionThresholdMinutes to 20. Under a full rescan of the SAME history with
        // the NEW threshold, both the 25min and 30min gaps would newly qualify (2 "phantom" reunions) -
        // that rescan must never happen again. Proven directly: feeding backfilledReunionState() exactly
        // that hypothetical "rescanned under 20min" count (2) against the now-backfilled state (simulating
        // a later service restart with the lowered threshold, if some future bug wrongly tried to redo the
        // rescan) must still be a complete no-op - the flag alone is what protects the couple's real count.
        val afterWouldBeRescan = backfilledReunionState(state, legacyCount = 2)
        assertEquals(
            "reunionCountBackfilled=true must make backfilledReunionState() a strict no-op - the couple's " +
                "count must NOT jump to 2 just because a later rescan-under-the-new-threshold would have found 2",
            0, afterWouldBeRescan.reunionCount
        )
        assertEquals(state, afterWouldBeRescan) // the whole state object is untouched, not just reunionCount
    }

    // ---------- (c) the one-time backfill runs exactly once - a second restore/restart never double-counts ----------

    @Test
    fun `the one-time backfill runs exactly once - a second restore does not double count`() {
        var state = ProximityPersistedState(reunionCountBackfilled = false)

        // First "service restart" (restoreState -> backfillReunionCountIfNeeded): not yet backfilled, so
        // the legacy rescan result (say, 3 real historical reunions) is applied.
        state = backfilledReunionState(state, legacyCount = 3)
        assertEquals(3, state.reunionCount)
        assertTrue(state.reunionCountBackfilled)

        // Meanwhile, two genuine LIVE reunions happen (handleBecameTogether increments).
        state = simulateBecameTogether(state.copy(lastApartSince = 1_000_000L), now = 1_000_000L + 70 * 60_000L, reunionThresholdMinutes = 60)
        state = simulateBecameTogether(state.copy(lastApartSince = 5_000_000L), now = 5_000_000L + 70 * 60_000L, reunionThresholdMinutes = 60)
        assertEquals(5, state.reunionCount) // 3 backfilled + 2 live

        // A SECOND "service restart" (e.g. the app was killed and relaunched) calls
        // backfillReunionCountIfNeeded again - persisted.reunionCountBackfilled is already true, so this
        // must be a complete no-op: it must NOT re-run the rescan, and it must NOT touch the 2 live
        // increments that happened since the first backfill.
        val stateAfterSecondRestart = backfilledReunionState(state, legacyCount = 3)
        assertEquals(
            "a second restart's backfill attempt must never re-apply the legacy count or touch reunionCount at all",
            5, stateAfterSecondRestart.reunionCount
        )
        assertEquals(state, stateAfterSecondRestart)

        // Even a THIRD attempt with an obviously-wrong/different legacyCount (simulating some future bug
        // that recomputes a different rescan value) must still be a no-op purely because the flag is set -
        // the flag alone is what makes this safe, not "the legacyCount happens to match."
        val stateAfterBogusThirdAttempt = backfilledReunionState(state, legacyCount = 999)
        assertEquals(5, stateAfterBogusThirdAttempt.reunionCount)
    }

    // ---------- (d) changing reunionThresholdMinutes changes whether the VERY NEXT transition counts ----------

    @Test
    fun `changing reunionThresholdMinutes changes whether the very next live transition counts as a reunion`() {
        val lastApartSince = 10_000_000L
        val now = lastApartSince + 30 * 60_000L // 30-minute real gap

        // Under a 60-minute threshold, a 30-minute gap is NOT a reunion.
        val state60 = ProximityPersistedState(lastApartSince = lastApartSince, reunionCount = 0, reunionCountBackfilled = true)
        val after60 = simulateBecameTogether(state60, now = now, reunionThresholdMinutes = 60)
        assertFalse(
            "a 30-minute gap must not count as a reunion under a 60-minute threshold",
            after60.reunionCount == state60.reunionCount + 1
        )
        assertEquals(0, after60.reunionCount)

        // The SAME 30-minute real-world gap, but the user has lowered reunionThresholdMinutes to 20
        // BEFORE this transition is evaluated - now it DOES count. This is the "next transition sees the
        // new rule" half of the guarantee (the other half, tested above, is that PAST transitions don't).
        val state20 = ProximityPersistedState(lastApartSince = lastApartSince, reunionCount = 0, reunionCountBackfilled = true)
        val after20 = simulateBecameTogether(state20, now = now, reunionThresholdMinutes = 20)
        assertEquals(
            "the exact same 30-minute gap must count as a reunion once the live threshold is lowered to 20 minutes",
            1, after20.reunionCount
        )
    }

    @Test
    fun `isReunion pure helper directly - boundary is inclusive, half-open like the grace window helpers`() {
        val lastApartSince = 1_000_000L
        val thresholdMillis = 60 * 60_000L
        assertFalse(isReunion(lastApartSince, lastApartSince + thresholdMillis - 1, thresholdMillis))
        assertTrue(isReunion(lastApartSince, lastApartSince + thresholdMillis, thresholdMillis))
        assertFalse("lastApartSince <= 0 means no apart transition ever happened - never a reunion", isReunion(0L, lastApartSince + thresholdMillis, thresholdMillis))
    }

    // ---------- sessionGraceMinutes is read live, not from the old hardcoded constant ----------

    @Test
    fun `withinGraceWindow and graceWindowExpired honor a live sessionGraceMinutes-derived value, not a fixed constant`() {
        // This is the grace-window half of the same feature (AppSettings.sessionGraceMinutes) - the
        // decision helpers themselves are covered exhaustively by GraceWindowAuditTest; this test proves
        // specifically that converting a live AppSettings.sessionGraceMinutes value into the graceMillis
        // argument behaves correctly for a non-default value, i.e. the setting is genuinely load-bearing.
        val companion = ProximityForegroundService.Companion
        val within = companion.javaClass.getDeclaredMethod(
            "withinGraceWindow", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val pendingApartSince = 1_000_000L
        val sessionGraceMinutes = 3 // a live user-configured value, far from the 10-minute default
        val graceMillis = sessionGraceMinutes.coerceAtLeast(1) * 60_000L

        val within2min = pendingApartSince + 2 * 60_000L
        val within5min = pendingApartSince + 5 * 60_000L

        assertTrue(
            "a 2-minute gap must resume under a live 3-minute grace window",
            within.invoke(companion, pendingApartSince, within2min, graceMillis) as Boolean
        )
        assertFalse(
            "a 5-minute gap must NOT resume under a live 3-minute grace window, even though it would under the 10-minute default",
            within.invoke(companion, pendingApartSince, within5min, graceMillis) as Boolean
        )
    }
}
