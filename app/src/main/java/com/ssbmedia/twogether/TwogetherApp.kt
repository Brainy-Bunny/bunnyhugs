package com.ssbmedia.twogether

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.ssbmedia.twogether.data.backup.BackupManager
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
        // Item 8 fix: wires up AppLockManager's ACTION_SCREEN_OFF receiver (needs a real Context - see
        // its own doc) before registering it as a ProcessLifecycleOwner observer, so the receiver is live
        // before the very first ON_STOP could ever fire.
        AppLockManager.init(this)
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
        // Resumes a backup restore that got interrupted before completing (process death mid-restore) -
        // see BackupManager.restoreBackupDurable/resumePendingRestoreIfAny's docs. A no-op on every
        // normal app start where nothing is pending, which is the overwhelming majority of the time.
        ServiceLocator.applicationScope.launch {
            BackupManager.resumePendingRestoreIfAny(this@TwogetherApp)
        }
        // DISABLED (ultimate-app-review, Fable's post-restart adversarial pass, 2026-08-08): this call
        // used to self-heal a row this device itself wrote with an implausibly future updatedAt during a
        // past period of genuine clock error (ClockSkewSelfHeal.run(), added to fix a MAJOR from this
        // same review round). Fable then live-reproduced a BLOCKER-severity regression in it: the
        // function trusts `System.currentTimeMillis()` as ground truth for judging already-stored data,
        // but if THIS device's clock is instead currently BEHIND real time (a different clock fault, not
        // the one the fix targeted), it re-stamps perfectly correct, recent local edits - including a
        // tombstone from a user-confirmed delete - back to the stale "now", silently undoing them once
        // the clock is corrected and a sync runs; the poisoned stamp then propagates to the partner.
        // This is the SECOND systemic clock-trust finding this review round (see BackupManager.
        // parseSessions' own history, which had the mirror-image bug on the restore path) - per this
        // review process's own one-restart cap, that means stop and get this in front of a human rather
        // than attempt a third hasty timestamp-trust patch overnight. The correct fix (Fable's own
        // proposal) is a single persisted, monotonically-non-decreasing "highest updatedAt ever
        // legitimately observed" high-water mark - fed by every local write AND every successful sync
        // merge - used here instead of raw `now`, so a temporarily-backward device clock can't regress
        // the ceiling. That's a real feature addition (new persisted state, new write-site integration
        // across every local write path), not a one-line fix, and deserves its own reviewed round rather
        // than being rushed in now. Until then, this call stays disabled and Major 2 (rows written during
        // a past clock-error period staying un-syncable until manually retyped) is deliberately back to
        // being an accepted, deferred annoyance rather than a "fixed" feature that can itself destroy
        // data. ClockSkewSelfHeal.kt is left in place, unused, as the documented starting point.
        // ServiceLocator.applicationScope.launch { ClockSkewSelfHeal.run() }
    }
}
