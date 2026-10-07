package com.ssbmedia.twogether.notif

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Plays the photo reminder as an alarm: the alarm sound loops and the phone vibrates until [stop] is called, or
 * until [RING_LIMIT_MILLIS] runs out (so a forgotten alarm can't ring all night). Only one ring plays at a time.
 *
 * Why not a notification sound: a notification sound plays once and is quiet on the alarm stream. This plays on
 * the alarm stream (audible at low media volume, and it's the stream Do Not Disturb lets through), looping.
 */
object AlarmRinger {
    private const val TAG = "AlarmRinger"
    /** Two minutes is long enough to be noticed and short enough to not be a nuisance if nobody answers. */
    const val RING_LIMIT_MILLIS = 2 * 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    /** Starts ringing. Restarts cleanly if a ring is already going. Never throws: a failed sound is logged, not fatal. */
    fun start(context: Context) {
        stop()
        val app = context.applicationContext
        val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(app, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the alarm sound", e)
            mp.release()
        }
        startVibration(app)
        handler.postDelayed(autoStop, RING_LIMIT_MILLIS)
    }

    /** Silences the ring and stops vibration. Safe to call when nothing is ringing. */
    fun stop() {
        handler.removeCallbacks(autoStop)
        player?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Alarm player was not in a stoppable state", e)
            }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun startVibration(context: Context) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        // Pause, buzz, pause, buzz... repeating from index 0 until cancelled.
        val pattern = longArrayOf(0, 700, 500)
        v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        vibrator = v
    }
}
