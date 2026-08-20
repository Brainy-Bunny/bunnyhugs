package com.ssbmedia.twogether.data.update

import android.content.Context
import androidx.work.BackoffPolicy
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
 * Background check for a newer GitHub release, mirroring BackupWorker's scheduling pattern
 * (enqueueUniquePeriodicWork, so calling schedule() on every app start never stacks a second duplicate
 * periodic job). Deliberately left ALWAYS scheduled regardless of the "Check for updates automatically"
 * setting - doWork() itself no-ops (but still reports success, never retry) when the setting is off, so
 * re-enabling it later needs nothing re-wired; it just starts doing something on the next scheduled run.
 *
 * Item 14 (update-nag reach fix): a real user shipped v2.7 (a genuine data-loss bug fix) and went days
 * without ever being prompted to update, in part because this was the ONLY of the three check paths with
 * no resilience against a failed/deferred run - Doze, an OEM battery manager deferring the job, or a
 * transient network blip could silently push the next real attempt out a full extra day. Two changes:
 * [setBackoffCriteria] so a failed attempt (WorkResult.retry() below, for CheckFailed/DownloadFailed)
 * retries with linear backoff instead of just waiting for tomorrow's scheduled run, and the period itself
 * tightened from 24h to 12h - relying on UpdateChecker.MIN_CHECK_INTERVAL_MS's existing 6h throttle
 * (shared with the app-start check and ProximityForegroundService's own periodic check, item 14's third
 * fix) as the REAL rate limiter, since a redundant run here is almost always just a small JSON GET
 * (checkAndNotify only downloads a full APK when this build is actually behind).
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
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                // Item 14 (update-nag reach fix): a transient failure (offline, momentary GitHub rate
                // limit) used to just sit and wait for the next full scheduled period - LINEAR backoff
                // starting at WorkManager's own allowed minimum delay means a failed attempt retries
                // quickly and then backs off linearly, instead of silently waiting up to 12h for the next
                // scheduled run.
                .setBackoffCriteria(BackoffPolicy.LINEAR, androidx.work.WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            // Item 14 (update-nag reach fix): UPDATE (not the original KEEP) - so tightening the period
            // above (24h -> 12h) and adding backoff criteria actually take effect for an app that already
            // has this job enqueued from a prior install, not just for a fresh one. UPDATE preserves the
            // job's own unique name/id and simply replaces its WorkSpec (period/constraints/backoff),
            // which is exactly what's needed here - there's no in-flight state on this particular job
            // worth preserving across the swap (each run is independent, and a run already executing
            // finishes normally either way per WorkManager's own UPDATE semantics).
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
