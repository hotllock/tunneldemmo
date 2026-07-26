package com.tunnel.demo.tunneldemo

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.tunnel.demo.tunneldemo.util.PrefsManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

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

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                // Memory leak detection hook
            }
        })

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("NetPeeker", "Uncaught on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
