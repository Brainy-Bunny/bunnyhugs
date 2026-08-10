package com.ssbmedia.twogether.audit

import android.content.Context
import com.ssbmedia.twogether.ble.GattSyncManager
import com.ssbmedia.twogether.data.datastore.PairingStore
import com.ssbmedia.twogether.data.datastore.SettingsStore
import com.ssbmedia.twogether.data.db.AppDatabase
import com.ssbmedia.twogether.data.db.DateIdea
import com.ssbmedia.twogether.data.db.DateIdeaDao
import com.ssbmedia.twogether.data.db.ListCategory
import com.ssbmedia.twogether.data.db.ListCategoryDao
import com.ssbmedia.twogether.data.db.Milestone
import com.ssbmedia.twogether.data.db.MilestoneDao
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.data.db.MomentDao
import com.ssbmedia.twogether.data.db.MomentNote
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Spec-tests for the two defects found by the independent live review of v2.6 (and the fixes for them).
 * Written against the SPEC ("what must be true"), not against the implementation's own reasoning, so
 * they keep their value if the fix is ever rewritten:
 *
 *  1. MAJOR - both phones must agree on whether a photo was taken while together. Before the fix the
 *     caption was derived from `Moment.sessionId`, which mergeRemoteStubs correctly nulls for every
 *     incoming row, so the identical photo read "Taken while together" on the sender and "Taken apart"
 *     on the receiver, permanently, for 100% of synced photos.
 *  2. MAJOR - a sync must not re-write rows whose content did not change. Before the fix, the peer clock
 *     offset was computed between the peer's payload-BUILD time and our payload-APPLY time, so the BLE
 *     transfer latency was counted as clock skew and inflated every incoming timestamp; the echoed copy
 *     then always looked newer, was accepted, re-written, and echoed back bigger still - a permanent
 *     ratchet (live-measured at +250..390ms per hop, monotonic, in both directions) that re-wrote every
 *     row of every LWW table on every sync forever.
 *
 * Uses the same reflection + Mockito approach as GattSyncMergeSecurityAuditTest (the serialize/
 * deserialize/offset helpers are correctly private), plus hand-written fake DAOs so the merge functions
 * can be driven for real and their WRITES counted - the whole point of defect 2 is how many writes happen.
 */
class SyncCaptionAndChurnAuditTest {

    // ---------------------------------------------------------------------------------------------
    // Fakes - real in-memory DAO implementations so writes can be counted, not just stubbed away.
    // ---------------------------------------------------------------------------------------------

    private class FakeDateIdeaDao(seed: List<DateIdea> = emptyList()) : DateIdeaDao {
        val rows = seed.associateBy { it.id }.toMutableMap()
        var upsertCount = 0
        override suspend fun upsert(idea: DateIdea) { upsertCount++; rows[idea.id] = idea }
        override suspend fun upsertAll(ideas: List<DateIdea>) { ideas.forEach { upsertCount++; rows[it.id] = it } }
        override fun observeActive(): Flow<List<DateIdea>> = flowOf(rows.values.filter { !it.deleted })
        override suspend fun getAll(): List<DateIdea> = rows.values.toList()
        override suspend fun clearAll() { rows.clear() }
    }

    private class FakeMilestoneDao(seed: List<Milestone> = emptyList()) : MilestoneDao {
        val rows = seed.associateBy { it.id }.toMutableMap()
        var upsertCount = 0
        override suspend fun upsert(milestone: Milestone) { upsertCount++; rows[milestone.id] = milestone }
        override suspend fun upsertAll(milestones: List<Milestone>) { milestones.forEach { upsertCount++; rows[it.id] = it } }
        override fun observeActive(): Flow<List<Milestone>> = flowOf(rows.values.filter { !it.deleted })
        override suspend fun getAll(): List<Milestone> = rows.values.toList()
        override suspend fun clearAll() { rows.clear() }
    }

