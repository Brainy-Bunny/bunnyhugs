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

    private fun itemIn(listId: String, done: Boolean = false, deleted: Boolean = false) =
        DateIdea(id = "$listId-$done-$deleted", text = "x", listId = listId, done = done, updatedAt = 1L, deleted = deleted)

    /** The reported case: a list whose every item is done must not fire its default reminder. */
    @Test fun listWithAllItemsDoneStaysQuiet() {
        val ideas = listOf(itemIn("L1", done = true), itemIn("L1", done = true))
        assertFalse(ProximityForegroundService.listDefaultReminderHasOpenItems("L1", ideas))
    }

    @Test fun listWithOneOpenItemStillFires() {
        val ideas = listOf(itemIn("L1", done = true), itemIn("L1"))
        assertTrue(ProximityForegroundService.listDefaultReminderHasOpenItems("L1", ideas))
    }

    /** Done/deleted items in other lists don't affect this list. */
    @Test fun otherListsItemsAreIgnored() {
        val ideas = listOf(itemIn("L1", done = true), itemIn("L2"))
        assertFalse(ProximityForegroundService.listDefaultReminderHasOpenItems("L1", ideas))
    }

    /** Deleted items don't count as open, so a list whose only open item was deleted goes quiet. */
    @Test fun deletedItemsDoNotCountAsOpen() {
        val ideas = listOf(itemIn("L1", done = true), itemIn("L1", deleted = true))
        assertFalse(ProximityForegroundService.listDefaultReminderHasOpenItems("L1", ideas))
    }

    /** An empty list keeps its previous behaviour: nothing was ever checked off, so the alert still fires. */
    @Test fun emptyListStillFires() {
        assertTrue(ProximityForegroundService.listDefaultReminderHasOpenItems("L1", emptyList()))
    }

    /** The photo-alarm-as-alarm spec: an expired snooze rings again as a normal snooze while still together. */
    @Test fun expiredSnoozeRingsAgainWhileTogether() {
        assertTrue(ProximityForegroundService.isStillTogetherForSnoozeRing(isTogether = true))
    }

    /** The reported case: snoozed for 10, actually apart by the time it runs out. One final "you missed your
     * chance" ring, no snooze option. Fable review round 2: this must NOT be swallowed by the session-grace
     * window (pendingApartSince > 0) - that window defaults to about the same length as the snooze itself,
     * which made this branch unreachable in practice. isTogether alone is the right signal: the state machine
     * only flips it after absenceTimeoutMillis (~100s) of confirmed absence, so it's already debounced. */
    @Test fun expiredSnoozeIsFinalRingWhenGenuinelyApart() {
        assertFalse(ProximityForegroundService.isStillTogetherForSnoozeRing(isTogether = false))
    }
}
