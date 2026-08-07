package com.ssbmedia.twogether.audit

import android.content.Context
import com.ssbmedia.twogether.ble.GattSyncManager
import com.ssbmedia.twogether.data.datastore.SettingsStore
import com.ssbmedia.twogether.data.db.AppDatabase
import com.ssbmedia.twogether.data.db.DateIdeaDao
import com.ssbmedia.twogether.data.db.ListCategoryDao
import com.ssbmedia.twogether.data.db.MilestoneDao
import com.ssbmedia.twogether.data.db.MomentDao
import com.ssbmedia.twogether.data.db.MomentNoteDao
import com.ssbmedia.twogether.data.db.TogetherSessionDao
import com.ssbmedia.twogether.data.repo.DateIdeaRepository
import com.ssbmedia.twogether.data.repo.ListCategoryRepository
import com.ssbmedia.twogether.data.repo.MilestoneRepository
import com.ssbmedia.twogether.data.repo.MomentNoteRepository
import com.ssbmedia.twogether.data.repo.MomentRepository
import com.ssbmedia.twogether.data.repo.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * ultimate-app-review spec-tests (Step 2/Step 3/Step 4, 2026-08-07) - independent tests derived from
 * checklist.md's top invariant ("a forged/replayed message must never delete or corrupt a real
 * local item") and F/S items covering the live-verified data-loss issues this loop found and fixed
 * in GattSyncManager: a forged/future `updatedAt` permanently winning every LWW merge, a
 * forged/implausible session bound corrupting all-time stats and unlocking every time capsule
 * (BLOCKERs), and Fable's F-4 finding that the fix for those two ALSO silently discarded honest data
 * from a partner phone whose clock is merely wrong, forever, with the UI still reporting "Synced!"
 * (MAJOR - see the peerClockOffsetMillis correction this file's later tests cover).
 * Written independently from the fix itself (these assert the SPEC, not the implementation's own
 * reasoning) and via reflection since the merge functions are (correctly) private to GattSyncManager;
 * do not weaken/delete on a later change without also updating the fix these guard. See
 * app/build.gradle.kts for why this module needs a real org.json jar and Mockito to run at all - the
 * Android unit-test stub throws for both otherwise.
 */
class GattSyncMergeSecurityAuditTest {

    private fun newManager(): GattSyncManager {
        val context = mock(Context::class.java)
        val dateIdeaRepo = DateIdeaRepository(mock(DateIdeaDao::class.java))
        val listCategoryRepo = ListCategoryRepository(
            mock(ListCategoryDao::class.java), dateIdeaRepo, mock(AppDatabase::class.java)
        )
        val sessionRepo = SessionRepository(mock(TogetherSessionDao::class.java))
        val momentRepo = MomentRepository(mock(MomentDao::class.java), context)
        val momentNoteRepo = MomentNoteRepository(mock(MomentNoteDao::class.java))
        val milestoneRepo = MilestoneRepository(mock(MilestoneDao::class.java))
        // Mocked, not constructed for real: SettingsStore's `settings` property initializer eagerly
        // touches context.settingsDs (a DataStore-backed extension property) at construction time,
        // which needs a real Context - irrelevant here since none of the merge functions under test
        // read settingsStore at all.
        val settingsStore = mock(SettingsStore::class.java)
        return GattSyncManager(
            context, dateIdeaRepo, listCategoryRepo, sessionRepo, momentRepo,
            momentNoteRepo, milestoneRepo, settingsStore, CoroutineScope(Dispatchers.Unconfined)
        )
    }

    private fun <T> invokePrivate(target: Any, name: String, vararg args: Any?): T {
        val paramTypes = args.map {
            when (it) {
                is Long -> Long::class.javaPrimitiveType
                is JSONArray, null -> JSONArray::class.java
                else -> it::class.java
            }
        }.toTypedArray()
        val method = target.javaClass.getDeclaredMethod(name, *paramTypes)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(target, *args) as T
    }

    private fun deserializeSessions(manager: GattSyncManager, arr: JSONArray, peerClockOffsetMillis: Long = 0L): List<*> =
        invokePrivate(manager, "deserializeSessions", arr, peerClockOffsetMillis)

    private fun deserializeDateIdeas(manager: GattSyncManager, arr: JSONArray, peerClockOffsetMillis: Long = 0L): List<*> =
        invokePrivate(manager, "deserializeDateIdeas", arr, peerClockOffsetMillis)

    private fun sessionJson(
        syncId: String, startedAt: Long, endedAt: Long, updatedAt: Long, deleted: Boolean = false
    ): JSONArray = JSONArray().put(
        JSONObject().apply {
            put("syncId", syncId)
            put("startedAt", startedAt)
            put("endedAt", endedAt)
            put("isManual", false)
            put("updatedAt", updatedAt)
            put("deleted", deleted)
        }
    )

    @Test
    fun `legitimate closed session survives the merge`() {
        val now = System.currentTimeMillis()
        val arr = sessionJson("s1", now - 3_600_000L, now - 1_800_000L, now)
        val result = deserializeSessions(newManager(), arr)
        assertEquals(1, result.size)
    }

    @Test
    fun `forged far-future endedAt is rejected, not clamped and accepted`() {
        val now = System.currentTimeMillis()
        val arr = sessionJson("poison", now - 100_000L, now + 999_999_999_999L, now)
        val result = deserializeSessions(newManager(), arr)
        assertTrue("a forged future endedAt must never merge in", result.isEmpty())
    }

    @Test
    fun `endedAt before startedAt is rejected`() {
        val now = System.currentTimeMillis()
        val arr = sessionJson("backwards", now, now - 60_000L, now)
        val result = deserializeSessions(newManager(), arr)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `implausibly long session duration is rejected`() {
        val now = System.currentTimeMillis()
        val arr = sessionJson("marathon", now - 40L * 24 * 3_600_000L, now, now)
        val result = deserializeSessions(newManager(), arr)
        assertTrue("a 40-day 'session' must never merge in and inflate all-time stats", result.isEmpty())
    }

    @Test
    fun `forged updatedAt on an otherwise-plausible session is still rejected`() {
        val now = System.currentTimeMillis()
        val arr = sessionJson("s2", now - 3_600_000L, now - 1_800_000L, now + 999_999_999_999L)
        val result = deserializeSessions(newManager(), arr)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `replaying the same poisoned session repeatedly never lets it win`() {
        // Regression test for the round-3 self-heal fix: reject-not-clamp means a re-sent poisoned
        // row is dropped identically every time, not re-clamped to a fresh later ceiling each round.
        val now = System.currentTimeMillis()
        val arr = sessionJson("poison", now - 100_000L, now + 999_999_999_999L, now + 999_999_999_999L)
        val manager = newManager()
        repeat(3) {
            val result = deserializeSessions(manager, arr)
            assertTrue("replay #$it of the same poisoned row must still be rejected", result.isEmpty())
        }
    }

    @Test
    fun `isPlausibleWireUpdatedAt accepts now and small future skew, rejects far future`() {
        val now = System.currentTimeMillis()
        val manager = newManager()
        assertTrue(invokePrivate<Boolean>(manager, "isPlausibleWireUpdatedAt", now))
        assertTrue(invokePrivate<Boolean>(manager, "isPlausibleWireUpdatedAt", now + 60_000L))
        assertTrue(
            "a forged updatedAt far in the future must be rejected",
            !invokePrivate<Boolean>(manager, "isPlausibleWireUpdatedAt", now + 999_999_999_999L)
        )
    }

    @Test
    fun `date idea with forged future updatedAt is dropped from the merge`() {
        val now = System.currentTimeMillis()
        val legit = JSONObject().apply {
            put("id", "d1"); put("text", "Picnic"); put("listId", "default")
            put("done", false); put("updatedAt", now); put("deleted", false)
        }
        val poisoned = JSONObject().apply {
            put("id", "d2"); put("text", "Poisoned"); put("listId", "default")
            put("done", false); put("updatedAt", now + 999_999_999_999L); put("deleted", true)
        }
        val arr = JSONArray().put(legit).put(poisoned)
        val result = deserializeDateIdeas(newManager(), arr)
        assertEquals("only the legitimate idea should survive the merge", 1, result.size)
    }

    // Below: proposed by the fresh Sonnet reviewer that re-verified the URL-validation fix and
    // critiqued this file's coverage (ultimate-app-review Step 4 localized-fix routing).

    @Test
    fun `isPlausibleWireUpdatedAt boundary - just inside vs just outside the skew tolerance`() {
        // MAX_CLOCK_SKEW_TOLERANCE_MILLIS = 5 minutes and the real check reads System.currentTimeMillis()
        // itself, so an exact-millisecond boundary against a `now` captured in the test is inherently
        // flaky (two separate clock reads, real wall-clock drift between them). A 2s safety margin on
        // each side is well within the 5-minute tolerance being tested but well outside realistic
        // test-execution drift.
        val now = System.currentTimeMillis()
        val manager = newManager()
        assertTrue(invokePrivate<Boolean>(manager, "isPlausibleWireUpdatedAt", now + 5 * 60_000L - 2_000L))
        assertTrue(
            "clearly past the skew tolerance must be rejected",
            !invokePrivate<Boolean>(manager, "isPlausibleWireUpdatedAt", now + 5 * 60_000L + 2_000L)
        )
    }

    @Test
    fun `a legitimate newer record still wins the merge`() {
        // Positive control for the reject-not-clamp fix: rejection must be specific to implausible
        // values, not an accidental blanket rejection of anything newer than what's already local.
        val now = System.currentTimeMillis()
        val arr = sessionJson("s3", now - 3_600_000L, now - 1_800_000L, now)
        val result = deserializeSessions(newManager(), arr)
        assertEquals(1, result.size)
    }

    // Below: Fable's F-4 spec-test ("Benign clock skew must not silently discard a partner's data"),
    // ultimate-app-review Step 4 systemic-finding routing - restart round. attacks.md's live evidence:
    // a partner phone 1 hour fast had every honest edit silently dropped, forever, while the UI still
    // said "Synced!". Fixed via peerClockOffsetMillis (derived from the payload's own deviceTimestamp
    // field, applyPayload's doc) subtracted from every wire timestamp before either the plausibility
    // check or storage - these tests exercise that correction directly against the same deserialize
    // functions Fable attacked, not a reimplementation of the fix's own logic.

    @Test
    fun `a legitimate row from a device whose clock is 1 hour fast is still accepted, corrected into our frame`() {
        val now = System.currentTimeMillis()
        val peerOffset = 60 * 60_000L // peer's clock reads 1h ahead of ours
        val peerNow = now + peerOffset
        val legit = JSONObject().apply {
            put("id", "d1"); put("text", "Legit from fast-clock peer"); put("listId", "default")
            put("done", false); put("updatedAt", peerNow); put("deleted", false)
        }
        val arr = JSONArray().put(legit)
        val result = deserializeDateIdeas(newManager(), arr, peerOffset)
        assertEquals("a merely-skewed peer's honest edit must not be silently dropped", 1, result.size)
    }

    @Test
    fun `a legitimate row from a device whose clock is 1 hour slow is still accepted`() {
        val now = System.currentTimeMillis()
        val peerOffset = -60 * 60_000L // peer's clock reads 1h behind ours
        val peerNow = now + peerOffset
        val legit = JSONObject().apply {
            put("id", "d1"); put("text", "Legit from slow-clock peer"); put("listId", "default")
            put("done", false); put("updatedAt", peerNow); put("deleted", false)
        }
        val arr = JSONArray().put(legit)
        val result = deserializeDateIdeas(newManager(), arr, peerOffset)
        assertEquals(1, result.size)
    }

    @Test
    fun `a genuinely forged far-future value from a skewed peer is still rejected, not laundered by the correction`() {
        // Adversarial control (Fable assertion 4): the correction must not become a new bypass - a
        // value that's implausible even AFTER subtracting the peer's own observed offset must still
        // be refused.
        val now = System.currentTimeMillis()
        val peerOffset = 60 * 60_000L
        val forged = JSONObject().apply {
            put("id", "d1"); put("text", "Still poisoned"); put("listId", "default")
            put("done", false); put("updatedAt", now + peerOffset + 999_999_999_999L); put("deleted", true)
        }
        val arr = JSONArray().put(forged)
        val result = deserializeDateIdeas(newManager(), arr, peerOffset)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a skewed peer's closed session bounds are corrected and accepted`() {
        val now = System.currentTimeMillis()
        val peerOffset = 60 * 60_000L
        val arr = sessionJson("skewed-1", now + peerOffset - 3_600_000L, now + peerOffset - 1_800_000L, now + peerOffset)
        val result = deserializeSessions(newManager(), arr, peerOffset)
        assertEquals("a skewed-but-honest session must not be rejected as implausible", 1, result.size)
    }

    @Test
    fun `replaying the same poisoned payload against a skewed peer still never lets it win`() {
        // Replay-resistance (Fable's repeated-attack pattern) must survive even with a nonzero peer
        // offset in play - reject-not-clamp, not clamp-to-a-later-ceiling-each-round.
        val now = System.currentTimeMillis()
        val peerOffset = 60 * 60_000L
        val arr = sessionJson("poison-skewed", now + peerOffset - 100_000L, now + peerOffset + 999_999_999_999L, now + peerOffset + 999_999_999_999L)
        val manager = newManager()
        repeat(3) {
            val result = deserializeSessions(manager, arr, peerOffset)
            assertTrue("replay #$it against a skewed peer must still be rejected", result.isEmpty())
        }
    }

    @Test
    fun `a corrected skewed timestamp is stored in the receivers frame, not the raw wire value`() {
        // Fable assertion 5: whatever admits the skewed row must not let it beat a genuinely-newer
        // local edit later - only true if the STORED updatedAt is the corrected value, not the raw
        // wire one (a raw-stored value would carry the peer's clock error into every future LWW
        // comparison against this row, permanently).
        val now = System.currentTimeMillis()
        val peerOffset = 60 * 60_000L
        val legit = JSONObject().apply {
            put("id", "d1"); put("text", "Legit from fast-clock peer"); put("listId", "default")
            put("done", false); put("updatedAt", now + peerOffset); put("deleted", false)
        }
        val arr = JSONArray().put(legit)
        val result = deserializeDateIdeas(newManager(), arr, peerOffset)
        val stored = (result.first() as com.ssbmedia.twogether.data.db.DateIdea).updatedAt
        assertTrue(
            "stored updatedAt ($stored) must be corrected close to our own now ($now), not the raw peer-clock value (${now + peerOffset})",
            kotlin.math.abs(stored - now) < 5_000L
        )
    }
}
