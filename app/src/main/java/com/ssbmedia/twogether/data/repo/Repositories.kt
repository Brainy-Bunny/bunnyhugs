package com.ssbmedia.twogether.data.repo

import com.ssbmedia.twogether.data.db.DateIdea
import com.ssbmedia.twogether.data.db.DateIdeaDao
import com.ssbmedia.twogether.data.db.Milestone
import com.ssbmedia.twogether.data.db.MilestoneDao
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.data.db.MomentDao
import com.ssbmedia.twogether.data.db.MomentNote
import com.ssbmedia.twogether.data.db.MomentNoteDao
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

    /**
     * Feature A: UNION merge (never last-write-wins-replace) of the partner's session rows into local
     * storage. Only CLOSED sessions (endedAt != null) are ever accepted here, even if a stray open one
     * slipped into the payload somehow - see GattSyncManager's doc for why an open session is
     * deliberately never sent in the first place. Dedup is by the stable [TogetherSession.syncId], not
     * the local auto-increment [TogetherSession.id] (which is meaningless across two independent
     * devices' Room databases - see syncId's doc in Entities.kt). Any remote row whose syncId we don't
     * already have locally is inserted as a brand-new local row (id=0 so Room assigns this device's own
     * next local id) with its original startedAt/endedAt/isManual/syncId preserved verbatim - nothing is
     * ever overwritten or dropped, so two devices with divergent history converge to the UNION of both
     * histories, never one side's data replacing the other's. Any real-world overlap this creates
     * (both phones independently logged the same BLE detection) is left in the DB as-is and correctly
     * de-duplicated at read time by StatsCalculator's existing interval-merge - see its doc.
     */
    suspend fun mergeRemoteSessions(remote: List<TogetherSession>) {
        val localSyncIds = dao.getAll().mapNotNull { it.syncId.takeIf { id -> id.isNotBlank() } }.toSet()
        val toInsert = remote.filter { it.endedAt != null && it.syncId.isNotBlank() && it.syncId !in localSyncIds }
        toInsert.forEach { dao.insert(it.copy(id = 0)) }
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

    /**
     * Feature D: union-merges the partner's moment METADATA (never photo bytes - see Moment.isRemote's
     * doc for why) so notes can reference a moment the partner hasn't necessarily seen locally yet.
     * Dedup by syncId, same reasoning as SessionRepository.mergeRemoteSessions. sessionId is deliberately
     * dropped (nulled out) on insert - a remote row's sessionId is a local auto-increment id from the
     * OTHER device's database and is meaningless (and potentially misleading/coincidentally-colliding)
     * here. isRemote is always forced true for anything inserted by this path, regardless of what the
     * sender claimed, since by definition anything we didn't already have locally originated elsewhere.
     */
    suspend fun mergeRemoteStubs(remote: List<Moment>) {
        val localSyncIds = dao.getAll().mapNotNull { it.syncId.takeIf { id -> id.isNotBlank() } }.toSet()
        val toInsert = remote.filter { it.syncId.isNotBlank() && it.syncId !in localSyncIds }
        toInsert.forEach { dao.insert(it.copy(id = 0, sessionId = null, isRemote = true)) }
    }
}

class MomentNoteRepository(private val dao: MomentNoteDao) {
    fun observeForMoment(momentSyncId: String): Flow<List<MomentNote>> = dao.observeForMoment(momentSyncId)
    suspend fun getAll(): List<MomentNote> = dao.getAll()
    suspend fun getAllForAuthor(deviceId: String): List<MomentNote> = dao.getAllForAuthor(deviceId)

    /** Saves (or, for blank text, tombstones) THIS device's own note for a moment - never the partner's;
     * each side only ever writes rows keyed by its own [authorDeviceId], see Entities.kt's doc on
     * MomentNote. Blank text sets deleted=true (mirroring DateIdeaRepository.softDelete) so clearing a
     * previously-synced note is itself something that can propagate to the partner on the next sync. */
    suspend fun saveMyNote(momentSyncId: String, authorDeviceId: String, text: String) {
        dao.upsert(
            MomentNote(
                momentSyncId = momentSyncId,
                authorDeviceId = authorDeviceId,
                text = text.trim(),
                updatedAt = System.currentTimeMillis(),
                deleted = text.isBlank()
            )
        )
    }

    /**
     * Feature D: last-write-wins merge of the PARTNER's notes into local storage, keyed by
     * (momentSyncId, authorDeviceId) + updatedAt - same LWW shape as DateIdeaRepository.mergeRemote, but
     * per-author rather than a single shared row, since both partners can each have their own note on
     * the same moment with no real conflict between them (they're different rows entirely).
     *
     * Rows claiming OUR OWN [myDeviceId] as author are dropped outright: a device's own note must only
     * ever come from its own local edits, never overwritten by something arriving over the wire (that
     * would mean either a protocol bug, or - after a Feature 4 restore onto a different physical device -
     * a genuinely confusing "which copy is really mine" situation neither side should silently resolve).
     */
    suspend fun mergeRemote(remote: List<MomentNote>, myDeviceId: String) {
        val incoming = remote.filter { it.authorDeviceId.isNotBlank() && it.authorDeviceId != myDeviceId }
        if (incoming.isEmpty()) return
        val localByKey = dao.getAll().associateBy { it.momentSyncId to it.authorDeviceId }
        val toUpsert = incoming.filter { r ->
            val l = localByKey[r.momentSyncId to r.authorDeviceId]
            l == null || r.updatedAt > l.updatedAt
        }
        if (toUpsert.isNotEmpty()) dao.upsertAll(toUpsert)
    }
}

class MilestoneRepository(private val dao: MilestoneDao) {
    fun observeActive(): Flow<List<Milestone>> = dao.observeActive()
    suspend fun getAll(): List<Milestone> = dao.getAll()

    suspend fun add(label: String, month: Int, day: Int, year: Int?): Milestone {
        val now = System.currentTimeMillis()
        val milestone = Milestone(
            id = UUID.randomUUID().toString(),
            label = label,
            month = month,
            day = day,
            year = year,
            createdAt = now,
            updatedAt = now
        )
        dao.upsert(milestone)
        return milestone
    }

    suspend fun delete(milestone: Milestone) {
        dao.upsert(milestone.copy(deleted = true, updatedAt = System.currentTimeMillis()))
    }

    /** Union+tombstone merge by id + updatedAt, same LWW shape as DateIdeaRepository.mergeRemote - these
     * are simple, rarely-edited additions, so plain last-write-wins is appropriate (see task spec). */
    suspend fun mergeRemote(remote: List<Milestone>) {
        val local = dao.getAll().associateBy { it.id }
        val toUpsert = remote.filter { r ->
            val l = local[r.id]
            l == null || r.updatedAt > l.updatedAt
        }
        if (toUpsert.isNotEmpty()) dao.upsertAll(toUpsert)
    }
}
