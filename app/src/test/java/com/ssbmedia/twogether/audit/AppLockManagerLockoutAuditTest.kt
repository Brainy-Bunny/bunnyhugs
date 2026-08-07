package com.ssbmedia.twogether.audit

import com.ssbmedia.twogether.lock.AppLockManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ultimate-app-review spec-test (Step 3, rounds 1-2, 2026-08-07) - independently derived from
 * checklist.md's PIN-throttle constraint ("5 wrong PIN attempts locks out for 30s; the lockout must
 * neither be indefinitely extendable nor ratchet stricter than documented"). AppLockManager is a
 * singleton (JVM-wide across this test class), so every test resets it first.
 */
class AppLockManagerLockoutAuditTest {

    @Before
    fun reset() {
        AppLockManager.unlock() // also clears failedPinAttempts/pinLockedOutUntilMillis
    }

    @Test
    fun `not locked out before any failed attempts`() {
        assertFalse(AppLockManager.isPinLockedOut())
    }

    @Test
    fun `5th failed attempt arms a lockout in the future`() {
        repeat(4) { AppLockManager.recordFailedPinAttempt() }
        assertFalse("must not lock out before the 5th attempt", AppLockManager.isPinLockedOut())
        AppLockManager.recordFailedPinAttempt()
        assertTrue(AppLockManager.isPinLockedOut())
    }

    @Test
    fun `a failed attempt recorded while already locked out never extends the lockout`() {
        repeat(5) { AppLockManager.recordFailedPinAttempt() }
        val armedUntil = AppLockManager.pinLockedOutUntilMillis
        assertTrue(armedUntil > System.currentTimeMillis())
        // Regression guard: without the insurance check, a call landing while still locked out would
        // still increment the counter and re-arm a FRESH 30s from "now", so the lockout could in
        // principle keep extending indefinitely instead of ever expiring.
        AppLockManager.recordFailedPinAttempt()
        assertEquals(
            "recordFailedPinAttempt must be a no-op while already locked out",
            armedUntil, AppLockManager.pinLockedOutUntilMillis
        )
    }

    @Test
    fun `unlock clears both the lockout and the attempt counter`() {
        repeat(5) { AppLockManager.recordFailedPinAttempt() }
        assertTrue(AppLockManager.isPinLockedOut())
        AppLockManager.unlock()
        assertFalse(AppLockManager.isPinLockedOut())
        assertEquals(0, AppLockManager.failedPinAttempts)
    }

    @Test
    fun `resetFailedPinAttempts clears the throttle without touching isLocked`() {
        AppLockManager.unlock() // isLocked = false
        repeat(5) { AppLockManager.recordFailedPinAttempt() }
        AppLockManager.resetFailedPinAttempts()
        assertFalse(AppLockManager.isPinLockedOut())
        assertFalse("resetFailedPinAttempts must not re-lock the app", AppLockManager.isLocked)
    }

    @Test
    fun `isPinLockedOut with an injected future now reflects an active lockout`() {
        repeat(5) { AppLockManager.recordFailedPinAttempt() }
        val until = AppLockManager.pinLockedOutUntilMillis
        assertTrue(AppLockManager.isPinLockedOut(until - 1))
        assertFalse("lockout must have genuinely expired by its own deadline", AppLockManager.isPinLockedOut(until))
    }
}
