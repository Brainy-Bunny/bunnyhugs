package com.ssbmedia.twogether.data.repo

import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.data.db.TogetherSessionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-FIX-PLAN.md Phase 3 item 19: coverage for [SessionRepository.updateManualSession] and the new
 * edit-propagation branch this adds to [SessionRepository.mergeRemoteSessions] - called out explicitly in
 * the plan as deserving real test coverage given the doc-comment change (a manual session used to be
 * documented as immutable post-creation; it no longer is).
 */
class SessionRepositoryTest {

    private class FakeTogetherSessionDao(seed: List<TogetherSession> = emptyList()) : TogetherSessionDao {
        // Every seed row in this test file is given an explicit non-zero id, so a plain associateBy is
        // sufficient here (no need to synthesize ids for zero-id seed rows).
        val rows = seed.associateBy { it.id }.toMutableMap()
        var nextId = (seed.maxOfOrNull { it.id } ?: 0L) + 1
        var updateCount = 0
        var insertCount = 0

        override suspend fun insert(session: TogetherSession): Long {
            insertCount++
            val id = if (session.id != 0L) session.id else nextId++
            rows[id] = session.copy(id = id)
            return id
        }

        override suspend fun update(session: TogetherSession) {
            updateCount++
            rows[session.id] = session
        }

        override fun observeAll(): Flow<List<TogetherSession>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<TogetherSession> = rows.values.toList()
        override fun observeActive(): Flow<List<TogetherSession>> = flowOf(rows.values.filter { !it.deleted })
        override suspend fun getActive(): List<TogetherSession> = rows.values.filter { !it.deleted }
        override suspend fun getOpenSession(): TogetherSession? = rows.values.firstOrNull { it.endedAt == null }
        override fun observeOpenSession(): Flow<TogetherSession?> = flowOf(getOpenSessionSync())
        private fun getOpenSessionSync(): TogetherSession? = rows.values.firstOrNull { it.endedAt == null }
        override suspend fun clearAll() { rows.clear() }
    }

    private fun manualSession(
        id: Long = 1L,
        startedAt: Long = 1_700_000_000_000L,
        endedAt: Long = 1_700_003_600_000L,
        syncId: String = "sync-1",
        updatedAt: Long = 1_000L,
        deleted: Boolean = false
    ) = TogetherSession(
        id = id, startedAt = startedAt, endedAt = endedAt, isManual = true,
        syncId = syncId, updatedAt = updatedAt, deleted = deleted
    )

    // ---------------------------------------------------------------------------------------------
    // updateManualSession - local edit
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `updateManualSession writes the new start and end for a manual session`() = runBlocking {
        val original = manualSession()
        val dao = FakeTogetherSessionDao(listOf(original))
        val repo = SessionRepository(dao)

        val newStart = original.startedAt + 60_000L
        val newEnd = original.endedAt!! + 60_000L
        repo.updateManualSession(original, newStart, newEnd)

        val stored = dao.rows.getValue(1L)
        assertEquals(newStart, stored.startedAt)
        assertEquals(newEnd, stored.endedAt)
        assertTrue("a real edit must bump updatedAt so it propagates on the next sync", stored.updatedAt > original.updatedAt)
    }

    @Test
    fun `updateManualSession no-ops for a genuine BLE-detected session`() = runBlocking {
        val bleSession = manualSession().copy(isManual = false)
        val dao = FakeTogetherSessionDao(listOf(bleSession))
        val repo = SessionRepository(dao)

        repo.updateManualSession(bleSession, bleSession.startedAt + 1_000L, bleSession.endedAt!! + 1_000L)

        assertEquals("a real historical BLE record must stay untouchable", 0, dao.updateCount)
        assertEquals(bleSession, dao.rows.getValue(1L))
    }

    @Test
    fun `updateManualSession no-ops for a degenerate non-positive duration`() = runBlocking {
        val original = manualSession()
        val dao = FakeTogetherSessionDao(listOf(original))
        val repo = SessionRepository(dao)

        repo.updateManualSession(original, startedAt = 5_000L, endedAt = 5_000L)

        assertEquals(0, dao.updateCount)
    }

