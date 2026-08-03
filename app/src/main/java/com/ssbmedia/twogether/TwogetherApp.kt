package com.ssbmedia.twogether

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.ssbmedia.twogether.data.backup.BackupWorker
import com.ssbmedia.twogether.data.update.UpdateChecker
import com.ssbmedia.twogether.data.update.UpdateWorker
import com.ssbmedia.twogether.lock.AppLockManager
import com.ssbmedia.twogether.notif.MilestoneAlarmScheduler
import com.ssbmedia.twogether.notif.Notifications
import kotlinx.coroutines.launch

class TwogetherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        Notifications.ensureChannels(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLockManager)
        // Registered on every app start (not just first pairing) since there's Room/settings data worth
        // backing up even before a couple pairs - enqueueUniquePeriodicWork + KEEP below makes this a
        // no-op on every start after the first, so it never stacks duplicate periodic jobs.
        BackupWorker.schedule(this)
        // Auto-update: daily background check via WorkManager, always scheduled (see UpdateWorker's
        // doc for why it's safe to leave scheduled even when the user has the setting off).
        UpdateWorker.schedule(this)
        // Auto-update: an additional check right on app start, so a user doesn't have to wait for the
        // next daily WorkManager run just because a release went out five minutes ago - throttled to
        // at most once per MIN_CHECK_INTERVAL_MS regardless of how often the app is reopened, and
        // skipped entirely when "Check for updates automatically" is off (manual "Check for updates
        // now" in Settings bypasses both of those, since an explicit tap is its own consent).
        ServiceLocator.applicationScope.launch {
            val settings = ServiceLocator.settingsStore.current()
            if (settings.autoUpdateCheckEnabled) {
                val now = System.currentTimeMillis()
                if (now - settings.lastUpdateCheckAt > UpdateChecker.MIN_CHECK_INTERVAL_MS) {
                    ServiceLocator.settingsStore.setLastUpdateCheckAt(now)
                    UpdateChecker.checkAndNotify(this@TwogetherApp)
                }
            }
        }
        // Feature F: AlarmManager alarms do NOT survive a reboot on their own (BootReceiver handles that
        // explicitly too, for the case the app itself isn't opened after a reboot) - re-arming on every
        // normal app start as well means an alarm that got silently dropped for any other reason (OS
        // alarm-store cleared, app data cleared then restored via Feature 4 backup, etc) self-heals the
        // next time the app is simply opened, not just after a reboot. Idempotent: each milestone's
        // PendingIntent uses a stable per-id request code (FLAG_UPDATE_CURRENT), so this just re-arms the
        // same alarm rather than stacking duplicates.
        ServiceLocator.applicationScope.launch {
            val milestones = ServiceLocator.milestoneRepository.getAll().filter { !it.deleted }
            MilestoneAlarmScheduler.scheduleAll(this@TwogetherApp, milestones)
        }
    }
}
