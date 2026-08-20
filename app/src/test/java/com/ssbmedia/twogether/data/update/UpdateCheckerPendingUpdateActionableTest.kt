package com.ssbmedia.twogether.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 14 (UX-FIX-PLAN.md Phase 2, update-nag reach fix) - coverage for
 * [UpdateChecker.isPendingUpdateActionable], the pure predicate shared by both Settings' "Update ready"
 * card (SettingsScreen.kt) and the new Home banner (HomeScreen.kt) to decide whether a pending update
 * flag is still real. A real user shipped v2.7 (a genuine data-loss bug fix) and never saw a prompt for
 * days - this predicate, and the fact both surfaces now call the SAME function instead of two independent
 * inline comparisons, is what keeps Home and Settings from silently drifting apart on this decision.
 */
class UpdateCheckerPendingUpdateActionableTest {

    @Test
    fun `pending version newer than current is actionable`() {
        assertTrue(UpdateChecker.isPendingUpdateActionable(pendingVersionCode = 15, currentVersionCode = 14))
    }

    @Test
    fun `pending version equal to current is stale, not actionable`() {
        // Covers AppSettings.pendingUpdateVersionCode's own doc: this build already covers whatever was
        // pending (most likely installed via a still-live notification tap without ever coming back
        // through UpdateChecker.checkAndNotify to clear the flag explicitly).
        assertFalse(UpdateChecker.isPendingUpdateActionable(pendingVersionCode = 14, currentVersionCode = 14))
    }

    @Test
    fun `pending version older than current is stale, not actionable`() {
        assertFalse(UpdateChecker.isPendingUpdateActionable(pendingVersionCode = 10, currentVersionCode = 14))
    }

    @Test
    fun `default zero pending version code (nothing ever recorded) is never actionable`() {
        // AppSettings.pendingUpdateVersionCode defaults to 0 - must never read as "actionable" against
        // any real positive versionCode.
        assertFalse(UpdateChecker.isPendingUpdateActionable(pendingVersionCode = 0, currentVersionCode = 1))
    }
}
