package com.hackpuntes.fridagate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.provider.Settings
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
    private var floatingView: View? = null
    private var windowManager: WindowManager? = null

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

        showFloatingBubble()
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
            .setContentText("${scriptName.ifBlank { "Script" }} · inyección solicitada; verifica el registro")
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

    private fun showFloatingBubble() {
        if (!Settings.canDrawOverlays(this) || floatingView != null) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val bubble = TextView(this).apply {
                text = "FG"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                contentDescription = "FridaGate activo. Toca para volver a la aplicación."
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(72, 90, 180))
                    setStroke(2, Color.WHITE)
                }
                elevation = 12f
            }
            val params = WindowManager.LayoutParams(
                (52 * resources.displayMetrics.density).toInt(),
                (52 * resources.displayMetrics.density).toInt(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (12 * resources.displayMetrics.density).toInt()
                y = (160 * resources.displayMetrics.density).toInt()
            }
            var startX = 0f
            var startY = 0f
            var initialX = 0
            var initialY = 0
            bubble.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX; startY = event.rawY
                        initialX = params.x; initialY = params.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX - (event.rawX - startX).toInt()
                        params.y = initialY + (event.rawY - startY).toInt()
                        runCatching { windowManager?.updateViewLayout(view, params) }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (kotlin.math.abs(event.rawX - startX) < 8 && kotlin.math.abs(event.rawY - startY) < 8) {
                            val launch = packageManager.getLaunchIntentForPackage(packageName)
                            launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            if (launch != null) startActivity(launch)
                        }
                        true
                    }
                    else -> true
                }
            }
            windowManager?.addView(bubble, params)
            floatingView = bubble
        } catch (_: Exception) {
            floatingView = null
        }
    }

    private fun removeFloatingBubble() {
        try { floatingView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        floatingView = null
        windowManager = null
    }

    override fun onDestroy() {
        removeFloatingBubble()
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
