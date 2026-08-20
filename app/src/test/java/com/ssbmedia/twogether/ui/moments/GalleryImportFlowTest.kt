package com.ssbmedia.twogether.ui.moments

import com.ssbmedia.twogether.data.db.TogetherSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * UX-FIX-PLAN.md Phase 2 item 12: gallery import auto-detect "taken together" + real EXIF time-of-day.
 * Two bugs being fixed, tested independently:
 *  1. [resolveTakenAtMillis] used to hardcode noon regardless of the photo's own EXIF time.
 *  2. [resolveImportedPhotoSessionId] used to always pass sessionId=null, so a gallery-imported photo
 *     always read "Taken apart" even when the user explicitly ticked "we were together that day" and a
 *     real session existed (or was just created) for that exact window.
 */
class GalleryImportFlowTest {

    private val zone = ZoneOffset.UTC

    private fun millisAt(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(zone).toEpochMilli()

    private fun session(startedAt: Long, endedAt: Long?, isManual: Boolean = false, id: Long = 0): TogetherSession =
        TogetherSession(id = id, startedAt = startedAt, endedAt = endedAt, isManual = isManual, syncId = UUID.randomUUID().toString())

    // ---- resolveTakenAtMillis ----

    @Test
    fun `resolveTakenAtMillis keeps the real EXIF time-of-day when present`() {
        val date = LocalDate.of(2026, 3, 20)
        val exifTime = LocalTime.of(15, 42)
        val takenAt = resolveTakenAtMillis(date, exifTime, zone)
        assertEquals(millisAt(2026, 3, 20, 15, 42), takenAt)
    }

    @Test
    fun `resolveTakenAtMillis falls back to noon only when EXIF has no time component`() {
        val date = LocalDate.of(2026, 3, 20)
        val takenAt = resolveTakenAtMillis(date, null, zone)
        assertEquals(millisAt(2026, 3, 20, 12, 0), takenAt)
    }

    // ---- resolveImportedPhotoSessionId ----

    @Test
    fun `photo taken inside an existing BLE-detected session is tagged with that session - takenWhileTogether true`() {
        val takenAt = millisAt(2026, 3, 20, 15, 0)
        val existing = session(millisAt(2026, 3, 20, 14, 0), millisAt(2026, 3, 20, 16, 0), id = 7)
        val sessionId = resolveImportedPhotoSessionId(
            sessions = listOf(existing),
            takenAt = takenAt,
            manualSessionId = null
        )
        assertEquals(7L, sessionId)
    }

    @Test
    fun `photo taken inside the just-created manual session (we were together checkbox) is tagged with it`() {
        val takenAt = millisAt(2026, 3, 20, 10, 30)
        // The manual session created by this exact import's checkbox - session creation happens BEFORE
        // this lookup, so it's already visible here, same as the production onConfirm ordering.
        val justCreated = session(millisAt(2026, 3, 20, 9, 0), millisAt(2026, 3, 20, 12, 0), isManual = true, id = 11)
        val sessionId = resolveImportedPhotoSessionId(
            sessions = listOf(justCreated),
            takenAt = takenAt,
            manualSessionId = justCreated.id
        )
        assertEquals(11L, sessionId)
    }

    @Test
    fun `explicit checkbox fallback wins even if EXIF time sits just outside the typed range`() {
        // EXIF says 8:55am, but the user typed "9:00 to 11:00" for the together range - a few minutes of
        // rounding/clock-skew must not silently produce a contradictory "Taken apart" result when the
        // user explicitly said they were together that day.
        val takenAt = millisAt(2026, 3, 20, 8, 55)
        val justCreated = session(millisAt(2026, 3, 20, 9, 0), millisAt(2026, 3, 20, 11, 0), isManual = true, id = 22)
        val sessionId = resolveImportedPhotoSessionId(
            sessions = listOf(justCreated),
            takenAt = takenAt,
            manualSessionId = justCreated.id
        )
        assertEquals(22L, sessionId)
    }

    @Test
    fun `no matching session and no checkbox - stays apart (null), the honest default`() {
        val takenAt = millisAt(2026, 3, 20, 15, 0)
        val unrelated = session(millisAt(2026, 1, 1, 9, 0), millisAt(2026, 1, 1, 11, 0))
        val sessionId = resolveImportedPhotoSessionId(
            sessions = listOf(unrelated),
            takenAt = takenAt,
            manualSessionId = null
        )
        assertNull(sessionId)
    }

    @Test
    fun `open (still-ongoing) session is clamped by lastSeenAt, same as every other read in the app`() {
        // Session opened long ago and never closed; lastSeenAt is recent, so its effective end should be
        // clamped near lastSeenAt + absence timeout, not run open-ended to "now".
        val startedAt = millisAt(2026, 1, 1, 0, 0)
        val lastSeenAt = millisAt(2026, 1, 1, 1, 0)
        val open = session(startedAt, endedAt = null)
        val farAfterLastSeen = millisAt(2026, 6, 1, 0, 0)
        val sessionId = resolveImportedPhotoSessionId(
            sessions = listOf(open),
            takenAt = farAfterLastSeen,
            manualSessionId = null,
            now = farAfterLastSeen,
            lastSeenAt = lastSeenAt
        )
        assertNull("A stale open session must not claim a photo taken long after it was last confirmed", sessionId)
    }
}
