package com.ssbmedia.twogether.audit

import androidx.sqlite.db.SupportSQLiteDatabase
import com.ssbmedia.twogether.data.db.AppDatabase
import com.ssbmedia.twogether.data.db.DateIdea
import com.ssbmedia.twogether.data.db.ListCategory
import com.ssbmedia.twogether.data.repo.DateIdeaRepository
import com.ssbmedia.twogether.service.ProximityForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

/**
 * Item 24 (UX-FIX-PLAN.md): configurable photo reminder interval + reminders on lists/list items.
 *
 * Covers the three pieces of Part B that are actually testable in this project's plain-JVM unit test
 * setup (no Robolectric/room-testing dependency is wired up - see app/build.gradle.kts's testImplementation
 * list, and MigrationTestHelper needs a real Android SQLite implementation neither this module nor any
 * existing test file has):
 *  - AppDatabase.MIGRATION_11_12's actual SQL, verified against a mocked SupportSQLiteDatabase (the same
 *    "run the real migrate() code, assert on what it actually executes" idea MigrationTestHelper would
 *    give, just via Mockito instead of a real SQLite file - this project already leans on Mockito+mocked
 *    Android/Room types elsewhere, see GattSyncMergeSecurityAuditTest's own doc for why).
 *  - The pure "has the reminder threshold been crossed yet" decision helper
 *    (ProximityForegroundService.listReminderThresholdCrossed), unit-tested directly via reflection -
 *    mirrors GraceWindowAuditTest's established pattern for testing a private companion function without
 *    standing up the full Android Service.
 *  - DateIdeaRepository.isPlausibleReminderMinutes, the shared bound BackupManager's untrusted-ingestion
 *    parser and the Settings/Our Lists UI dialogs both enforce.
 *  - A plain entity-level round-trip (default value + copy()) for the two new nullable Int fields, since
 *    a genuine DAO/Room round-trip test isn't reachable without the same missing test infrastructure.
 */
class ListReminderAuditTest {

    // ---------- MIGRATION_11_12 ----------
    // NOTE(merge): this migration was renumbered from the source branch's original MIGRATION_8_9 during
    // conflict resolution - this branch was already at schema v11 (independent Group J work) by the time
    // of merge, and both branches had independently used the name MIGRATION_8_9 for genuinely different
    // schema changes. See AppDatabase's @Database version doc for the full reasoning.

    @Test
    fun `MIGRATION_11_12 adds both new reminder columns as nullable INTEGER, nothing else`() {
        val db = mock(SupportSQLiteDatabase::class.java)
        AppDatabase.MIGRATION_11_12.migrate(db)

        // Reads the mock's recorded invocations directly rather than an ArgumentCaptor - Mockito's
        // ArgumentCaptor.capture() returns a Kotlin-unsound null placeholder at call time, which throws
        // an NPE the instant it's passed into a Kotlin function expecting a non-null String parameter
        // (execSQL(sql: String)), before Mockito ever gets to record the real call. Reading raw
        // Invocation.arguments (untyped Any?) sidesteps that Kotlin/Mockito interop footgun entirely.
        val statements = mockingDetails(db).invocations
            .filter { it.method.name == "execSQL" }
            .map { it.arguments[0] as String }
        assertEquals("expected exactly two execSQL calls, got: $statements", 2, statements.size)

        val dateIdeasStatement = statements.singleOrNull { it.contains("date_ideas") }
        assertTrue("expected exactly one ALTER TABLE date_ideas statement, got: $statements", dateIdeasStatement != null)
        assertTrue(dateIdeasStatement!!.contains("ADD COLUMN remindAfterTogetherMinutes"))
        assertTrue(dateIdeasStatement.contains("INTEGER"))
        // Nullable Kotlin field (Int? = null) - must NOT be NOT NULL, matching MIGRATION_9_10's own
        // documented reasoning for linkedMomentSyncId (a later app-history migration, same shape) - a
        // NOT NULL column here would fail Room's schema validation against the nullable @Entity field.
        assertFalse(dateIdeasStatement.contains("NOT NULL"))

        val listCategoriesStatement = statements.singleOrNull { it.contains("list_categories") }
        assertTrue("expected exactly one ALTER TABLE list_categories statement, got: $statements", listCategoriesStatement != null)
        assertTrue(listCategoriesStatement!!.contains("ADD COLUMN defaultRemindAfterTogetherMinutes"))
        assertTrue(listCategoriesStatement.contains("INTEGER"))
        assertFalse(listCategoriesStatement.contains("NOT NULL"))
    }

