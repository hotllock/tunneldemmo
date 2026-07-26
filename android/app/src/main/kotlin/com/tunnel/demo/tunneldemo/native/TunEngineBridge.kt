package com.tunnel.demo.tunneldemo.native

import android.util.Log
import com.tunnel.demo.tunneldemo.service.InspectionVpnService

object TunEngineBridge {

    private const val TAG = "TunEngineBridge"
    private var loaded = false

    // Stats type constants
    const val STAT_PACKETS = 0
    const val STAT_BYTES = 1
    const val STAT_HTTP = 2
    const val STAT_HTTPS = 3
    const val STAT_ERRORS = 4
    const val STAT_ACTIVE_CONNS = 5

    init {
        try {
            System.loadLibrary("tun_engine")
            loaded = true
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
            Log.w(TAG, "Native library not available: ${e.message}")
        }
    }

    fun isNativeAvailable(): Boolean = loaded

    fun nativeInit(tunFd: Int): Boolean {
        if (!loaded || tunFd < 0) return false
        return try {
            init(tunFd) == 0
        } catch (e: Exception) {
            Log.e(TAG, "nativeInit failed", e)
            false
        }
    }

    fun nativeStop() {
        if (!loaded) return
        try { stop() } catch (e: Exception) { Log.e(TAG, "nativeStop failed", e) }
    }

    fun isRunning(): Boolean {
        if (!loaded) return false
        return try { isRunning() } catch (_: Exception) { false }
    }

    @JvmStatic
    fun nativeSetProxyPort(port: Int) {
        if (!loaded) return
        try { setProxyPort(port) } catch (_: Exception) {}
    }

    @JvmStatic
    fun nativeGetStats(statType: Int): Int {
        if (!loaded) return 0
        return try { getStats(statType) } catch (_: Exception) { 0 }
    }

    fun getPacketCount(): Int = nativeGetStats(STAT_PACKETS)
    fun getBytesCount(): Int = nativeGetStats(STAT_BYTES)
    fun getHttpCount(): Int = nativeGetStats(STAT_HTTP)
    fun getHttpsCount(): Int = nativeGetStats(STAT_HTTPS)
    fun getErrorCount(): Int = nativeGetStats(STAT_ERRORS)
    fun getActiveConnections(): Int = nativeGetStats(STAT_ACTIVE_CONNS)

    /** Protect a native socket fd from VPN loop. Called by C++ engine via JNI. */
    @JvmStatic
    fun nativeProtectSocket(fd: Int): Boolean {
        if (fd < 0) return false
        return try {
            InspectionVpnService.protectSocket(fd)
        } catch (_: Exception) { false }
    }

    // --- Native methods ---
    private external fun init(tunFd: Int): Int
    private external fun isRunning(): Boolean
    private external fun stop()
    private external fun setProxyPort(port: Int)
    private external fun getStats(statType: Int): Int
}