    private class FakeListCategoryDao(seed: List<ListCategory> = emptyList()) : ListCategoryDao {
        val rows = seed.associateBy { it.id }.toMutableMap()
        var upsertCount = 0
        override suspend fun upsert(category: ListCategory) { upsertCount++; rows[category.id] = category }
        override suspend fun upsertAll(categories: List<ListCategory>) { categories.forEach { upsertCount++; rows[it.id] = it } }
        override fun observeActive(): Flow<List<ListCategory>> = flowOf(rows.values.filter { !it.deleted })
        override suspend fun getAll(): List<ListCategory> = rows.values.toList()
        override suspend fun clearAll() { rows.clear() }
        /** Faithful to the real @Query, including its `AND createdAt > :createdAt` one-way guard. */
        override suspend fun lowerCreatedAt(id: String, createdAt: Long) {
            rows[id]?.let { if (it.createdAt > createdAt) rows[id] = it.copy(createdAt = createdAt) }
        }
    }

    private class FakeMomentNoteDao(seed: List<MomentNote> = emptyList()) : MomentNoteDao {
        val rows = seed.associateBy { it.momentSyncId to it.authorDeviceId }.toMutableMap()
        var upsertCount = 0
        override suspend fun upsert(note: MomentNote) { upsertCount++; rows[note.momentSyncId to note.authorDeviceId] = note }
        override suspend fun upsertAll(notes: List<MomentNote>) {
            notes.forEach { upsertCount++; rows[it.momentSyncId to it.authorDeviceId] = it }
        }
        override fun observeForMoment(momentSyncId: String): Flow<List<MomentNote>> =
            flowOf(rows.values.filter { it.momentSyncId == momentSyncId })
        override suspend fun getAllForAuthor(deviceId: String): List<MomentNote> = rows.values.filter { it.authorDeviceId == deviceId }
        override suspend fun getAll(): List<MomentNote> = rows.values.toList()
        override suspend fun clearAll() { rows.clear() }
    }

    private fun newManager(): GattSyncManager {
        val context = mock(Context::class.java)
        val dateIdeaRepo = DateIdeaRepository(mock(DateIdeaDao::class.java))
        val listCategoryRepo = ListCategoryRepository(
            mock(ListCategoryDao::class.java), dateIdeaRepo, mock(AppDatabase::class.java)
        )
        return GattSyncManager(
            context,
            dateIdeaRepo,
            listCategoryRepo,
            SessionRepository(mock(TogetherSessionDao::class.java)),
            MomentRepository(mock(MomentDao::class.java), context),
            MomentNoteRepository(mock(MomentNoteDao::class.java)),
            MilestoneRepository(mock(MilestoneDao::class.java)),
            TimeCapsuleRepository(mock(TimeCapsuleDao::class.java)),
            mock(SettingsStore::class.java),
            mock(PairingStore::class.java),
            CoroutineScope(Dispatchers.Unconfined)
        )
    }

