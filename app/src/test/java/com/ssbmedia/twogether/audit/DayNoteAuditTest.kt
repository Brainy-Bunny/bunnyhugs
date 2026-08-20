package com.ssbmedia.twogether.audit

import androidx.sqlite.db.SupportSQLiteDatabase
import com.ssbmedia.twogether.data.db.AppDatabase
import com.ssbmedia.twogether.data.db.DayNote
import com.ssbmedia.twogether.data.db.DayNoteDao
import com.ssbmedia.twogether.data.repo.DayNoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

/**
 * UX-FIX-PLAN.md Phase 4 item 25: Calendar day notes - a free-text note attached to a specific calendar
 * day (not tied to any Moment/photo), structurally mirroring MomentNote's per-device LWW+tombstone shape
 * (see DayNote's own doc in Entities.kt for the full reasoning).
 *
 * Covers, mirroring SyncCaptionAndChurnAuditTest's MomentNoteRepository.mergeRemote coverage almost
 * line-for-line (same composite-key-LWW shape, same "never accept a row claiming my own deviceId" guard)
 * and ListReminderAuditTest's mocked-SupportSQLiteDatabase migration-verification approach (no Robolectric/
 * room-testing MigrationTestHelper is wired into this module - see that test's own doc for why):
 *  - AppDatabase.MIGRATION_12_13's actual SQL (CREATE TABLE, not an ALTER TABLE - a brand-new entity).
 *  - DayNoteRepository.saveMyNote's blank-means-tombstone semantics.
 *  - DayNoteRepository.mergeRemote's LWW-by-(date, authorDeviceId) merge, no-op-on-unchanged-echo guard,
 *    and the "my own deviceId is never trusted from the wire" guard.
 *  - A plain entity-level default/copy() round-trip.
 */
class DayNoteAuditTest {

    // ---------- MIGRATION_12_13 ----------

    @Test
    fun `MIGRATION_12_13 creates day_notes with exactly the entity's column set, nothing else`() {
        val db = mock(SupportSQLiteDatabase::class.java)
        AppDatabase.MIGRATION_12_13.migrate(db)

        // Same reflection-avoidance reasoning as ListReminderAuditTest's matching migration test: reading
        // raw Invocation.arguments sidesteps ArgumentCaptor's Kotlin-unsound null-placeholder NPE.
        val statements = mockingDetails(db).invocations
            .filter { it.method.name == "execSQL" }
            .map { it.arguments[0] as String }
        assertEquals("expected exactly one execSQL call, got: $statements", 1, statements.size)

        val createStatement = statements.single()
        assertTrue(createStatement.contains("CREATE TABLE"))
        assertTrue(createStatement.contains("day_notes"))
        // Column set, types, and NOT NULL/DEFAULT must match the @Entity exactly (Room's schema
        // validation throws on the first open after migration if they disagree) - same rigor
        // MIGRATION_1_2's own doc describes for a plain ALTER TABLE, applied here to a CREATE TABLE.
        assertTrue(createStatement.contains("date INTEGER NOT NULL"))
        assertTrue(createStatement.contains("authorDeviceId TEXT NOT NULL"))
        assertTrue(createStatement.contains("text TEXT NOT NULL"))
        assertTrue(createStatement.contains("updatedAt INTEGER NOT NULL"))
        assertTrue(createStatement.contains("deleted INTEGER NOT NULL DEFAULT 0"))
        // Composite primary key, matching @Entity(primaryKeys = ["date", "authorDeviceId"]) - the same
        // natural sync identity MomentNote's own (momentSyncId, authorDeviceId) composite key gives it,
        // with no separate syncId column needed.
        assertTrue(createStatement.contains("PRIMARY KEY(date, authorDeviceId)"))
    }

    // ---------- Fake DAO - real in-memory implementation so writes can be counted ----------

