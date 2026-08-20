package com.ssbmedia.twogether.lock

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * In-memory app-lock gate. Starts locked (cold start - a fresh process always starts with [isLocked]
 * defaulting to true, and every piece of state this class tracks about a background stretch is a plain
 * in-memory var that resets with the process, so "app closed" i.e. task fully removed always means
 * locked again next launch, no matter how it left off). Only meaningful when PIN lock is enabled in
 * settings; the UI layer decides whether to actually show the lock screen based on that setting.
 *
 * Item 8 fix ("App lock re-locks too aggressively"): re-locking used to be unconditional on
 * ProcessLifecycleOwner ON_STOP, which fires the instant the whole app process leaves the foreground for
 * ANY reason - including this app itself launching a system picker (photo picker, restore-backup SAF
 * picker, battery-optimization settings screen - see RestorePickerHost's own doc for the related,
 * already-fixed "picked file lost on PIN relock" bug that came from exactly this). Per the user's own
 * ask ("keep the app unlocked until phone is locked or app is closed - don't lock again after switching
 * apps for just a second"), ON_STOP now only records when the background stretch started; ON_START
 * defers the actual relock decision to [AppLockPolicy] (kept pure/testable there), using either a grace
 * window or a genuine "the device was locked while we were away" signal - the latter fed by
 * [onScreenOff] (immediate, via a runtime-registered ACTION_SCREEN_OFF receiver - the most direct
 * interpretation of "lock when the phone is locked") and, as a defensive backup, a lazy KeyguardManager
 * check at resume time.
 */
object AppLockManager : DefaultLifecycleObserver {
    var isLocked by mutableStateOf(true)
        private set

    /** elapsedRealtime() timestamp of the most recent ON_STOP, or null while the process is currently
     * foregrounded (including right after a fresh cold start, before any backgrounding has happened yet -
     * nothing to judge, so [AppLockPolicy.shouldRelock] treats null as "don't relock"). elapsedRealtime
     * (not System.currentTimeMillis) deliberately - it's immune to wall-clock changes (timezone, NTP
     * correction, the user manually changing the clock) and keeps advancing through light sleep, so it
     * measures genuine away-time rather than what the calendar/clock says. Plain in-memory var, never
     * persisted - see this class's top-of-file doc for why that matters for "app closed" staying locked. */
    private var backgroundedAtElapsedRealtime: Long? = null

    /** True once a genuine "device locked" signal has been observed since the most recent ON_STOP - set
     * by [onScreenOff] (the primary, immediate signal) and OR'd with a lazy KeyguardManager check in
     * [onStart] (a defensive backup in case the broadcast is somehow missed). Cleared every time
     * [onStart] makes its decision, so it only ever reflects "since we most recently backgrounded", never
     * a stale signal from further back. Plain in-memory var for the same reason as
     * [backgroundedAtElapsedRealtime]. */
    private var deviceLockedWhileAway: Boolean = false

    private var appContext: Context? = null

    /** Wires up the pieces that need a real Context - call once from TwogetherApp.onCreate, mirroring
     * ServiceLocator.init's own pattern. ACTION_SCREEN_OFF is a protected broadcast Android has never
     * allowed a manifest-declared `<receiver>` to listen for; it must be registered at runtime via
     * Context.registerReceiver. Registered once for the whole process lifetime - there's nothing to ever
     * unregister it from, since this singleton and the Application share that same lifetime. Idempotent
     * (a second call is a no-op) so it's safe even if something calls it more than once - registering two
     * receiver instances would otherwise leak the first. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                onScreenOff()
            }
        }
        ContextCompat.registerReceiver(
            appContext!!,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Fired the instant the screen turns off, for any reason (power button, sleep timeout, ...) - the
     * most direct, immediate reading of "the phone got locked", ahead of and independent from whatever
     * ON_STOP/ON_START otherwise decide. Recorded regardless of whether the process happens to be
     * foregrounded or already backgrounded at that instant: screen-off backgrounds the foreground
     * activity almost immediately anyway (ON_STOP follows right after when it wasn't already
     * backgrounded), so by the time [onStart] runs its decision this flag is already set either way. */
    private fun onScreenOff() {
        deviceLockedWhileAway = true
    }

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
        // BUG fix (item 8): used to set isLocked = true unconditionally right here - see this class's
        // top-of-file doc for why that was too aggressive. Just record when the background stretch
        // started; onStart below makes the actual decision once we're back.
        backgroundedAtElapsedRealtime = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        val elapsed = backgroundedAtElapsedRealtime?.let { SystemClock.elapsedRealtime() - it }
        // Defensive backup for [deviceLockedWhileAway] - see that field's own doc. By the time onStart
        // runs the user has typically already dismissed the keyguard to get back here, so this is mostly
        // insurance for the (rare, OS/timing-dependent) case where the screen-off broadcast was somehow
        // missed; the receiver remains the primary, reliable signal.
        val keyguardLocked = appContext?.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        if (AppLockPolicy.shouldRelock(elapsed, deviceLockedWhileAway || keyguardLocked)) {
            isLocked = true
        }
        backgroundedAtElapsedRealtime = null
        deviceLockedWhileAway = false
    }

    private const val FAILED_ATTEMPTS_BEFORE_LOCKOUT = 5
    private const val LOCKOUT_MILLIS = 30_000L
}