    private fun serializeMoments(manager: GattSyncManager, moments: List<Moment>): JSONArray {
        val m = GattSyncManager::class.java.getDeclaredMethod("serializeMoments", List::class.java)
        m.isAccessible = true
        return m.invoke(manager, moments) as JSONArray
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserializeMoments(manager: GattSyncManager, arr: JSONArray, offset: Long = 0L): List<Moment> {
        val m = GattSyncManager::class.java.getDeclaredMethod(
            "deserializeMoments", JSONArray::class.java, Long::class.javaPrimitiveType
        )
        m.isAccessible = true
        return m.invoke(manager, arr, offset) as List<Moment>
    }

    private fun boundPeerClockOffset(manager: GattSyncManager, raw: Long): Long {
        val m = GattSyncManager::class.java.getDeclaredMethod("boundPeerClockOffset", Long::class.javaPrimitiveType)
        m.isAccessible = true
        return m.invoke(manager, raw) as Long
    }

    // ---------------------------------------------------------------------------------------------
    // Defect 1 - the two phones must agree on "taken while together"
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `moment taken during a session defaults to takenWhileTogether true`() {
        assertTrue(Moment(photoUri = "/p.jpg", takenAt = 1L, sessionId = 7L).takenWhileTogether)
    }

    @Test
    fun `moment taken with no open session defaults to takenWhileTogether false`() {
        assertFalse(Moment(photoUri = "/p.jpg", takenAt = 1L, sessionId = null).takenWhileTogether)
    }

    /** The core regression: what the sender says about a photo must be what the receiver shows. */
    @Test
    fun `takenWhileTogether survives the wire round-trip so both phones agree`() {
        val manager = newManager()
        val sent = Moment(
            photoUri = "/data/user/0/pkg/files/moments/a.jpg",
            takenAt = 1_700_000_000_000L,
            sessionId = 42L,
            syncId = "11111111-1111-4111-8111-111111111111",
            updatedAt = 1_700_000_000_000L
        )
        assertTrue("precondition: the sending phone considers this taken together", sent.takenWhileTogether)

        val received = deserializeMoments(manager, serializeMoments(manager, listOf(sent))).single()

        assertEquals(sent.syncId, received.syncId)
        assertTrue(
            "the receiving phone must NOT caption the partner's together-photo as 'Taken apart'",
            received.takenWhileTogether
        )
    }

    @Test
    fun `a photo genuinely taken apart stays apart across the wire`() {
        val manager = newManager()
        val sent = Moment(
            photoUri = "/p.jpg",
            takenAt = 1_700_000_000_000L,
            sessionId = null,
            syncId = "22222222-2222-4222-8222-222222222222",
            updatedAt = 1_700_000_000_000L
        )
        val received = deserializeMoments(manager, serializeMoments(manager, listOf(sent))).single()
        assertFalse(received.takenWhileTogether)
    }

    /**
     * A partner still on a build that predates this field sends no such key. That must degrade to the
     * old behaviour (false) rather than throwing or defaulting to a wrong `true`, and - critically - must
     * not silently resolve to the entity's own `sessionId != null` default, which would be evaluated
     * against the null sessionId this constructor leaves behind and reintroduce the original bug.
     */
    @Test
    fun `a payload from an older partner build without the field degrades to false`() {
        val manager = newManager()
        val arr = JSONArray(
            """[{"syncId":"33333333-3333-4333-8333-333333333333","photoUri":"/p.jpg",
                 "takenAt":1700000000000,"hasPhoto":true,"updatedAt":1700000000000,"deleted":false}]"""
        )
        val received = deserializeMoments(manager, arr).single()
        assertFalse(received.takenWhileTogether)
    }

    // ---------------------------------------------------------------------------------------------
    // Defect 2a - transfer latency must not be acted on as if it were clock skew
    // ---------------------------------------------------------------------------------------------

    /**
     * REGRESSION GUARD for a fix that was proposed, shipped briefly, and then REJECTED by review round 2.
     * A "latency floor" (ignore offsets below ~60s as transfer noise) looked like a clean fix for the
     * ratchet, but it cannot distinguish transfer latency from a partner clock that is genuinely a few
     * tens of seconds fast - and in that case suppressing the correction makes LWW resolve by whose clock
     * is ahead instead of who edited last, silently destroying the later edit. Any sub-tolerance skew
     * MUST still be corrected for; the ratchet is the merge layer's problem, not this function's.
     */
    @Test
    fun `a small but genuine clock skew is still corrected for and never floored to zero`() {
        val manager = newManager()
        listOf(400L, 5_000L, 30_000L, 59_999L, 4 * 60_000L).forEach { skew ->
            assertEquals(
                "a ${skew}ms offset must still be corrected - flooring it lets the faster phone's older " +
                    "edit beat the slower phone's newer one",
                skew, boundPeerClockOffset(manager, skew)
            )
            assertEquals(-skew, boundPeerClockOffset(manager, -skew))
        }
    }

    @Test
    fun `a genuinely skewed peer clock is still corrected for`() {
        val manager = newManager()
        val oneHour = 60 * 60 * 1000L
        assertEquals(oneHour, boundPeerClockOffset(manager, oneHour))
        assertEquals(-oneHour, boundPeerClockOffset(manager, -oneHour))
    }

    @Test
    fun `an absurd peer clock still falls back to no correction`() {
        val manager = newManager()
        val oneYear = 365L * 24 * 60 * 60 * 1000
        assertEquals(0L, boundPeerClockOffset(manager, oneYear))
        assertEquals(0L, boundPeerClockOffset(manager, -oneYear))
    }

    // ---------------------------------------------------------------------------------------------
    // Defect 2b - a merge must not write when nothing meaningful changed
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `date idea echoed back with only a newer timestamp is not re-written`() = runBlocking {
        val local = DateIdea(id = "i1", text = "Dune Part Two", listId = "L", done = false, updatedAt = 1_000L)
        val dao = FakeDateIdeaDao(listOf(local))
        DateIdeaRepository(dao).mergeRemote(listOf(local.copy(updatedAt = 1_350L)))
        assertEquals("an unchanged idea must not be re-written on every sync", 0, dao.upsertCount)
    }

    @Test
    fun `date idea with a real change is still applied`() = runBlocking {
        val local = DateIdea(id = "i1", text = "Dune Part Two", listId = "L", done = false, updatedAt = 1_000L)
        val dao = FakeDateIdeaDao(listOf(local))
        DateIdeaRepository(dao).mergeRemote(listOf(local.copy(done = true, updatedAt = 2_000L)))
        assertEquals(1, dao.upsertCount)
        assertTrue(dao.rows.getValue("i1").done)
    }

    @Test
    fun `date idea tombstone is still applied`() = runBlocking {
        val local = DateIdea(id = "i1", text = "Ghibli marathon", listId = "L", done = false, updatedAt = 1_000L)
        val dao = FakeDateIdeaDao(listOf(local))
        DateIdeaRepository(dao).mergeRemote(listOf(local.copy(deleted = true, updatedAt = 2_000L)))
        assertEquals(1, dao.upsertCount)
        assertTrue(dao.rows.getValue("i1").deleted)
    }

    @Test
    fun `a brand new remote idea is always inserted`() = runBlocking {
        val dao = FakeDateIdeaDao()
        DateIdeaRepository(dao).mergeRemote(
            listOf(DateIdea(id = "new", text = "Picnic", listId = "L", done = false, updatedAt = 5_000L))
        )
        assertEquals(1, dao.upsertCount)
    }

    @Test
    fun `an older remote idea never overwrites a newer local edit`() = runBlocking {
        val local = DateIdea(id = "i1", text = "local edit", listId = "L", done = false, updatedAt = 9_000L)
        val dao = FakeDateIdeaDao(listOf(local))
        DateIdeaRepository(dao).mergeRemote(listOf(local.copy(text = "stale remote", updatedAt = 1_000L)))
        assertEquals(0, dao.upsertCount)
        assertEquals("local edit", dao.rows.getValue("i1").text)
    }

    @Test
    fun `milestone echoed back with only a newer timestamp is not re-written`() = runBlocking {
        val local = Milestone(id = "m1", label = "First Date", month = 8, day = 10, createdAt = 1L, updatedAt = 1_000L)
        val dao = FakeMilestoneDao(listOf(local))
        val upserted = MilestoneRepository(dao).mergeRemote(listOf(local.copy(updatedAt = 1_400L)), emptySet())
        assertEquals(0, dao.upsertCount)
        assertTrue("nothing changed, so no alarm should be re-armed either", upserted.isEmpty())
    }

    @Test
    fun `milestone with a real change is still applied and returned for alarm re-arming`() = runBlocking {
        val local = Milestone(id = "m1", label = "First Date", month = 8, day = 10, createdAt = 1L, updatedAt = 1_000L)
        val dao = FakeMilestoneDao(listOf(local))
        val upserted = MilestoneRepository(dao).mergeRemote(listOf(local.copy(day = 11, updatedAt = 2_000L)), emptySet())
        assertEquals(1, dao.upsertCount)
        assertEquals(1, upserted.size)
        assertEquals(11, dao.rows.getValue("m1").day)
    }

    /**
     * Guards the interaction between this fix and the existing round-1 BLOCKER fix: an echo from a
     * partner build that omits linkedMomentSyncId must still NOT clobber a locally-set link (original
     * fix), and must now also not be written at all (this fix) - the preservation has to happen before
     * the content comparison, or the echo looks "changed" forever.
     */
    @Test
    fun `milestone echo missing the linked-photo field neither clobbers nor re-writes`() = runBlocking {
        val local = Milestone(
            id = "m1", label = "Anniversary", month = 2, day = 14, createdAt = 1L, updatedAt = 1_000L,
            linkedMomentSyncId = "photo-1"
        )
        val dao = FakeMilestoneDao(listOf(local))
        val echoed = local.copy(linkedMomentSyncId = null, updatedAt = 1_300L)
        val upserted = MilestoneRepository(dao).mergeRemote(listOf(echoed), missingLinkedMomentField = setOf("m1"))
        assertEquals(0, dao.upsertCount)
        assertTrue(upserted.isEmpty())
        assertEquals("photo-1", dao.rows.getValue("m1").linkedMomentSyncId)
    }

    /**
     * Coverage gap flagged by review round 2: every other linked-photo test exercises the SKIPPED path.
     * This one covers the case where the preservation actually has to survive into a real write - an
     * old-build partner echoing a milestone it genuinely renamed. The link must be preserved AND the
     * rename must land.
     */
    @Test
    fun `an old-build partner's real edit preserves the local link while applying the change`() = runBlocking {
        val local = Milestone(
            id = "m1", label = "Anniversary", month = 2, day = 14, createdAt = 1L, updatedAt = 1_000L,
            linkedMomentSyncId = "photo-1"
        )
        val dao = FakeMilestoneDao(listOf(local))
        val renamedByOldBuild = local.copy(label = "Our Anniversary", linkedMomentSyncId = null, updatedAt = 2_000L)

        val upserted = MilestoneRepository(dao).mergeRemote(listOf(renamedByOldBuild), missingLinkedMomentField = setOf("m1"))

        assertEquals(1, upserted.size)
        assertEquals("the real edit must be applied", "Our Anniversary", dao.rows.getValue("m1").label)
        assertEquals(
            "and the local photo link must survive it, not be nulled by the old build's omission",
            "photo-1", dao.rows.getValue("m1").linkedMomentSyncId
        )
    }

    @Test
    fun `an explicit clear of the linked photo from a same-build partner is still applied`() = runBlocking {
        val local = Milestone(
            id = "m1", label = "Anniversary", month = 2, day = 14, createdAt = 1L, updatedAt = 1_000L,
            linkedMomentSyncId = "photo-1"
        )
        val dao = FakeMilestoneDao(listOf(local))
        val cleared = local.copy(linkedMomentSyncId = null, updatedAt = 2_000L)
        MilestoneRepository(dao).mergeRemote(listOf(cleared), missingLinkedMomentField = emptySet())
        assertEquals(1, dao.upsertCount)
        assertEquals(null, dao.rows.getValue("m1").linkedMomentSyncId)
    }

    @Test
    fun `list echoed back with only a newer timestamp is not re-written`() = runBlocking {
        val local = ListCategory(id = "L", name = "Movie Night", createdAt = 500L, updatedAt = 1_000L)
        val dao = FakeListCategoryDao(listOf(local))
        val repo = ListCategoryRepository(dao, DateIdeaRepository(FakeDateIdeaDao()), mock(AppDatabase::class.java))
        repo.mergeRemote(listOf(local.copy(updatedAt = 1_300L)))
        assertEquals(0, dao.upsertCount)
    }

    /**
     * The two devices seed DEFAULT_LIST_ID from their own migration clocks, so their createdAt values
     * differ permanently. If createdAt counted as content, the guard would never fire for the one row
     * every couple is guaranteed to have.
     */
    @Test
    fun `a list whose createdAt differs between devices is still not re-written`() = runBlocking {
        val local = ListCategory(id = "L", name = "Date Ideas", createdAt = 500L, updatedAt = 1_000L)
        val dao = FakeListCategoryDao(listOf(local))
        val repo = ListCategoryRepository(dao, DateIdeaRepository(FakeDateIdeaDao()), mock(AppDatabase::class.java))
        repo.mergeRemote(listOf(local.copy(createdAt = 512L, updatedAt = 1_300L)))
        assertEquals(0, dao.upsertCount)
    }

    /**
     * The default list is seeded independently on each phone, so its createdAt differs - by weeks for a
     * couple who installed at different times - and the "Our Lists" screen orders by createdAt ASC. With
     * the no-op-write guard in place nothing would ever carry that value across, so it must be converged
     * explicitly. Both directions are asserted because the whole point is that the two devices reach the
     * SAME answer regardless of which one merges first.
     */
    @Test
    fun `list createdAt converges downward so both phones order lists identically`() = runBlocking {
        val earlier = 1_000L
        val later = 9_999_000L

        val laterDao = FakeListCategoryDao(listOf(ListCategory(id = "L", name = "Date Ideas", createdAt = later, updatedAt = 500L)))
        ListCategoryRepository(laterDao, DateIdeaRepository(FakeDateIdeaDao()), mock(AppDatabase::class.java))
            .mergeRemote(listOf(ListCategory(id = "L", name = "Date Ideas", createdAt = earlier, updatedAt = 500L)))
        assertEquals(earlier, laterDao.rows.getValue("L").createdAt)

        val earlierDao = FakeListCategoryDao(listOf(ListCategory(id = "L", name = "Date Ideas", createdAt = earlier, updatedAt = 500L)))
        ListCategoryRepository(earlierDao, DateIdeaRepository(FakeDateIdeaDao()), mock(AppDatabase::class.java))
            .mergeRemote(listOf(ListCategory(id = "L", name = "Date Ideas", createdAt = later, updatedAt = 500L)))
        assertEquals("must never be dragged upward - that is what makes it converge", earlier, earlierDao.rows.getValue("L").createdAt)
    }

    @Test
    fun `list createdAt convergence self-extinguishes and stays frozen`() = runBlocking {
        val dao = FakeListCategoryDao(listOf(ListCategory(id = "L", name = "Date Ideas", createdAt = 9_000L, updatedAt = 500L)))
        val repo = ListCategoryRepository(dao, DateIdeaRepository(FakeDateIdeaDao()), mock(AppDatabase::class.java))
        val incoming = listOf(ListCategory(id = "L", name = "Date Ideas", createdAt = 1_000L, updatedAt = 500L))
        repeat(10) { repo.mergeRemote(incoming) }
        assertEquals(1_000L, dao.rows.getValue("L").createdAt)
        assertEquals("convergence must not turn into churn", 0, dao.upsertCount)
    }

    /** A rename must not undo the convergence by writing the remote's raw (higher) createdAt back. */
    @Test
    fun `a rename carries the converged createdAt, not the remote's raw one`() = runBlocking {
        val dao = FakeListCategoryDao(listOf(ListCategory(id = "L", name = "Movie Night", createdAt = 1_000L, updatedAt = 1_000L)))
        val repo = ListCategoryRepository(dao, DateIdeaRepository(FakeDateIdeaDao()), mock(AppDatabase::class.java))
        repo.mergeRemote(listOf(ListCategory(id = "L", name = "Films", createdAt = 9_000L, updatedAt = 2_000L)))
        assertEquals("Films", dao.rows.getValue("L").name)
        assertEquals(1_000L, dao.rows.getValue("L").createdAt)
    }

    @Test
    fun `a renamed list is still applied`() = runBlocking {
        val local = ListCategory(id = "L", name = "Movie Night", createdAt = 500L, updatedAt = 1_000L)
        val dao = FakeListCategoryDao(listOf(local))
        val repo = ListCategoryRepository(dao, DateIdeaRepository(FakeDateIdeaDao()), mock(AppDatabase::class.java))
        repo.mergeRemote(listOf(local.copy(name = "Films", updatedAt = 2_000L)))
        assertEquals(1, dao.upsertCount)
        assertEquals("Films", dao.rows.getValue("L").name)
    }

    @Test
    fun `the default list can still never be tombstoned by a peer`() = runBlocking {
        val defaultList = ListCategory(
            id = com.ssbmedia.twogether.data.db.DEFAULT_LIST_ID, name = "Date Ideas", createdAt = 1L, updatedAt = 1_000L
        )
        val dao = FakeListCategoryDao(listOf(defaultList))
        val repo = ListCategoryRepository(dao, DateIdeaRepository(FakeDateIdeaDao()), mock(AppDatabase::class.java))
        repo.mergeRemote(listOf(defaultList.copy(deleted = true, updatedAt = 9_000L)))
        assertEquals(0, dao.upsertCount)
        assertFalse(dao.rows.getValue(com.ssbmedia.twogether.data.db.DEFAULT_LIST_ID).deleted)
    }

    @Test
    fun `partner note echoed back with only a newer timestamp is not re-written`() = runBlocking {
        val note = MomentNote(momentSyncId = "s1", authorDeviceId = "partner", text = "Our first test photo", updatedAt = 1_000L)
        val dao = FakeMomentNoteDao(listOf(note))
        MomentNoteRepository(dao).mergeRemote(listOf(note.copy(updatedAt = 1_300L)), myDeviceId = "me")
        assertEquals(0, dao.upsertCount)
    }

    @Test
    fun `an edited partner note is still applied`() = runBlocking {
        val note = MomentNote(momentSyncId = "s1", authorDeviceId = "partner", text = "old", updatedAt = 1_000L)
        val dao = FakeMomentNoteDao(listOf(note))
        MomentNoteRepository(dao).mergeRemote(listOf(note.copy(text = "new", updatedAt = 2_000L)), myDeviceId = "me")
        assertEquals(1, dao.upsertCount)
        assertEquals("new", dao.rows.getValue("s1" to "partner").text)
    }

    @Test
    fun `my own note is still never taken from the wire`() = runBlocking {
        val mine = MomentNote(momentSyncId = "s1", authorDeviceId = "me", text = "mine", updatedAt = 1_000L)
        val dao = FakeMomentNoteDao(listOf(mine))
        MomentNoteRepository(dao).mergeRemote(listOf(mine.copy(text = "spoofed", updatedAt = 9_000L)), myDeviceId = "me")
        assertEquals(0, dao.upsertCount)
        assertEquals("mine", dao.rows.getValue("s1" to "me").text)
    }

    // ---------------------------------------------------------------------------------------------
    // Defect 1, upgrade path - photos ALREADY synced before the field existed must self-heal
    // ---------------------------------------------------------------------------------------------

    private open class FakeMomentDao(seed: List<Moment> = emptyList()) : MomentDao {
        val rows = seed.associateBy { it.syncId }.toMutableMap()
        var updateCount = 0
        var insertCount = 0
        override suspend fun insert(moment: Moment): Long { insertCount++; rows[moment.syncId] = moment; return 1L }
        override suspend fun update(moment: Moment) { updateCount++; rows[moment.syncId] = moment }
        override fun observeAll(): Flow<List<Moment>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<Moment> = rows.values.toList()
        override fun observeActive(): Flow<List<Moment>> = flowOf(rows.values.filter { !it.deleted })
        override suspend fun getActive(): List<Moment> = rows.values.filter { !it.deleted }
        override suspend fun getBySyncId(syncId: String): Moment? = rows[syncId]
        override suspend fun updatePhotoDownloaded(syncId: String, downloaded: Boolean) {
            rows[syncId]?.let { rows[syncId] = it.copy(photoDownloaded = downloaded) }
        }
        /** Faithful to the real @Query: touches exactly one column, never a whole-row rewrite. */
        override suspend fun markTakenWhileTogether(syncId: String) {
            updateCount++
            rows[syncId]?.let { rows[syncId] = it.copy(takenWhileTogether = true) }
        }
        override suspend fun clearAll() { rows.clear() }
    }

    private fun remoteStub(syncId: String, takenWhileTogether: Boolean) = Moment(
        id = 1L, photoUri = "/local/$syncId.jpg", takenAt = 1_700_000_000_000L, sessionId = null,
        takenWhileTogether = takenWhileTogether, syncId = syncId, isRemote = true,
        photoDownloaded = true, updatedAt = 1_000L
    )

    @Test
    fun `an already-synced partner photo self-heals its caption on the next sync`() = runBlocking {
        // Exactly the state MIGRATION_10_11 leaves behind for a photo shared before the upgrade:
        // isRemote, sessionId null, so takenWhileTogether backfilled to false.
        val stale = remoteStub("s1", takenWhileTogether = false)
        val dao = FakeMomentDao(listOf(stale))
        val repo = MomentRepository(dao, mock(Context::class.java))

        repo.mergeRemoteStubs(listOf(stale.copy(takenWhileTogether = true, updatedAt = 1_000L)))

        assertTrue("the caption must correct itself, not stay wrong forever", dao.rows.getValue("s1").takenWhileTogether)
        assertEquals("a backfill is not a user edit - updatedAt must not move", 1_000L, dao.rows.getValue("s1").updatedAt)
    }

    @Test
    fun `the caption self-heal applies at most once and then stops writing`() = runBlocking {
        val dao = FakeMomentDao(listOf(remoteStub("s1", takenWhileTogether = false)))
        val repo = MomentRepository(dao, mock(Context::class.java))
        val incoming = listOf(remoteStub("s1", takenWhileTogether = true))
        repeat(5) { repo.mergeRemoteStubs(incoming) }
        assertEquals("must converge - one corrective write, not one per sync", 1, dao.updateCount)
    }

    /**
     * Review round 2: the self-heal must not be a stale-read full-row write. `local` comes from a
     * snapshot taken at the top of mergeRemoteStubs, so a photo transfer that completes in between would
     * have had its `photoDownloaded = true` reverted by a whole-row rewrite. Simulated here by mutating
     * the row behind the repository's snapshot, exactly as a concurrent markPhotoDownloaded would.
     */
    @Test
    fun `the caption self-heal cannot revert a concurrently-completed photo download`() = runBlocking {
        val stale = remoteStub("s1", takenWhileTogether = false).copy(photoDownloaded = false)
        val dao = object : FakeMomentDao(listOf(stale)) {
            override suspend fun getAll(): List<Moment> {
                // Hand out the pre-transfer snapshot, then let the "transfer" land before the write.
                val snapshot = super.getAll()
                rows["s1"] = rows.getValue("s1").copy(photoDownloaded = true)
                return snapshot
            }
        }
        MomentRepository(dao, mock(Context::class.java))
            .mergeRemoteStubs(listOf(remoteStub("s1", takenWhileTogether = true)))

        assertTrue("the caption backfill must still apply", dao.rows.getValue("s1").takenWhileTogether)
        assertTrue(
            "and must NOT have clobbered the photo-download flag committed after the snapshot",
            dao.rows.getValue("s1").photoDownloaded
        )
    }

    @Test
    fun `a peer can never flip the caption of a photo this phone took`() = runBlocking {
        val mine = Moment(
            id = 1L, photoUri = "/local/mine.jpg", takenAt = 1_700_000_000_000L, sessionId = null,
            takenWhileTogether = false, syncId = "s1", isRemote = false, photoDownloaded = true, updatedAt = 1_000L
        )
        val dao = FakeMomentDao(listOf(mine))
        val repo = MomentRepository(dao, mock(Context::class.java))
        repo.mergeRemoteStubs(listOf(mine.copy(takenWhileTogether = true, isRemote = true)))
        assertFalse(
            "our own capture-time value is authoritative for a photo we took",
            dao.rows.getValue("s1").takenWhileTogether
        )
        assertEquals(0, dao.updateCount)
    }

    @Test
    fun `the self-heal never flips a caption back from together to apart`() = runBlocking {
        val dao = FakeMomentDao(listOf(remoteStub("s1", takenWhileTogether = true)))
        val repo = MomentRepository(dao, mock(Context::class.java))
        repo.mergeRemoteStubs(listOf(remoteStub("s1", takenWhileTogether = false)))
        assertTrue(dao.rows.getValue("s1").takenWhileTogether)
        assertEquals(0, dao.updateCount)
    }

    @Test
    fun `a moment tombstone still wins over the caption self-heal`() = runBlocking {
        val dao = FakeMomentDao(listOf(remoteStub("s1", takenWhileTogether = false)))
        val repo = MomentRepository(dao, mock(Context::class.java))
        repo.mergeRemoteStubs(listOf(remoteStub("s1", takenWhileTogether = true).copy(deleted = true, updatedAt = 2_000L)))
        assertTrue("deleting a photo must not be starved by the caption backfill", dao.rows.getValue("s1").deleted)
    }

    /**
     * The end-to-end shape of defect 2: repeatedly re-applying the same unchanged payload, each time with
     * a slightly newer timestamp (exactly what the ratchet produced), must converge to zero writes rather
     * than writing on every single round forever.
     */
    @Test
    fun `repeated echoes of unchanged data converge to zero writes`() = runBlocking {
        val local = DateIdea(id = "i1", text = "Dune Part Two", listId = "L", done = false, updatedAt = 1_000L)
        val dao = FakeDateIdeaDao(listOf(local))
        val repo = DateIdeaRepository(dao)
        var stamp = 1_000L
        repeat(20) {
            stamp += 350L
            repo.mergeRemote(listOf(local.copy(updatedAt = stamp)))
        }
        assertEquals("20 syncs of unchanged data must produce no writes at all", 0, dao.upsertCount)
    }
}
