package com.ssbmedia.twogether.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The photo alarm stops for a photo from the current together-stretch only. An older gallery backfill doesn't. */
class PhotoStopsReminderTest {

    private val stretchStart = 1_700_000_000_000L
    private val stretch = ProximityPersistedState(
        isTogether = true,
        continuousTogetherSince = stretchStart,
        snoozeUntil = stretchStart + 600_000L,
        reminderFiredForSession = true
    )

    @Test fun photoFromThisStretchCancelsASnoozeAndStopsTheAlarm() {
        val s = stretch.afterPhotoTaken(takenAt = stretchStart + 60_000L)
        assertEquals(0L, s.snoozeUntil)
        assertTrue(s.reminderFiredForSession)
    }

    @Test fun photoTakenAtExactlyTheStretchStartCounts() {
        assertTrue(stretch.isPhotoForThisStretch(stretchStart))
    }

    /** A gallery backfill of an older day must not stop or silence the alarm. */
    @Test fun backfilledOlderPhotoLeavesTheAlarmAlone() {
        val older = stretch.afterPhotoTaken(takenAt = stretchStart - 86_400_000L)
        assertEquals(stretch, older)
        assertFalse(stretch.isPhotoForThisStretch(stretchStart - 1L))
    }

    /** With no stretch running there is nothing for a photo to stop. */
    @Test fun noStretchMeansNothingIsStopped() {
        val idle = ProximityPersistedState(continuousTogetherSince = 0L, snoozeUntil = 5L)
        assertEquals(idle, idle.afterPhotoTaken(takenAt = 1_000L))
    }
}
