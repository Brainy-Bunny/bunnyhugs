package com.ssbmedia.twogether.lock

/**
 * Item 5 (deferred UX fix, 4-model advisory audit): pure gating logic for the optional biometric-unlock
 * alternative offered on [com.ssbmedia.twogether.ui.lock.PinLockScreen] - extracted so both "should
 * Settings even show the toggle" and "should the lock screen actually offer a biometric prompt right now"
 * are unit-testable without a real androidx.biometric.BiometricManager/Context (this project's unit tests
 * have no Robolectric - see app/build.gradle.kts's testImplementation list, same reasoning
 * SettingsStore.fromPreferences' own doc gives for being extracted the same way).
 *
 * [SUCCESS] duplicates androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS's own value (0) as a plain
 * Int constant, rather than this file referencing the real androidx.biometric class, so this file (and its
 * test) stay dependency-free plain Kotlin, JVM-testable with zero Android/androidx runtime involved. Call
 * sites in the UI layer pass BiometricManager.canAuthenticate(...)'s real return value straight through -
 * the two are guaranteed to agree because BIOMETRIC_SUCCESS is a stable, documented public API constant
 * that has never changed value across androidx.biometric releases.
 */
object BiometricGate {
    const val SUCCESS: Int = 0

    /** Whether Settings should even offer the "Unlock with fingerprint/face" toggle. Per this feature's
     * own design ask, a device with no usable biometric enrollment never sees a disabled-with-explanation
     * toggle - it's hidden entirely, since there is nothing the user could do about it from inside this
     * app anyway. Also gated on [pinEnabled]: biometric unlock is an ALTERNATIVE to the PIN screen, never
     * a replacement, so offering it while there is no PIN lock for it to speed past would be meaningless -
     * there would be no lock screen for it to ever appear on. */
    fun shouldShowToggle(pinEnabled: Boolean, canAuthenticateResult: Int): Boolean =
        pinEnabled && canAuthenticateResult == SUCCESS

    /** Whether [com.ssbmedia.twogether.ui.lock.PinLockScreen] should actually offer a biometric prompt
     * right now. Re-checks LIVE hardware/enrollment state (via [canAuthenticateResult], not just the
     * persisted [biometricUnlockEnabled] toggle) so a device that had biometrics enrolled when the toggle
     * was turned on, then later had every fingerprint/face removed, silently and safely falls back to
     * PIN-only instead of ever launching a prompt that can no longer succeed. PIN entry itself is never
     * gated by this result either way - see PinLockScreen's own doc for why the PIN field must always
     * remain available regardless of what this returns. */
    fun canOfferBiometric(biometricUnlockEnabled: Boolean, canAuthenticateResult: Int): Boolean =
        biometricUnlockEnabled && canAuthenticateResult == SUCCESS
}
