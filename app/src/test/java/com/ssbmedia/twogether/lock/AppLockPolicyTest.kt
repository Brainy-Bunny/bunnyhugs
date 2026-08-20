package com.ssbmedia.twogether.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 8 fix ("App lock re-locks too aggressively") spec test: [AppLockPolicy.shouldRelock] is the
 * whole decision this fix hinges on, pulled out into a pure function specifically so it can be tested
 * with plain values here instead of needing a real Android Context/SystemClock/KeyguardManager (see
 * AppLockManagerReLockAuditTest for the lifecycle-callback side of this same fix).
 */
class AppLockPolicyTest {

    private val grace = AppLockPolicy.DEFAULT_GRACE_WINDOW_MILLIS

    @Test
    fun `never backgrounded and device not locked - never relock`() {
        assertFalse(AppLockPolicy.shouldRelock(elapsedSinceBackgroundedMillis = null, wasDeviceLockedWhileAway = false))
    }

    @Test
    fun `brief app-switch well under the grace window does not relock`() {
        // The user's literal ask: "don't lock the app again after I switch to a different app for just a
        // second".
        assertFalse(AppLockPolicy.shouldRelock(elapsedSinceBackgroundedMillis = 2_000L, wasDeviceLockedWhileAway = false))
    }

    @Test
    fun `just under the grace window does not relock`() {
        assertFalse(AppLockPolicy.shouldRelock(elapsedSinceBackgroundedMillis = grace - 1, wasDeviceLockedWhileAway = false))
    }

    @Test
    fun `exactly at the grace window relocks`() {
        assertTrue(AppLockPolicy.shouldRelock(elapsedSinceBackgroundedMillis = grace, wasDeviceLockedWhileAway = false))
    }

    @Test
    fun `well past the grace window relocks`() {
        assertTrue(AppLockPolicy.shouldRelock(elapsedSinceBackgroundedMillis = grace * 10, wasDeviceLockedWhileAway = false))
    }

    @Test
    fun `device locked while away relocks immediately regardless of how little time elapsed`() {
        assertTrue(AppLockPolicy.shouldRelock(elapsedSinceBackgroundedMillis = 500L, wasDeviceLockedWhileAway = true))
    }

    @Test
    fun `device locked while away relocks even with a null elapsed time`() {
        // Defensive case: shouldn't be reachable in practice (a device-locked signal implies we did
        // background at some point), but the device-lock signal must never be silently swallowed by a
        // missing elapsed reading.
        assertTrue(AppLockPolicy.shouldRelock(elapsedSinceBackgroundedMillis = null, wasDeviceLockedWhileAway = true))
    }

    @Test
    fun `a custom grace window is honored instead of the default`() {
        val customGrace = 5_000L
        assertFalse(
            AppLockPolicy.shouldRelock(
                elapsedSinceBackgroundedMillis = 4_999L,
                wasDeviceLockedWhileAway = false,
                graceWindowMillis = customGrace
            )
        )
        assertTrue(
            AppLockPolicy.shouldRelock(
                elapsedSinceBackgroundedMillis = 5_000L,
                wasDeviceLockedWhileAway = false,
                graceWindowMillis = customGrace
            )
        )
    }

    @Test
    fun `default grace window is 60 seconds`() {
        assertTrue(grace == 60_000L)
    }
}
