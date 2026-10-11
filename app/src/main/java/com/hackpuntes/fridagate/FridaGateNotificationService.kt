package com.hackpuntes.fridagate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import com.hackpuntes.fridagate.utils.FridaInjectUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mantiene el icono flotante de FridaGate durante las pruebas.
 * Android exige una notificación para mantener un servicio en primer plano;
 * se usa una notificación mínima y silenciosa, sin el antiguo título de marca.
 */
class FridaGateNotificationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var floatingView: View? = null
    private var bubbleView: ImageView? = null
    private var panelView: LinearLayout? = null
    private var stopButtonView: Button? = null
    private var windowManager: WindowManager? = null
    private var activeTargetPackage: String = ""

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Servicio de icono flotante", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Mantiene disponible el icono flotante de FridaGate"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_VISIBLE, false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RETURN_TO_APP -> {
                openFridaGate()
                return START_NOT_STICKY
            }
            ACTION_STOP_TARGET -> {
                val target = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
                    .ifBlank { activeTargetPackage }
                stopTargetAndReturn(target)
                return START_NOT_STICKY
            }
        }

        activeTargetPackage = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
            .ifBlank {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_TARGET, "").orEmpty()
            }
        stopButtonView?.isEnabled = activeTargetPackage.isNotBlank()

        // Foreground services must post a notification on modern Android.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 1001, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.fridagate_icon)
            .setContentTitle("Servicio de icono flotante")
            .setContentText("Icono flotante de FridaGate activo")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(contentIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        showFloatingPanel()
        return START_NOT_STICKY
    }

    private fun showFloatingPanel() {
        if (!Settings.canDrawOverlays(this) || floatingView != null) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val density = resources.displayMetrics.density
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            }

            val bubble = ImageView(this).apply {
                setImageDrawable(packageManager.getApplicationIcon(packageName))
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Icono flotante de FridaGate. Toca para abrir el menú."
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(72, 90, 180))
                    setStroke((2 * density).toInt(), Color.WHITE)
                }
                clipToOutline = true
                elevation = 12f
            }
            root.addView(bubble, LinearLayout.LayoutParams((56 * density).toInt(), (56 * density).toInt()))

            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.rgb(30, 34, 48))
                    cornerRadius = 14 * density
                    setStroke((1 * density).toInt(), Color.rgb(120, 130, 190))
                }
                elevation = 16f
            }
            val iconHeader = ImageView(this).apply {
                setImageDrawable(packageManager.getApplicationIcon(packageName))
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "FridaGate 2.0"
            }
            panel.addView(iconHeader, LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (4 * density).toInt()
            })

            val returnButton = Button(this).apply {
                text = "VOLVER A FRIDAGATE 2.0"
                isAllCaps = true
                setOnClickListener {
                    panel.visibility = View.GONE
                    openFridaGate()
                }
            }
            panel.addView(returnButton, LinearLayout.LayoutParams((220 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))

            val stopButton = Button(this).apply {
                text = "DETENER APLICACIÓN"
                isAllCaps = true
                isEnabled = activeTargetPackage.isNotBlank()
                setOnClickListener { stopTargetAndReturn(activeTargetPackage) }
            }
            panel.addView(stopButton, LinearLayout.LayoutParams((220 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            stopButtonView = stopButton

            val byline = android.widget.TextView(this).apply {
                text = "BY TINCHODOKO"
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, (6 * density).toInt(), 0, (4 * density).toInt())
            }
            panel.addView(byline, LinearLayout.LayoutParams((220 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            root.addView(panel)
            bubbleView = bubble
            panelView = panel

            val params = WindowManager.LayoutParams(
                (244 * density).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (12 * density).toInt()
                y = (160 * density).toInt()
            }

            var startX = 0f
            var startY = 0f
            var initialX = 0
            var initialY = 0
            var moved = false
            bubble.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        initialX = params.x
                        initialY = params.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        if (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5) moved = true
                        params.x = initialX - dx.toInt()
                        params.y = initialY + dy.toInt()
                        runCatching { windowManager?.updateViewLayout(root, params) }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                            runCatching { windowManager?.updateViewLayout(root, params) }
                        }
                        true
                    }
                    else -> true
                }
            }
            windowManager?.addView(root, params)
            floatingView = root
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_VISIBLE, true).apply()
        } catch (_: Exception) {
            floatingView = null
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_VISIBLE, false).apply()
        }
    }

    private fun openFridaGate() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(launch)
    }

    private fun stopTargetAndReturn(targetPackage: String) {
        if (targetPackage.isBlank()) {
            openFridaGate()
            return
        }
        serviceScope.launch {
            FridaInjectUtils.stopTargetApp(targetPackage)
            withContext(Dispatchers.Main) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .remove(PREF_TARGET)
                    .putBoolean(PREF_VISIBLE, false)
                    .apply()
                openFridaGate()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun removeFloatingPanel() {
        try { floatingView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        floatingView = null
        bubbleView = null
        panelView = null
        stopButtonView = null
        windowManager = null
    }

    override fun onDestroy() {
        removeFloatingPanel()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_VISIBLE, false).apply()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_HIDE = "com.hackpuntes.fridagate.action.HIDE_NOTIFICATION"
        const val ACTION_SCRIPT_INJECTED = "com.hackpuntes.fridagate.action.SCRIPT_INJECTED"
        const val ACTION_STOP_TARGET = "com.hackpuntes.fridagate.action.STOP_TARGET"
        const val ACTION_RETURN_TO_APP = "com.hackpuntes.fridagate.action.RETURN_TO_APP"
        const val EXTRA_SCRIPT_NAME = "extra_script_name"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val PREFS_NAME = "fridagate_runtime"
        const val PREF_VISIBLE = "floating_icon_visible"
        const val PREF_TARGET = "active_target_package"
        private const val CHANNEL_ID = "fridagate_floating_service"
        private const val NOTIFICATION_ID = 2200
    }
}
