package com.ssbmedia.twogether

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.ssbmedia.twogether.data.backup.BackupWorker
import com.ssbmedia.twogether.lock.AppLockManager
import com.ssbmedia.twogether.notif.Notifications

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
    }
}
