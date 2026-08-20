package com.ssbmedia.twogether.audit

import android.content.Context
import com.ssbmedia.twogether.ble.GattSyncManager
import com.ssbmedia.twogether.data.datastore.PairingStore
import com.ssbmedia.twogether.data.datastore.SettingsStore
import com.ssbmedia.twogether.data.db.AppDatabase
import com.ssbmedia.twogether.data.db.DateIdeaDao
import com.ssbmedia.twogether.data.db.DayNoteDao
import com.ssbmedia.twogether.data.db.ListCategoryDao
import com.ssbmedia.twogether.data.db.MilestoneDao
import com.ssbmedia.twogether.data.db.MomentDao
import com.ssbmedia.twogether.data.db.MomentNoteDao
import com.ssbmedia.twogether.data.db.TimeCapsule
import com.ssbmedia.twogether.data.db.TimeCapsuleDao
import com.ssbmedia.twogether.data.db.TogetherSessionDao
import com.ssbmedia.twogether.data.repo.DateIdeaRepository
import com.ssbmedia.twogether.data.repo.DayNoteRepository
import com.ssbmedia.twogether.data.repo.ListCategoryRepository
import com.ssbmedia.twogether.data.repo.MilestoneRepository
import com.ssbmedia.twogether.data.repo.MomentNoteRepository
import com.ssbmedia.twogether.data.repo.MomentRepository
import com.ssbmedia.twogether.data.repo.SessionRepository
import com.ssbmedia.twogether.data.repo.TimeCapsuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * ultimate-app-review spec-test (Feature: Time Capsule sync, AppDatabase.MIGRATION_8_9, 2026-08-08) -
 * independently derived from checklist.md's deferred-item note ("Time Capsules are never included in
 * the sync payload - a capsule written for your partner never reaches them") and the user's explicit
 * follow-up ask to finish the feature "without bugs." The central invariant this file guards:
 * TimeCapsule.unlockedAt must NEVER be settable by a remote peer's payload - only this device's own
 * TimeCapsuleRepository.unlockEligible (computed from its own already-validated session data) may ever
 * unlock a capsule. This mirrors every other "never trust a peer's claim about high-stakes, irreversible
 * state" fix this whole review applied to timestamps - here applied to a capsule's reveal state instead.
 *
 * UPDATED (ultimate-app-review round 2, Opus+Sonnet both independently live-reproduced as S9):
 * mergeRemote is no longer a general LWW merge of every field - an already-known syncId now only ever
 * receives a one-way tombstone-apply (`deleted: false -> true`), mirroring SessionRepository/
 * MomentRepository's sibling merges exactly, since a capsule's definitional fields never legitimately
 * change after creation (no "edit capsule" feature exists). The old general-LWW shape let a purely-local,
 * no-user-action unlock's updatedAt bump on one device beat and resurrect a tombstone the OTHER device
 * had explicitly applied - see the tests below for the exact reproduced scenario.
 *
 * Merge tests use a hand-written fake DAO rather than a Mockito mock - ArgumentCaptor.capture()
 * returns a Java `null` placeholder internally, which crashes against this DAO's non-null Kotlin
 * `TimeCapsule` parameters; a fake sidesteps that Kotlin/Mockito interop gap entirely.
 */
class TimeCapsuleSyncAuditTest {

    // ---- GattSyncManager.deserializeTimeCapsules (reflection, same pattern as the other audit files) ----

    private fun newManager(): GattSyncManager {
        val context = mock(Context::class.java)
        val dateIdeaRepo = DateIdeaRepository(mock(DateIdeaDao::class.java))
        val listCategoryRepo = ListCategoryRepository(mock(ListCategoryDao::class.java), dateIdeaRepo, mock(AppDatabase::class.java))
        val sessionRepo = SessionRepository(mock(TogetherSessionDao::class.java))
        val momentRepo = MomentRepository(mock(MomentDao::class.java), context)
        val momentNoteRepo = MomentNoteRepository(mock(MomentNoteDao::class.java))
        val dayNoteRepo = DayNoteRepository(mock(DayNoteDao::class.java))
        val milestoneRepo = MilestoneRepository(mock(MilestoneDao::class.java))
        val timeCapsuleRepo = TimeCapsuleRepository(mock(TimeCapsuleDao::class.java))
        val settingsStore = mock(SettingsStore::class.java)
        val pairingStore = mock(PairingStore::class.java)
        return GattSyncManager(
            context, dateIdeaRepo, listCategoryRepo, sessionRepo, momentRepo,
            momentNoteRepo, dayNoteRepo, milestoneRepo, timeCapsuleRepo, settingsStore, pairingStore, CoroutineScope(Dispatchers.Unconfined)
        )
    }

