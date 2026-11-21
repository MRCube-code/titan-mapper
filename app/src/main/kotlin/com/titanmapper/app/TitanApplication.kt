package com.titanmapper.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.titanmapper.service.services.CrashRecoveryWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Application entry point. @HiltAndroidApp triggers Hilt's component hierarchy generation.
 *
 * Also acts as the WorkManager [Configuration.Provider] (required so [HiltWorkerFactory] can
 * build [CrashRecoveryWorker], a @HiltWorker class -- the default WorkerFactory has no idea how
 * to call its Hilt-generated constructor) and installs a last-resort uncaught-exception handler
 * that writes a crash marker and enqueues [CrashRecoveryWorker] to restart [TitanInputService]
 * shortly after an abnormal process death.
 */
@HiltAndroidApp
class TitanApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        installCrashMarker()
    }

    private fun installCrashMarker() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Write a crash marker to shared prefs before handing off to the system handler.
            runCatching {
                getSharedPreferences("titan_crash", MODE_PRIVATE)
                    .edit()
                    .putLong("last_crash_at", System.currentTimeMillis())
                    .putString("last_crash_msg", throwable.message?.take(500))
                    .apply()
            }
            Log.e("TitanMapper", "Uncaught exception on ${thread.name}", throwable)
            // Bounded, one-shot recovery: REPLACE means a second crash before the first retry
            // fires just resets the single pending work item rather than stacking up duplicates,
            // and WorkManager's own exponential backoff keeps a genuine crash loop from
            // hammering the battery instead of retrying at a fixed short interval forever.
            runCatching {
                val request = OneTimeWorkRequestBuilder<CrashRecoveryWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(this)
                    .enqueueUniqueWork(CrashRecoveryWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            }
            default?.uncaughtException(thread, throwable)
        }
    }
}
