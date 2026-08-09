package com.ssbmedia.twogether.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TogetherSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: TogetherSession): Long

    @Update
    suspend fun update(session: TogetherSession)

    /** Raw/unfiltered - includes soft-deleted (tombstoned) rows. Used directly by BackupManager (so
     * backups round-trip tombstones) and by SessionRepository.mergeRemoteSessions (which needs to see
     * already-tombstoned local rows to match syncIds regardless of deletion state). Everywhere else that
     * wants "sessions the user actually sees" should go through SessionRepository.observeAll(), which is
     * backed by [observeActive] below instead. */
    @Query("SELECT * FROM together_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<TogetherSession>>

    @Query("SELECT * FROM together_sessions ORDER BY startedAt DESC")
    suspend fun getAll(): List<TogetherSession>

    @Query("SELECT * FROM together_sessions WHERE deleted = 0 ORDER BY startedAt DESC")
    fun observeActive(): Flow<List<TogetherSession>>

    @Query("SELECT * FROM together_sessions WHERE deleted = 0 ORDER BY startedAt DESC")
    suspend fun getActive(): List<TogetherSession>

    @Query("SELECT * FROM together_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getOpenSession(): TogetherSession?

    @Query("SELECT * FROM together_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeOpenSession(): Flow<TogetherSession?>

    /** Wipes every row - used only by Feature 4's backup restore, which always fully repopulates this
     * table immediately afterward inside the same DB transaction. */
    @Query("DELETE FROM together_sessions")
    suspend fun clearAll()
}

@Dao
interface DateIdeaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(idea: DateIdea)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(ideas: List<DateIdea>)

    @Query("SELECT * FROM date_ideas WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<DateIdea>>

    @Query("SELECT * FROM date_ideas")
    suspend fun getAll(): List<DateIdea>

    /** Wipes every row - used only by Feature 4's backup restore, which always fully repopulates this
     * table immediately afterward inside the same DB transaction. */
    @Query("DELETE FROM date_ideas")
    suspend fun clearAll()
}

@Dao
interface TimeCapsuleDao {
    @Insert
    suspend fun insert(capsule: TimeCapsule): Long

    // MINOR fix (test-code-allmodels, Sonnet - final clean-room pass): the raw @Update(capsule) was left
    // on this interface with zero remaining production callers (every real write site now goes through
    // the narrower unlockIfNotDeleted/tombstone/insert queries above/below) - removed rather than left as
    // a latent footgun. A full-row @Update is exactly the stale-read-full-row-overwrite shape this file's
    // two targeted queries exist to avoid; a future feature reaching for the obvious dao.update(capsule.
    // copy(...)) here would silently reintroduce the same race class this review spent multiple rounds
    // closing. If a genuine future need for a full-row update arises, add a fresh, deliberately-scoped
    // query rather than resurrecting this one.

    /** Feature: Time Capsule sync. Excludes soft-deleted (tombstoned) rows - what the UI should always
     * see. */
    @Query("SELECT * FROM time_capsules WHERE deleted = 0 ORDER BY unlockAtHours ASC")
    fun observeActive(): Flow<List<TimeCapsule>>

    @Query("SELECT * FROM time_capsules WHERE unlockedAt IS NULL AND deleted = 0")
    suspend fun getLocked(): List<TimeCapsule>

    /** MINOR fix (test-code-allmodels, Fable): TimeCapsuleRepository.unlockEligible used to read a
     * capsule via [getLocked], then write it back with a plain [update] - a stale-read full-row
     * overwrite with no transaction. If a tombstone (local delete, or a sync merge's tombstone-apply)
     * landed on that exact row between the read and the write, the [update] would carry the STALE
     * `deleted = false` forward, silently resurrecting a just-deleted capsule as unlocked - the
     * race-window variant of the exact resurrection bug class this app's whole review history has
     * already fixed logically (see TimeCapsuleRepository.mergeRemote's own doc). This targeted query
     * re-checks `deleted = 0` as part of the same atomic SQL statement that performs the write, so a
     * tombstone that landed after the read but before this executes correctly makes the row not match
     * and the unlock silently (and correctly) doesn't happen - the next `unlockEligible` pass on a
     * still-eligible, still-active capsule would unlock it normally regardless. */
    @Query("UPDATE time_capsules SET unlockedAt = :unlockedAt, updatedAt = :updatedAt WHERE id = :id AND deleted = 0")
    suspend fun unlockIfNotDeleted(id: Long, unlockedAt: Long, updatedAt: Long)

    /** MINOR fix (code-review, Sonnet - same race class as [unlockIfNotDeleted], one call site over):
     * TimeCapsuleRepository.delete()/mergeRemote's tombstone-apply branch used to read a capsule then
     * write it back with a plain [update] carrying the WHOLE row, including whatever `unlockedAt` the
     * read snapshot happened to have. If a concurrent `unlockEligible` pass committed a real unlock on
     * that exact row between the read and this write, the stale-snapshot [update] would silently null it
     * back out - the mirror-image of the resurrection race [unlockIfNotDeleted] closes, this time
     * clobbering a just-set unlock instead of reviving a just-deleted row. This targeted query only ever
     * touches `deleted`/`updatedAt`, never `unlockedAt`, so it structurally cannot carry a stale
     * unlockedAt snapshot forward regardless of timing - no read-before-write needed at all. */
    @Query("UPDATE time_capsules SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun tombstone(id: Long, updatedAt: Long)

    /** Raw/unfiltered - includes soft-deleted (tombstoned) rows. Used by BackupManager (so backups
     * round-trip tombstones) and by TimeCapsuleRepository.mergeRemote (needs to see already-tombstoned
     * local rows to match syncIds regardless of deletion state), same pattern as every other synced
     * entity's DAO. */
    @Query("SELECT * FROM time_capsules ORDER BY unlockAtHours ASC")
    suspend fun getAll(): List<TimeCapsule>

    /** Wipes every row - used only by Feature 4's backup restore, which always fully repopulates this
     * table immediately afterward inside the same DB transaction. */
    @Query("DELETE FROM time_capsules")
    suspend fun clearAll()
}

@Dao
interface MomentDao {
    @Insert
    suspend fun insert(moment: Moment): Long

    @Update
    suspend fun update(moment: Moment)

    /** Raw/unfiltered - includes soft-deleted (tombstoned) rows. Used directly by BackupManager (so
     * backups round-trip tombstones), by GattSyncManager's payload builder (which must send deleted
     * moments too so the deletion itself propagates to the partner's phone), and by
     * MomentRepository.mergeRemoteStubs (which needs to see already-tombstoned local rows to match
     * syncIds regardless of deletion state). Everywhere else that wants "moments the user actually sees"
     * should go through MomentRepository.observeAll(), which is backed by [observeActive] below instead. */
    @Query("SELECT * FROM moments ORDER BY takenAt DESC")
    fun observeAll(): Flow<List<Moment>>

    @Query("SELECT * FROM moments ORDER BY takenAt DESC")
    suspend fun getAll(): List<Moment>

    @Query("SELECT * FROM moments WHERE deleted = 0 ORDER BY takenAt DESC")
    fun observeActive(): Flow<List<Moment>>

    @Query("SELECT * FROM moments WHERE deleted = 0 ORDER BY takenAt DESC")
    suspend fun getActive(): List<Moment>

    @Query("SELECT * FROM moments WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): Moment?

    /** Feature 2: flips a Moment's photoDownloaded flag once GattSyncManager's photo-transfer phase has
     * fully written and renamed the real file into place at [Moment.photoUri] - see
     * MomentRepository.markPhotoDownloaded's doc. */
    @Query("UPDATE moments SET photoDownloaded = :downloaded WHERE syncId = :syncId")
    suspend fun updatePhotoDownloaded(syncId: String, downloaded: Boolean)

    /** Wipes every row - used only by Feature 4's backup restore, which always fully repopulates this
     * table immediately afterward inside the same DB transaction. */
    @Query("DELETE FROM moments")
    suspend fun clearAll()
}

@Dao
interface MomentNoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: MomentNote)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notes: List<MomentNote>)

    @Query("SELECT * FROM moment_notes WHERE momentSyncId = :momentSyncId")
    fun observeForMoment(momentSyncId: String): Flow<List<MomentNote>>

    @Query("SELECT * FROM moment_notes WHERE authorDeviceId = :deviceId")
    suspend fun getAllForAuthor(deviceId: String): List<MomentNote>

    @Query("SELECT * FROM moment_notes")
    suspend fun getAll(): List<MomentNote>

    /** Wipes every row - used only by Feature 4's backup restore, which always fully repopulates this
     * table immediately afterward inside the same DB transaction. */
    @Query("DELETE FROM moment_notes")
    suspend fun clearAll()
}

@Dao
interface MilestoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(milestone: Milestone)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(milestones: List<Milestone>)

    @Query("SELECT * FROM milestones WHERE deleted = 0 ORDER BY month ASC, day ASC")
    fun observeActive(): Flow<List<Milestone>>

    @Query("SELECT * FROM milestones")
    suspend fun getAll(): List<Milestone>

    /** Wipes every row - used only by Feature 4's backup restore, which always fully repopulates this
     * table immediately afterward inside the same DB transaction. */
    @Query("DELETE FROM milestones")
    suspend fun clearAll()
}

@Dao
interface ListCategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: ListCategory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<ListCategory>)

    @Query("SELECT * FROM list_categories WHERE deleted = 0 ORDER BY createdAt ASC")
    fun observeActive(): Flow<List<ListCategory>>

    @Query("SELECT * FROM list_categories")
    suspend fun getAll(): List<ListCategory>

    /** Wipes every row - used only by Feature 4's backup restore, which always fully repopulates this
     * table immediately afterward inside the same DB transaction. */
    @Query("DELETE FROM list_categories")
    suspend fun clearAll()
}