    private fun deserializeTimeCapsules(arr: JSONArray, peerClockOffsetMillis: Long = 0L): List<TimeCapsule> {
        val method = GattSyncManager::class.java.getDeclaredMethod(
            "deserializeTimeCapsules", JSONArray::class.java, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(newManager(), arr, peerClockOffsetMillis) as List<TimeCapsule>
    }

    private fun capsuleJson(
        syncId: String, unlockAtHours: Double, updatedAt: Long, unlockedAt: Long? = null, deleted: Boolean = false
    ): JSONArray = JSONArray().put(
        JSONObject().apply {
            put("syncId", syncId)
            put("text", "note")
            put("unlockAtHours", unlockAtHours)
            put("createdAt", updatedAt)
            put("unlockedAt", unlockedAt ?: JSONObject.NULL)
            put("manualHoursAtCreation", 0.0)
            put("updatedAt", updatedAt)
            put("deleted", deleted)
        }
    )

    @Test
    fun `a legitimate capsule survives deserialization`() {
        val now = System.currentTimeMillis()
        val result = deserializeTimeCapsules(capsuleJson("c1", 100.0, now))
        assertEquals(1, result.size)
    }

    @Test
    fun `a forged future updatedAt is dropped, same as every other synced entity`() {
        val now = System.currentTimeMillis()
        val result = deserializeTimeCapsules(capsuleJson("c1", 100.0, now + 999_999_999_999L))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a negative unlockAtHours is rejected`() {
        val now = System.currentTimeMillis()
        val result = deserializeTimeCapsules(capsuleJson("c1", -5.0, now))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a zero unlockAtHours is rejected`() {
        val now = System.currentTimeMillis()
        val result = deserializeTimeCapsules(capsuleJson("c1", 0.0, now))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an absurdly large unlockAtHours is rejected`() {
        val now = System.currentTimeMillis()
        val result = deserializeTimeCapsules(capsuleJson("c1", 999_999_999.0, now))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a hand-crafted NaN unlockAtHours is rejected, not thrown`() {
        // org.json's own JSONObject.put() refuses to WRITE a NaN double (throws immediately), but its
        // tokener is lenient on PARSING - a malicious peer's raw payload could still contain a literal
        // NaN token, so this constructs the attack via a raw string, the same shape applyPayload
        // actually receives bytes as, rather than via the (safe) builder API.
        val now = System.currentTimeMillis()
        val json = """[{"syncId":"c1","text":"n","unlockAtHours":NaN,"createdAt":$now,"unlockedAt":null,"manualHoursAtCreation":0.0,"updatedAt":$now,"deleted":false}]"""
        val result = deserializeTimeCapsules(JSONArray(json))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `unlockedAt is never read from the wire, even when the peer claims one`() {
        // Central security property: a peer claiming an early unlockedAt must never reveal a capsule's
        // contents on the receiving device.
        val now = System.currentTimeMillis()
        val result = deserializeTimeCapsules(capsuleJson("c1", 500.0, now, unlockedAt = now - 999_999_999L))
        assertNull("a freshly-deserialized capsule must never carry a wire-claimed unlockedAt", result.first().unlockedAt)
    }

    // ---- TimeCapsuleRepository.mergeRemote ----

    private class FakeTimeCapsuleDao(rows: List<TimeCapsule>) : TimeCapsuleDao {
        var localRows: List<TimeCapsule> = rows
        val inserted = mutableListOf<TimeCapsule>()
        val updated = mutableListOf<TimeCapsule>()
        override suspend fun insert(capsule: TimeCapsule): Long {
            inserted.add(capsule)
            return 1L
        }
        override fun observeActive(): Flow<List<TimeCapsule>> = throw NotImplementedError("not used by mergeRemote")
        override suspend fun getLocked(): List<TimeCapsule> = throw NotImplementedError("not used by mergeRemote")
        override suspend fun unlockIfNotDeleted(id: Long, unlockedAt: Long, updatedAt: Long) = throw NotImplementedError("not used by mergeRemote")
        override suspend fun tombstone(id: Long, updatedAt: Long) {
            localRows.firstOrNull { it.id == id }?.let { row ->
                val tombstoned = row.copy(deleted = true, updatedAt = updatedAt)
                updated.add(tombstoned)
                localRows = localRows.map { if (it.id == id) tombstoned else it }
            }
        }
        override suspend fun getAll(): List<TimeCapsule> = localRows
        override suspend fun clearAll() { localRows = emptyList() }
    }

    // NOTE (ultimate-app-review round 2, Opus+Sonnet both independently live-reproduced as S9): the test
    // that used to live here asserted the OLD, buggy contract - that a known syncId's `text`/other
    // definitional fields update from a LWW-newer remote row. That was itself the bug: it let a
    // background, no-user-action unlock (which also bumps updatedAt) on one device beat and UN-DELETE a
    // capsule the other device had explicitly deleted moments earlier. TimeCapsuleRepository.mergeRemote
    // now only ever applies a one-way tombstone for an already-known syncId (mirroring
    // SessionRepository.mergeRemoteSessions/MomentRepository.mergeRemoteStubs exactly) - replaced below
    // with tests of the corrected contract, including the exact S9 resurrection scenario both reviewers
    // reproduced live.

    @Test
    fun `mergeRemote ignores a non-delete remote edit to an already-known syncId - definitional fields are immutable after creation`() = runBlocking {
        val local = TimeCapsule(
            id = 42L, text = "old text", unlockAtHours = 100f, createdAt = 0L,
            manualHoursAtCreation = 10f, syncId = "shared-id", updatedAt = 5_000L
        )
        val dao = FakeTimeCapsuleDao(listOf(local))
        val repo = TimeCapsuleRepository(dao)

        // A newer updatedAt and a forged manualHoursAtCreation must NOT move the local row at all - there
        // is no "edit capsule" feature, so nothing but a tombstone may ever change a known capsule.
        val remote = TimeCapsule(
            id = 999L, text = "edited text", unlockAtHours = 100f, createdAt = 0L,
            manualHoursAtCreation = 999_999f, syncId = "shared-id", updatedAt = 6_000L, deleted = false
        )
        val result = repo.mergeRemote(listOf(remote))

        assertTrue("no update and no insert - a non-delete edit to a known syncId is a pure no-op", dao.updated.isEmpty())
        assertTrue(dao.inserted.isEmpty())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mergeRemote does not resurrect a local tombstone even when the remote's non-deleted row is newer (S9)`() = runBlocking {
        // The exact live-reproduced scenario: device B deletes a capsule (tombstone at T1); device A
        // independently auto-unlocks the SAME capsule in the background at T2 > T1 (no user action, just
        // unlockEligible's updatedAt bump) before it ever saw B's delete; B then receives A's row on the
        // next sync. B's tombstone must survive.
        val local = TimeCapsule(
            id = 1L, text = "note", unlockAtHours = 100f, createdAt = 0L,
            syncId = "shared-id", updatedAt = 1_000L, deleted = true // B's delete, T1 = 1000
        )
        val dao = FakeTimeCapsuleDao(listOf(local))
        val repo = TimeCapsuleRepository(dao)

        val remoteFromA = TimeCapsule(
            id = 2L, text = "note", unlockAtHours = 100f, createdAt = 0L,
            syncId = "shared-id", updatedAt = 2_000L, deleted = false // A's background unlock bump, T2 > T1
        )
        val result = repo.mergeRemote(listOf(remoteFromA))

        assertTrue("a non-delete remote row must never un-delete a local tombstone, regardless of updatedAt", dao.updated.isEmpty())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mergeRemote inserts a genuinely new remote capsule with unlockedAt null, never the remote's claim`() = runBlocking {
        val dao = FakeTimeCapsuleDao(emptyList())
        val repo = TimeCapsuleRepository(dao)

        val remote = TimeCapsule(
            id = 999L, text = "brand new", unlockAtHours = 50f, createdAt = 0L,
            unlockedAt = 123L, syncId = "new-id", updatedAt = 1_000L
        )
        repo.mergeRemote(listOf(remote))

        assertEquals(1, dao.inserted.size)
        assertEquals(0L, dao.inserted.first().id)
        assertNull("even a brand-new remote capsule must never arrive pre-unlocked", dao.inserted.first().unlockedAt)
    }

    @Test
    fun `mergeRemote ignores a non-delete remote row for an already-known syncId regardless of updatedAt`() = runBlocking {
        val local = TimeCapsule(id = 1L, text = "current", unlockAtHours = 100f, createdAt = 0L, syncId = "id-1", updatedAt = 5_000L)
        val dao = FakeTimeCapsuleDao(listOf(local))
        val repo = TimeCapsuleRepository(dao)

        // Even a newer updatedAt changes nothing, since there is no field left that a non-delete remote
        // row is allowed to update - see the mergeRemote contract change above (S9 fix).
        val newerNonDeleteRemote = TimeCapsule(id = 2L, text = "stale", unlockAtHours = 100f, createdAt = 0L, syncId = "id-1", updatedAt = 6_000L)
        val result = repo.mergeRemote(listOf(newerNonDeleteRemote))

        assertTrue(result.isEmpty())
        assertTrue(dao.inserted.isEmpty())
        assertTrue(dao.updated.isEmpty())
    }

    @Test
    fun `mergeRemote applies a remote deletion tombstone to an already-known, not-yet-deleted local row`() = runBlocking {
        val local = TimeCapsule(id = 1L, text = "note", unlockAtHours = 100f, createdAt = 0L, syncId = "id-1", updatedAt = 5_000L, deleted = false)
        val dao = FakeTimeCapsuleDao(listOf(local))
        val repo = TimeCapsuleRepository(dao)

        val remoteDelete = TimeCapsule(id = 2L, text = "note", unlockAtHours = 100f, createdAt = 0L, syncId = "id-1", updatedAt = 6_000L, deleted = true)
        repo.mergeRemote(listOf(remoteDelete))

        assertEquals(1, dao.updated.size)
        assertTrue(dao.updated.first().deleted)
        assertEquals("local id preserved on a tombstone-apply too", 1L, dao.updated.first().id)
    }
}
