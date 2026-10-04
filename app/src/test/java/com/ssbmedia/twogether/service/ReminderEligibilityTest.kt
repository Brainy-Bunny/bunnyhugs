package com.ssbmedia.twogether.service

import com.ssbmedia.twogether.data.db.DateIdea
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Remind me X minutes after we're together" must never announce an idea that's already been checked off
 * (moved into the Completed section), or one that was deleted. */
class ReminderEligibilityTest {

    private fun idea(done: Boolean = false, deleted: Boolean = false) =
        DateIdea(id = "i1", text = "Picnic", done = done, updatedAt = 1L, deleted = deleted, remindAfterTogetherMinutes = 30)

    @Test fun activeIdeaIsEligible() {
        assertTrue(ProximityForegroundService.isReminderEligibleIdea(idea()))
    }

    /** The reported bug: a completed idea kept firing its reminder. */
    @Test fun completedIdeaIsNotEligible() {
        assertFalse(ProximityForegroundService.isReminderEligibleIdea(idea(done = true)))
    }

    @Test fun deletedIdeaIsNotEligible() {
        assertFalse(ProximityForegroundService.isReminderEligibleIdea(idea(deleted = true)))
    }
}
