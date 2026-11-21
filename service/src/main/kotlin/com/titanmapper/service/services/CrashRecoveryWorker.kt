package com.titanmapper.service.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-shot restart of [TitanInputService], enqueued by [com.titanmapper.app.TitanApplication]'s
 * uncaught-exception handler with `ExistingWorkPolicy.REPLACE` and WorkManager's standard
 * exponential backoff, starting from [androidx.work.WorkRequest.MIN_BACKOFF_MILLIS], so a repeat
 * crash retries with growing delay instead of hammering the battery (Architecture §8). The
 * profile the service resumes on is whatever [SettingsRepository]/[ProfileRepository] already
 * persist -- this worker only needs to get the foreground service running again.
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
