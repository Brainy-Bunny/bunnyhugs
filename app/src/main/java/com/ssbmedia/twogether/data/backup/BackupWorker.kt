package com.ssbmedia.twogether.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Runs the same export as the manual "Back up now" button, on a weekly schedule via WorkManager. */
class BackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): WorkResult {
        val result = BackupManager.createBackup(applicationContext)
        // Retry (WorkManager's own backoff policy) rather than fail outright on a transient error (e.g.
        // storage momentarily unavailable) - createBackup() already recorded the failure via
        // setLastBackupResult() either way, so Settings' "last backup" status is accurate regardless.
        return if (result.success) WorkResult.success() else WorkResult.retry()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "weekly_backup"

        /**
         * Registers the weekly periodic backup job, idempotently: ExistingPeriodicWorkPolicy.KEEP means
         * calling this on every app start (or every re-pairing) never stacks a second duplicate periodic
         * job - if one is already scheduled, this call is a no-op. Deliberately no constraints (no
         * charging/unmetered-network/idle requirement) since this just needs to reliably run in the
         * background on its own weekly cadence, matching the task's "should work without requiring
         * charging/wifi" requirement.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
