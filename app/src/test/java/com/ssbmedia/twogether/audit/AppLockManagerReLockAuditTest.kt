package com.ssbmedia.twogether.audit

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.ssbmedia.twogether.lock.AppLockManager
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Item 8 fix ("App lock re-locks too aggressively") regression test: ON_STOP must no longer
 * unconditionally set isLocked = true - see AppLockManager's own top-of-file doc, and
 * AppLockPolicyTest for the actual relock-decision matrix (kept as a pure, separately-tested function).
 *
 * This only exercises the "onStop must not lock immediately" half directly: AppLockManager.init(context)
 * is never called in this plain-JVM unit test (no real Android Context available here), so appContext
 * stays null and the KeyguardManager backup check in onStart is a no-op - the elapsed-time/grace-window
 * half of onStart's decision depends on SystemClock.elapsedRealtime(), which this module's
 * `unitTests.isReturnDefaultValues = true` config stubs to a constant 0L rather than a real clock, so
 * it isn't meaningfully exercisable here without Robolectric. That combination (elapsed always computing
 * as 0, well under the grace window) is exactly what the second test below relies on and documents.
 *
 * AppLockManager is a singleton (JVM-wide across this test class, and shared with
 * AppLockManagerLockoutAuditTest), so every test resets it first.
 */
class AppLockManagerReLockAuditTest {

    private val fakeOwner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = throw NotImplementedError("unused by onStop/onStart")
    }

    @Before
    fun reset() {
        AppLockManager.unlock()
    }

    @Test
    fun `onStop no longer relocks immediately - only records the background timestamp`() {
        AppLockManager.unlock()
        AppLockManager.onStop(fakeOwner)
        assertFalse(
            "onStop must defer the relock decision to onStart, not set isLocked = true directly - " +
                "that was the whole bug behind item 8 (re-locking on every brief background, including " +
                "this app's own system pickers)",
            AppLockManager.isLocked
        )
    }

    @Test
    fun `an immediate onStop-then-onStart within the grace window does not relock`() {
        AppLockManager.unlock()
        AppLockManager.onStop(fakeOwner)
        AppLockManager.onStart(fakeOwner)
        assertFalse(
            "a background stretch this short (well under the grace window) must not require the PIN again",
            AppLockManager.isLocked
        )
    }
}
