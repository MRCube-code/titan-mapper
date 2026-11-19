package com.titanmapper.service.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-shot restart of [TitanInputService], enqueued by the app's uncaught-exception handler with
 * `ExistingWorkPolicy.REPLACE` and exponential backoff (capped at 30s by the WorkRequest's own
 * backoff policy) so a genuine crash loop can't hammer the battery (Architecture §8). Reads the
 * last-known-good profile id persisted by the pipeline so the restarted service resumes
 * identically rather than falling back to "no profile".
 */
@HiltWorker
class CrashRecoveryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        TitanInputService.start(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "titan_crash_recovery"
    }
}
