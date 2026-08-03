package com.ssbmedia.twogether.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "together_sessions")
data class TogetherSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** True for sessions backfilled by hand via "Add past time together" rather than detected over BLE. */
    val isManual: Boolean = false,
    /** Feature A: stable cross-device identity used for GATT sync dedup. The local auto-increment [id]
     * is NOT safe to compare across two independent devices - both phones' Room databases hand out
     * their own overlapping 1, 2, 3... sequences, so two totally unrelated sessions could share the
     * same [id] by coincidence. [syncId] is generated once (at insert, or backfilled by
     * AppDatabase.MIGRATION_2_3 for rows that predate sync) and never changes, so
     * SessionRepository.mergeRemoteSessions can tell "already have this" from "genuinely new" reliably. */
    val syncId: String = UUID.randomUUID().toString()
)

@Entity(tableName = "date_ideas")
data class DateIdea(
    @PrimaryKey val id: String,
    val text: String,
    val category: String? = null,
    val done: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false
)

@Entity(tableName = "time_capsules")
data class TimeCapsule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val unlockAtHours: Float,
    val createdAt: Long,
    val unlockedAt: Long? = null
)

@Entity(tableName = "moments")
data class Moment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoUri: String,
    val takenAt: Long,
    val sessionId: Long? = null,
    /** Feature D: stable cross-device identity, same reasoning as TogetherSession.syncId above - lets
     * MomentNote reference a specific moment by an id that means the same thing on both phones. */
    val syncId: String = UUID.randomUUID().toString(),
    /**
     * True when this Moment row arrived via GATT sync from the partner's phone rather than being taken
     * on this device. DELIBERATE SCOPE LIMITATION (flagged in the batch report): only the moment's
     * metadata (syncId/takenAt/existence) is synced, never the actual photo bytes - transferring a
     * multi-megabyte JPEG over this text-chunk BLE protocol (~180 byte chunks, write+ack per chunk)
     * would take minutes per photo, which is impractical. A remote-stub row's [photoUri] points to a
     * path that only exists on the ORIGINAL device, so it never resolves to a real file here. This still
     * lets the partner see that the moment exists (grouped correctly by date, included in the milestone
     * retrospective) and write their own note on it (Feature D's core ask) even without the pixels - the
     * UI must check local file existence before trying to render it as an image and show a clear
     * "photo is on {partner}'s phone" placeholder instead of a broken image.
     */
    val isRemote: Boolean = false
)

/**
 * Feature D: one partner's own free-form note on a Moment (photo). Composite-keyed by
 * (momentSyncId, authorDeviceId) rather than a synthetic id - there is at most one note per author per
 * moment (writing again just replaces it), which maps naturally onto a REPLACE upsert. Each device only
 * ever writes rows where authorDeviceId == its own SettingsStore.getOrCreateLocalDeviceId(); rows
 * authored by the OTHER id arrive purely via GattSyncManager's merge and are read-only in the UI - see
 * MomentNoteRepository's doc for the full sync contract.
 */
@Entity(tableName = "moment_notes", primaryKeys = ["momentSyncId", "authorDeviceId"])
data class MomentNote(
    val momentSyncId: String,
    val authorDeviceId: String,
    val text: String,
    val updatedAt: Long,
    /** Tombstone, mirroring DateIdea's pattern: saving blank text sets this instead of deleting the row
     * outright, so the clearing itself is something that can propagate to the partner on the next sync. */
    val deleted: Boolean = false
)

/**
 * Feature F: a free-form dated milestone (Anniversary, First Kiss, custom label, ...). [id] doubles as
 * the sync identity (same pattern as DateIdea.id) - a client-generated UUID string, union+tombstone
 * merged by (id, updatedAt) rather than last-write-wins-replace-everything, matching DateIdeaRepository.
 * Stored as separate month/day/year fields (rather than a single epoch date) because the yearly
 * reminder and "throughout the years" retrospective both key off "this month+day, any year" - keeping
 * that query trivial (no per-row date-math needed) was worth the extra columns.
 */
@Entity(tableName = "milestones")
data class Milestone(
    @PrimaryKey val id: String,
    val label: String,
    val month: Int,
    val day: Int,
    /** The actual year it happened, if known/entered (e.g. "First Date" in 2024) - purely informational,
     * shown as "since 2024" style copy; null means "recurring day, year not tracked". Never used to
     * decide whether the yearly notification fires (that's month/day only, every year). */
    val year: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false
)
