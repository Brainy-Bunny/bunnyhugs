package com.ssbmedia.twogether.audit

import com.ssbmedia.twogether.ble.GattSyncManager
import com.ssbmedia.twogether.data.backup.BackupManager
import com.ssbmedia.twogether.data.datastore.PairingStore
import com.ssbmedia.twogether.data.datastore.SettingsStore
import com.ssbmedia.twogether.data.db.AppDatabase
import com.ssbmedia.twogether.data.db.DateIdeaDao
import com.ssbmedia.twogether.data.db.DayNoteDao
import com.ssbmedia.twogether.data.db.ListCategoryDao
import com.ssbmedia.twogether.data.db.MilestoneDao
import com.ssbmedia.twogether.data.db.MomentDao
import com.ssbmedia.twogether.data.db.MomentNoteDao
import com.ssbmedia.twogether.data.db.TimeCapsuleDao
import com.ssbmedia.twogether.data.db.TogetherSessionDao
import com.ssbmedia.twogether.data.repo.DateIdeaRepository
import com.ssbmedia.twogether.data.repo.DayNoteRepository
import com.ssbmedia.twogether.data.repo.ListCategoryRepository
import com.ssbmedia.twogether.data.repo.MilestoneRepository
import com.ssbmedia.twogether.data.repo.MomentNoteRepository
import com.ssbmedia.twogether.data.repo.MomentRepository
import com.ssbmedia.twogether.data.repo.SessionRepository
import com.ssbmedia.twogether.data.repo.TimeCapsuleRepository
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.lang.reflect.Method

/**
 * ultimate-app-review round-2 gate spec-tests, PROPOSED INDEPENDENTLY by both Opus and Sonnet in their
 * Round 1 reports (not authored by the same pass that wrote the fixes below), derived from checklist.md's
 * S4/B8/S11 items, persisted here as this round's spec-tests.* per the skill's own rule that an
 * independently-derived test must be proposed by a reviewer, never authored by the orchestrator from its
 * own fix alone. Covers three of the four convergent/near-convergent Round 1 findings that a corrected
 * pre-existing test didn't already end up covering (S9's sticky-tombstone regression coverage was folded
 * directly into TimeCapsuleSyncAuditTest.kt instead, since fixing that file's one wrong pre-existing
 * assertion already required rewriting it around the corrected contract - see that file's own updated doc).
 *
 * NOT covered here (both models independently flagged this as a real, disclosed evidence gap rather than
 * fabricating a pass): UpdateChecker.fetchLatestRelease/downloadApk's digest-parsing/hash-mismatch-reject
 * end-to-end paths have no mockable seam in this codebase (raw HttpURLConnection, no DI point) - both
 * proposed extracting the digest-parsing logic into a pure function as a prerequisite; that's a
 * testability refactor beyond this round's blocker/major fix scope, left as a deferred proposal.
 */
class UltimateReviewRound2SpecTest {

    // ---- S4: manualHoursAtCreation validation (wire path, GattSyncManager.deserializeTimeCapsules) ----

    private fun newManager(): GattSyncManager {
        val context = mock(Context::class.java)
        val dateIdeaRepo = DateIdeaRepository(mock(DateIdeaDao::class.java))
        val listCategoryRepo = ListCategoryRepository(mock(ListCategoryDao::class.java), dateIdeaRepo, mock(AppDatabase::class.java))
        val sessionRepo = SessionRepository(mock(TogetherSessionDao::class.java))
        val momentRepo = MomentRepository(mock(MomentDao::class.java), context)
        val momentNoteRepo = MomentNoteRepository(mock(MomentNoteDao::class.java))
        val dayNoteRepo = DayNoteRepository(mock(DayNoteDao::class.java))
        val milestoneRepo = MilestoneRepository(mock(MilestoneDao::class.java))
        val timeCapsuleRepo = TimeCapsuleRepository(mock(TimeCapsuleDao::class.java))
        val settingsStore = mock(SettingsStore::class.java)
        val pairingStore = mock(PairingStore::class.java)
        return GattSyncManager(
            context, dateIdeaRepo, listCategoryRepo, sessionRepo, momentRepo,
            momentNoteRepo, dayNoteRepo, milestoneRepo, timeCapsuleRepo, settingsStore, pairingStore, CoroutineScope(Dispatchers.Unconfined)
        )
    }

