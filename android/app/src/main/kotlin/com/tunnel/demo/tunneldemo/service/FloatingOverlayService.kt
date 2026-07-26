package com.tunnel.demo.tunneldemo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.tunnel.demo.tunneldemo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FloatingOverlayService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "overlay_service_channel"
        const val ACTION_SHOW = "com.tunnel.demo.tunneldemo.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.tunnel.demo.tunneldemo.HIDE_OVERLAY"

        private var isOverlayShowing = false
        private var isMinimized = false

        fun isOverlayVisible(): Boolean = isOverlayShowing

        fun hasOverlayPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        }

        fun openOverlaySettings(context: Context) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statsUpdateJob: Job? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private val _vpnState = MutableStateFlow(false)
    val vpnState: StateFlow<Boolean> = _vpnState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                if (hasOverlayPermission(this)) {
                    showOverlay()
                }
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_HIDE -> {
                hideOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (isOverlayShowing) return
        isOverlayShowing = true

        val layoutInflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = layoutInflater.inflate(R.layout.overlay_floating_widget, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = layoutFlag
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        overlayView?.let { view ->
            windowManager.addView(view, layoutParams)
            setupViewListeners(view)
            startStatsUpdates()
        }
    }

    private fun hideOverlay() {
        if (!isOverlayShowing) return
        isOverlayShowing = false
        statsUpdateJob?.cancel()
        statsUpdateJob = null

        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
        }
        overlayView = null
        layoutParams = null
    }

    private fun setupViewListeners(view: View) {
        val rootLayout = view.findViewById<View>(R.id.overlay_root)

        rootLayout?.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener true

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    val distance = Math.sqrt((dx * dx + dy * dy).toDouble())
                    if (distance < 10.0) {
                        // It's a tap, not a drag
                        handleTap(view)
                    }
                    true
                }
                else -> false
            }
        }

        val toggleButton = view.findViewById<Button>(R.id.btn_toggle_vpn)
        toggleButton?.setOnClickListener {
            toggleVpn()
        }

        val minimizeButton = view.findViewById<View>(R.id.btn_minimize)
        minimizeButton?.setOnClickListener {
            toggleMinimize(view)
        }

        val closeButton = view.findViewById<View>(R.id.btn_close)
        closeButton?.setOnClickListener {
            stopSelf()
        }
    }

    private fun handleTap(view: View) {
        if (isMinimized) {
            toggleMinimize(view)
        }
    }

    private fun toggleMinimize(view: View) {
        isMinimized = !isMinimized

        val expandedLayout = view.findViewById<View>(R.id.overlay_expanded)
        val minimizedLayout = view.findViewById<View>(R.id.overlay_minimized)

        expandedLayout?.visibility = if (isMinimized) View.GONE else View.VISIBLE
        minimizedLayout?.visibility = if (isMinimized) View.VISIBLE else View.GONE

        // Resize
        val params = layoutParams
        if (params != null) {
            params.width = if (isMinimized) {
                WindowManager.LayoutParams.WRAP_CONTENT
            } else {
                WindowManager.LayoutParams.WRAP_CONTENT
            }
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }
    }

    private fun toggleVpn() {
        val intent = Intent(this, InspectionVpnService::class.java).apply {
            action = if (_vpnState.value) {
                InspectionVpnService.ACTION_STOP
            } else {
                InspectionVpnService.ACTION_START
            }
        }
        startService(intent)
        _vpnState.value = !_vpnState.value
    }

    private fun startStatsUpdates() {
        statsUpdateJob?.cancel()
        statsUpdateJob = serviceScope.launch {
            while (true) {
                updateStatsDisplay()
                _vpnState.value = PacketStatsCollector.getActiveConnections() > 0 ||
                        PacketStatsCollector.getPacketsProcessed() > 0
                delay(1000L)
            }
        }
    }

    private fun updateStatsDisplay() {
        val view = overlayView ?: return
        val stats = PacketStatsCollector.getSnapshot()

        val packetsText = view.findViewById<TextView>(R.id.txt_packets)
        packetsText?.text = "P: ${stats.totalPackets}"

        val httpText = view.findViewById<TextView>(R.id.txt_http)
        httpText?.text = "HTTP: ${stats.httpPackets}"

        val activeText = view.findViewById<TextView>(R.id.txt_active)
        activeText?.text = "Act: ${stats.activeConnections}"

        val toggleButton = view.findViewById<Button>(R.id.btn_toggle_vpn)
        toggleButton?.text = if (_vpnState.value) "Stop" else "Start"

        // Also update minimized view
        val miniPackets = view.findViewById<TextView>(R.id.txt_mini_packets)
        miniPackets?.text = "${stats.totalPackets}"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating overlay for NetPeeker"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val hideIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = ACTION_HIDE
        }
        val hidePendingIntent = PendingIntent.getService(
            this, 0, hideIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NetPeeker Overlay")
            .setContentText("Floating stats overlay active")
            .setSmallIcon(R.drawable.ic_overlay)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_hide, "Hide", hidePendingIntent)
            .build()
    }
}
