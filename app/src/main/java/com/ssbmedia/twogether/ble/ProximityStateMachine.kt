package com.ssbmedia.twogether.ble

import android.os.SystemClock

/**
 * Debounces raw BLE beacon sightings into a stable together/apart signal so RSSI flicker doesn't
 * spam session start/end events. Flips to "together" only after several consecutive sightings in
 * a short rolling window, and flips back to "apart" only after a longer stretch of silence.
 */
class ProximityStateMachine(
    private val requiredConsecutiveDetections: Int = 3,
    private val detectionWindowMillis: Long = 30_000L,
    /** Public so callers (ProximityForegroundService) can clamp a closed session's endedAt to
     * lastSeenAt + this timeout instead of crediting an arbitrary "now", and can reason about how
     * stale a restored lastSeenAt is on process restart. */
    val absenceTimeoutMillis: Long = DEFAULT_ABSENCE_TIMEOUT_MILLIS
) {
    companion object {
        const val DEFAULT_ABSENCE_TIMEOUT_MILLIS = 100_000L
    }

    private val detectionTimestamps = ArrayDeque<Long>()

    var isTogether: Boolean = false
        private set
    var lastSeenAt: Long = 0L
        private set

    /** Monotonic (boot-time, never wall-clock) mirror of [lastSeenAt], used ONLY for the absence-timeout
     * arithmetic in [checkAbsence] - never for anything user-facing. Wall-clock lastSeenAt is vulnerable
     * to the device's system clock being changed backwards (accidentally, or deliberately) while the
     * absence timer is running: "now - lastSeenAt" would then never reach absenceTimeoutMillis, pinning
     * isTogether=true forever with no self-correction. SystemClock.elapsedRealtime() can't be wound
     * backwards by the user or NTP and keeps advancing through deep sleep, so it's immune to that.
     * Deliberately kept in-memory only (never persisted verbatim across a process restart) since it
     * resets to a small number on an actual device reboot - see [restoreState] for how that's handled. */
    var lastSeenElapsedRealtime: Long = 0L
        private set

    /** Call whenever the partner's beacon is spotted in a scan result. Returns true iff this call flips apart->together. */
    fun onBeaconSeen(now: Long): Boolean {
        lastSeenAt = now
        lastSeenElapsedRealtime = SystemClock.elapsedRealtime()
        detectionTimestamps.addLast(now)
        while (detectionTimestamps.isNotEmpty() && now - detectionTimestamps.first() > detectionWindowMillis) {
            detectionTimestamps.removeFirst()
        }
        if (!isTogether && detectionTimestamps.size >= requiredConsecutiveDetections) {
            isTogether = true
            return true
        }
        return false
    }

    /** Call periodically even without new sightings. Returns true iff this call flips together->apart.
     * [now] (wall-clock) is intentionally unused for the actual timeout comparison - see
     * [lastSeenElapsedRealtime]'s doc - but kept as a parameter since callers already have it handy and
     * it costs nothing to accept. */
    fun checkAbsence(now: Long): Boolean {
        val elapsedSinceLastSeen = if (lastSeenElapsedRealtime != 0L) {
            SystemClock.elapsedRealtime() - lastSeenElapsedRealtime
        } else {
            Long.MAX_VALUE
        }
        if (isTogether && lastSeenAt != 0L && elapsedSinceLastSeen >= absenceTimeoutMillis) {
            isTogether = false
            detectionTimestamps.clear()
            return true
        }
        return false
    }

    /** Restores state after process restart, without re-triggering a transition event. [lastSeen] is
     * still the wall-clock timestamp (from persisted state) for display/session-clamping purposes, but
     * lastSeenElapsedRealtime can't be meaningfully reconstructed from a value persisted before this
     * restart (elapsedRealtime resets on an actual reboot, so an old persisted value could look
     * absurdly far in the past - or even in the future). The caller (ProximityForegroundService) has
     * already independently vetted [together] against the wall-clock timeout before calling this, so
     * if we're resuming together, it's correct to just restart the monotonic countdown fresh from now. */
    fun restoreState(together: Boolean, lastSeen: Long) {
        isTogether = together
        lastSeenAt = lastSeen
        lastSeenElapsedRealtime = if (together) SystemClock.elapsedRealtime() else 0L
        detectionTimestamps.clear()
        if (together) detectionTimestamps.addLast(lastSeen)
    }
}
