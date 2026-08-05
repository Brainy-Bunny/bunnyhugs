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
        failedPinAttempts++
        if (failedPinAttempts >= FAILED_ATTEMPTS_BEFORE_LOCKOUT) {
            pinLockedOutUntilMillis = System.currentTimeMillis() + LOCKOUT_MILLIS
        }
    }

    fun isPinLockedOut(now: Long = System.currentTimeMillis()): Boolean = now < pinLockedOutUntilMillis

    override fun onStop(owner: LifecycleOwner) {
        isLocked = true
    }

    private const val FAILED_ATTEMPTS_BEFORE_LOCKOUT = 5
    private const val LOCKOUT_MILLIS = 30_000L
}