    private class FakeDayNoteDao(seed: List<DayNote> = emptyList()) : DayNoteDao {
        val rows = seed.associateBy { it.date to it.authorDeviceId }.toMutableMap()
        var upsertCount = 0
        override suspend fun upsert(note: DayNote) { upsertCount++; rows[note.date to note.authorDeviceId] = note }
        override suspend fun upsertAll(notes: List<DayNote>) {
            notes.forEach { upsertCount++; rows[it.date to it.authorDeviceId] = it }
        }
        override fun observeForDate(date: Long): Flow<List<DayNote>> =
            flowOf(rows.values.filter { it.date == date && !it.deleted })
        override fun observeActive(): Flow<List<DayNote>> = flowOf(rows.values.filter { !it.deleted })
        override suspend fun getAllForAuthor(deviceId: String): List<DayNote> = rows.values.filter { it.authorDeviceId == deviceId }
        override suspend fun getAll(): List<DayNote> = rows.values.toList()
        override suspend fun clearAll() { rows.clear() }
    }

    // ---------- DayNoteRepository.saveMyNote ----------

    @Test
    fun `saveMyNote writes non-blank text as a live (non-tombstoned) row`() = runBlocking {
        val dao = FakeDayNoteDao()
        DayNoteRepository(dao).saveMyNote(date = 19_950L, authorDeviceId = "me", text = "  A lovely day out  ")
        val stored = dao.rows.getValue(19_950L to "me")
        assertEquals("text is trimmed, mirroring MomentNoteRepository.saveMyNote", "A lovely day out", stored.text)
        assertFalse(stored.deleted)
    }

    @Test
    fun `saveMyNote with blank text tombstones instead of leaving live empty text`() = runBlocking {
        val dao = FakeDayNoteDao()
        DayNoteRepository(dao).saveMyNote(date = 19_950L, authorDeviceId = "me", text = "   ")
        val stored = dao.rows.getValue(19_950L to "me")
        assertEquals("", stored.text)
        assertTrue("blank text must tombstone, mirroring MomentNoteRepository.saveMyNote", stored.deleted)
    }

    @Test
    fun `saveMyNote replaces a previous note for the same day and author (REPLACE upsert)`() = runBlocking {
        val dao = FakeDayNoteDao()
        val repo = DayNoteRepository(dao)
        repo.saveMyNote(date = 19_950L, authorDeviceId = "me", text = "first draft")
        repo.saveMyNote(date = 19_950L, authorDeviceId = "me", text = "final version")
        assertEquals(1, dao.rows.size)
        assertEquals("final version", dao.rows.getValue(19_950L to "me").text)
    }

    // ---------- DayNoteRepository.mergeRemote ----------

    @Test
    fun `partner note echoed back with only a newer timestamp is not re-written`() = runBlocking {
        val note = DayNote(date = 19_950L, authorDeviceId = "partner", text = "Beach day", updatedAt = 1_000L)
        val dao = FakeDayNoteDao(listOf(note))
        DayNoteRepository(dao).mergeRemote(listOf(note.copy(updatedAt = 1_300L)), myDeviceId = "me")
        assertEquals(0, dao.upsertCount)
    }

    @Test
    fun `an edited partner note is still applied`() = runBlocking {
        val note = DayNote(date = 19_950L, authorDeviceId = "partner", text = "old", updatedAt = 1_000L)
        val dao = FakeDayNoteDao(listOf(note))
        DayNoteRepository(dao).mergeRemote(listOf(note.copy(text = "new", updatedAt = 2_000L)), myDeviceId = "me")
        assertEquals(1, dao.upsertCount)
        assertEquals("new", dao.rows.getValue(19_950L to "partner").text)
    }

    @Test
    fun `a brand new remote note for a day we have no local row for yet is inserted`() = runBlocking {
        val dao = FakeDayNoteDao()
        val remote = DayNote(date = 20_001L, authorDeviceId = "partner", text = "Movie night", updatedAt = 5_000L)
        DayNoteRepository(dao).mergeRemote(listOf(remote), myDeviceId = "me")
        assertEquals(1, dao.upsertCount)
        assertEquals("Movie night", dao.rows.getValue(20_001L to "partner").text)
    }