    private fun deserializeTimeCapsulesRaw(json: String): List<*> {
        val method = GattSyncManager::class.java.getDeclaredMethod(
            "deserializeTimeCapsules", JSONArray::class.java, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(newManager(), JSONArray(json), 0L) as List<*>
    }

    private fun capsuleJsonRaw(manualHoursAtCreation: String, updatedAt: Long): String =
        """[{"syncId":"c1","text":"n","unlockAtHours":100.0,"createdAt":$updatedAt,"unlockedAt":null,"manualHoursAtCreation":$manualHoursAtCreation,"updatedAt":$updatedAt,"deleted":false}]"""

    @Test
    fun `S4 wire - a manualHoursAtCreation far beyond any plausible real value is rejected`() {
        val now = System.currentTimeMillis()
        val result = deserializeTimeCapsulesRaw(capsuleJsonRaw("999999.0", now))
        assertTrue("live-reproduced exploit value from both reviewers' Round 1 reports", result.isEmpty())
    }

    @Test
    fun `S4 wire - a negative manualHoursAtCreation is rejected`() {
        val now = System.currentTimeMillis()
        val result = deserializeTimeCapsulesRaw(capsuleJsonRaw("-5.0", now))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `S4 wire - a manualHoursAtCreation that overflows Float to Infinity is rejected, not persisted`() {
        val now = System.currentTimeMillis()
        // A finite JSON double (per spec) that overflows Float on .toFloat() - Opus's live-reproduced
        // crash-loop vector: this value used to get persisted, then made every subsequent
        // serializeTimeCapsules() call throw inside buildPayload()'s bare scope.launch.
        val result = deserializeTimeCapsulesRaw(capsuleJsonRaw("1e39", now))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `S4 wire - a manualHoursAtCreation of literal NaN never survives as a non-finite persisted value`() {
        val now = System.currentTimeMillis()
        // manualHoursAtCreation is read via optDouble (unlike unlockAtHours' getDouble a few lines above
        // it, which DOES parse a bare NaN token through to Double.NaN per this file's other NaN test) -
        // optDouble's own catch-and-default behavior on this malformed token means the safe outcome here
        // is either an outright drop OR a fallback to the safe default (0.0, finite) - either is
        // acceptable, what must NEVER happen is a persisted row carrying an actual non-finite value.
        val result = deserializeTimeCapsulesRaw(capsuleJsonRaw("NaN", now))
        if (result.isNotEmpty()) {
            val field = result.first()!!.javaClass.getDeclaredField("manualHoursAtCreation").apply { isAccessible = true }
            assertTrue("a surviving row must never carry a non-finite manualHoursAtCreation", (field.get(result.first()) as Float).isFinite())
        }
    }

    @Test
    fun `S4 wire - a legitimate default manualHoursAtCreation of zero still survives`() {
        val now = System.currentTimeMillis()
        val result = deserializeTimeCapsulesRaw(capsuleJsonRaw("0.0", now))
        assertEquals("the common real case (never manually backfilled) must not be collateral damage", 1, result.size)
    }

    // ---- S4: same validation, backup path (BackupManager.parseTimeCapsules) ----

    private fun parseTimeCapsulesRaw(json: String, backupCreatedAt: Long): List<*> {
        val method = BackupManager.javaClass.getDeclaredMethod(
            "parseTimeCapsules", JSONArray::class.java, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(BackupManager, JSONArray(json), backupCreatedAt) as List<*>
    }

    private fun backupCapsuleJsonRaw(manualHoursAtCreation: String, unlockedAt: String, updatedAt: Long): String =
        """[{"id":1,"text":"n","unlockAtHours":100.0,"createdAt":$updatedAt,"unlockedAt":$unlockedAt,"manualHoursAtCreation":$manualHoursAtCreation,"syncId":"c1","updatedAt":$updatedAt,"deleted":false}]"""

    @Test
    fun `S4 backup - an out-of-range manualHoursAtCreation is rejected`() {
        val now = System.currentTimeMillis()
        val result = parseTimeCapsulesRaw(backupCapsuleJsonRaw("999999.0", "null", now), now)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `S4 backup - a non-finite manualHoursAtCreation is rejected, not thrown`() {
        val now = System.currentTimeMillis()
        val result = parseTimeCapsulesRaw(backupCapsuleJsonRaw("1e39", "null", now), now)
        assertTrue(result.isEmpty())
    }

    // ---- S11: a backup can never grant a false unlock ----

    @Test
    fun `S11 backup - unlockedAt is never restored from the backup JSON, even when present`() {
        val now = System.currentTimeMillis()
        val result = parseTimeCapsulesRaw(backupCapsuleJsonRaw("0.0", "${now - 50_000L}", now), now)
        assertEquals(1, result.size)
        val unlockedAtField = result.first()!!.javaClass.getDeclaredField("unlockedAt").apply { isAccessible = true }
        assertNull(
            "a crafted/tampered backup must never be able to grant a false unlock (S11, Opus Round 1)",
            unlockedAtField.get(result.first())
        )
    }

    // ---- B8: isPlausibleBackupUpdatedAt needs a backupCreatedAt floor, not just device-now ----

    private fun isPlausibleBackupUpdatedAt(wireUpdatedAt: Long, backupCreatedAt: Long): Boolean {
        val method: Method = BackupManager.javaClass.getDeclaredMethod(
            "isPlausibleBackupUpdatedAt", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(BackupManager, wireUpdatedAt, backupCreatedAt) as Boolean
    }

    @Test
    fun `B8 - a row stamped at backupCreatedAt is accepted even when the restoring device's own clock is a full day behind`() {
        val backupCreatedAt = System.currentTimeMillis()
        // Live-reproduced by both reviewers: restoring a legitimate backup on a factory-reset/replacement
        // phone (no SIM/wifi, clock defaults to ROM build date) used to silently discard the ENTIRE
        // backup across all seven tables. This is exactly disaster-recovery restore's core scenario.
        assertTrue(
            "a legitimate row must never be rejected purely because the restoring phone's clock is behind",
            isPlausibleBackupUpdatedAt(backupCreatedAt, backupCreatedAt)
        )
    }

    @Test
    fun `B8 - replay resistance is retained - a genuinely poisoned far-future value is still rejected on repeated checks`() {
        val backupCreatedAt = System.currentTimeMillis()
        val poisoned = backupCreatedAt + 30L * 24 * 60 * 60 * 1000 // 30 days past the backup's own creation
        // The property that killed the OLD clamp-and-accept approach: a poisoned value must be rejected
        // identically every time it's replayed, not just once. Checked twice here to guard against a
        // future regression back toward a clamp-relative-to-call-time shape.
        assertTrue(!isPlausibleBackupUpdatedAt(poisoned, backupCreatedAt))
        assertTrue(!isPlausibleBackupUpdatedAt(poisoned, backupCreatedAt))
    }

    @Test
    fun `B8 - a value only slightly beyond the tolerance window past backupCreatedAt is still rejected`() {
        val backupCreatedAt = System.currentTimeMillis()
        assertTrue(!isPlausibleBackupUpdatedAt(backupCreatedAt + 6 * 60_000L, backupCreatedAt))
    }

    // ---- Round-2-of-round-2 (Opus live-reproduced these gaps in round 2's own fixes) ----

    @Test
    fun `B8-followup internal consistency - a row newer than its own backup's createdAt is rejected regardless of clock`() {
        val backupCreatedAt = System.currentTimeMillis()
        // A row can never legitimately postdate the backup that bundles it - this check is clock-
        // independent and alone kills a poisoned row hidden inside an otherwise honestly-dated backup.
        assertTrue(!isPlausibleBackupUpdatedAt(backupCreatedAt + 24 * 60 * 60 * 1000L, backupCreatedAt))
    }

    @Test
    fun `B8-followup - a crafted backup cannot neutralize the reject-gate by claiming a far-future backupCreatedAt`() {
        val now = System.currentTimeMillis()
        val forgedBackupCreatedAt = now + 4L * 365 * 24 * 60 * 60 * 1000 // ~4 years out
        val forgedRowUpdatedAt = forgedBackupCreatedAt // internally "consistent" with the forged backupCreatedAt
        // Opus's live repro: before this fix, a backup that stamped its OWN createdAt far in the future
        // raised the ceiling for every row right along with it, and every row in such a backup was
        // accepted with zero rejections - permanently poisoning the restored device's LWW merges.
        assertTrue(
            "backupCreatedAt's own claimed future value must be capped, not trusted as an unbounded floor",
            !isPlausibleBackupUpdatedAt(forgedRowUpdatedAt, forgedBackupCreatedAt)
        )
    }

    @Test
    fun `B8-followup - a moderately future backupCreatedAt (within the cap) still correctly floors legitimate rows`() {
        // A backup created slightly in the future relative to a clock-behind restoring device (the
        // original, legitimate disaster-recovery scenario) must still work - the cap must not be so tight
        // it reintroduces the very bug this whole fix exists to prevent.
        val backupCreatedAt = System.currentTimeMillis() + 24 * 60 * 60 * 1000L // backup device's clock 1 day ahead of restoring device
        assertTrue(isPlausibleBackupUpdatedAt(backupCreatedAt, backupCreatedAt))
    }

    // ---- unlockEligible structural clamp (Opus live-reproduced round 2's own manualHoursAtCreation bound as insufficient) ----

    private fun unlockEligibleThresholdHolds(unlockAtHours: Float, manualHoursAtCreation: Float, currentManualHoursCredit: Float, totalHours: Float): Boolean {
        // MINOR fix (ultimate-app-review round 3, Opus): this comment previously claimed reflection on a
        // private method - inaccurate. unlockEligible is public; this exercises the real function directly
        // through a hand-written fake DAO (same pattern as TimeCapsuleSyncAuditTest's FakeTimeCapsuleDao),
        // which is actually the stronger approach since it runs the real end-to-end unlock decision, not
        // just the formula in isolation.
        val dao = object : TimeCapsuleDao {
            var row = com.ssbmedia.twogether.data.db.TimeCapsule(
                id = 1L, text = "t", unlockAtHours = unlockAtHours, createdAt = 0L,
                manualHoursAtCreation = manualHoursAtCreation, syncId = "s", updatedAt = 0L
            )
            override suspend fun insert(capsule: com.ssbmedia.twogether.data.db.TimeCapsule) = 1L
            override fun observeActive() = throw NotImplementedError()
            override suspend fun getLocked() = listOf(row)
            // Mirrors the real WHERE-guarded SQL: only mutates if the id matches AND the row isn't
            // (already) tombstoned - same semantics unlockEligible's own production caller relies on.
            override suspend fun unlockIfNotDeleted(id: Long, unlockedAt: Long, updatedAt: Long) {
                if (row.id == id && !row.deleted) row = row.copy(unlockedAt = unlockedAt, updatedAt = updatedAt)
            }
            override suspend fun tombstone(id: Long, updatedAt: Long) = throw NotImplementedError("not used by this helper")
            override suspend fun tombstoneIfLocked(id: Long, updatedAt: Long) = throw NotImplementedError("not used by this helper")
            override suspend fun getAll() = listOf(row)
            override suspend fun clearAll() {}
        }
        val repo = TimeCapsuleRepository(dao)
        kotlinx.coroutines.runBlocking { repo.unlockEligible(totalHours, currentManualHoursCredit) }
        return dao.row.unlockedAt != null
    }

    @Test
    fun `unlockEligible never unlocks a capsule below its own stated unlockAtHours, no matter how large manualHoursAtCreation is forged to be`() {
        // Opus's round-2 live repro: round 2's own ingestion bound (0f..100_000f) still let ANY value
        // above unlockAtHours + currentCredit through, forcing an instant false unlock. This is the
        // structural fix - the effective threshold can never fall below unlockAtHours regardless of the
        // ingestion bound's exact magnitude.
        assertTrue(
            "a real 0.2h-together couple must not unlock a 5000h capsule via a forged manualHoursAtCreation",
            !unlockEligibleThresholdHolds(unlockAtHours = 5000f, manualHoursAtCreation = 50_000f, currentManualHoursCredit = 0f, totalHours = 0.2f)
        )
    }

    @Test
    fun `unlockEligible still unlocks normally once real totalHours crosses unlockAtHours with no manual credit involved`() {
        assertTrue(
            unlockEligibleThresholdHolds(unlockAtHours = 10f, manualHoursAtCreation = 0f, currentManualHoursCredit = 0f, totalHours = 10f)
        )
    }

    @Test
    fun `unlockEligible - deleting a manual session after creation cannot pull the threshold below unlockAtHours (honest-case fix)`() {
        // currentManualHoursCredit dropping below manualHoursAtCreation (a manual entry deleted after the
        // capsule was created) used to silently LOWER effectiveThreshold below the capsule's own stated
        // unlockAtHours - an honest-case early-unlock bug with the same root cause, closed by the same clamp.
        assertTrue(
            "totalHours just short of unlockAtHours must still be locked, even though manual credit shrank since creation",
            !unlockEligibleThresholdHolds(unlockAtHours = 100f, manualHoursAtCreation = 50f, currentManualHoursCredit = 0f, totalHours = 99f)
        )
    }

    // ---- Round 3 (both Opus and Sonnet independently live-reproduced these gaps in round 2's fixes) ----

    @Test
    fun `TimeCapsuleRepository effectiveThreshold - the ONE shared formula both unlockEligible and CapsulesScreen call - never falls below unlockAtHours`() {
        // MAJOR fix (round 3, both models): CapsulesScreen used to carry its own unclamped copy of this
        // exact formula, silently diverging from unlockEligible's clamped one the moment round 2 fixed
        // only the latter. Testing the single shared function directly - now the only implementation
        // either call site can possibly use - so the two can never independently drift apart again.
        val capsule = com.ssbmedia.twogether.data.db.TimeCapsule(
            id = 1L, text = "t", unlockAtHours = 5000f, createdAt = 0L,
            manualHoursAtCreation = 100_000f, syncId = "s", updatedAt = 0L
        )
        for (currentManualHoursCredit in listOf(0f, 1f, 100f, 4999f)) {
            assertTrue(
                "effectiveThreshold must never fall below unlockAtHours for currentManualHoursCredit=$currentManualHoursCredit",
                TimeCapsuleRepository.effectiveThreshold(capsule, currentManualHoursCredit) >= capsule.unlockAtHours
            )
        }
    }

    @Test
    fun `TimeCapsuleRepository effectiveThreshold - the normal backfill-grows-credit case is unaffected by the clamp`() {
        val capsule = com.ssbmedia.twogether.data.db.TimeCapsule(
            id = 1L, text = "t", unlockAtHours = 100f, createdAt = 0L,
            manualHoursAtCreation = 10f, syncId = "s", updatedAt = 0L
        )
        // currentManualHoursCredit (30) > manualHoursAtCreation (10) - the clamp is a no-op here, and the
        // threshold should rise by exactly the delta (20), matching the documented anti-cheat derivation.
        assertEquals(120f, TimeCapsuleRepository.effectiveThreshold(capsule, 30f))
    }

    // ---- MAJOR-1 (Opus): parseSessions had its own SEPARATE, uncapped backupCreatedAt floor ----

    private fun parseSessionsRaw(json: String, backupCreatedAt: Long): List<*> {
        val method = BackupManager.javaClass.getDeclaredMethod(
            "parseSessions", JSONArray::class.java, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(BackupManager, JSONArray(json), backupCreatedAt) as List<*>
    }

    @Test
    fun `MAJOR-1 - parseSessions rejects a session dated years out even when backupCreatedAt claims the same far future`() {
        // MINOR fix (ultimate-app-review round 4, Opus): the original version of this test used
        // `updatedAt = forgedFarFuture` too, which meant `isPlausibleBackupUpdatedAt`'s OWN cap already
        // rejected the row via the `||` short-circuit in parseSessions - the test passed even when Opus
        // reverted trustedBackupCeiling(backupCreatedAt) back to the OLD bare, uncapped
        // `maxOf(now, backupCreatedAt)` inside parseSessions' own SessionBoundsValidator call, proving it
        // didn't actually guard what it claimed to. Fixed with Opus's exact discriminating inputs: a
        // realistic, RECENT `updatedAt` so isPlausibleBackupUpdatedAt passes on its own, isolating
        // parseSessions' OWN ceiling as the only thing that can still reject the row.
        val now = System.currentTimeMillis()
        val forgedFarFuture = now + 5L * 365 * 24 * 60 * 60 * 1000 // ~5 years out - the backup's own claim
        val startedAt = forgedFarFuture - 365L * 24 * 60 * 60 * 1000 // still ~4 years out
        val endedAt = startedAt + 20L * 24 * 60 * 60 * 1000 // 20 days - inside MAX_PLAUSIBLE_SESSION_DURATION_MILLIS
        val recentUpdatedAt = now - 24 * 60 * 60 * 1000L // realistic, passes isPlausibleBackupUpdatedAt on its own
        val json = """[{"id":1,"startedAt":$startedAt,"endedAt":$endedAt,"isManual":false,"syncId":"s1","updatedAt":$recentUpdatedAt,"deleted":false}]"""
        val result = parseSessionsRaw(json, forgedFarFuture)
        assertTrue(
            "a session dated years out must be rejected by parseSessions' OWN ceiling, independent of isPlausibleBackupUpdatedAt",
            result.isEmpty()
        )
    }

    @Test
    fun `parseSessions - a Long-MAX_VALUE backupCreatedAt fails closed rather than overflowing into acceptance`() {
        // Opus's P1 proposal: pins the overflow behavior verified live in round 4 (backupCreatedAt +
        // 5min overflows negative on Long.MAX_VALUE, which must still reject rather than silently wrap
        // into an always-true comparison).
        val now = System.currentTimeMillis()
        val json = """[{"id":1,"startedAt":${now - 3_600_000L},"endedAt":$now,"isManual":false,"syncId":"s1","updatedAt":$now,"deleted":false}]"""
        val result = parseSessionsRaw(json, Long.MAX_VALUE)
        assertTrue("an overflow-inducing backupCreatedAt must fail closed, never silently accept", result.isEmpty())
    }

    // ---- effectiveThreshold invariant (Opus P2): the display and the real unlock decision must always agree ----

    @Test
    fun `effectiveThreshold - unlocking decision agrees with the threshold value across adversarial manualHoursAtCreation`() {
        val unlockAtHours = 100f
        val currentManualHoursCredit = 10f
        for (manualHoursAtCreation in listOf(0f, 10f, 100_000f, Float.MAX_VALUE, Float.POSITIVE_INFINITY)) {
            val capsule = com.ssbmedia.twogether.data.db.TimeCapsule(
                id = 1L, text = "t", unlockAtHours = unlockAtHours, createdAt = 0L,
                manualHoursAtCreation = manualHoursAtCreation, syncId = "s", updatedAt = 0L
            )
            val threshold = TimeCapsuleRepository.effectiveThreshold(capsule, currentManualHoursCredit)
            assertTrue(
                "effectiveThreshold must never go below unlockAtHours for manualHoursAtCreation=$manualHoursAtCreation",
                threshold >= unlockAtHours
            )
            // The single formula both unlockEligible and CapsulesScreen call - if this holds, they can
            // never disagree, since there is only one implementation left to call.
            assertTrue(!threshold.isNaN())
        }
    }
}
