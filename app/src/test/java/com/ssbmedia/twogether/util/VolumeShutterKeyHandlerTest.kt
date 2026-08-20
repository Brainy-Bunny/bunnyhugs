package com.ssbmedia.twogether.util

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-FIX-PLAN.md Phase 3 item 16, point 5: coverage for MainActivity.dispatchKeyEvent's pure decision
 * logic. Only KeyEvent's own int constants are touched here (KEYCODE_VOLUME_UP/DOWN, ACTION_DOWN/UP,
 * plus an arbitrary other keycode) - no real KeyEvent instance is constructed, so this runs fine under
 * this project's plain JVM unit tests (no Robolectric needed).
 */
class VolumeShutterKeyHandlerTest {

    // ---- shouldConsumeAsShutter ----

    @Test
    fun `volume keys are consumed while camera screen is active`() {
        assertTrue(VolumeShutterKeyHandler.shouldConsumeAsShutter(true, KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(VolumeShutterKeyHandler.shouldConsumeAsShutter(true, KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `volume keys are NOT consumed when camera screen is not active`() {
        assertFalse(VolumeShutterKeyHandler.shouldConsumeAsShutter(false, KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(VolumeShutterKeyHandler.shouldConsumeAsShutter(false, KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `non-volume keys are never consumed even while camera screen is active`() {
        assertFalse(VolumeShutterKeyHandler.shouldConsumeAsShutter(true, KeyEvent.KEYCODE_BACK))
        assertFalse(VolumeShutterKeyHandler.shouldConsumeAsShutter(true, KeyEvent.KEYCODE_VOLUME_MUTE))
    }

    // ---- shouldTriggerCapture ----

    @Test
    fun `only ACTION_DOWN triggers a capture`() {
        assertTrue(VolumeShutterKeyHandler.shouldTriggerCapture(KeyEvent.ACTION_DOWN))
        assertFalse(VolumeShutterKeyHandler.shouldTriggerCapture(KeyEvent.ACTION_UP))
        assertFalse(VolumeShutterKeyHandler.shouldTriggerCapture(KeyEvent.ACTION_MULTIPLE))
    }
}
