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
    val syncId: String = UUID.randomUUID().toString(),
    val updatedAt: Long = 0L,
    /** Tombstone, mirroring DateIdea/MomentNote's pattern - only ever set for [isManual] rows, so a
     * backfilled entry entered wrong can be deleted and have that deletion propagate to the partner's
     * phone on the next sync. A genuine BLE-detected session must never have this set by any code path -
     * see SessionRepository.softDeleteManual and mergeRemoteSessions for the enforcement. */
    val deleted: Boolean = false
)

/** Fixed (not randomly generated) so every device's independent DB migration creates a list with the
 * SAME id - if each phone generated its own random UUID here, the two partners' phones would end up
 * with two different "Date Ideas" lists that could never merge into one via sync (which merges by
 * stable id), producing a visible duplicate the first time they synced after this update. */
const val DEFAULT_LIST_ID = "a0000000-0000-4000-8000-000000000001"

@Entity(tableName = "date_ideas")
data class DateIdea(
    @PrimaryKey val id: String,
    val text: String,
    /** The owning [ListCategory.id] this idea belongs to - lets "Our Lists" scope ideas per-list instead
     * of one flat global checklist. Defaults to [DEFAULT_LIST_ID] (the migration-seeded "Date Ideas"
     * list) so any code path that constructs a DateIdea without explicitly picking a list still lands
     * somewhere real rather than referencing a nonexistent ListCategory row. */
    val listId: String = DEFAULT_LIST_ID,
    val done: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** Item 24 (UX-FIX-PLAN.md): per-item "remind me X minutes after we're together" reminder, in
     * addition to (and independent from) [ListCategory.defaultRemindAfterTogetherMinutes]'s per-LIST
     * default - see ProximityForegroundService.checkListReminders' doc for how the two combine. Null
     * means "no reminder set on this specific idea"; a per-item value here takes precedence over the
     * owning list's default when both happen to be set. Deliberately LOCAL-ONLY for now: it does not
     * currently travel over the GATT wire protocol (GattSyncManager.serializeDateIdeas/
     * deserializeDateIdeas were not touched - see this feature's own implementation notes) - a reminder
     * set on one phone stays on that phone until wire-protocol support is added. It DOES round-trip
     * through BackupManager's JSON backup/restore (see buildManifest/parseDateIdeas), and
     * DateIdeaRepository.mergeRemote is careful to preserve this device's own local value across an
     * otherwise-legitimate sync merge of some OTHER field, rather than let the wire's always-null value
     * silently clobber it. */
    val remindAfterTogetherMinutes: Int? = null
)

/**
 * "Our Lists": a named, couple-shared checklist (e.g. "Date Ideas", "Movie Watchlist") that owns zero or
 * more [DateIdea] rows via [DateIdea.listId]. Built exactly like [Milestone] on purpose - same
 * @PrimaryKey val id: String sync identity, same LWW-tombstone-by-(id, updatedAt) merge shape (see
 * ListCategoryRepository.mergeRemote) - these are simple, rarely-edited rows with no need for Milestone's
 * more elaborate union-merge cousins (SessionRepository/MomentRepository). One row of this table (id ==
 * [DEFAULT_LIST_ID]) is special: it's created by AppDatabase.MIGRATION_7_8 on every existing install so
 * every pre-existing DateIdea (previously one flat global list) lands somewhere real after the upgrade.
 */
@Entity(tableName = "list_categories")
data class ListCategory(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** Item 24 (UX-FIX-PLAN.md): per-LIST default "remind me X minutes after we're together" reminder -
     * e.g. "remind me about our bucket list every time we've been together 2+ hours". Fires ONE
     * notification about the list as a whole (see Notifications.showListReminder), independent of any
     * per-item [DateIdea.remindAfterTogetherMinutes] reminders also firing for ideas inside it. Null
     * means "no default reminder for this list". Same local-only-for-now caveat as
     * DateIdea.remindAfterTogetherMinutes - see its own doc. */
    val defaultRemindAfterTogetherMinutes: Int? = null
)

@Entity(tableName = "time_capsules")
data class TimeCapsule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val unlockAtHours: Float,
    val createdAt: Long,
    /** SECURITY: deliberately never trusted from a peer's payload during sync merge, even when the
     * remote row otherwise wins the LWW comparison on [updatedAt] - see
     * TimeCapsuleRepository.mergeRemote's doc for the full reasoning. Only this device's own
     * TimeCapsuleRepository.unlockEligible, computed from its own already-validated session data, may
     * ever set this field to non-null. */
    val unlockedAt: Long? = null,
    /** Anti-cheat snapshot: how much of the couple's total hours were already attributable to manual
     * (hand-entered) backfill at the moment this capsule was created - see
     * TimeCapsuleRepository.unlockEligible's doc for the full reasoning. Everything from here on is
     * measured relative to this frozen value, so adding or deleting manual backfill after creation
     * always moves this capsule's effective threshold by the exact same amount as the couple's total
     * hours moves - the two changes cancel out, so backfill activity (past or future) can never change
     * how many genuine BLE-detected hours are actually required to unlock. Defaults to 0 for capsules
     * that predate this field (AppDatabase.MIGRATION_6_7 backfills a real value for those instead). */
    val manualHoursAtCreation: Float = 0f,
    /** Feature: Time Capsule sync (AppDatabase.MIGRATION_8_9). Stable cross-device identity, same
     * reasoning as TogetherSession.syncId/Moment.syncId - the local auto-increment [id] is NOT safe to
     * compare across two independent devices. Defaults to a fresh UUID for any newly-constructed row;
     * pre-migration rows are backfilled a real one by MIGRATION_8_9 itself. */
    val syncId: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false
)

