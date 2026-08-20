package com.ssbmedia.twogether.lock

/**
 * Item 8 fix ("App lock re-locks too aggressively"): pure decision logic for whether resuming the app
 * from the background should require the PIN again, deliberately pulled out of AppLockManager's
 * lifecycle callbacks so it can be unit-tested with plain values instead of a real Android
 * Context/SystemClock/KeyguardManager - see AppLockManagerReLockAuditTest / AppLockPolicyTest.
 *
 * The old behavior (`onStop { isLocked = true }`, unconditionally) re-armed the PIN screen the instant
 * the whole app process left the foreground for ANY reason - a brief app-switch, a recents peek, or even
 * this app itself launching a system picker (photo picker, restore-backup SAF picker, battery-
 * optimization settings screen), which backgrounds the whole process just as surely as switching to
 * another app on purpose does. The user's own ask was explicit: "Keep the app unlocked until phone is
 * locked or app is closed. Don't lock the app again after I switch to a different app for just a
 * second." This policy encodes exactly that: only relock once either (a) a genuine amount of time has
 * passed while away (long enough that it's no longer plausible this was "just a second"), or (b) the
 * device's own lock screen was actually engaged while away - a much stronger, more direct signal than
 * elapsed time alone that this was a real "put the phone down" event, not a quick switch.
 */
object AppLockPolicy {
    /**
     * How long the app can sit in the background before a resume relocks it anyway, even without a
     * device-lock signal (e.g. screen timeout set very long, or the phone plugged in with "stay awake"
     * on, so the screen never actually turns off during the away period). 60 seconds is generously long
     * for "switch to check a message and come back" while still being short enough that it can't
     * plausibly be mistaken for "I put the phone down and walked away" - real absences are expected to be
     * caught sooner anyway via [wasDeviceLockedWhileAway], since a phone's screen timeout is virtually
     * always well under a minute in practice.
     */
    const val DEFAULT_GRACE_WINDOW_MILLIS: Long = 60_000L

    /**
     * @param elapsedSinceBackgroundedMillis wall-clock-independent (SystemClock.elapsedRealtime()-based)
     *   duration the process has been backgrounded, or `null` if the process was never observed to leave
     *   the foreground (nothing to judge - defaults to "don't relock").
     * @param wasDeviceLockedWhileAway true if a genuine "device locked" signal (the ACTION_SCREEN_OFF
     *   receiver firing, or a KeyguardManager check) was observed at any point since the process last
     *   backgrounded. This overrides the grace window entirely and relocks immediately, regardless of how
     *   little time has elapsed - the whole point of "lock when the phone is locked".
     * @param graceWindowMillis see [DEFAULT_GRACE_WINDOW_MILLIS].
     * @return true if the app should require the PIN again before showing any content.
     */
    fun shouldRelock(
        elapsedSinceBackgroundedMillis: Long?,
        wasDeviceLockedWhileAway: Boolean,
        graceWindowMillis: Long = DEFAULT_GRACE_WINDOW_MILLIS
    ): Boolean {
        if (wasDeviceLockedWhileAway) return true
        if (elapsedSinceBackgroundedMillis == null) return false
        return elapsedSinceBackgroundedMillis >= graceWindowMillis
    }
}
