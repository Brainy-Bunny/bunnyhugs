package com.ssbmedia.twogether.ui.snooze

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.notif.AlarmRinger
import com.ssbmedia.twogether.notif.Notifications
import kotlinx.coroutines.launch

/**
 * Handles the Snooze and Stop buttons on the photo alarm. It has no screen: it acts on the tap and closes.
 *
 * - Snooze: silences the alarm and rings again after the user's snooze length (Settings), if we're still together
 *   and no photo has been taken by then.
 * - Stop: silences the alarm for this together-stretch.
 */
class SnoozeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmRinger.stop()
        Notifications.cancelPhotoReminder(this)

        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_SNOOZE -> applySnooze()
            ACTION_STOP -> applyStop()
            else -> finish()
        }
    }

    private fun applySnooze() {
        lifecycleScope.launch {
            val minutes = ServiceLocator.settingsStore.current().defaultSnoozeMinutes.coerceAtLeast(1)
            val until = System.currentTimeMillis() + minutes * 60_000L
            ServiceLocator.proximityStateStore.update {
                it.copy(snoozeUntil = until, reminderFiredForSession = true)
            }
            finish()
        }
    }

    private fun applyStop() {
        lifecycleScope.launch {
            ServiceLocator.proximityStateStore.update {
                it.copy(snoozeUntil = 0L, reminderFiredForSession = true)
            }
            finish()
        }
    }

    companion object {
        const val EXTRA_ACTION = "snooze_action"
        const val ACTION_SNOOZE = "snooze"
        const val ACTION_STOP = "stop"
    }
}
