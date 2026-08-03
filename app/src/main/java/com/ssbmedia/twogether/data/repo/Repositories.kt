package com.ssbmedia.twogether.data.repo

import com.ssbmedia.twogether.data.db.DateIdea
import com.ssbmedia.twogether.data.db.DateIdeaDao
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.data.db.MomentDao
import com.ssbmedia.twogether.data.db.TimeCapsule
import com.ssbmedia.twogether.data.db.TimeCapsuleDao
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.data.db.TogetherSessionDao
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class SessionRepository(private val dao: TogetherSessionDao) {
    fun observeAll(): Flow<List<TogetherSession>> = dao.observeAll()
    fun observeOpenSession(): Flow<TogetherSession?> = dao.observeOpenSession()
    suspend fun getAll(): List<TogetherSession> = dao.getAll()
    suspend fun getOpenSession(): TogetherSession? = dao.getOpenSession()

    suspend fun startSession(startedAt: Long): Long = dao.insert(TogetherSession(startedAt = startedAt))

    suspend fun endSession(session: TogetherSession, endedAt: Long) {
        dao.update(session.copy(endedAt = endedAt))
    }

    /** Backfills a completed together-session by hand (e.g. time spent together before install, or a
     * day BLE detection missed). Counts fully toward stats/streaks/calendar exactly like a BLE-detected
     * session - the only difference is isManual=true, kept purely for optional UI labeling.
     *
     * Returns null (instead of throwing) for a degenerate non-positive duration rather than crashing
     * the caller's coroutine - CalendarScreen's dialog already validates this up front so it shouldn't
     * normally happen, but this is the last line of defense against an uncaught
     * IllegalArgumentException inside viewModelScope.launch if a caller ever slips a bad value through
     * (e.g. a same-day entry that clips down to exactly zero right at local midnight). */
    suspend fun addManualSession(startedAt: Long, endedAt: Long): Long? {
        if (endedAt <= startedAt) return null
        return dao.insert(TogetherSession(startedAt = startedAt, endedAt = endedAt, isManual = true))
    }
}

class DateIdeaRepository(private val dao: DateIdeaDao) {
    fun observeActive(): Flow<List<DateIdea>> = dao.observeActive()
    suspend fun getAll(): List<DateIdea> = dao.getAll()

    suspend fun add(text: String, category: String?) {
        dao.upsert(
            DateIdea(
                id = UUID.randomUUID().toString(),
                text = text,
                category = category,
                done = false,
                updatedAt = System.currentTimeMillis(),
                deleted = false
            )
        )
    }

    suspend fun setDone(idea: DateIdea, done: Boolean) {
        dao.upsert(idea.copy(done = done, updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(idea: DateIdea) {
        dao.upsert(idea.copy(deleted = true, updatedAt = System.currentTimeMillis()))
    }

    /** Last-write-wins merge of a remote list of ideas into local storage, by id + updatedAt. */
    suspend fun mergeRemote(remote: List<DateIdea>) {
        val local = dao.getAll().associateBy { it.id }
        val toUpsert = remote.filter { r ->
            val l = local[r.id]
            l == null || r.updatedAt > l.updatedAt
        }
        if (toUpsert.isNotEmpty()) dao.upsertAll(toUpsert)
    }
}

class TimeCapsuleRepository(private val dao: TimeCapsuleDao) {
    fun observeAll(): Flow<List<TimeCapsule>> = dao.observeAll()

    suspend fun add(text: String, unlockAtHours: Float) {
        dao.insert(TimeCapsule(text = text, unlockAtHours = unlockAtHours, createdAt = System.currentTimeMillis()))
    }

    /** Unlocks any capsules whose threshold has been crossed by the given cumulative together-hours. */
    suspend fun unlockEligible(cumulativeHours: Float) {
        val locked = dao.getLocked()
        val now = System.currentTimeMillis()
        locked.filter { it.unlockAtHours <= cumulativeHours }
            .forEach { dao.update(it.copy(unlockedAt = now)) }
    }
}

class MomentRepository(private val dao: MomentDao) {
    fun observeAll(): Flow<List<Moment>> = dao.observeAll()
    suspend fun getAll(): List<Moment> = dao.getAll()

    suspend fun add(photoUri: String, sessionId: Long?): Long =
        dao.insert(Moment(photoUri = photoUri, takenAt = System.currentTimeMillis(), sessionId = sessionId))
}
