package com.hackpuntes.fridagate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hackpuntes.fridagate.utils.FridaInjectUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows FridaGate's foreground notification while instrumentation is active.
 * The STOP action uses the same stopTargetApp routine as the Scripts screen.
 */
class FridaGateNotificationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "FridaGate en ejecución", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_TARGET -> {
                val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
                if (targetPackage.isBlank()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    FridaInjectUtils.stopTargetApp(targetPackage)
                    withContext(Dispatchers.Main) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
        }

        val scriptName = intent?.getStringExtra(EXTRA_SCRIPT_NAME).orEmpty()
        val targetPackage = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 1001, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.fridagate_icon)
            .setContentTitle("FRIDAGATE 2.0 - TINCHODOKO")
            .setContentText("${scriptName.ifBlank { "Script" }} inyectado")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${scriptName.ifBlank { "Script" }} inyectado\n$targetPackage"
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)

        if (targetPackage.isNotBlank()) {
            val stopIntent = Intent(this, FridaGateNotificationService::class.java).apply {
                action = ACTION_STOP_TARGET
                putExtra(EXTRA_PACKAGE_NAME, targetPackage)
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                targetPackage.hashCode(),
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "STOP", stopPendingIntent)
        } else {
            builder.setContentText("En ejecución · By Tinchodoko")
        }

        val notification: Notification = builder.build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_HIDE = "com.hackpuntes.fridagate.action.HIDE_NOTIFICATION"
        const val ACTION_SCRIPT_INJECTED = "com.hackpuntes.fridagate.action.SCRIPT_INJECTED"
        const val ACTION_STOP_TARGET = "com.hackpuntes.fridagate.action.STOP_TARGET"
        const val EXTRA_SCRIPT_NAME = "extra_script_name"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val CHANNEL_ID = "fridagate_running"
        private const val NOTIFICATION_ID = 2200
    }
}
