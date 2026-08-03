package com.ssbmedia.twogether.data.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ssbmedia.twogether.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * Daily background check for a newer GitHub release, mirroring BackupWorker's scheduling pattern
 * (enqueueUniquePeriodicWork + KEEP, so calling schedule() on every app start never stacks a second
 * duplicate periodic job). Deliberately left ALWAYS scheduled regardless of the "Check for updates
 * automatically" setting - doWork() itself no-ops (but still reports success, never retry) when the
 * setting is off, so re-enabling it later needs nothing re-wired; it just starts doing something on
 * the next scheduled run.
 */
class UpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): WorkResult {
        val settings = ServiceLocator.settingsStore.current()
        if (!settings.autoUpdateCheckEnabled) return WorkResult.success()

        ServiceLocator.settingsStore.setLastUpdateCheckAt(System.currentTimeMillis())
        val outcome = UpdateChecker.checkAndNotify(applicationContext)
        // A failed GitHub API call / download is almost always transient (offline, momentary rate
        // limit) - let WorkManager's own backoff policy retry rather than silently waiting a full
        // extra day for the next scheduled run.
        return if (outcome == UpdateChecker.CheckOutcome.DownloadFailed ||
            outcome == UpdateChecker.CheckOutcome.CheckFailed
        ) {
            WorkResult.retry()
        } else {
            WorkResult.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "daily_update_check"

        /** Requires a connected network (unlike BackupWorker, which needs none) since this job's
         * entire job is a network call - no point waking it up with no connectivity at all. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