    // ---------------------------------------------------------------------------------------------
    // mergeRemoteSessions - the new edit-propagation branch
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `mergeRemoteSessions adopts a newer remote edit to a known local manual session`() = runBlocking {
        val local = manualSession()
        val dao = FakeTogetherSessionDao(listOf(local))
        val repo = SessionRepository(dao)

        val remoteEdit = local.copy(
            startedAt = local.startedAt + 120_000L,
            endedAt = local.endedAt!! + 120_000L,
            updatedAt = local.updatedAt + 1L
        )
        repo.mergeRemoteSessions(listOf(remoteEdit))

        val stored = dao.rows.getValue(1L)
        assertEquals(remoteEdit.startedAt, stored.startedAt)
        assertEquals(remoteEdit.endedAt, stored.endedAt)
        assertEquals(remoteEdit.updatedAt, stored.updatedAt)
    }

    @Test
    fun `mergeRemoteSessions ignores a remote edit that is not actually newer`() = runBlocking {
        val local = manualSession()
        val dao = FakeTogetherSessionDao(listOf(local))
        val repo = SessionRepository(dao)

        val staleRemoteEdit = local.copy(startedAt = local.startedAt + 999_000L, updatedAt = local.updatedAt)
        repo.mergeRemoteSessions(listOf(staleRemoteEdit))

        assertEquals("LWW: an equal-or-older updatedAt must never win", local.startedAt, dao.rows.getValue(1L).startedAt)
        assertEquals(0, dao.updateCount)
    }

    @Test
    fun `mergeRemoteSessions ignores a no-op remote edit whose bounds already match`() = runBlocking {
        val local = manualSession()
        val dao = FakeTogetherSessionDao(listOf(local))
        val repo = SessionRepository(dao)

        // Same startedAt/endedAt, just a newer updatedAt (e.g. clock-skew re-transmission) - must not
        // trigger a write since nothing meaningful changed.
        val identicalRemote = local.copy(updatedAt = local.updatedAt + 1L)
        repo.mergeRemoteSessions(listOf(identicalRemote))

        assertEquals(0, dao.updateCount)
    }

    @Test
    fun `mergeRemoteSessions never applies an edit claimed for a real BLE-detected session`() = runBlocking {
        // SECURITY: local.isManual is false (this is our own record of a genuine BLE detection) - the
        // incoming remote row LIES about isManual=true and tries to move the bounds. Must be rejected
        // outright, exactly like the tombstone-apply branch's own gate.
        val bleSession = manualSession().copy(isManual = false)
        val dao = FakeTogetherSessionDao(listOf(bleSession))
        val repo = SessionRepository(dao)

        val forgedEdit = bleSession.copy(
            isManual = true,
            startedAt = bleSession.startedAt + 500_000L,
            updatedAt = bleSession.updatedAt + 1L
        )
        repo.mergeRemoteSessions(listOf(forgedEdit))

        assertEquals("a BLE-detected session's bounds must never be settable by any incoming payload", bleSession.startedAt, dao.rows.getValue(1L).startedAt)
        assertFalse(dao.rows.getValue(1L).deleted)
        assertEquals(0, dao.updateCount)
    }

    @Test
    fun `mergeRemoteSessions still applies a tombstone for a known manual session`() = runBlocking {
        val local = manualSession()
        val dao = FakeTogetherSessionDao(listOf(local))
        val repo = SessionRepository(dao)

        val remoteTombstone = local.copy(deleted = true, updatedAt = local.updatedAt + 1L)
        repo.mergeRemoteSessions(listOf(remoteTombstone))

        assertTrue("deletion must still propagate exactly as before", dao.rows.getValue(1L).deleted)
    }

    @Test
    fun `mergeRemoteSessions inserts a genuinely new session as before`() = runBlocking {
        val dao = FakeTogetherSessionDao()
        val repo = SessionRepository(dao)

        val remote = manualSession(id = 999L, syncId = "new-sync")
        repo.mergeRemoteSessions(listOf(remote))

        assertEquals(1, dao.insertCount)
        assertTrue(dao.rows.values.any { it.syncId == "new-sync" })
    }
}
