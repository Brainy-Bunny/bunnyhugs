package com.ssbmedia.twogether.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.ssbmedia.twogether.data.db.AppDatabase
import com.ssbmedia.twogether.data.db.DEFAULT_LIST_ID
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
import com.ssbmedia.twogether.data.db.TimeCapsule
import com.ssbmedia.twogether.data.db.TimeCapsuleDao
import com.ssbmedia.twogether.data.db.TogetherSession
import com.ssbmedia.twogether.data.db.TogetherSessionDao
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

class SessionRepository(private val dao: TogetherSessionDao) {
    /** Excludes soft-deleted (tombstoned) rows - backed by [TogetherSessionDao.observeActive]. Every
     * existing caller (HomeScreen, StatsScreen, CalendarScreen, BadgesScreen, CapsulesScreen,
     * ProximityForegroundService, GattSyncManager) genuinely wants "sessions the user actually sees", so
     * this filters transparently for all of them with no call-site changes needed. The one place that
     * needs tombstones too (the sync payload builder) uses [getAllIncludingDeleted] instead. */
    fun observeAll(): Flow<List<TogetherSession>> = dao.observeActive()
    fun observeOpenSession(): Flow<TogetherSession?> = dao.observeOpenSession()
    suspend fun getAll(): List<TogetherSession> = dao.getActive()
    suspend fun getOpenSession(): TogetherSession? = dao.getOpenSession()

    /** The raw/complete table, tombstones included - only for GattSyncManager.buildPayload, which must
     * send deleted manual sessions too so the deletion itself propagates to the partner's phone. */
    suspend fun getAllIncludingDeleted(): List<TogetherSession> = dao.getAll()

    suspend fun startSession(startedAt: Long): Long =
        dao.insert(TogetherSession(startedAt = startedAt, updatedAt = System.currentTimeMillis()))

