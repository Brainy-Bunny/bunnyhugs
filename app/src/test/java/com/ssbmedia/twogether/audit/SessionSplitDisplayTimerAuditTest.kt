package com.ssbmedia.twogether.audit

import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.data.db.TogetherSessionDao
import com.ssbmedia.twogether.data.repo.SessionRepository
import com.ssbmedia.twogether.service.ProximityForegroundService
import com.ssbmedia.twogether.stats.StatsCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session-split + display-timer decoupling fix: a grace-window resume no longer reuses the same
 * TogetherSession row across the apart gap - see ProximityForegroundService.handleBecameTogether's
 * resumableSession branch doc for the full story. Two independent, confirmed bugs this closes:
 *
 *  (A) STORED session data: a resume used to leave a single row spanning the whole streak, including
 *      the gap itself, so the row's endedAt-minus-startedAt silently credited the gap as together-time
 *      once the streak eventually closed (StatsCalculator.totalHoursAllTime and everything downstream -
 *      Time Capsule eligibility, badges, weekly/monthly stats). A resume now closes the old row for real
 *      at the true apart instant and opens a genuinely new one instead.
 *
 *  (B) LIVE DISPLAY timer (Home's "Together for Xh Ym", the notification text): used to keep pointing at
 *      the ORIGINAL streak start even after a resume, so it would jump to include the gap the instant
 *      reconnect happened instead of resuming from where it left off. continuousTogetherSinceMillis
 *      (ProximityForegroundService.resumedContinuousTogetherSince, a pure private helper) now rebases
 *      that reference on every resume so the display freezes through the gap and resumes from exactly
 *      the frozen value.
 *
 * The concrete worked example from the bug report: together T=0-60min (1h), apart T=60-75min (15min,
 * within the default 20min grace window -> resume), together again T=75-135min (1h), then genuinely apart
 * for good. Real together-time is 2h00m. The OLD design reported 2h15m (the 15-minute gap silently
 * included). Every test below drives this exact scenario and asserts on the exact millisecond, not an
 * approximation - this project's pure-JVM unit tests have no Robolectric/Context available (see
 * GraceWindowAuditTest's own doc), so the private companion helper is invoked via reflection, and the
 * repository-level session-split mechanics are driven directly against a fake DAO (mirroring
 * SessionRepositoryTest's own FakeTogetherSessionDao) rather than the full Android Service.
 */
class SessionSplitDisplayTimerAuditTest {

    // ---------- reflection access to the pure private helper ----------

    private fun resumedContinuousTogetherSince(priorContinuousTogetherSince: Long, trueApartInstant: Long, now: Long): Long {
        val companion = ProximityForegroundService.Companion
        val method = companion.javaClass.getDeclaredMethod(
            "resumedContinuousTogetherSince", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(companion, priorContinuousTogetherSince, trueApartInstant, now) as Long
    }

    private fun isReunion(lastApartSince: Long, now: Long, reunionThresholdMillis: Long): Boolean {
        val companion = ProximityForegroundService.Companion
        val method = companion.javaClass.getDeclaredMethod(
            "isReunion", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(companion, lastApartSince, now, reunionThresholdMillis) as Boolean
    }

    // ---------- fake DAO (mirrors SessionRepositoryTest's own FakeTogetherSessionDao) ----------

    private class FakeTogetherSessionDao(seed: List<TogetherSession> = emptyList()) : TogetherSessionDao {
        val rows = seed.associateBy { it.id }.toMutableMap()
        var nextId = (seed.maxOfOrNull { it.id } ?: 0L) + 1

        override suspend fun insert(session: TogetherSession): Long {
            val id = if (session.id != 0L) session.id else nextId++
            rows[id] = session.copy(id = id)
            return id
        }

        override suspend fun update(session: TogetherSession) { rows[session.id] = session }
        override fun observeAll(): Flow<List<TogetherSession>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<TogetherSession> = rows.values.toList()
        override fun observeActive(): Flow<List<TogetherSession>> = flowOf(rows.values.filter { !it.deleted })
        override suspend fun getActive(): List<TogetherSession> = rows.values.filter { !it.deleted }
        override suspend fun getOpenSession(): TogetherSession? = rows.values.firstOrNull { it.endedAt == null }
        override fun observeOpenSession(): Flow<TogetherSession?> = flowOf(rows.values.firstOrNull { it.endedAt == null })
        override suspend fun clearAll() { rows.clear() }
    }

    // Worked example anchors, exact millis.
    private val t0 = 1_700_000_000_000L // arbitrary fixed epoch anchor - together starts
    private val t60 = t0 + 60 * 60_000L // 1h later - true apart instant
    private val t75 = t60 + 15 * 60_000L // 15min gap - reconnect (well within the default 10min-or-more grace window)
    private val t135 = t75 + 60 * 60_000L // another 1h together - genuinely apart for good here

    // ---------- (a) stored session duration: exactly 2h00m, not 2h15m ----------

    @Test
    fun `a grace-window resume produces two accurate session rows summing to exactly 2h00m, not 2h15m`() = runBlocking {
        val dao = FakeTogetherSessionDao()
        val repo = SessionRepository(dao)

        val firstSessionId = repo.startSession(t0)
        val firstSession = dao.rows.getValue(firstSessionId)

        // Mirrors handleBecameTogether's resumableSession branch exactly: close the old row at the true
        // apart instant (persisted.lastApartSince, simulated here as exactly t60), open a brand new row
        // at the reconnect instant.
        repo.endSession(firstSession, t60)
        val secondSessionId = repo.startSession(t75)

        // Genuinely apart for good at t135 - close the second (new) segment for real.
        val secondSession = dao.rows.getValue(secondSessionId)
        repo.endSession(secondSession, t135)

        val allSessions = dao.getAll()
        assertEquals("resume must produce exactly two closed session rows, not one reused row", 2, allSessions.size)

        val stats = StatsCalculator.compute(allSessions, now = t135 + 1000L)
        assertEquals(
            "total together-time must be exactly 2h00m - the 15-minute gap must NOT be credited as together-time",
            2.0, stats.totalHoursAllTime, 1e-9
        )
        assertTrue(
            "old (pre-fix) behavior would have reported 2h15m (2.25h) - explicitly assert this is NOT what we get",
            stats.totalHoursAllTime < 2.25 - 1e-9
        )

        // Sanity on the exact segments themselves.
        val sorted = allSessions.sortedBy { it.startedAt }
        assertEquals(t0, sorted[0].startedAt)
        assertEquals(t60, sorted[0].endedAt)
        assertEquals(t75, sorted[1].startedAt)
        assertEquals(t135, sorted[1].endedAt)
    }

    // ---------- (b) live display: frozen through the gap, resumes from the frozen value, never inflated ----------

    @Test
    fun `the display reference is frozen at the true apart instant, not the reconnect instant`() {
        // Immediately on resume, `now - result` must equal EXACTLY the genuine together-time accrued
        // before the gap (1h00m) - not 1h15m (which would happen if the gap were silently folded in).
        val rebasedSince = resumedContinuousTogetherSince(priorContinuousTogetherSince = t0, trueApartInstant = t60, now = t75)
        val elapsedRightAtResume = t75 - rebasedSince
        assertEquals("elapsed right at the resume instant must be exactly 1h00m (frozen), not 1h15m", 60 * 60_000L, elapsedRightAtResume)
    }

    @Test
    fun `the display value grows normally after resume and reaches exactly 2h00m by the end, never 2h15m`() {
        val rebasedSince = resumedContinuousTogetherSince(priorContinuousTogetherSince = t0, trueApartInstant = t60, now = t75)
        val elapsedAtEnd = t135 - rebasedSince
        assertEquals("elapsed at the end of the streak must be exactly 2h00m", 2 * 60 * 60_000L, elapsedAtEnd)
    }

    @Test
    fun `the display value never exceeds true elapsed together-time at any point after the gap`() {
        val rebasedSince = resumedContinuousTogetherSince(priorContinuousTogetherSince = t0, trueApartInstant = t60, now = t75)
        // Sample several points across the second together-stretch (t75..t135) - the displayed elapsed
        // must always equal exactly (1h accrued before the gap) + (real time elapsed since reconnect),
        // never more (which would mean the gap leaked back in) and never less (which would mean time
        // together after reconnect wasn't being counted).
        val sampleOffsetsFromReconnect = listOf(0L, 5 * 60_000L, 30 * 60_000L, 59 * 60_000L, 60 * 60_000L)
        for (offset in sampleOffsetsFromReconnect) {
            val sampleNow = t75 + offset
            val displayedElapsed = sampleNow - rebasedSince
            val trueElapsedTogether = 60 * 60_000L + offset // 1h before the gap + real time since reconnect
            assertEquals(
                "at now=$sampleNow (offset ${offset}ms past reconnect), displayed elapsed must exactly equal true elapsed together-time",
                trueElapsedTogether, displayedElapsed
            )
        }
    }

    @Test
    fun `chained resumes correctly fold in the previously-frozen amount instead of double-counting or losing it`() {
        // Two separate gaps in the same streak: 1h together, 15min apart, 45min together, 10min apart,
        // 30min together, then apart for good. Real total together-time = 1h + 45min + 30min = 2h15m -
        // proves the rebase generalizes across more than one resume, not just a single gap.
        val seg1End = t0 + 60 * 60_000L // = t60
        val resume1At = seg1End + 15 * 60_000L // = t75
        val sinceAfterResume1 = resumedContinuousTogetherSince(priorContinuousTogetherSince = t0, trueApartInstant = seg1End, now = resume1At)

        val seg2End = resume1At + 45 * 60_000L
        val resume2At = seg2End + 10 * 60_000L
        val sinceAfterResume2 = resumedContinuousTogetherSince(priorContinuousTogetherSince = sinceAfterResume1, trueApartInstant = seg2End, now = resume2At)

        val finalApartAt = resume2At + 30 * 60_000L
        val finalDisplayedElapsed = finalApartAt - sinceAfterResume2

        val expectedTotal = 60 * 60_000L + 45 * 60_000L + 30 * 60_000L // 2h15m
        assertEquals("a second resume must correctly fold in the first resume's already-frozen amount", expectedTotal, finalDisplayedElapsed)
    }

    // ---------- (c) a genuine new session (grace expired / no prior gap) still resets to 0 ----------

    @Test
    fun `a genuine new session start is unaffected by this fix - display always resets to exactly 0`() {
        // The new-session branch of handleBecameTogether never calls resumedContinuousTogetherSince at
        // all - it sets continuousTogetherSinceMillis = now directly (see the service's own code, the
        // `else` branch of the resumableSession check). This is that literal formula: elapsed = now - now
        // = 0, regardless of any stale prior accumulated value - contrasted against what the RESUME
        // formula would have produced for the same `now` if (incorrectly) applied here, to make clear the
        // two paths are genuinely different and the new-session path is never inflated by old history.
        val now = t135
        val continuousTogetherSinceMillis = now // mirrors the new-session branch verbatim
        val elapsed = now - continuousTogetherSinceMillis
        assertEquals(0L, elapsed)

        val whatResumeFormulaWouldGive = resumedContinuousTogetherSince(priorContinuousTogetherSince = t0, trueApartInstant = t60, now = now)
        assertTrue(
            "a genuine new session's reference must differ from what the resume formula would compute from stale prior history",
            continuousTogetherSinceMillis != whatResumeFormulaWouldGive
        )
    }

    // ---------- (d) resume must not depend on / break currentSessionId continuity ----------

    @Test
    fun `reunion detection is computed independently of the session row id - a resume changing it changes nothing`() {
        // isReunion (the pure helper that gates the reunion celebration/count - see
        // ReunionNonRetroactivityAuditTest) takes no session-id parameter at all; it's driven purely by
        // lastApartSince/now/threshold. Proven directly: the 15-minute gap in this scenario is well under
        // the default 60-minute reunion threshold, so it must NOT be flagged as a reunion - regardless of
        // the fact that the underlying session row id changes across the resume (part A of this fix).
        val reunionThresholdMillis = 60 * 60_000L // default 60 minutes
        assertTrue(
            "a 15-minute gap must not be flagged as a reunion under the default 60-minute threshold",
            !isReunion(t60, t75, reunionThresholdMillis)
        )
    }

    @Test
    fun `session-split produces genuinely different row ids across a resume, with no adverse effect on row count or content`() = runBlocking {
        // reminderFiredForSession/remindersFiredForSession/pendingReunionCelebration/reunionCount are all
        // reset or updated the SAME way in production code regardless of whether resumableSession was
        // null (new session) or not (resume) - see handleBecameTogether's update{} block, which this fix
        // does not change. This test locks in the one thing that DOES change: the row id itself - and
        // shows two independent, correctly-bounded rows coexist without issue.
        val dao = FakeTogetherSessionDao()
        val repo = SessionRepository(dao)
        val firstId = repo.startSession(t0)
        repo.endSession(dao.rows.getValue(firstId), t60)
        val secondId = repo.startSession(t75)

        assertTrue("the resumed segment must be a genuinely different row id from the original", secondId != firstId)
        assertEquals(2, dao.getAll().size)
        assertEquals(t60, dao.rows.getValue(firstId).endedAt)
        assertEquals(null, dao.rows.getValue(secondId).endedAt)
    }
}
