package com.ssbmedia.twogether.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.ble.BlePermissions
import com.ssbmedia.twogether.notif.MilestoneAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restarts the proximity foreground service after a reboot, if this device is already paired. Also
 * re-arms every milestone's yearly alarm (Feature F) - AlarmManager alarms are wiped by a reboot and
 * are NOT automatically restored, regardless of pairing state. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ServiceLocator.init(appContext)
                val paired = ServiceLocator.pairingStore.current().isPaired
                // Starting the service without BLE permission crashes the app on Android 12+ (see
                // MainActivity.startProximityService) - if the user denied/revoked it before the
                // reboot, wait for them to open the app and grant it via HomeScreen's banner instead.
                if (paired && BlePermissions.hasBlePermissions(appContext)) {
                    val serviceIntent = Intent(appContext, ProximityForegroundService::class.java)
                    ContextCompat.startForegroundService(appContext, serviceIntent)
                }
                val milestones = ServiceLocator.milestoneRepository.getAll().filter { !it.deleted }
                MilestoneAlarmScheduler.scheduleAll(appContext, milestones)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
