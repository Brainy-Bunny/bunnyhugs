package com.ssbmedia.twogether.util

import android.view.KeyEvent

/**
 * UX-FIX-PLAN.md Phase 3 item 16, point 5: pure decision logic for MainActivity.dispatchKeyEvent's
 * volume-button-as-camera-shutter handling. Kept separate from the Activity method itself so it's
 * unit-testable without a real KeyEvent/Activity/Robolectric - MainActivity's own dispatchKeyEvent stays
 * a thin wrapper that just forwards the real event's keyCode/action plus whether the Camera screen is
 * currently the active destination.
 */
object VolumeShutterKeyHandler {

    /** True when this key event is a volume up/down press/release that should be CONSUMED (i.e.
     * dispatchKeyEvent should return true and not call super) so the OS doesn't also raise/lower media
     * volume or show its volume UI - but only while the Camera screen is the active destination;
     * everywhere else in the app, volume keys must behave completely normally. */
    fun shouldConsumeAsShutter(isCameraScreenActive: Boolean, keyCode: Int): Boolean {
        if (!isCameraScreenActive) return false
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
    }

    /** Of the (at least) two KeyEvents a single physical press/release generates, only ACTION_DOWN
     * should actually fire a capture - matching a real camera shutter button's "fires on press" feel.
     * ACTION_UP for the same key still gets consumed via [shouldConsumeAsShutter] above (purely to keep
     * suppressing the system volume change), it just must not ALSO trigger a second capture. */
    fun shouldTriggerCapture(action: Int): Boolean = action == KeyEvent.ACTION_DOWN
}
