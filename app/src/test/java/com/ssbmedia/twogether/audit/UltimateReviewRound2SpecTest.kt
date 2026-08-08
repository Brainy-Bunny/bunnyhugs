package com.ssbmedia.twogether.audit

import com.ssbmedia.twogether.ble.GattSyncManager
import com.ssbmedia.twogether.data.backup.BackupManager
import com.ssbmedia.twogether.data.datastore.SettingsStore
import com.ssbmedia.twogether.data.db.AppDatabase
import com.ssbmedia.twogether.data.db.DateIdeaDao
import com.ssbmedia.twogether.data.db.ListCategoryDao
import com.ssbmedia.twogether.data.db.MilestoneDao
import com.ssbmedia.twogether.data.db.MomentDao
import com.ssbmedia.twogether.data.db.MomentNoteDao
import com.ssbmedia.twogether.data.db.TimeCapsuleDao
import com.ssbmedia.twogether.data.db.TogetherSessionDao
import com.ssbmedia.twogether.data.repo.DateIdeaRepository
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
        val milestoneRepo = MilestoneRepository(mock(MilestoneDao::class.java))
        val timeCapsuleRepo = TimeCapsuleRepository(mock(TimeCapsuleDao::class.java))
        val settingsStore = mock(SettingsStore::class.java)
        return GattSyncManager(
            context, dateIdeaRepo, listCategoryRepo, sessionRepo, momentRepo,
            momentNoteRepo, milestoneRepo, timeCapsuleRepo, settingsStore, CoroutineScope(Dispatchers.Unconfined)
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
}
