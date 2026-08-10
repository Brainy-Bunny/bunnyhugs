package com.ssbmedia.twogether.audit

import android.content.Context
import com.ssbmedia.twogether.ble.GattSyncManager
import com.ssbmedia.twogether.data.datastore.PairingStore
import com.ssbmedia.twogether.data.datastore.SettingsStore
import com.ssbmedia.twogether.data.db.AppDatabase
import com.ssbmedia.twogether.data.db.DateIdeaDao
import com.ssbmedia.twogether.data.db.ListCategoryDao
import com.ssbmedia.twogether.data.db.Milestone
import com.ssbmedia.twogether.data.db.MilestoneDao
import com.ssbmedia.twogether.data.db.MomentDao
import com.ssbmedia.twogether.data.db.MomentNoteDao
import com.ssbmedia.twogether.data.db.TimeCapsuleDao
import com.ssbmedia.twogether.data.db.TogetherSessionDao
import com.ssbmedia.twogether.data.repo.DateIdeaRepository
import com.ssbmedia.twogether.data.repo.ListCategoryRepository
import com.ssbmedia.twogether.data.repo.MilestoneRepository
import com.ssbmedia.twogether.data.repo.MomentNoteRepository
import com.ssbmedia.twogether.data.repo.MomentRepository
import com.ssbmedia.twogether.data.repo.SessionRepository
import com.ssbmedia.twogether.data.repo.TimeCapsuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * ultimate-app-review spec-tests (round 1, commit 29dfba1, 2026-08-10) - independent tests derived
 * from checklist.md, proposed convergently by both the fresh Opus and fresh Sonnet round-1 reviewers
 * (test #4/#5 in each of their reports). Direct regression coverage for round 1's BLOCKER (Opus):
 * a partner still on a pre-this-commit build echoes a milestone back with no "linkedMomentSyncId" key
 * at all - org.json's JSONObject.isNull() treats "key absent" identically to "key present and
 * explicitly null", so this used to deserialize to null unconditionally, and since the bounded
 * clock-skew correction usually makes an unmodified echo's updatedAt land just after ours, the
 * whole-row LWW merge would then silently null out a link the user had just set, with zero user
 * action and zero error - live-reproduced 3 times against real installs. Fixed by having
 * deserializeMilestones report which ids had the key genuinely absent (as opposed to present-null),
 * and having MilestoneRepository.mergeRemote preserve the LOCAL value for exactly those ids instead of
 * trusting the remote's null - an explicit same-build clear (key present, value null) is unaffected
 * and still wins on LWW like any other field change.
 *
 * A full applyPayload()-level test of the top invariant (S2: a mismatched-sender payload must be
 * rejected before any merge) was deliberately NOT attempted here despite both reviewers proposing
 * it - every prior review round's spec-tests in this package also stopped short of it (see
 * GattSyncMergeSecurityAuditTest's own doc: "never applyPayload itself"), and MilestoneAlarmScheduler.
 * scheduleAll's real AlarmManager/Context dependency makes a full mock graph for it fragile to get
 * right under this review's time budget. S2 was instead verified with strong LIVE evidence by both
 * round-1 reviewers (logcat rejection captured, pin cross-verified via on-device DataStore dumps,
 * repeated-rejection tested against real installs) - see round-1-opus.md/round-1-sonnet.md. Left as a
 * disclosed, known coverage gap for Step 5's report rather than forcing a fragile mock-heavy test into
 * the permanent suite.
 */
class UltimateReviewRound3SpecTest {

    private fun newManager(): GattSyncManager {
        val context = mock(Context::class.java)
        val dateIdeaRepo = DateIdeaRepository(mock(DateIdeaDao::class.java))
        val listCategoryRepo = ListCategoryRepository(
            mock(ListCategoryDao::class.java), dateIdeaRepo, mock(AppDatabase::class.java)
        )
        val sessionRepo = SessionRepository(mock(TogetherSessionDao::class.java))
        val momentRepo = MomentRepository(mock(MomentDao::class.java), context)
        val momentNoteRepo = MomentNoteRepository(mock(MomentNoteDao::class.java))
        val milestoneRepo = MilestoneRepository(mock(MilestoneDao::class.java))
        val timeCapsuleRepo = TimeCapsuleRepository(mock(TimeCapsuleDao::class.java))
        val settingsStore = mock(SettingsStore::class.java)
        val pairingStore = mock(PairingStore::class.java)
        return GattSyncManager(
            context, dateIdeaRepo, listCategoryRepo, sessionRepo, momentRepo,
            momentNoteRepo, milestoneRepo, timeCapsuleRepo, settingsStore, pairingStore, CoroutineScope(Dispatchers.Unconfined)
        )
    }

    private fun <T> invokePrivate(target: Any, name: String, vararg args: Any?): T {
        val paramTypes = args.map {
            when (it) {
                is Long -> Long::class.javaPrimitiveType
                is JSONArray, null -> JSONArray::class.java
                else -> it::class.java
            }
        }.toTypedArray()
        val method = target.javaClass.getDeclaredMethod(name, *paramTypes)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(target, *args) as T
    }

    private fun deserializeMilestones(
        manager: GattSyncManager,
        arr: JSONArray,
        peerClockOffsetMillis: Long = 0L
    ): Pair<List<Milestone>, Set<String>> = invokePrivate(manager, "deserializeMilestones", arr, peerClockOffsetMillis)

    private fun milestoneJson(
        id: String,
        updatedAt: Long,
        linkedMomentSyncId: Any? = JSONObject.NULL, // JSONObject.NULL = key present, explicit null; omit the put() call entirely for "key absent"
        includeLinkedMomentKey: Boolean = true
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", "Anniversary")
        put("month", 6)
        put("day", 12)
        put("year", JSONObject.NULL)
        put("createdAt", updatedAt)
        put("updatedAt", updatedAt)
        put("deleted", false)
        if (includeLinkedMomentKey) put("linkedMomentSyncId", linkedMomentSyncId ?: JSONObject.NULL)
    }

    @Test
    fun `deserializeMilestones parses an explicit linked photo normally`() {
        val now = System.currentTimeMillis()
        val arr = JSONArray().put(milestoneJson("m1", now, linkedMomentSyncId = "moment-123"))
        val (parsed, missing) = deserializeMilestones(newManager(), arr)
        assertEquals(1, parsed.size)
        assertEquals("moment-123", parsed[0].linkedMomentSyncId)
        assertTrue("a present key must never be flagged as missing", missing.isEmpty())
    }

    @Test
    fun `deserializeMilestones parses an explicit null (same-build clear) as null, not flagged missing`() {
        val now = System.currentTimeMillis()
        val arr = JSONArray().put(milestoneJson("m1", now, linkedMomentSyncId = JSONObject.NULL))
        val (parsed, missing) = deserializeMilestones(newManager(), arr)
        assertEquals(1, parsed.size)
        assertNull(parsed[0].linkedMomentSyncId)
        assertTrue(
            "an EXPLICIT null must be distinguished from an absent key - only the latter is a preserve-local signal",
            missing.isEmpty()
        )
    }

    @Test
    fun `deserializeMilestones flags a genuinely absent key as missing (pre-commit partner build)`() {
        val now = System.currentTimeMillis()
        val arr = JSONArray().put(milestoneJson("m1", now, includeLinkedMomentKey = false))
        val (parsed, missing) = deserializeMilestones(newManager(), arr)
        assertEquals(1, parsed.size)
        assertNull("still deserializes to null at this layer - mergeRemote decides what to do with it", parsed[0].linkedMomentSyncId)
        assertEquals(setOf("m1"), missing)
    }

    /** Minimal in-memory fake, not a Mockito mock - a suspend-heavy DAO interface is far more reliable
     * to fake directly than to stub with Mockito for this kind of "seed state, call merge, assert
     * final state" test. */
    private class FakeMilestoneDao(seed: List<Milestone> = emptyList()) : MilestoneDao {
        private val store = LinkedHashMap<String, Milestone>().apply { seed.forEach { put(it.id, it) } }
        override suspend fun upsert(milestone: Milestone) { store[milestone.id] = milestone }
        override suspend fun upsertAll(milestones: List<Milestone>) { milestones.forEach { store[it.id] = it } }
        override fun observeActive(): Flow<List<Milestone>> = flowOf(store.values.filter { !it.deleted })
        override suspend fun getAll(): List<Milestone> = store.values.toList()
        override suspend fun clearAll() { store.clear() }
    }

    private fun localMilestone(id: String, updatedAt: Long, linkedMomentSyncId: String?) = Milestone(
        id = id, label = "Anniversary", month = 6, day = 12, year = null,
        createdAt = updatedAt, updatedAt = updatedAt, deleted = false, linkedMomentSyncId = linkedMomentSyncId
    )

    @Test
    fun `BLOCKER regression - a newer but link-less remote echo (missing field) must not clobber a local link`() = runBlocking {
        val local = localMilestone("m1", updatedAt = 1_000L, linkedMomentSyncId = "moment-123")
        val dao = FakeMilestoneDao(listOf(local))
        val repo = MilestoneRepository(dao)
        // Simulates a pre-commit partner's unmodified echo: same id, newer updatedAt (as clock-skew
        // correction typically produces), null link, id present in missingLinkedMomentField.
        val remoteEcho = localMilestone("m1", updatedAt = 2_000L, linkedMomentSyncId = null)

        val upserted = repo.mergeRemote(listOf(remoteEcho), missingLinkedMomentField = setOf("m1"))

        assertEquals(1, upserted.size)
        assertEquals(
            "the local link must survive an old-build partner's echo, not be silently nulled",
            "moment-123",
            upserted[0].linkedMomentSyncId
        )
        assertEquals("every other field (updatedAt) must still come from the remote as normal", 2_000L, upserted[0].updatedAt)
        assertEquals("moment-123", dao.getAll().first().linkedMomentSyncId)
    }

    @Test
    fun `an explicit clear from a same-build partner still wins on LWW (not treated as missing)`() = runBlocking {
        val local = localMilestone("m1", updatedAt = 1_000L, linkedMomentSyncId = "moment-123")
        val dao = FakeMilestoneDao(listOf(local))
        val repo = MilestoneRepository(dao)
        // Same-build partner deliberately cleared the link - key was present, deserialized to null,
        // so this id is NOT in missingLinkedMomentField.
        val remoteExplicitClear = localMilestone("m1", updatedAt = 2_000L, linkedMomentSyncId = null)

        val upserted = repo.mergeRemote(listOf(remoteExplicitClear), missingLinkedMomentField = emptySet())

        assertEquals(1, upserted.size)
        assertNull("an explicit clear must still work - only an ABSENT key preserves local", upserted[0].linkedMomentSyncId)
    }

    @Test
    fun `a genuinely older remote row never wins regardless of missingLinkedMomentField`() = runBlocking {
        val local = localMilestone("m1", updatedAt = 5_000L, linkedMomentSyncId = "moment-123")
        val dao = FakeMilestoneDao(listOf(local))
        val repo = MilestoneRepository(dao)
        val staleRemote = localMilestone("m1", updatedAt = 1_000L, linkedMomentSyncId = null)

        val upserted = repo.mergeRemote(listOf(staleRemote), missingLinkedMomentField = setOf("m1"))

        assertTrue("an older remote row must not be upserted at all, missing-field or not", upserted.isEmpty())
        assertEquals("moment-123", dao.getAll().first().linkedMomentSyncId)
    }

    @Test
    fun `a brand-new milestone (no local row) with a missing link field simply has no link to preserve`() = runBlocking {
        val dao = FakeMilestoneDao(emptyList())
        val repo = MilestoneRepository(dao)
        val remoteNew = localMilestone("m2", updatedAt = 1_000L, linkedMomentSyncId = null)

        val upserted = repo.mergeRemote(listOf(remoteNew), missingLinkedMomentField = setOf("m2"))

        assertEquals(1, upserted.size)
        assertNull("nothing local to preserve for a milestone this device has never seen before", upserted[0].linkedMomentSyncId)
    }
}
