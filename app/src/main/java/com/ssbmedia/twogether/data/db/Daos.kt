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

    @Query("SELECT * FROM together_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<TogetherSession>>

    @Query("SELECT * FROM together_sessions ORDER BY startedAt DESC")
    suspend fun getAll(): List<TogetherSession>

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

    @Update
    suspend fun update(capsule: TimeCapsule)

    @Query("SELECT * FROM time_capsules ORDER BY unlockAtHours ASC")
    fun observeAll(): Flow<List<TimeCapsule>>

    @Query("SELECT * FROM time_capsules WHERE unlockedAt IS NULL")
    suspend fun getLocked(): List<TimeCapsule>

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

    @Query("SELECT * FROM moments ORDER BY takenAt DESC")
    fun observeAll(): Flow<List<Moment>>

    @Query("SELECT * FROM moments ORDER BY takenAt DESC")
    suspend fun getAll(): List<Moment>

    /** Wipes every row - used only by Feature 4's backup restore, which always fully repopulates this
     * table immediately afterward inside the same DB transaction. */
    @Query("DELETE FROM moments")
    suspend fun clearAll()
}
