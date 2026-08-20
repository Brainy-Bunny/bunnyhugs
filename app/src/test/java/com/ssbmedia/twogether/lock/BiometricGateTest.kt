package com.ssbmedia.twogether.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 5 (deferred UX fix, 4-model advisory audit): the optional biometric-unlock alternative to the PIN
 * screen. [BiometricGate] is deliberately plain Kotlin with no androidx.biometric/Android dependency (see
 * its own doc) so both the "should Settings show the toggle at all" and "should PinLockScreen actually
 * offer a biometric prompt right now" decisions are covered here without Robolectric.
 *
 * NOT_SUCCESS below stands in for any of BiometricManager's many non-success result codes (no hardware, no
 * enrollment, hardware currently unavailable, security update required, unknown/unsupported) - BiometricGate
 * only ever cares whether the result equals SUCCESS, not which specific way it failed, so a single
 * representative failure code is enough to exercise every "not available" branch.
 */
class BiometricGateTest {
    private val notSuccess = 12 // BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, chosen arbitrarily - any non-zero code exercises the same branch.

    // --- shouldShowToggle: whether Settings even offers the toggle ---

    @Test
    fun `toggle hidden when PIN lock is off, even with usable biometric hardware`() {
        assertFalse(BiometricGate.shouldShowToggle(pinEnabled = false, canAuthenticateResult = BiometricGate.SUCCESS))
    }

    @Test
    fun `toggle hidden when biometric hardware unavailable, even with PIN lock on`() {
        assertFalse(BiometricGate.shouldShowToggle(pinEnabled = true, canAuthenticateResult = notSuccess))
    }

    @Test
    fun `toggle hidden when both PIN lock is off AND biometric hardware unavailable`() {
        assertFalse(BiometricGate.shouldShowToggle(pinEnabled = false, canAuthenticateResult = notSuccess))
    }

    @Test
    fun `toggle shown only when PIN lock is on AND biometric hardware is usable`() {
        assertTrue(BiometricGate.shouldShowToggle(pinEnabled = true, canAuthenticateResult = BiometricGate.SUCCESS))
    }

    // --- canOfferBiometric: whether PinLockScreen should actually launch a prompt right now ---

    @Test
    fun `no prompt offered when the user has never opted in, even with usable hardware`() {
        assertFalse(BiometricGate.canOfferBiometric(biometricUnlockEnabled = false, canAuthenticateResult = BiometricGate.SUCCESS))
    }

    @Test
    fun `no prompt offered when opted in but hardware enrollment is no longer usable`() {
        // The core safety property this method exists for: a user who enabled the setting while
        // enrolled, then later removed every fingerprint/face, must silently fall back to PIN-only
        // rather than the lock screen ever trying (and failing) to launch a prompt.
        assertFalse(BiometricGate.canOfferBiometric(biometricUnlockEnabled = true, canAuthenticateResult = notSuccess))
    }

    @Test
    fun `no prompt offered when neither opted in nor hardware usable`() {
        assertFalse(BiometricGate.canOfferBiometric(biometricUnlockEnabled = false, canAuthenticateResult = notSuccess))
    }

    @Test
    fun `prompt offered only when opted in AND hardware is currently usable`() {
        assertTrue(BiometricGate.canOfferBiometric(biometricUnlockEnabled = true, canAuthenticateResult = BiometricGate.SUCCESS))
    }
}
