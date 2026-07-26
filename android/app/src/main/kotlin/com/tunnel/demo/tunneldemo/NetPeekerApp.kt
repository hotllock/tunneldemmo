package com.tunnel.demo.tunneldemo

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Environment
import android.util.Log
import com.tunnel.demo.tunneldemo.util.PrefsManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.FileWriter
import java.security.Security
import java.text.SimpleDateFormat
import java.util.*

class NetPeekerApp : Application() {

    companion object {
        @Volatile
        lateinit var instance: NetPeekerApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        PrefsManager.init(this)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.US)
                val time = sdf.format(Date())
                val dir = getExternalFilesDir(null) ?: filesDir
                val logFile = File(dir, "crash_$time.txt")
                FileWriter(logFile).use { w ->
                    w.write("Thread: ${thread.name}\n")
                    w.write("Time: $time\n\n")
                    throwable.printStackTrace(java.io.PrintWriter(w))
                }
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
