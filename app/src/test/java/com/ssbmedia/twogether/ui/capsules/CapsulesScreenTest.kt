package com.ssbmedia.twogether.ui.capsules

import com.ssbmedia.twogether.data.db.TimeCapsule
import com.ssbmedia.twogether.data.repo.TimeCapsuleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * UX-FIX-PLAN.md Phase 2 item 11: Time Capsule's persistent creation/unlock timeline text. The bug being
 * fixed was that a capsule's creation/unlock info used to blank out entirely once it unlocked (the
 * Unlocked card branch only ever rendered the note text) - these tests assert the timeline survives
 * BOTH pre- and post-unlock, plus that [TimeCapsuleRepository.effectiveThreshold] (the same formula the
 * real lock/unlock decision uses) is what the timeline's hours figure is built from.
 */
class CapsulesScreenTest {

    private val zone = ZoneOffset.UTC
    private val EPS = 1e-4f

    private fun millisAt(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        java.time.LocalDateTime.of(y, mo, d, h, mi).toInstant(zone).toEpochMilli()

    @Test
    fun `locked capsule shows sealed line but no opened line`() {
        val capsule = TimeCapsule(
            id = 1,
            text = "secret",
            unlockAtHours = 50f,
            createdAt = millisAt(2026, 1, 5),
            unlockedAt = null,
            manualHoursAtCreation = 0f
        )
        val effectiveThreshold = TimeCapsuleRepository.effectiveThreshold(capsule, currentManualHoursCredit = 0f)
        val timeline = buildCapsuleTimelineText(capsule, effectiveThreshold, zone)

        assertEquals("Sealed on 05 01 2026 · unlocks after 50h together", timeline.sealedLine)
        assertNull("A still-locked capsule must not show an Opened line yet", timeline.openedLine)
    }

    @Test
    fun `unlocked capsule keeps showing sealed line AND now shows opened line - the reported bug`() {
        val capsule = TimeCapsule(
            id = 2,
            text = "secret",
            unlockAtHours = 50f,
            createdAt = millisAt(2026, 1, 5),
            unlockedAt = millisAt(2026, 3, 20),
            manualHoursAtCreation = 0f
        )
        val effectiveThreshold = TimeCapsuleRepository.effectiveThreshold(capsule, currentManualHoursCredit = 0f)
        val timeline = buildCapsuleTimelineText(capsule, effectiveThreshold, zone)

        // BUG fix under test: this info must NOT disappear once unlocked.
        assertEquals("Sealed on 05 01 2026 · unlocks after 50h together", timeline.sealedLine)
        assertEquals("Opened on 20 03 2026", timeline.openedLine)
    }

    @Test
    fun `timeline hours figure reflects the effective (anti-cheat-adjusted) threshold, not the raw one`() {
        val capsule = TimeCapsule(
            id = 3,
            text = "secret",
            unlockAtHours = 50f,
            createdAt = millisAt(2026, 1, 5),
            unlockedAt = null,
            manualHoursAtCreation = 10f
        )
        // Manual-hours credit grew by 15h since creation (10 -> 25) - effective threshold should move by
        // the same +15h, exactly like TimeCapsuleRepository.unlockEligible's own real decision.
        val effectiveThreshold = TimeCapsuleRepository.effectiveThreshold(capsule, currentManualHoursCredit = 25f)
        assertEquals(65f, effectiveThreshold, EPS)

        val timeline = buildCapsuleTimelineText(capsule, effectiveThreshold, zone)
        assertTrue(timeline.sealedLine.contains("unlocks after 65h together"))
    }
}
