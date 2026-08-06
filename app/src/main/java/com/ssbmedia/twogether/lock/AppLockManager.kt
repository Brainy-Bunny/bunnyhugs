package com.ssbmedia.twogether.lock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * In-memory app-lock gate. Starts locked (cold start). Whenever the whole app process goes to
 * the background (ProcessLifecycleOwner ON_STOP) it re-arms the lock, so resuming from background
 * requires the PIN again — standard app-lock behavior. Only meaningful when PIN lock is enabled in
 * settings; the UI layer decides whether to actually show the lock screen based on that setting.
 */
object AppLockManager : DefaultLifecycleObserver {
    var isLocked by mutableStateOf(true)
        private set

    // Basic throttle against rapid-fire PIN guessing. This app's threat model is a physically unlocked
    // phone (see PinLockScreen), so this is a nice-to-have rather than essential - but leaving repeated
    // wrong entries completely unthrottled was too cheap a gap to leave in. Deliberately in-memory only
    // (resets on process death); that's an acceptable tradeoff for a local-only PIN with this threat
    // model, and avoids adding a persisted store just for this.
    var failedPinAttempts by mutableStateOf(0)
        private set
    var pinLockedOutUntilMillis by mutableStateOf(0L)
        private set

    fun unlock() {
        isLocked = false
        resetFailedPinAttempts()
    }

    /** Resets just the failed-attempt throttle, without touching [isLocked] - for a successful PIN
     * verification that happens OUTSIDE the main lock screen (e.g. SettingsScreen's
     * VerifyCurrentPinDialog, which shares this same counter - see its own doc), where the app is already
     * unlocked and flipping [isLocked] would be meaningless. MINOR fix: without this, wrong guesses typed
     * into that dialog used to linger in the counter even after a subsequent correct one, so a single
     * wrong PIN typed anywhere afterward (including on the main lock screen, later) could trigger an
     * immediate lockout instead of needing FAILED_ATTEMPTS_BEFORE_LOCKOUT fresh wrong guesses. */
    fun resetFailedPinAttempts() {
        failedPinAttempts = 0
        pinLockedOutUntilMillis = 0L
    }

    fun recordFailedPinAttempt() {
        val now = System.currentTimeMillis()
        // BUG fix: a lockout already in progress must never be extended by a further recorded attempt -
        // every caller's UI already disables its PIN field/button while isPinLockedOut() is true, so this
        // shouldn't be reachable through normal interaction, but it's cheap, correct insurance against
        // any caller that doesn't check first (or a race between the lockout timer and a submit action):
        // without this guard, a call landing while still locked out would still increment the counter and
        // re-arm a FRESH 30s from "now", so a lockout could in principle keep extending indefinitely
        // instead of ever actually expiring.
        if (isPinLockedOut(now)) return
        // BUG fix: failedPinAttempts used to never come back down except on a full success - so once it
        // first reached the threshold, EVERY later wrong guess re-armed a brand new 30s lockout
        // immediately, even long after the previous one had already expired and the documented "5 wrong
        // attempts -> 30-second lockout" behavior would suggest a fresh start. A served, expired lockout
        // now genuinely resets the count, giving a real fresh set of attempts rather than a ratchet that
        // only ever gets stricter.
        if (pinLockedOutUntilMillis != 0L && now >= pinLockedOutUntilMillis) {
            failedPinAttempts = 0
            pinLockedOutUntilMillis = 0L
        }
        failedPinAttempts++
        if (failedPinAttempts >= FAILED_ATTEMPTS_BEFORE_LOCKOUT) {
            pinLockedOutUntilMillis = now + LOCKOUT_MILLIS
        }
    }

    fun isPinLockedOut(now: Long = System.currentTimeMillis()): Boolean = now < pinLockedOutUntilMillis

    override fun onStop(owner: LifecycleOwner) {
        isLocked = true
    }

    private const val FAILED_ATTEMPTS_BEFORE_LOCKOUT = 5
    private const val LOCKOUT_MILLIS = 30_000L
}
