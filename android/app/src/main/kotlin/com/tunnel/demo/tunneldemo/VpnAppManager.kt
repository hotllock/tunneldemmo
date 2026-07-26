package com.tunnel.demo.tunneldemo

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.tunnel.demo.tunneldemo.util.PrefsManager

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

object VpnAppManager {

    private const val KEY_ALLOWED_APPS = "vpn_allowed_apps"

    fun getAllowedApps(): Set<String> {
        val raw = PrefsManager.getInstance().getString(KEY_ALLOWED_APPS, "")
        if (raw.isEmpty()) return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun setAllowedApps(packages: Set<String>) {
        PrefsManager.getInstance().putString(KEY_ALLOWED_APPS, packages.joinToString(","))
    }

    fun addAllowedApp(packageName: String) {
        val current = getAllowedApps().toMutableSet()
        current.add(packageName)
        setAllowedApps(current)
    }

    fun removeAllowedApp(packageName: String) {
        val current = getAllowedApps().toMutableSet()
        current.remove(packageName)
        setAllowedApps(current)
    }

    fun isAppAllowed(packageName: String): Boolean {
        return getAllowedApps().contains(packageName)
    }

    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        return intent
            .filter { it.packageName != context.packageName }
            .map {
                val name = pm.getApplicationLabel(it).toString()
                val icon = it.loadIcon(pm)
                AppInfo(it.packageName, name, icon)
            }
            .sortedBy { it.label.lowercase() }
    }
}
