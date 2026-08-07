package com.ssbmedia.twogether.data.repo

import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.ble.GattSyncManager

/**
 * MAJOR fix (ultimate-app-review, post-restart full-scope round, Opus): GattSyncManager.
 * isPlausibleWireUpdatedAt fixes "the peer's clock is CURRENTLY wrong" (see its own doc), but not
 * "the peer's clock WAS wrong when a row was written" - a row locally stamped with a future
 * `updatedAt` during a period of genuine clock error stays stamped that way even after the clock is
 * corrected, and every future sync attempt sends that same too-far-future value, which every partner
 * device keeps rejecting as implausible, forever. Live-verified: a date idea written while a phone's
 * clock briefly read 2050 was still being silently dropped by every sync 3 attempts later, well after
 * the clock had been fixed - the only in-app remedy was deleting and retyping it.
 *
 * Run once at app start (see TwogetherApp.onCreate): sweeps every synced table for a row whose
 * `updatedAt` is implausibly ahead of THIS device's own current clock and clamps it back to now -
 * self-correcting a past mistake using the same clock that made it, which is always trustworthy for
 * "is this from my own past," unlike a peer's claimed clock. Deliberately scoped to `updatedAt` only,
 * not `TogetherSession.startedAt`/`endedAt` - rewriting a session's actual together-time bounds is a
 * materially different (and riskier) operation than correcting a bookkeeping timestamp, and a
 * session that fails GattSyncManager's own bounds check simply stays local (safe) rather than
 * corrupting anything; the user can always re-add real together-time manually if it matters.
 */
object ClockSkewSelfHeal {
    suspend fun run() {
        val now = System.currentTimeMillis()
        val maxPlausible = now + GattSyncManager.MAX_CLOCK_SKEW_TOLERANCE_MILLIS
        val db = ServiceLocator.database

        db.dateIdeaDao().getAll().filter { it.updatedAt > maxPlausible }.let { stale ->
            if (stale.isNotEmpty()) db.dateIdeaDao().upsertAll(stale.map { it.copy(updatedAt = now) })
        }
        db.listCategoryDao().getAll().filter { it.updatedAt > maxPlausible }.let { stale ->
            if (stale.isNotEmpty()) db.listCategoryDao().upsertAll(stale.map { it.copy(updatedAt = now) })
        }
        db.momentDao().getAll().filter { it.updatedAt > maxPlausible }.forEach { moment ->
            db.momentDao().update(moment.copy(updatedAt = now))
        }
        db.momentNoteDao().getAll().filter { it.updatedAt > maxPlausible }.let { stale ->
            if (stale.isNotEmpty()) db.momentNoteDao().upsertAll(stale.map { it.copy(updatedAt = now) })
        }
        db.milestoneDao().getAll().filter { it.updatedAt > maxPlausible }.let { stale ->
            if (stale.isNotEmpty()) db.milestoneDao().upsertAll(stale.map { it.copy(updatedAt = now) })
        }
    }
}