    suspend fun endSession(session: TogetherSession, endedAt: Long) {
        dao.update(session.copy(endedAt = endedAt, updatedAt = System.currentTimeMillis()))
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
        return dao.insert(
            TogetherSession(
                startedAt = startedAt,
                endedAt = endedAt,
                isManual = true,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Soft-deletes a manually-backfilled session that was entered wrong - a no-op for a genuine
     * BLE-detected session, which must remain an untouchable historical record; only ever reachable from
     * the Calendar screen's delete affordance, which itself is only ever shown for isManual sessions, but
     * this guard is the real enforcement point regardless of what any future UI code does. The row stays in
     * the table (marked deleted) rather than being hard-deleted, purely so the deletion itself can
     * propagate to the partner's phone on the next sync - see mergeRemoteSessions' tombstone-apply branch. */
    suspend fun softDeleteManual(session: TogetherSession) {
        if (!session.isManual) return
        dao.update(session.copy(deleted = true, updatedAt = System.currentTimeMillis()))
    }

    /** UX-FIX-PLAN.md Phase 3 item 19: edits an already-existing manually-backfilled session's start/end
     * (e.g. the date/time was entered wrong, or the native pickers used to create it made a rounding
     * mistake) - same isManual-only guard as [softDeleteManual], since a genuine BLE-detected session
     * must remain an untouchable historical record no local UI action can ever alter. No-ops (silently
     * drops the request rather than writing anything) for a non-manual session or a degenerate/invalid
     * range, mirroring [addManualSession]'s own defensive validation - CalendarScreen's own dialog
     * already validates this up front, but this is the last line of defense against a bad value slipping
     * through into an uncaught exception inside viewModelScope.launch. */
    suspend fun updateManualSession(session: TogetherSession, startedAt: Long, endedAt: Long) {
        if (!session.isManual || endedAt <= startedAt) return
        dao.update(session.copy(startedAt = startedAt, endedAt = endedAt, updatedAt = System.currentTimeMillis()))
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
     *
     * UX-FIX-PLAN.md Phase 3 item 19: a manual session is NOT immutable after creation anymore - two
     * exceptions to "never overwritten" now exist for an already-known syncId, both gated the exact same
     * defensive way: `local.isManual`, never `r.isManual` - we trust ONLY our own local record of
     * whether a session is manual, never the remote's claim about it, so a BLE-detected session's
     * deleted/startedAt/endedAt can never be altered by any incoming payload, even a buggy or malicious
     * one that lies about isManual for a syncId we already know locally as a real BLE detection.
     *  1. TOMBSTONE: if the remote row carries a tombstone (r.deleted) and our local copy isn't already
     *     tombstoned, we apply it - this is how a manual-entry deletion propagates to the partner's phone.
     *  2. EDIT (new): if neither side is deleted and the remote row's `startedAt`/`endedAt` genuinely
     *     differ from ours with a newer `updatedAt`, we adopt the remote's bounds - this is how
     *     [SessionRepository.updateManualSession] on the OTHER phone propagates here. Plain LWW by
     *     `updatedAt`, matching every other per-row edit merge in this file (e.g.
     *     DateIdeaRepository.mergeRemote); no extra bounds re-validation is needed here specifically
     *     because [r]'s startedAt/endedAt/updatedAt already passed GattSyncManager.deserializeSessions'
     *     own SessionBoundsValidator/clock-skew checks before ever reaching this function - a syncId's
     *     row only gets here once it's already known-plausible.
     * A closed session's `isManual`/`syncId` are still set once at creation and never mutated again (see
     * [addManualSession]) - deleted and startedAt/endedAt are the only two things an already-known manual
     * syncId can ever have changed here, and only ever moving the direction the remote's own newer
     * `updatedAt` justifies.
     */
    suspend fun mergeRemoteSessions(remote: List<TogetherSession>) {
        val localBySyncId = dao.getAll().filter { it.syncId.isNotBlank() }.associateBy { it.syncId }
        remote.filter { it.endedAt != null && it.syncId.isNotBlank() }.forEach { r ->
            val rEndedAt = r.endedAt!! // guaranteed non-null by the filter above
            val local = localBySyncId[r.syncId]
            if (local == null) {
                dao.insert(r.copy(id = 0))
            } else if (local.isManual) {
                if (r.deleted && !local.deleted) {
                    dao.update(local.copy(deleted = true, updatedAt = maxOf(local.updatedAt, r.updatedAt)))
                } else if (!r.deleted && !local.deleted && r.updatedAt > local.updatedAt &&
                    (r.startedAt != local.startedAt || rEndedAt != local.endedAt)
                ) {
                    dao.update(local.copy(startedAt = r.startedAt, endedAt = rEndedAt, updatedAt = r.updatedAt))
                }
            }
        }
    }

    /** UX (user-requested follow-up): each phone's live OPEN session is deliberately never sent to the
     * partner as a full row (see mergeRemoteSessions' own doc for why - it would let two devices each
     * own a different "currently open" copy). But the two phones independently timestamping "together
     * since" from their own BLE detection can legitimately disagree by anywhere from a few seconds
     * (normal discovery jitter) to a few minutes (e.g. right after an unpair/reconnect cycle, where
     * each phone rediscovers the other at its own pace) - live-observed by the user as two visibly
     * different "Together for Xm" numbers that read as a bug even though nothing was wrong. This adopts
     * the EARLIER of the two candidates as canonical on both sides: whoever noticed first is the more
     * accurate "together since" anyway (the other phone's later detection was just slower to notice a
     * moment that had already started). Deliberately a plain min() - clamping is one-directional
     * (never moves LATER), so once both sides agree they stay agreed regardless of which one calls
     * this next, and there's no oscillation. [candidateStartedAt] is clamped to no earlier than
     * [MAX_ADOPT_EARLIER_WINDOW_MILLIS] before this device's own value, so a wildly implausible or
     * spoofed remote value (e.g. from a device that hasn't been pinned yet) can't make this device
     * claim an absurd multi-year "together" duration - genuine discovery drift is minutes, not years.
     * A no-op if this device has no open session of its own right now: this device's OWN local
     * proximity detection is still the sole authority on whether IT considers itself "together" -
     * receiving a peer's open-session hint is never enough to fabricate a local session that doesn't
     * already exist. */
    suspend fun adoptEarlierOpenSessionStart(candidateStartedAt: Long) {
        val open = dao.getOpenSession() ?: return
        val earliestPlausible = open.startedAt - MAX_ADOPT_EARLIER_WINDOW_MILLIS
        val clamped = candidateStartedAt.coerceAtLeast(earliestPlausible)
        if (clamped < open.startedAt) {
            dao.update(open.copy(startedAt = clamped, updatedAt = System.currentTimeMillis()))
        }
    }

    companion object {
        private const val MAX_ADOPT_EARLIER_WINDOW_MILLIS = 24 * 60 * 60 * 1000L
    }
}

class DateIdeaRepository(private val dao: DateIdeaDao) {
    companion object {
        /** Item 24 (UX-FIX-PLAN.md): guardrail against a typo or a corrupted/hand-edited backup value
         * producing a reminder that fires immediately (0 or negative) or effectively never (an absurdly
         * large number of minutes) - mirrors TimeCapsuleRepository's MAX_UNLOCK_AT_HOURS/
         * isPlausibleUnlockAtHours pattern. Shared by the Settings/Our Lists UI dialogs AND
         * BackupManager's untrusted-ingestion parser so both enforce the exact same bound and can't drift
         * apart. 1440 minutes = 24 hours - generously above any reasonable "remind me X minutes after
         * we're together" use case, while still bounded. */
        const val MAX_REMIND_AFTER_TOGETHER_MINUTES = 1440

        fun isPlausibleReminderMinutes(minutes: Int?): Boolean =
            minutes == null || minutes in 1..MAX_REMIND_AFTER_TOGETHER_MINUTES
    }

    fun observeActive(): Flow<List<DateIdea>> = dao.observeActive()
    suspend fun getAll(): List<DateIdea> = dao.getAll()

    suspend fun add(text: String, listId: String) {
        dao.upsert(
            DateIdea(
                id = UUID.randomUUID().toString(),
                text = text,
                listId = listId,
                done = false,
                updatedAt = System.currentTimeMillis(),
                deleted = false
            )
        )
    }

    suspend fun setDone(idea: DateIdea, done: Boolean) {
        dao.upsert(idea.copy(done = done, updatedAt = System.currentTimeMillis()))
    }

    /** UX-FIX-PLAN.md Phase 3 item 19: renames an idea's text - previously an idea could be added/
     * checked-off/deleted but never renamed. A plain content edit, same whole-row upsert shape as
     * [setDone]/[softDelete] - DateIdeaRepository.mergeRemote's existing LWW-by-(id, updatedAt) merge
     * already handles an arbitrary field change correctly (it was built generically, not photo/done-only),
     * so no merge-layer change is needed for this to sync. No-ops on blank text rather than letting an
     * idea's label be cleared out from under it. */
    suspend fun rename(idea: DateIdea, text: String) {
        if (text.isBlank()) return
        dao.upsert(idea.copy(text = text.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(idea: DateIdea) {
        dao.upsert(idea.copy(deleted = true, updatedAt = System.currentTimeMillis()))
    }

    /** Item 24 (UX-FIX-PLAN.md): sets (or clears, via null) this idea's own "remind me X minutes after
     * we're together" reminder - see DateIdea.remindAfterTogetherMinutes' own doc for why this is
     * currently local-only (not yet wired through GattSyncManager's wire protocol). */
    suspend fun setReminder(idea: DateIdea, minutes: Int?) {
        dao.upsert(idea.copy(remindAfterTogetherMinutes = minutes, updatedAt = System.currentTimeMillis()))
    }

    /** Last-write-wins merge of a remote list of ideas into local storage, by id + updatedAt.
     *
     * MAJOR fix (independent review), applied identically to all four LWW merges in this file: an
     * incoming row that is CONTENT-identical to the one we already hold is skipped instead of being
     * re-written just because its `updatedAt` is a few hundred milliseconds newer. See
     * GattSyncManager.boundPeerClockOffset's doc for the live-measured ratchet that made every sync
     * re-write every row of every LWW table forever; that fix removes the cause, and this one removes the
     * whole failure MODE - no residual clock difference, from any source, can produce a write when
     * nothing a user would recognise as data has actually changed. Deliberately compares only the
     * meaningful columns (never `updatedAt` itself, which is exactly the field that drifts, and never
     * remindAfterTogetherMinutes - see the merge note right below for why that field is excluded from
     * both the wire protocol and this comparison).
     *
     * Item 24 (UX-FIX-PLAN.md): [DateIdea.remindAfterTogetherMinutes] is deliberately NOT part of the
     * wire protocol yet (GattSyncManager.deserializeDateIdeas never sets it, so every incoming remote
     * row's value is always the Kotlin default of null) - upserting an already-known row's remote copy
     * verbatim would silently wipe out this device's own locally-set reminder the next time the
     * partner's copy of this SAME idea legitimately wins the LWW comparison on some other field (text/
     * listId/done/deleted). Carrying the local value forward on every already-known row closes that,
     * without needing the reminder field itself to be part of the sync payload at all. A genuinely new
     * remote row (not known locally yet) has no local value to preserve, so it inserts with null - the
     * same "no reminder set" state a locally-created idea starts with anyway. */
    suspend fun mergeRemote(remote: List<DateIdea>) {
        val local = dao.getAll().associateBy { it.id }
        val toUpsert = remote.mapNotNull { r ->
            val l = local[r.id] ?: return@mapNotNull r
            if (r.updatedAt <= l.updatedAt || r.sameContentAs(l)) return@mapNotNull null
            r.copy(remindAfterTogetherMinutes = l.remindAfterTogetherMinutes)
        }
        if (toUpsert.isNotEmpty()) dao.upsertAll(toUpsert)
    }

    private fun DateIdea.sameContentAs(other: DateIdea): Boolean =
        text == other.text && listId == other.listId && done == other.done && deleted == other.deleted

    /**
     * Self-heals orphaned ideas - a real, if narrow, race that isn't specific to any one merge: while two
     * devices are apart, one deletes a whole list (cascading a soft-delete to every idea IT can currently
     * see in that list - see ListCategoryRepository.delete), while the OTHER independently adds a brand
     * new idea to that same list. Neither device did anything wrong in isolation, but once they sync, the
     * list itself correctly ends up deleted (a real LWW-tombstone decision) while the new idea - which the
     * deleting device never knew existed, so could never have cascaded to - still points at a listId that
     * no longer resolves to anything active. Since the UI only ever renders ideas nested under an active
     * list card, an idea like that becomes invisible forever with nothing telling anyone it happened.
     *
     * Reassigns any currently-active idea whose listId isn't in [validListIds] back to DEFAULT_LIST_ID,
     * bumping its updatedAt so the repair itself propagates to the partner's phone via the same
     * tombstone-sync mechanism on the next connection - both devices converge on the same "rescued into
     * Date Ideas" outcome, not just whichever one happened to run this first.
     *
     * Low-level primitive - always call it via ListCategoryRepository.reassignOrphanIdeas() rather than
     * computing [validListIds] yourself, unless you have a specific reason not to (that function's own doc
     * explains why a caller-supplied set, especially one derived from independently-updating Flows, is
     * NOT safe here).
     */
    suspend fun reassignOrphans(validListIds: Set<String>) {
        val orphans = dao.getAll().filter { !it.deleted && it.listId !in validListIds }
        if (orphans.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.upsertAll(orphans.map { it.copy(listId = DEFAULT_LIST_ID, updatedAt = now) })
    }
}

class TimeCapsuleRepository(private val dao: TimeCapsuleDao) {
    companion object {
        /** MINOR fix (ultimate-app-review round 2/Step 4, Opus+Sonnet+Fable all independently proposed
         * this): the "typo guardrail" bound on a capsule's unlock-hours threshold used to be a bare
         * `5000f` literal duplicated in three places (here as CapsulesScreen's own local
         * `MAX_CAPSULE_UNLOCK_HOURS`, plus GattSyncManager.deserializeTimeCapsules and
         * BackupManager.parseTimeCapsules) - free to silently drift apart. One shared constant, in the
         * data layer so both the UI and the untrusted-ingestion paths can reference it without a
         * UI-to-data-layer dependency running backward. */
        const val MAX_UNLOCK_AT_HOURS = 5000f

        /** MINOR fix, same round: the bound on the wire/backup-untrusted `manualHoursAtCreation` field
         * (see [effectiveThreshold]'s doc for why this field matters) was duplicated identically in
         * GattSyncManager.deserializeTimeCapsules and BackupManager.parseTimeCapsules. One shared
         * constant, same reasoning as [MAX_UNLOCK_AT_HOURS] above. */
        const val MAX_MANUAL_HOURS_AT_CREATION = 100_000f

        /** MAJOR fix (ultimate-app-review round 3, both Opus and Sonnet independently live-reproduced):
         * this formula used to be duplicated verbatim - once here (fixed in round 2, commit `16b1468`)
         * and once, unclamped, in CapsulesScreen's own display code. The two agreed before round 2's fix
         * and silently diverged after it, since the fix only touched one copy - CapsulesScreen kept
         * showing a nonsensical/alarming negative "hours to go" (or even a still-Locked capsule reading
         * "0 to go") for a forged `manualHoursAtCreation`, and a real honest-use case (deleting a manual
         * backfill entry after creating a capsule) rendered "Lowered by 20h..." text describing behavior
         * that no longer exists post-clamp. Extracted to ONE shared function both [unlockEligible] and
         * CapsulesScreen now call, so they structurally cannot re-diverge - see [unlockEligible]'s own doc
         * for the full anti-cheat derivation and why the `maxOf(0f, ...)` clamp is there. */
        fun effectiveThreshold(capsule: TimeCapsule, currentManualHoursCredit: Float): Float =
            capsule.unlockAtHours + maxOf(0f, currentManualHoursCredit - capsule.manualHoursAtCreation)

        /** MINOR fix (code-review): the "typo guardrail" bound check itself (not just the two constants
         * above it) used to be duplicated verbatim as inline predicate logic in both
         * GattSyncManager.deserializeTimeCapsules and BackupManager.parseTimeCapsules - a future change to
         * either predicate applied to one copy and forgotten in the other would silently reintroduce the
         * exact drift the shared constants were meant to prevent, one level too shallow. Both untrusted
         * ingestion paths now call these two functions instead of hand-writing the check. */
        fun isPlausibleUnlockAtHours(hours: Float): Boolean =
            hours.isFinite() && hours > 0f && hours <= MAX_UNLOCK_AT_HOURS

        fun isPlausibleManualHoursAtCreation(hours: Float): Boolean =
            hours.isFinite() && hours >= 0f && hours <= MAX_MANUAL_HOURS_AT_CREATION
    }

    fun observeAll(): Flow<List<TimeCapsule>> = dao.observeActive()

    /** Raw/unfiltered - includes soft-deleted (tombstoned) rows. Used by BackupManager and by
     * GattSyncManager's payload builder, which must send deleted capsules too so the deletion itself
     * propagates to the partner's phone - same pattern as every other synced entity. */
    suspend fun getAllIncludingDeleted(): List<TimeCapsule> = dao.getAll()

    /** [manualHoursAtCreation] is the couple's CURRENT manual-hours credit (StatsCalculator.
     * manualHoursCredit) at the moment this capsule is created - see TimeCapsule's own doc for why this
     * snapshot is what lets the anti-cheat math in [unlockEligible] work.
     *
     * MINOR fix (code-review): this is the ONE local writer of unlockAtHours/manualHoursAtCreation - the
     * wire path, the backup path, and effectiveThreshold's own clamp all treat these fields as
     * security-critical and validate/bound them, but this origin point had zero validation of its own.
     * Not exploitable today (the only caller, CapsulesViewModel.add, is fed UI-validated input via
     * AddCapsuleDialog's own bound check), but leaving the one local writer unguarded was inconsistent
     * with this whole feature's own stated design goal of enforcing these bounds structurally rather than
     * by convention. No-ops (silently drops the request) on an invalid value, same "reject, don't clamp"
     * shape every untrusted ingestion path already uses. */
    suspend fun add(text: String, unlockAtHours: Float, manualHoursAtCreation: Float) {
        if (!isPlausibleUnlockAtHours(unlockAtHours) || !isPlausibleManualHoursAtCreation(manualHoursAtCreation)) return
        val now = System.currentTimeMillis()
        dao.insert(
            TimeCapsule(
                text = text,
                unlockAtHours = unlockAtHours,
                createdAt = now,
                manualHoursAtCreation = manualHoursAtCreation,
                updatedAt = now
            )
        )
    }

    /** Feature: Time Capsule sync. Soft-delete (tombstone) - same pattern as every other synced entity's
     * delete, so removing a capsule you created by mistake propagates to your partner's phone too.
     *
     * MINOR fix (code-review, Sonnet): used to be a stale-read-shaped `dao.update(capsule.copy(...))`
     * that could clobber a concurrent unlock - see [TimeCapsuleDao.tombstone]'s own doc. */
    suspend fun delete(capsule: TimeCapsule) {
        dao.tombstone(capsule.id, System.currentTimeMillis())
    }

    /**
     * Unlocks any capsule whose EFFECTIVE threshold has been crossed by [totalHours] (the couple's real
     * total together-hours, manual backfill included exactly like every other stat in this app).
     * [currentManualHoursCredit] is the couple's CURRENT manual-hours credit (StatsCalculator.
     * manualHoursCredit, computed the same way for both call sites of this function).
     *
     * ANTI-CHEAT: each capsule's effective threshold is `unlockAtHours + (currentManualHoursCredit -
     * manualHoursAtCreation)` - it grows or shrinks by exactly however much manual-hours credit has
     * changed since the capsule was created. Substituting `totalHours = realHours + currentManualHoursCredit`
     * into the unlock condition `totalHours >= effectiveThreshold` and simplifying, currentManualHoursCredit
     * cancels out completely: the condition reduces to `realHours >= unlockAtHours - manualHoursAtCreation`,
     * a fixed bar set once at creation time that NO subsequent manual backfill activity - adding it,
     * deleting it, at any point before or after - can ever move. This replaces an earlier version of this
     * anti-cheat that simply excluded manual hours from counting at all; that worked too, but silently
     * changed what "hours to go" meant compared to every other screen in the app (which always shows
     * TRUE total hours), which read as a confusing regression to anyone who'd used manual backfill
     * before. This version keeps totalHours as the one true number shown everywhere, and instead moves
     * the goalpost by the same amount as the backfill - transparent, and provably ungameable either way.
     *
     * BLOCKER fix (ultimate-app-review round 2, Opus live-reproduced against round 1's own fix): the
     * `(currentManualHoursCredit - manualHoursAtCreation)` delta is now clamped to never go BELOW zero.
     * Bounding manualHoursAtCreation on ingestion (GattSyncManager.deserializeTimeCapsules,
     * BackupManager.parseTimeCapsules) closed the exploit for the specific magnitudes round 1 happened to
     * test, but any forged value still above `unlockAtHours + currentManualHoursCredit` (up to the
     * ingestion bound) drove this delta deeply negative and the capsule open regardless of real
     * togetherness - a peer/backup could still force an instant false unlock of a capsule it introduced,
     * just needing a slightly less extreme number than round 1's live repro used. Clamping the delta at 0
     * means `effectiveThreshold` can now NEVER fall below the capsule's own stated `unlockAtHours` (itself
     * already bounded to (0, 5000] on every untrusted path) - closing this structurally, independent of
     * whatever bound ingestion happens to enforce, rather than by picking a tighter magic number that the
     * next review round would just need to re-litigate. In the normal (non-adversarial) case where manual
     * credit only grows after a capsule's creation, `currentManualHoursCredit >= manualHoursAtCreation`
     * already, so the clamp is a no-op and the derivation above (currentManualHoursCredit cancels out
     * completely) holds exactly as documented. Side effect, also a genuine fix: previously, deleting a
     * manual session after a capsule's creation could shrink currentManualHoursCredit below
     * manualHoursAtCreation, silently pulling effectiveThreshold BELOW the capsule's own stated
     * unlockAtHours - an honest-case bug (unlock arriving early) with the same root cause, closed by the
     * same clamp.
     */
    suspend fun unlockEligible(totalHours: Float, currentManualHoursCredit: Float) {
        val locked = dao.getLocked()
        val now = System.currentTimeMillis()
        locked.forEach { capsule ->
            if (effectiveThreshold(capsule, currentManualHoursCredit) <= totalHours) {
                // updatedAt bumped too (Feature: Time Capsule sync) - otherwise a local-only unlock would
                // never itself be a reason to re-send this capsule's row, though in practice the initial
                // add() sync (or any later local edit) already covers propagating the definition; bumping
                // here is just consistent with "every real local write touches updatedAt."
                //
                // MINOR fix (test-code-allmodels, Fable): was a plain dao.update(capsule.copy(...)) - a
                // stale-read full-row overwrite. See unlockIfNotDeleted's own doc for the race this
                // closes.
                dao.unlockIfNotDeleted(capsule.id, unlockedAt = now, updatedAt = now)
            }
        }
    }

    /** Feature: Time Capsule sync. Union+STICKY-tombstone merge by [TimeCapsule.syncId], mirroring
     * SessionRepository.mergeRemoteSessions'/MomentRepository.mergeRemoteStubs' shape exactly (TimeCapsule
     * uses the same local-autoincrement-[id] + separate-syncId identity those do, unlike Milestone/
     * DateIdea's single-UUID-primary-key shape) - a genuinely new remote row inserts with `id = 0` (Room
     * autogenerates); an already-known syncId only ever has its tombstone applied one-way
     * (`deleted: false -> true`, never the reverse), exactly like those two siblings' own tombstone-apply
     * branch.
     *
     * BLOCKER fix (ultimate-app-review round 2, Opus+Sonnet both independently live-reproduced as S9):
     * this used to be a general LWW merge that also took `text`/`unlockAtHours`/`manualHoursAtCreation`
     * from the remote whenever `r.updatedAt > local.updatedAt` - but per [add]'s and [unlockEligible]'s
     * own docs, those fields are a fixed snapshot set ONCE at creation and never legitimately mutated
     * again by this app (there is no "edit capsule" feature). The only two post-creation mutations that
     * exist are [delete] (tombstone) and [unlockEligible] (which also bumps updatedAt, purely locally,
     * with no user action). Combined, the old LWW-take-everything shape let a background unlock on one
     * device's newer updatedAt beat and UN-DELETE a capsule the OTHER device had explicitly, intentionally
     * deleted moments earlier - live-reproduced by both reviewers in a completely ordinary two-partner
     * scenario, no attacker needed. Restricting an already-known row to tombstone-apply-only, like every
     * sibling synced entity in this file already does, closes that resurrection path structurally rather
     * than by convention - and as a side effect also closes the "resend an existing syncId with a forged
     * manualHoursAtCreation to move its already-set anti-cheat threshold" variant of S4, since no field
     * but `deleted`/`updatedAt` can ever change after a capsule's first insert.
     *
     * SECURITY: [TimeCapsule.unlockedAt] is NEVER taken from the remote side, on either branch - only this
     * device's OWN [unlockEligible], computed from its own already-validated session data, may ever set
     * it. Without this, a compromised or buggy peer could claim `unlockedAt` in its payload and falsely
     * reveal a capsule's contents on the receiving device without that device's own total hours having
     * actually crossed the threshold - the exact "never trust a peer's claim about high-stakes,
     * irreversible state" principle this whole review applied to every timestamp in the app. Both devices
     * still converge on the same real unlock moment naturally, since totalHours is derived from the SAME
     * (already-synced) session data on both sides - nothing is lost by keeping this one field strictly
     * local.
     */
    suspend fun mergeRemote(remote: List<TimeCapsule>): List<TimeCapsule> {
        val localBySyncId = dao.getAll().filter { it.syncId.isNotBlank() }.associateBy { it.syncId }
        val changed = mutableListOf<TimeCapsule>()
        // MINOR fix (code-review, independently found twice - Opus in test-code-allmodels round 1 and a
        // separate code-review pass): localBySyncId above is a snapshot taken ONCE before this loop, so
        // two remote rows sharing the same syncId in one malformed/buggy payload would BOTH see
        // local == null and BOTH get inserted, creating duplicate local rows for one syncId - a later
        // delete would only tombstone whichever copy dao.getAll() happens to return last, leaving the
        // other undeletable from the partner's side. Deduped within this one payload first (LWW by
        // updatedAt, same rule already applied against local state two lines down) so at most one row per
        // syncId is ever considered per call.
        val dedupedRemote = remote.filter { it.syncId.isNotBlank() }
            .groupBy { it.syncId }
            .mapValues { (_, rows) -> rows.maxBy { it.updatedAt } }
            .values
        dedupedRemote.forEach { r ->
            val local = localBySyncId[r.syncId]
            if (local == null) {
                val toInsert = r.copy(id = 0, unlockedAt = null)
                dao.insert(toInsert)
                changed.add(toInsert)
            } else if (r.deleted && !local.deleted) {
                // MINOR fix (code-review, Sonnet): used to be dao.update(local.copy(...)), a stale-read
                // full-row write that could clobber a concurrent unlockEligible commit - see
                // TimeCapsuleDao.tombstone's own doc. The write itself now only ever touches deleted/
                // updatedAt; `merged` below is constructed purely for this function's own return value
                // (still unread by any production caller today, per A1), not used as the DB write.
                val newUpdatedAt = maxOf(local.updatedAt, r.updatedAt)
                dao.tombstone(local.id, newUpdatedAt)
                val merged = local.copy(deleted = true, updatedAt = newUpdatedAt)
                changed.add(merged)
            }
        }
        return changed
    }
}

class MomentRepository(private val dao: MomentDao, private val context: Context) {
    /** Excludes soft-deleted (tombstoned) rows - backed by [MomentDao.observeActive]. Every existing
     * caller (MomentsScreen, CalendarScreen's photo-day-marking, GattSyncManager's photo-transfer phase)
     * genuinely wants "moments the user still has", so this filters transparently for all of them with
     * no call-site changes needed. The one place that needs tombstones too (the sync payload builder)
     * uses [getAllIncludingDeleted] instead. */
    fun observeAll(): Flow<List<Moment>> = dao.observeActive()
    suspend fun getAll(): List<Moment> = dao.getActive()
    suspend fun getBySyncId(syncId: String): Moment? = dao.getBySyncId(syncId)

    /** The raw/complete table, tombstones included - only for GattSyncManager.buildPayload, which must
     * send deleted moments too so the deletion itself propagates to the partner's phone. */
    suspend fun getAllIncludingDeleted(): List<Moment> = dao.getAll()

    /** [takenAt] defaults to "now" for a live camera capture (CameraScreen); a gallery backfill
     * (GalleryImportFlow) passes the date the user picked instead, so the resulting Moment groups under
     * that PAST day everywhere takenAt is read (MomentsScreen's day grouping, CalendarScreen's
     * daysWithPhotos), never under today. */
    suspend fun add(photoUri: String, sessionId: Long?, takenAt: Long = System.currentTimeMillis()): Long =
        dao.insert(Moment(photoUri = photoUri, takenAt = takenAt, sessionId = sessionId, updatedAt = System.currentTimeMillis()))

    /** Feature 2: called once GattSyncManager has fully received a photo's bytes, written them to a temp
     * file, and successfully renamed that into place at the Moment's real photoUri - see
     * GattSyncManager.savePhotoBytes. Room's own Flow (observeAll) picks this up automatically, so
     * MomentsScreen re-renders the real image with no further plumbing needed. */
    suspend fun markPhotoDownloaded(syncId: String) {
        dao.updatePhotoDownloaded(syncId, true)
    }

    /** UX-FIX-PLAN.md Phase 3 item 17: lets the user manually correct a Moment's "taken apart"/"taken
     * together" caption after the fact - previously [Moment.takenWhileTogether] was set once at capture/
     * import time (see its own doc) and never editable again. A targeted single-column write via
     * [MomentDao.setTakenWhileTogether], bumping updatedAt so the edit propagates to the partner on the
     * next sync - same "don't build a stale-read full-row copy" reasoning as [markTakenWhileTogether]'s
     * own doc. */
    suspend fun setTakenWhileTogether(moment: Moment, together: Boolean) {
        dao.setTakenWhileTogether(moment.syncId, together, System.currentTimeMillis())
    }

    /** Soft-deletes a Moment - unlike a manual session, ANY moment can be deleted regardless of isRemote,
     * since every moment is content one of the two people created, not an automatically-collected record
     * (see Moment.deleted's doc). Also deletes the local photo FILE if we actually hold one
     * (photoDownloaded), to free disk space and stop it being offered to the partner during a future
     * photo-transfer phase - see computeToSend in GattSyncManager, which already reads through
     * observeAll()/getAll() above so a tombstoned moment is naturally excluded from both directions of
     * the photo-bytes exchange without any extra filtering needed there. Deletes via [moment]'s OWN
     * already-locally-verified photoUri (never a path freshly parsed from an incoming wire payload) -
     * same "never trust wire data as a filesystem destination" rule mergeRemoteStubs' doc explains for
     * why photoUri is always syncId-derived in the first place. The file delete is wrapped in
     * [runCatching] so a filesystem hiccup (already-missing file, permission edge case) never blocks the
     * DB tombstone write, which is the part that actually matters for sync/UI correctness - a leftover
     * orphaned file in a rare failure case is a minor cost, a stuck "can't delete" UI is not acceptable. */
    suspend fun softDelete(moment: Moment) {
        if (moment.photoDownloaded) {
            runCatching { File(moment.photoUri).delete() }
        }
        dao.update(moment.copy(deleted = true, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Feature D: union-merges the partner's moment METADATA (never photo bytes - see Moment.isRemote's
     * doc for why) so notes can reference a moment the partner hasn't necessarily seen locally yet.
     * Dedup by syncId, same reasoning as SessionRepository.mergeRemoteSessions. sessionId is deliberately
     * dropped (nulled out) on insert - a remote row's sessionId is a local auto-increment id from the
     * OTHER device's database and is meaningless (and potentially misleading/coincidentally-colliding)
     * here. isRemote is always forced true for anything inserted by this path, regardless of what the
     * sender claimed, since by definition anything we didn't already have locally originated elsewhere.
     *
     * SECURITY / DATA-INTEGRITY (was the top blocker of this pass): [it.photoUri] here is WIRE DATA from
     * the partner device and is NEVER trusted as a local filesystem path - the local `photoUri` column is
     * always overwritten below with [localPhotoFile], a path deterministically derived from the moment's
     * own [Moment.syncId] (a UUID, already trusted as an identifier for merge/dedup). This is the ONLY
     * place a remote-stub row's local photoUri is ever set, so it's correct from the very first
     * metadata-only insert onward - GattSyncManager.savePhotoBytes later just writes bytes to this same
     * already-safe path (see its own doc + canonical-path assertion for the defense-in-depth half of
     * this fix). Previously the raw wire value was stored verbatim, which (a) let two devices' Moments
     * silently collide onto the SAME local file when both captured a photo in the same clock second
     * (CameraScreen names files by second-granularity timestamp, and both phones share the same
     * filesDir/moments/ layout under the same applicationId) - overwriting a real local photo with the
     * partner's incoming bytes with zero attacker involved - and (b) let a malicious authenticated peer
     * (anyone who knows the pairing handshake token) point photoUri at an arbitrary app-writable path
     * (e.g. the Room DB file) for savePhotoBytes to later overwrite.
     *
     * TOMBSTONE-APPLY (moment delete): for a syncId we already know locally, a remote row that carries a
     * tombstone (r.deleted) we haven't already applied is the one mutation an existing row can receive
     * here - this is how deleting a moment on one phone propagates to the other. UNLIKE
     * SessionRepository.mergeRemoteSessions' tombstone-apply branch, there is NO local.isManual-equivalent
     * gate here: any moment, remote or local, can be tombstoned by an incoming payload, which is
     * intentional - deletion is allowed for any moment by design (see Moment.deleted's doc), so trusting
     * the remote's deleted claim for an already-known syncId is correct rather than a hole to close. If we
     * hold real photo bytes for the now-tombstoned row, delete the local file too (same reasoning as
     * softDelete above), using OUR OWN local row's already-safe photoUri, never r.photoUri (untrusted
     * wire data).
     */
    suspend fun mergeRemoteStubs(remote: List<Moment>) {
        val localBySyncId = dao.getAll().filter { it.syncId.isNotBlank() }.associateBy { it.syncId }
        remote.filter { it.syncId.isNotBlank() }.forEach { r ->
            val local = localBySyncId[r.syncId]
            if (local == null) {
                // The wire's photoUri string is used for NOTHING but a best-effort file-extension hint
                // here - extensionFromHint() only ever extracts and validates a short suffix against a
                // hardcoded allowlist, so even a maliciously-crafted string (path traversal, absolute
                // path, etc.) can never influence the actual destination directory - see
                // localPhotoFile()/sanitizeExtension().
                val safePath = localPhotoFile(context, r.syncId, extensionFromHint(r.photoUri)).absolutePath
                // takenWhileTogether is deliberately carried through from `r` (the peer's payload) rather
                // than being re-derived from the sessionId we're about to null out - that re-derivation is
                // precisely the bug this field exists to fix (see Moment.takenWhileTogether's doc). Note
                // `copy` does NOT re-evaluate the entity's default expression, so the wire value survives
                // the `sessionId = null` below; it is set explicitly all the same so a future refactor of
                // either side can't silently reintroduce the old always-"Taken apart" behaviour.
                dao.insert(
                    r.copy(
                        id = 0,
                        sessionId = null,
                        takenWhileTogether = r.takenWhileTogether,
                        isRemote = true,
                        photoUri = safePath
                    )
                )
            } else if (r.deleted && !local.deleted) {
                if (local.photoDownloaded) {
                    runCatching { File(local.photoUri).delete() }
                }
                dao.update(local.copy(deleted = true, updatedAt = maxOf(local.updatedAt, r.updatedAt)))
            } else if (local.isRemote && !local.takenWhileTogether && r.takenWhileTogether) {
                // SELF-HEAL for moments that were already synced BEFORE takenWhileTogether existed (see
                // that field's doc). Those rows were inserted with no such information and were backfilled
                // to false by AppDatabase.MIGRATION_10_11 (their sessionId is null, as it is for every
                // remote stub), so without this branch every photo a couple had already shared would keep
                // the wrong "Taken apart" caption forever on the receiving phone - the upgrade would only
                // fix photos taken from now on.
                //
                // Deliberately narrow, in three ways, so this cannot become a hole:
                //  - ONE-WAY (false -> true only), exactly like the tombstone branch above, so it converges
                //    and cannot flip back and forth between syncs.
                //  - Gated on `local.isRemote`, so a peer can never rewrite the caption of a photo THIS
                //    phone took; for our own rows our own capture-time value is authoritative.
                //  - `updatedAt` is deliberately NOT bumped: this is a one-time backfill of missing
                //    information, not a user edit, and bumping it would feed the very sync write-
                //    amplification the sibling LWW guards exist to prevent. The condition is
                //    self-extinguishing (it is false on every subsequent sync once applied), so this
                //    writes at most once per affected row, ever.
                // MINOR fix (independent review round 2): a targeted single-column UPDATE rather than
                // `dao.update(local.copy(...))`. `local` came from the snapshot read at the top of this
                // function, so a full-row write could silently revert a `photoDownloaded = true` that a
                // photo transfer committed in between - see MomentDao.markTakenWhileTogether's own doc.
                dao.markTakenWhileTogether(local.syncId)
            }
        }
    }

    companion object {
        private val SAFE_PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        /** Extracts a bare extension (no dots/slashes/path separators) from an untrusted hint string and
         * validates it against a small hardcoded allowlist - defaults to "jpg" (what CameraX always
         * produces) for anything unrecognized, blank, or unsafe. Never used to build a directory, only a
         * file suffix on an already-safe base path - see [localPhotoFile]. */
        fun extensionFromHint(hint: String): String {
            val ext = File(hint).extension.lowercase()
            return if (ext in SAFE_PHOTO_EXTENSIONS) ext else "jpg"
        }

        /** syncId is documented above/at both call sites as "a UUID, already trusted as an identifier" -
         * but neither caller (mergeRemoteStubs' wire data, BackupManager.parseMoments' backup-JSON data)
         * actually validated that shape before this fix, so a crafted syncId containing "/" or ".."
         * could steer the file this function returns outside filesDir/moments/ entirely (e.g.
         * syncId = "../../databases/evil"). SECURITY fix: validated HERE, the one shared place both
         * callers already funnel through, instead of trusting either caller to have checked - anything
         * not matching a plain UUID-safe charset (letters/digits/hyphen/underscore, reasonable length)
         * is replaced with a fresh random UUID rather than used verbatim. */
        private val SAFE_SYNC_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** The ONE place a remote Moment's local photo destination is computed: deterministic, keyed only
         * by the moment's own syncId (a UUID) inside this app's own filesDir/moments/ directory - never
         * influenced by anything the network peer sent beyond the sanitized extension. Because every
         * remote-stub row gets its own unique syncId-derived path, two moments can never collide onto the
         * same file the way second-granularity capture-timestamp filenames could. */
        fun localPhotoFile(context: Context, syncId: String, extension: String): File {
            val safeExt = if (extension in SAFE_PHOTO_EXTENSIONS) extension else "jpg"
            val safeSyncId = if (SAFE_SYNC_ID.matches(syncId)) syncId else java.util.UUID.randomUUID().toString()
            val dir = File(context.filesDir, "moments")
            return File(dir, "$safeSyncId.$safeExt")
        }
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
        // Content-identical rows are skipped rather than re-written - see DateIdeaRepository.mergeRemote's
        // doc for the full reasoning behind this guard on all four LWW merges in this file.
        val toUpsert = incoming.filter { r ->
            val l = localByKey[r.momentSyncId to r.authorDeviceId] ?: return@filter true
            r.updatedAt > l.updatedAt && !(r.text == l.text && r.deleted == l.deleted)
        }
        if (toUpsert.isNotEmpty()) dao.upsertAll(toUpsert)
    }
}

class MilestoneRepository(private val dao: MilestoneDao) {
    fun observeActive(): Flow<List<Milestone>> = dao.observeActive()
    suspend fun getAll(): List<Milestone> = dao.getAll()

    suspend fun add(label: String, month: Int, day: Int, year: Int?, linkedMomentSyncId: String? = null): Milestone {
        val now = System.currentTimeMillis()
        val milestone = Milestone(
            id = UUID.randomUUID().toString(),
            label = label,
            month = month,
            day = day,
            year = year,
            createdAt = now,
            updatedAt = now,
            linkedMomentSyncId = linkedMomentSyncId
        )
        dao.upsert(milestone)
        return milestone
    }

    suspend fun delete(milestone: Milestone) {
        dao.upsert(milestone.copy(deleted = true, updatedAt = System.currentTimeMillis()))
    }

    /** Changes (or clears, if [linkedMomentSyncId] is null) an already-existing milestone's linked photo -
     * see Milestone.linkedMomentSyncId's own doc. Same whole-row dao.upsert() shape as [delete] above;
     * milestones have no concurrent-field-clobber race to guard against the way TimeCapsule/Moment do (no
     * OTHER field here is ever mutated independently by a background process the way e.g. unlockedAt is -
     * see TimeCapsuleDao.tombstone's doc for that contrast), so a plain copy+upsert is correct as-is. */
    suspend fun setLinkedMoment(milestone: Milestone, linkedMomentSyncId: String?) {
        dao.upsert(milestone.copy(linkedMomentSyncId = linkedMomentSyncId, updatedAt = System.currentTimeMillis()))
    }

    /** UX-FIX-PLAN.md Phase 3 item 19: a full edit (label + month/day/year, alongside the linked photo)
     * for an already-existing milestone - previously only the photo was editable (see MilestonesScreen's
     * former EditMilestonePhotoDialog, which this supersedes) and label/date editing was explicitly
     * unsupported. Same plain whole-row dao.upsert() shape as [setLinkedMoment]/[delete] - see
     * [setLinkedMoment]'s own doc for why no extra concurrency guard is needed (nothing else mutates a
     * Milestone row independently in the background the way e.g. TimeCapsule.unlockedAt does). The caller
     * (MilestonesViewModel.update) is responsible for re-arming this milestone's yearly alarm afterward
     * via MilestoneAlarmScheduler - this function only owns the DB write, matching how [add]'s own caller
     * relationship with the alarm scheduler already works one layer up. mergeRemote's existing LWW-by-
     * (id, updatedAt) merge already handles label/month/day/year changing together in one write, exactly
     * like any other field change it already supports - no merge-layer change needed for this to sync. */
    suspend fun update(milestone: Milestone, label: String, month: Int, day: Int, year: Int?, linkedMomentSyncId: String?) {
        dao.upsert(
            milestone.copy(
                label = label,
                month = month,
                day = day,
                year = year,
                linkedMomentSyncId = linkedMomentSyncId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** The columns that make a milestone meaningfully different, for [mergeRemote]'s no-op-write guard -
     * deliberately excludes `updatedAt` (the field that drifts) and `createdAt`. `createdAt` is excluded
     * simply because it is immutable: a milestone is created on ONE device and copied to the other
     * verbatim on insert (see the `local == null` branch), so both copies always already agree on it and
     * comparing it would be dead weight. Unlike ListCategory's `createdAt` - which genuinely does differ
     * between devices, because the default list is seeded independently on each - there is nothing here
     * to converge. */
    private fun Milestone.sameContentAs(other: Milestone): Boolean =
        label == other.label && month == other.month && day == other.day && year == other.year &&
            deleted == other.deleted && linkedMomentSyncId == other.linkedMomentSyncId

    /** Union+tombstone merge by id + updatedAt, same LWW shape as DateIdeaRepository.mergeRemote - these
     * are simple, rarely-edited additions, so plain last-write-wins is appropriate (see task spec).
     * BUG fix: returns what was actually upserted (was Unit) - an independent audit round found that
     * unlike every OTHER way a Milestone enters the DB (a local add, a backup restore, app start, boot),
     * a milestone arriving via THIS path never got its yearly alarm armed at all until the next cold
     * start - see this function's caller in GattSyncManager for the actual fix.
     *
     * BLOCKER fix (ultimate-app-review round 1, Opus): [missingLinkedMomentField] is the id set of any
     * remote row whose wire payload had no "linkedMomentSyncId" key at all (a pre-this-commit partner
     * build echoing the milestone back unmodified) - GattSyncManager.deserializeMilestones can no longer
     * tell that apart from an explicit clear, so it deserializes both to null. Without this, a whole-row
     * LWW win by that echoed row (the common case, since the sender didn't touch updatedAt) would
     * silently null out a link the LOCAL user had just set, with zero user action and zero error. For
     * exactly those ids, this function keeps the local linkedMomentSyncId instead of trusting the remote's
     * null - every other field on the row (label/month/day/updatedAt/deleted) still comes from the remote
     * as normal, so an explicit clear from a same-build partner (key present, value null) is unaffected
     * and still wins on LWW like any other field change.
     *
     * MINOR fix (ultimate-app-review round 2, Opus): [missingLinkedMomentField] no longer defaults to
     * emptySet() - there is exactly one production caller (GattSyncManager, already explicit) plus this
     * class's own test suite, so the default bought nothing except letting a FUTURE second caller forget
     * the argument and silently reintroduce round 1's BLOCKER with no compile error. */
    suspend fun mergeRemote(remote: List<Milestone>, missingLinkedMomentField: Set<String>): List<Milestone> {
        val local = dao.getAll().associateBy { it.id }
        val toUpsert = remote.mapNotNull { r ->
            val l = local[r.id] ?: return@mapNotNull r
            if (r.updatedAt <= l.updatedAt) return@mapNotNull null
            // The linkedMomentSyncId preservation is applied BEFORE the content comparison below, so the
            // thing we compare is the row we would actually write - otherwise an echoed row from a
            // pre-linkedMomentSyncId partner build would look "changed" (remote null vs local set) and be
            // written on every sync, exactly the churn the guard exists to stop.
            val effective = if (r.id in missingLinkedMomentField) r.copy(linkedMomentSyncId = l.linkedMomentSyncId) else r
            // Content-identical rows are skipped - see DateIdeaRepository.mergeRemote's doc. Skipping also
            // correctly avoids re-arming this milestone's yearly alarm at the call site (which schedules
            // only what this function returns): nothing about the date changed, so the alarm already
            // armed by the original upsert is still the right one.
            if (effective.sameContentAs(l)) return@mapNotNull null
            effective
        }
        if (toUpsert.isNotEmpty()) dao.upsertAll(toUpsert)
        return toUpsert
    }
}

/**
 * "Our Lists": built exactly like [MilestoneRepository] - same shape, same LWW-by-(id, updatedAt) merge.
 * Takes a [DateIdeaRepository] (rather than a raw DateIdeaDao) so [delete]'s cascade can reuse
 * DateIdeaRepository's own already-working softDelete path (each affected idea gets a correct
 * deleted=true/updatedAt=now write that syncs via DateIdea's existing tombstone sync) instead of writing
 * new raw SQL - matching how other repositories that need cross-table awareness in this file are
 * constructed (e.g. MomentRepository takes a Context to compute photo paths, not a raw file API).
 */
class ListCategoryRepository(
    private val dao: ListCategoryDao,
    private val dateIdeaRepository: DateIdeaRepository,
    private val database: AppDatabase
) {
    fun observeActive(): Flow<List<ListCategory>> = dao.observeActive()
    suspend fun getAll(): List<ListCategory> = dao.getAll()

    suspend fun add(name: String): ListCategory {
        val now = System.currentTimeMillis()
        val category = ListCategory(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = now,
            updatedAt = now
        )
        dao.upsert(category)
        return category
    }

    /** UX-FIX-PLAN.md Phase 3 item 19: renames a list - previously a list could be created/deleted but
     * never renamed. Allowed even for [DEFAULT_LIST_ID] (only its DELETE tombstone is specially blocked -
     * see [mergeRemote]'s own doc, "Any NON-delete update to it (e.g. a rename) still applies normally").
     * No-ops on blank text rather than letting a list's name be cleared out from under it. */
    suspend fun rename(category: ListCategory, name: String) {
        if (name.isBlank()) return
        dao.upsert(category.copy(name = name.trim(), updatedAt = System.currentTimeMillis()))
    }

    /** Soft-deletes the list itself AND cascades to every currently-active idea it owns - deleting a list
     * takes its items with it, the same way deleting a note deletes its lines. The cascade goes through
     * DateIdeaRepository.softDelete (the SAME per-idea soft-delete path the UI's own delete-one-idea
     * button already uses) rather than a raw bulk UPDATE, so each cascaded idea gets its own correct
     * deleted=true/updatedAt=now row and propagates to the partner's phone via DateIdea's ALREADY-WORKING
     * tombstone sync (GattSyncManager.serializeDateIdeas/DateIdeaRepository.mergeRemote) - no new sync
     * mechanism is needed for the cascade itself, only ListCategory's own entity needs new sync wiring.
     * Wrapped in one DB transaction so a process kill partway through (e.g. a low-memory kill, not even
     * a crash) can't land between the category's own tombstone write and the per-idea cascade writes -
     * without this, an interrupted cascade could leave an idea still active in the DB but permanently
     * invisible in the UI (which only ever groups ideas under an active list card), with no way to
     * reach it again to delete or restore it. */
    suspend fun delete(category: ListCategory) {
        // BLOCKER fix: DEFAULT_LIST_ID must never actually go away - DateIdeaRepository.reassignOrphans
        // relies on it always resolving as the permanent fallback for orphaned ideas (see its doc). If it
        // could be deleted, an orphan would get reassigned into a list that itself doesn't exist, and -
        // worse - since reassignOrphans is also driven reactively by OurListsViewModel's own Flow
        // collector, every re-run would see the same still-orphaned ideas again and write them again,
        // an unbounded loop of DB writes/Flow emissions for as long as that screen is open. The UI already
        // hides the delete affordance for this list (see OurListsScreen's ListCategoryCard), this is the
        // backstop in case anything else ever calls delete() directly.
        if (category.id == DEFAULT_LIST_ID) return
        database.withTransaction {
            dao.upsert(category.copy(deleted = true, updatedAt = System.currentTimeMillis()))
            dateIdeaRepository.getAll()
                .filter { it.listId == category.id && !it.deleted }
                .forEach { dateIdeaRepository.softDelete(it) }
        }
    }

    /** Item 24 (UX-FIX-PLAN.md): sets (or clears, via null) this list's own default "remind me X minutes
     * after we're together" reminder - see ListCategory.defaultRemindAfterTogetherMinutes' own doc for
     * why this is currently local-only (not yet wired through GattSyncManager's wire protocol). */
    suspend fun setDefaultReminder(category: ListCategory, minutes: Int?) {
        dao.upsert(category.copy(defaultRemindAfterTogetherMinutes = minutes, updatedAt = System.currentTimeMillis()))
    }

    /** Union+tombstone merge by id + updatedAt, same LWW shape as MilestoneRepository.mergeRemote - these
     * are simple, rarely-edited rows, so plain last-write-wins is appropriate.
     *
     * BLOCKER fix: a delete-tombstone for DEFAULT_LIST_ID is never applied, regardless of updatedAt - a
     * partner device (an older/buggy build, or any other way its own copy got soft-deleted) must never be
     * able to remove the one list this device's own reassignOrphans() permanently depends on existing. Any
     * NON-delete update to it (e.g. a rename) still applies normally.
     *
     * Item 24 (UX-FIX-PLAN.md): [ListCategory.defaultRemindAfterTogetherMinutes] is, like DateIdea's
     * equivalent field, deliberately NOT part of the wire protocol yet - see
     * DateIdeaRepository.mergeRemote's matching doc for the full reasoning. Same fix here: an
     * already-known row's local value is carried forward into the upserted copy instead of being
     * silently overwritten by the wire's always-null value. */
    suspend fun mergeRemote(remote: List<ListCategory>) {
        val local = dao.getAll().associateBy { it.id }
        // Converge createdAt downward for lists we already know - see ListCategoryDao.lowerCreatedAt's
        // doc. Runs independently of (and before) the LWW decision below, because the whole point is the
        // case where LWW correctly decides there is nothing to write.
        remote.forEach { r ->
            val l = local[r.id] ?: return@forEach
            if (r.createdAt < l.createdAt) dao.lowerCreatedAt(r.id, r.createdAt)
        }
        val toUpsert = remote.mapNotNull { r ->
            if (r.id == DEFAULT_LIST_ID && r.deleted) return@mapNotNull null
            val l = local[r.id] ?: return@mapNotNull r
            // Content-identical rows are skipped - see DateIdeaRepository.mergeRemote's doc. `createdAt`
            // is excluded from the comparison on purpose: the two devices' copies of the DEFAULT list are
            // seeded independently and so hold permanently different createdAt values, and treating that
            // as a content change would defeat the guard entirely for the one row every couple is
            // guaranteed to have. It is instead converged separately, above and just below.
            if (r.updatedAt <= l.updatedAt || (r.name == l.name && r.deleted == l.deleted)) return@mapNotNull null
            // A real change IS being written - carry the converged (minimum) createdAt into it rather
            // than the remote's raw value, otherwise this write would undo the convergence above and the
            // default list's position would diverge again on the first rename. Also carry forward the
            // locally-set defaultRemindAfterTogetherMinutes - see DateIdeaRepository.mergeRemote's
            // matching doc for why (not part of the wire protocol yet).
            r.copy(
                createdAt = minOf(l.createdAt, r.createdAt),
                defaultRemindAfterTogetherMinutes = l.defaultRemindAfterTogetherMinutes
            )
        }
        if (toUpsert.isNotEmpty()) dao.upsertAll(toUpsert)
    }

    /** BLOCKER fix: merges a sync payload's DateIdea AND ListCategory tables, then reassigns orphans -
     * all inside ONE database transaction, called by GattSyncManager.applyPayload instead of doing the
     * three steps as separate top-level suspend calls. */
    suspend fun mergeRemoteWithIdeas(remoteCategories: List<ListCategory>, remoteIdeas: List<DateIdea>) {
        database.withTransaction {
            dateIdeaRepository.mergeRemote(remoteIdeas)
            mergeRemote(remoteCategories)
            reassignOrphanIdeas()
        }
    }

    /**
     * Self-heal entry point for DateIdeaRepository.reassignOrphans - reads the CURRENT set of valid list
     * ids ITSELF, fresh, at the moment this actually runs, rather than accepting a caller-supplied
     * snapshot.
     *
     * BLOCKER fix, round 2: wrapping mergeRemoteWithIdeas' three steps in one database.withTransaction
     * (round 1's fix) guarantees the WRITE is atomic, but does NOT guarantee two SEPARATE Room Flows
     * (ListCategoryDao.observeActive() and DateIdeaDao.observeActive(), as consumed independently by
     * OurListsViewModel's `lists`/`ideas` StateFlows) become visible to a `combine()` of them at the same
     * instant - each Flow independently re-queries and re-emits once notified of invalidation, and those
     * two re-queries are separate async operations with no ordering guarantee between them, even though
     * the underlying transaction that triggered both was atomic. A caller that fed in `combine(lists,
     * ideas)`'s snapshot (as OurListsViewModel's opportunistic collector used to) could therefore still
     * observe the ideas-Flow's post-sync value paired with the lists-Flow's PRE-sync value, compute
     * orphans against a stale/incomplete valid-list-id set, and permanently misfile a real idea - the
     * exact corruption this whole mechanism exists to prevent, just one layer further down than round 1's
     * fix reached. Reading fresh via [dao] directly here sidesteps that entirely: by the time ANY
     * observer's invalidation callback fires for a committed transaction, the transaction is already fully
     * committed in SQLite, so a direct read at that moment (bypassing both StateFlows' own cached/lagging
     * values) always sees the complete, consistent post-transaction state for both tables.
     *
     * Also self-heals DEFAULT_LIST_ID itself if it's ever found inactive (deleted or missing) - e.g. a
     * pre-hardening build's local delete, or a not-yet-restored-through backup - by resurrecting its
     * existing row (preserving name/createdAt) or synthesizing a fresh one. Without this, an orphan would
     * get reassigned to a list that itself doesn't resolve, immediately becoming an orphan again on the
     * very next run - an unbounded loop for as long as anything keeps calling this (every sync, and every
     * time OurListsScreen is open).
     */
    suspend fun reassignOrphanIdeas() {
        database.withTransaction {
            val allCategories = dao.getAll()
            var validIds = allCategories.filter { !it.deleted }.map { it.id }.toSet()
            if (DEFAULT_LIST_ID !in validIds) {
                val now = System.currentTimeMillis()
                val existing = allCategories.firstOrNull { it.id == DEFAULT_LIST_ID }
                dao.upsert(
                    existing?.copy(deleted = false, updatedAt = now)
                        ?: ListCategory(id = DEFAULT_LIST_ID, name = "Date Ideas", createdAt = now, updatedAt = now)
                )
                validIds = validIds + DEFAULT_LIST_ID
            }
            dateIdeaRepository.reassignOrphans(validIds)
        }
    }
}