@Entity(tableName = "moments")
data class Moment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoUri: String,
    val takenAt: Long,
    val sessionId: Long? = null,
    /**
     * MAJOR fix (independent review, live-reproduced on two devices): whether this photo was taken while
     * the couple were actually together. Previously the Moments viewer derived that caption from
     * `sessionId != null` directly - but [MomentRepository.mergeRemoteStubs] deliberately (and correctly)
     * NULLS `sessionId` on every incoming remote row, because it is the OTHER device's local
     * auto-increment id and is meaningless (and potentially coincidentally-colliding) here. The result was
     * that the very same photo read "Taken while together 💕" on the phone that shot it and "Taken apart"
     * on the partner's phone, permanently, for 100% of synced photos - two phones stating contradictory
     * facts about one shared memory.
     *
     * This is the sync-safe replacement: a plain boolean that means the same thing on both devices and
     * therefore CAN travel over the wire (unlike [sessionId]). Set once at capture time from this
     * device's own live proximity state (see MomentRepository.add) and never mutated afterward.
     * AppDatabase.MIGRATION_10_11 backfills it for existing rows from their own `sessionId IS NOT NULL`,
     * which is exactly what the old UI check computed, so no already-correct local caption ever changes.
     *
     * TRUST NOTE: for a remote-stub row this is taken from the peer's payload verbatim. That is a
     * deliberate, bounded exception to this app's "never trust a peer's claim" rule and is safe
     * specifically because this field is display-only: it feeds one caption string and nothing else - no
     * stats, no capsule threshold, no filesystem path, no merge/LWW decision. A lying peer can at worst
     * mislabel its own photo, which it could equally do by simply taking the photo while together.
     */
    val takenWhileTogether: Boolean = sessionId != null,
    /** Feature D: stable cross-device identity, same reasoning as TogetherSession.syncId above - lets
     * MomentNote reference a specific moment by an id that means the same thing on both phones. */
    val syncId: String = UUID.randomUUID().toString(),
    /**
     * True when this Moment row arrived via GATT sync from the partner's phone rather than being taken
     * on this device. ORIGINAL SCOPE LIMITATION, now superseded by Feature 2 (see [photoDownloaded]'s own
     * doc, right below): at first, only the moment's metadata (syncId/takenAt/existence) was synced,
     * never the actual photo bytes - a downscaled copy (see ImageDownscaler) IS now transferred over this
     * text-chunk BLE protocol via a dedicated photo-transfer phase once both phones are together, into a
     * deterministic syncId-derived LOCAL path (see MomentRepository.localPhotoFile) that DOES resolve to
     * a real file here once that transfer completes - [photoUri] is never the original device's own path.
     * [isRemote] itself still only ever means "did the metadata arrive via sync" (see [photoDownloaded]
     * for "do the actual bytes exist locally yet") - a remote-stub row still lets the partner see that
     * the moment exists (grouped correctly by date, included in the milestone retrospective) and write
     * their own note on it (Feature D's core ask) even before the transfer completes; the UI checks
     * [photoDownloaded] (not this flag) before rendering it as an image, showing a clear "photo is on
     * {partner}'s phone" placeholder in the meantime.
     */
    val isRemote: Boolean = false,
    /**
     * Feature 2 (photo sync): split from [isRemote] on purpose - [isRemote] answers "whose photo is
     * this / did the metadata arrive via sync", [photoDownloaded] answers the separate question "does
     * THIS device actually hold the real photo bytes on disk right now". A moment taken locally is
     * trivially true from the moment it's inserted (see MomentRepository.add - isRemote=false there, so
     * the default `!isRemote` below resolves to true). A remote-stub row created by mergeRemoteStubs is
     * always isRemote=true, so this defaults to false - exactly the "no pixels yet" state the old
     * isRemote-only model conflated - and GattSyncManager's photo-transfer phase flips it to true once
     * the actual bytes have been written to [photoUri] and fsync'd into place (see
     * MomentRepository.markPhotoDownloaded). The UI (MomentsScreen) renders off THIS flag now, not
     * isRemote, so a remote-stub moment correctly starts showing the real image the moment its transfer
     * completes without needing isRemote itself to ever change.
     */
    val photoDownloaded: Boolean = !isRemote,
    val updatedAt: Long = 0L,
    /** Tombstone, mirroring DateIdea/MomentNote/TogetherSession's pattern - so a deleted photo can
     * propagate that deletion to the partner's phone on the next sync. UNLIKE TogetherSession.deleted,
     * there is no isManual-equivalent restriction here: ANY moment, whether captured locally or arrived
     * as a remote stub ([isRemote]), can be soft-deleted by either partner - every moment was deliberately
     * created by a person on one of the two phones, not an automatically-collected "untouchable
     * historical record" the way a BLE-detected session is. See MomentRepository.softDelete and
     * mergeRemoteStubs for where this is set (with no equivalent gate to SessionRepository's isManual
     * check - that's intentional, not an oversight). */
    val deleted: Boolean = false
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
 * UX-FIX-PLAN.md Phase 4 item 25: a free-text note attached to a specific calendar day (not tied to any
 * Moment/photo) - lets the couple jot something about a day even without a photo. Structurally mirrors
 * [MomentNote] as closely as possible (see that entity's own doc for the full sync-contract reasoning
 * this one repeats): composite-keyed by (date, authorDeviceId) rather than a synthetic id - there is at
 * most one note per author per day (writing again just replaces it), which maps naturally onto a REPLACE
 * upsert, and lets both partners independently have their own note on the same day with no merge
 * conflict (two different rows entirely) - no separate syncId is needed, same reasoning MomentNote's own
 * composite key already establishes.
 *
 * [date] is an epoch-day Long (`LocalDate.toEpochDay()`) - the same cross-boundary "day" representation
 * CalendarScreen.kt already uses everywhere it needs to pass a day as a plain value (`jumpToEpochDay`,
 * `onOpenMoments`), matching that existing convention rather than inventing a new date representation
 * (an ISO string, a separate year/month/day column set like Milestone's) for this one feature.
 *
 * Each device only ever writes rows where authorDeviceId == SettingsStore.getOrCreateLocalDeviceId();
 * rows authored by the OTHER id are meant to arrive purely via GattSyncManager's merge and be read-only
 * in the UI - see DayNoteRepository's doc for the full sync contract.
 *
 * NOTE (deliberate scope limit): unlike MomentNote, [DayNote] does NOT yet travel over
 * GattSyncManager's wire protocol - GattSyncManager.kt is intentionally untouched by this feature (see
 * DayNoteRepository.mergeRemote's own doc). It DOES round-trip through BackupManager's JSON backup/
 * restore, same as every other synced-shaped entity in this file.
 */
@Entity(tableName = "day_notes", primaryKeys = ["date", "authorDeviceId"])
data class DayNote(
    val date: Long,
    val authorDeviceId: String,
    val text: String,
    val updatedAt: Long,
    /** Tombstone, mirroring MomentNote's pattern: saving blank text sets this instead of deleting the row
     * outright, so the clearing itself is something that can propagate to the partner once wire-protocol
     * support for this entity is added. */
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
    val deleted: Boolean = false,
    /** Optional link to a Moment's photo (Moment.syncId) so this milestone can show a specific chosen
     * memory instead of (or alongside) MilestoneRetrospective's auto-matched "same day, any year" gallery
     * - see MilestonesScreen's AddMilestoneDialog for where this gets set. Deliberately just a syncId
     * reference, not a copy of the photo itself: rides the exact same photo-transfer/local-file machinery
     * Moments already have (GattSyncManager's Feature 2 phase, Moment.photoDownloaded/localPhotoFile) -
     * resolving this to an actual displayable image always means looking up the referenced Moment, same
     * as MilestoneRetrospective already does today, just by a specific syncId instead of a date match. Null
     * means "no photo chosen"; a value that no longer resolves to any local Moment (partner deleted it, or
     * we haven't received it yet) is treated as "no photo" too - see MilestonesScreen/HomeScreen's own
     * resolution logic. */
    val linkedMomentSyncId: String? = null
)
