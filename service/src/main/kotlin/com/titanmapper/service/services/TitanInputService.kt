package com.titanmapper.service.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.titanmapper.service.R
import com.titanmapper.service.api.ServiceController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service hosting the remapping pipeline (Architecture §8). Must run in the
 * foreground on Android 8+ to avoid background-execution limits, and must declare a foreground
 * service *type* on Android 14+; there is no closer-fitting type than `specialUse` for HID
 * remapping, declared with a justification string in AndroidManifest.xml.
 */
@AndroidEntryPoint
class TitanInputService : Service() {

    @Inject lateinit var controller: ServiceController

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        controller.start()
        return START_STICKY // the crash-recovery worker also restarts us; this covers plain OS kills
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Titan Mapper active", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shows while Titan Mapper is remapping input"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = openApp?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_titan_notification)
            .setContentTitle("Titan Mapper is active")
            .setContentText("Remapping your keyboard and mouse")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "titan_input_service"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, TitanInputService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TitanInputService::class.java))
        }
    }
}
