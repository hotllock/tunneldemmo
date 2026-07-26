package com.tunnel.demo.tunneldemo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tunnel.demo.tunneldemo.MainActivity
import com.tunnel.demo.tunneldemo.R
import com.tunnel.demo.tunneldemo.VpnAppManager
import com.tunnel.demo.tunneldemo.native.TunEngineBridge
import kotlinx.coroutines.*

class InspectionVpnService : VpnService() {

    companion object {
        private const val TAG = "InspectionVpnService"

        const val ACTION_START = "com.tunnel.demo.tunneldemo.START_VPN"
        const val ACTION_STOP = "com.tunnel.demo.tunneldemo.STOP_VPN"
        const val NOTIFICATION_CHANNEL = "vpn_status"
        const val NOTIFICATION_ID = 1001
        const val VPN_MTU = 1500
        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_ADDRESS_PREFIX = 30
        const val VPN_DNS1 = "8.8.8.8"
        const val VPN_DNS2 = "1.1.1.1"

        @Volatile
        private var _instance: InspectionVpnService? = null

        @Volatile
        private var vpnFd: ParcelFileDescriptor? = null

        fun isRunning(): Boolean = vpnFd != null

        /** Key method: protects sockets from VPN loop by adding them to VPN exempt list */
        fun protectSocket(socket: java.net.Socket): Boolean {
            val inst = _instance ?: return false
            return try { inst.protect(socket) } catch (_: Exception) { false }
        }

        fun protectSocket(fd: Int): Boolean {
            val inst = _instance ?: return false
            return try { inst.protect(fd) } catch (_: Exception) { false }
        }

        fun start(context: Context) {
            val intent = Intent(context, InspectionVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InspectionVpnService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        _instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> {
                    startVpnInternal()
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
                ACTION_STOP -> {
                    stopVpnInternal()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand error", e)
            stopVpnInternal()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpnInternal()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnInternal()
        _instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startVpnInternal() {
        if (vpnFd != null) return
        try {
            val builder = Builder()
            builder.setSession(getString(R.string.vpn_connection_name))
            builder.setMtu(VPN_MTU)
            builder.addAddress(VPN_ADDRESS, VPN_ADDRESS_PREFIX)
            builder.addRoute("0.0.0.0", 0) // Route ALL traffic through VPN
            builder.addDnsServer(VPN_DNS1)
            builder.addDnsServer(VPN_DNS2)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // Allow VPN's own app to bypass the tunnel (prevent loop)
            builder.addDisallowedApplication(packageName)

            // Apply user-selected app filter: only inspect selected apps
            val allowedApps = VpnAppManager.getAllowedApps()
            if (allowedApps.isNotEmpty()) {
                for (pkg in allowedApps) {
                    builder.addAllowedApplication(pkg)
                }
            }

            vpnFd = builder.establish() ?: return
            LocalProxyServer.start(this)
            PacketStatsCollector.start()

            scope.launch {
                TunEngineBridge.nativeInit(vpnFd!!.fd)
            }
        } catch (e: Exception) {
            cleanupVpn()
        }
    }

    private fun stopVpnInternal() {
        scope.launch { TunEngineBridge.nativeStop() }
        cleanupVpn()
        PacketStatsCollector.reset()
        LocalProxyServer.stop()
    }

    private fun cleanupVpn() {
        try { vpnFd?.close() } catch (_: Exception) {}
        vpnFd = null
        LocalProxyServer.stop()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.notification_channel_vpn),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_vpn_running)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, InspectionVpnService::class.java).apply {
                action = ACTION_STOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_vpn_running))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.stop_vpn), stopIntent)
            .setOngoing(true)
            .setSound(null)
            .build()
    }
}