    @Test
    fun `a remote tombstone is still applied`() = runBlocking {
        val note = DayNote(date = 19_950L, authorDeviceId = "partner", text = "oops wrong day", updatedAt = 1_000L)
        val dao = FakeDayNoteDao(listOf(note))
        DayNoteRepository(dao).mergeRemote(listOf(note.copy(deleted = true, updatedAt = 2_000L)), myDeviceId = "me")
        assertTrue(dao.rows.getValue(19_950L to "partner").deleted)
    }

    @Test
    fun `an older remote note never overwrites a newer local edit`() = runBlocking {
        val local = DayNote(date = 19_950L, authorDeviceId = "partner", text = "local newer edit", updatedAt = 9_000L)
        val dao = FakeDayNoteDao(listOf(local))
        DayNoteRepository(dao).mergeRemote(listOf(local.copy(text = "stale remote", updatedAt = 1_000L)), myDeviceId = "me")
        assertEquals(0, dao.upsertCount)
        assertEquals("local newer edit", dao.rows.getValue(19_950L to "partner").text)
    }

    /**
     * CRITICAL guard, mirroring MomentNoteRepository.mergeRemote's identical protection verbatim (see
     * that function's own doc): a device's own note must only ever come from its own local edits, never
     * overwritten by something arriving over the wire - whether from a protocol bug, or a genuinely
     * confusing "which copy is really mine" situation after a Feature 4 restore onto a different physical
     * device. Rows claiming OUR OWN deviceId as author are dropped outright, regardless of updatedAt.
     */
    @Test
    fun `my own note is never taken from the wire, even with a far-newer timestamp`() = runBlocking {
        val mine = DayNote(date = 19_950L, authorDeviceId = "me", text = "mine", updatedAt = 1_000L)
        val dao = FakeDayNoteDao(listOf(mine))
        DayNoteRepository(dao).mergeRemote(listOf(mine.copy(text = "spoofed", updatedAt = 9_000L)), myDeviceId = "me")
        assertEquals(0, dao.upsertCount)
        assertEquals("mine", dao.rows.getValue(19_950L to "me").text)
    }

    @Test
    fun `both partners can independently have their own note on the same day with no conflict`() = runBlocking {
        val dao = FakeDayNoteDao(listOf(DayNote(date = 19_950L, authorDeviceId = "me", text = "my note", updatedAt = 1_000L)))
        val remote = DayNote(date = 19_950L, authorDeviceId = "partner", text = "partner's note", updatedAt = 1_000L)
        DayNoteRepository(dao).mergeRemote(listOf(remote), myDeviceId = "me")
        assertEquals(2, dao.rows.size)
        assertEquals("my note", dao.rows.getValue(19_950L to "me").text)
        assertEquals("partner's note", dao.rows.getValue(19_950L to "partner").text)
    }

    @Test
    fun `repeated echoes of unchanged partner data converge to zero writes`() = runBlocking {
        val local = DayNote(date = 19_950L, authorDeviceId = "partner", text = "Beach day", updatedAt = 1_000L)
        val dao = FakeDayNoteDao(listOf(local))
        val repo = DayNoteRepository(dao)
        var stamp = 1_000L
        repeat(20) {
            stamp += 350L
            repo.mergeRemote(listOf(local.copy(updatedAt = stamp)), myDeviceId = "me")
        }
        assertEquals("20 syncs of unchanged data must produce no writes at all", 0, dao.upsertCount)
    }

    // ---------- Entity round-trip ----------

    @Test
    fun `DayNote defaults to not deleted, and copy() round-trips a tombstone`() {
        val note = DayNote(date = 19_950L, authorDeviceId = "me", text = "A day to remember", updatedAt = 1L)
        assertFalse(note.deleted)
        val tombstoned = note.copy(deleted = true)
        assertTrue(tombstoned.deleted)
    }
}
