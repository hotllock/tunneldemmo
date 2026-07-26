package com.tunnel.demo.tunneldemo.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PrefsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "netpeeker_prefs"

        const val KEY_VPN_ENABLED = "vpn_enabled"
        const val KEY_PROXY_PORT = "proxy_port"
        const val KEY_FIRST_LAUNCH = "first_launch"
        const val KEY_CA_INSTALLED = "ca_installed"
        const val KEY_THEME_MODE = "theme_mode"

        private const val DEFAULT_PROXY_PORT = 8080
        private const val DEFAULT_THEME_MODE = "system"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isVpnEnabled: Boolean
        get() = prefs.getBoolean(KEY_VPN_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_VPN_ENABLED, value) }

    var proxyPort: Int
        get() = prefs.getInt(KEY_PROXY_PORT, DEFAULT_PROXY_PORT)
        set(value) = prefs.edit { putInt(KEY_PROXY_PORT, value) }

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = prefs.edit { putBoolean(KEY_FIRST_LAUNCH, value) }

    var isCaInstalled: Boolean
        get() = prefs.getBoolean(KEY_CA_INSTALLED, false)
        set(value) = prefs.edit { putBoolean(KEY_CA_INSTALLED, value) }

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
        set(value) = prefs.edit { putString(KEY_THEME_MODE, value) }

    fun getString(key: String, default: String = ""): String {
        return prefs.getString(key, default) ?: default
    }

    fun putString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    fun getInt(key: String, default: Int = 0): Int {
        return prefs.getInt(key, default)
    }

    fun putInt(key: String, value: Int) {
        prefs.edit { putInt(key, value) }
    }

    fun getLong(key: String, default: Long = 0L): Long {
        return prefs.getLong(key, default)
    }

    fun putLong(key: String, value: Long) {
        prefs.edit { putLong(key, value) }
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return prefs.getBoolean(key, default)
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit { putBoolean(key, value) }
    }

    fun contains(key: String): Boolean {
        return prefs.contains(key)
    }

    fun remove(key: String) {
        prefs.edit { remove(key) }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    fun registerOnChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
