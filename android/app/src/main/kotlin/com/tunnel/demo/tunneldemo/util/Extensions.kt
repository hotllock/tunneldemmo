package com.tunnel.demo.tunneldemo.util

import android.content.Context
import android.net.wifi.WifiInfo
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.net.InetAddress
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun String.toHex(): String {
    return this.map { byte ->
        String.format("%02x", byte.code)
    }.joinToString("")
}

fun ByteArray.toHexString(): String {
    return this.joinToString("") { byte ->
        String.format("%02x", byte.toInt() and 0xFF)
    }
}

fun ByteArray.toHexStringWithSpaces(): String {
    return this.joinToString(" ") { byte ->
        String.format("%02x", byte.toInt() and 0xFF)
    }
}

fun Date.formatLog(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    return sdf.format(this)
}

fun Long.formatTimestamp(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    return sdf.format(Date(this))
}

fun View.hideKeyboard() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(windowToken, 0)
}

fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

fun Context.toastLong(message: String) {
    toast(message, Toast.LENGTH_LONG)
}

fun Int.toIpAddressString(): String {
    return try {
        InetAddress.getByAddress(
            byteArrayOf(
                (this shr 24 and 0xFF).toByte(),
                (this shr 16 and 0xFF).toByte(),
                (this shr 8 and 0xFF).toByte(),
                (this and 0xFF).toByte()
            )
        ).hostAddress ?: "0.0.0.0"
    } catch (_: Exception) {
        "0.0.0.0"
    }
}

fun String.toIpAddressInt(): Int {
    return try {
        val parts = this.split(".")
        if (parts.size == 4) {
            ((parts[0].toInt() and 0xFF) shl 24) or
                    ((parts[1].toInt() and 0xFF) shl 16) or
                    ((parts[2].toInt() and 0xFF) shl 8) or
                    (parts[3].toInt() and 0xFF)
        } else 0
    } catch (_: Exception) {
        0
    }
}

fun Int.toPortString(): String {
    return this.toString()
}

fun String.toPortInt(): Int {
    return this.toIntOrNull() ?: 0
}

fun Long.toHumanReadableSize(): String {
    return when {
        this < 1024L -> "$this B"
        this < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", this / 1024.0)
        this < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", this / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", this / (1024.0 * 1024.0 * 1024.0))
    }
}

fun Int.toHumanReadableSpeed(): String {
    return (this.toLong()).toHumanReadableSize()
}

fun ByteBuffer.toByteArray(): ByteArray {
    val arr = ByteArray(remaining())
    get(arr)
    return arr
}

fun String.isValidIpAddress(): Boolean {
    val ipPattern = Regex(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
                "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    )
    return ipPattern.matches(this)
}

fun String.isValidHostname(): Boolean {
    val hostnamePattern = Regex(
        "^([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])" +
                "(\\.([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9]))*$"
    )
    return hostnamePattern.matches(this)
}

fun Collection<String>.toHttpHeaders(): Map<String, String> {
    return this.associate { line ->
        val idx = line.indexOf(":")
        if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        else "Unknown" to line
    }
}

fun Map<String, String>.toHttpHeaderLines(): List<String> {
    return this.map { (key, value) -> "$key: $value" }
}

fun Long.formatBytes(): String = when {
    this < 1024L -> "$this B"
    this < 1048576L -> "%.1f KB".format(this / 1024.0)
    this < 1073741824L -> "%.1f MB".format(this / 1048576.0)
    else -> "%.1f GB".format(this / 1073741824.0)
}

fun Long.formatElapsed(): String = when {
    this < 1000 -> "${this}ms"
    this < 60000 -> "%.1fs".format(this / 1000.0)
    else -> "${this / 60000}m ${(this % 60000) / 1000}s"
}

fun String.truncate(maxLen: Int): String =
    if (length <= maxLen) this else substring(0, maxLen) + "..."
