package com.titanmapper.app

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. @HiltAndroidApp triggers Hilt's component hierarchy generation.
 * We also install a last-resort uncaught exception handler that logs a crash marker so
 * CrashRecoveryWorker can detect an abnormal shutdown on the next launch.
 */
@HiltAndroidApp
class TitanApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashMarker()
    }

    private fun installCrashMarker() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Write a crash marker to shared prefs before handing off to the system handler.
            // CrashRecoveryWorker reads this on next launch and auto-restarts the service if needed.
            runCatching {
                getSharedPreferences("titan_crash", MODE_PRIVATE)
                    .edit()
                    .putLong("last_crash_at", System.currentTimeMillis())
                    .putString("last_crash_msg", throwable.message?.take(500))
                    .apply()
            }
            Log.e("TitanMapper", "Uncaught exception on ${thread.name}", throwable)
            default?.uncaughtException(thread, throwable)
        }
    }
}