    // ---------- listReminderThresholdCrossed (pure decision helper) ----------

    private fun thresholdCrossed(thresholdMinutes: Int, elapsedMillis: Long): Boolean {
        val companion = ProximityForegroundService.Companion
        val method = companion.javaClass.getDeclaredMethod(
            "listReminderThresholdCrossed", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(companion, thresholdMinutes, elapsedMillis) as Boolean
    }

    @Test
    fun `not yet crossed just before the threshold`() {
        assertFalse(thresholdCrossed(30, 30 * 60_000L - 1))
    }

    @Test
    fun `crossed exactly at the threshold - half-open interval, same as checkPhotoReminder's own greater-or-equal`() {
        assertTrue(thresholdCrossed(30, 30 * 60_000L))
    }

    @Test
    fun `crossed well past the threshold`() {
        assertTrue(thresholdCrossed(30, 45 * 60_000L))
    }

    @Test
    fun `a zero threshold never fires - defensive against a corrupted or hand-crafted value`() {
        assertFalse(thresholdCrossed(0, 0L))
        assertFalse(thresholdCrossed(0, 999_999_999L))
    }

    @Test
    fun `a negative threshold never fires`() {
        assertFalse(thresholdCrossed(-5, 999_999_999L))
    }

    @Test
    fun `one-minute threshold fires at exactly 60 seconds`() {
        assertFalse(thresholdCrossed(1, 59_999L))
        assertTrue(thresholdCrossed(1, 60_000L))
    }

    // ---------- DateIdeaRepository.isPlausibleReminderMinutes ----------

    @Test
    fun `null is always plausible - it means no reminder set`() {
        assertTrue(DateIdeaRepository.isPlausibleReminderMinutes(null))
    }

    @Test
    fun `zero and negative minutes are rejected`() {
        assertFalse(DateIdeaRepository.isPlausibleReminderMinutes(0))
        assertFalse(DateIdeaRepository.isPlausibleReminderMinutes(-1))
        assertFalse(DateIdeaRepository.isPlausibleReminderMinutes(-1000))
    }

    @Test
    fun `a small positive value is plausible`() {
        assertTrue(DateIdeaRepository.isPlausibleReminderMinutes(1))
        assertTrue(DateIdeaRepository.isPlausibleReminderMinutes(120))
    }

    @Test
    fun `exactly the max bound is plausible, one past it is not`() {
        val max = DateIdeaRepository.MAX_REMIND_AFTER_TOGETHER_MINUTES
        assertTrue(DateIdeaRepository.isPlausibleReminderMinutes(max))
        assertFalse(DateIdeaRepository.isPlausibleReminderMinutes(max + 1))
    }

    @Test
    fun `an absurdly large or corrupted value is rejected`() {
        assertFalse(DateIdeaRepository.isPlausibleReminderMinutes(Int.MAX_VALUE))
    }

    // ---------- Entity round-trip ----------

    @Test
    fun `DateIdea defaults to no reminder, and copy() round-trips a set value`() {
        val idea = DateIdea(id = "x", text = "Picnic", updatedAt = 1L)
        assertNull("a freshly-constructed DateIdea must have no reminder by default", idea.remindAfterTogetherMinutes)

        val withReminder = idea.copy(remindAfterTogetherMinutes = 45)
        assertEquals(45, withReminder.remindAfterTogetherMinutes)

        val cleared = withReminder.copy(remindAfterTogetherMinutes = null)
        assertNull(cleared.remindAfterTogetherMinutes)
    }

    @Test
    fun `ListCategory defaults to no default reminder, and copy() round-trips a set value`() {
        val list = ListCategory(id = "l", name = "Bucket List", createdAt = 1L, updatedAt = 1L)
        assertNull(list.defaultRemindAfterTogetherMinutes)

        val withDefault = list.copy(defaultRemindAfterTogetherMinutes = 120)
        assertEquals(120, withDefault.defaultRemindAfterTogetherMinutes)
    }
}
