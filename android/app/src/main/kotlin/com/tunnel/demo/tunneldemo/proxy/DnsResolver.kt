package com.tunnel.demo.tunneldemo.proxy

import android.util.Log
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

object DnsResolver {

    private const val TAG = "DnsResolver"
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()
    private val cacheTtl = ConcurrentHashMap<String, Long>()
    private const val CACHE_TTL_MS = 300_000L

    private val fallbackDohServers = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/dns-query",
        "https://9.9.9.9/dns-query",
        "https://dns.quad9.net/dns-query",
        "https://dns.google/dns-query",
        "https://cloudflare-dns.com/dns-query"
    )

    private var config: ProxyConfig = ProxyConfig.DEFAULT

    fun setConfig(cfg: ProxyConfig) { config = cfg }
    fun getConfig(): ProxyConfig = config

    fun resolve(host: String): InetAddress? {
        // Check cache
        val now = System.currentTimeMillis()
        cache[host]?.let { ips ->
            val expiry = cacheTtl[host] ?: 0
            if (now < expiry) return ips.firstOrNull()
        }

        // Check hosts override
        config.hostsOverride[host]?.let { ip ->
            try {
                val addr = InetAddress.getByName(ip)
                cache[host] = listOf(addr)
                cacheTtl[host] = now + CACHE_TTL_MS
                return addr
            } catch (_: Exception) {}
        }

        // Try DoH if enabled
        if (config.useCustomDns) {
            val result = resolveViaDoh(host)
            if (result != null) {
                cache[host] = result
                cacheTtl[host] = now + CACHE_TTL_MS
                return result.firstOrNull()
            }
        }

        // Fall back to system DNS
        return try {
            val addrs = InetAddress.getAllByName(host).toList()
            cache[host] = addrs
            cacheTtl[host] = now + CACHE_TTL_MS
            addrs.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "System DNS failed for $host: ${e.message}")
            null
        }
    }

    fun resolveAll(host: String): List<InetAddress> {
        resolve(host)
        return cache[host] ?: emptyList()
    }

    fun clearCache() {
        cache.clear()
        cacheTtl.clear()
    }

    private fun resolveViaDoh(host: String): List<InetAddress>? {
        dohServers@ for (dohUrl in getDohUrls()) {
            try {
                val url = "$dohUrl?name=${URLEncoder.encode(host, "UTF-8")}&type=A"
                val conn = URL(url).openConnection() as HttpsURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Accept", "application/dns-json")

                val code = conn.responseCode
                if (code != 200) continue

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val ips = parseDohResponse(body)
                if (ips.isNotEmpty()) {
                    Log.i(TAG, "DoH resolved $host -> ${ips.first()} via $dohUrl")
                    return ips
                }
            } catch (e: Exception) {
                Log.w(TAG, "DoH failed $dohUrl for $host: ${e.message}")
            }
        }
        return null
    }

    private fun getDohUrls(): List<String> {
        val urls = mutableListOf<String>()
        if (config.useCustomDns && config.dohUrl.isNotBlank()) {
            urls.add(config.dohUrl)
        }
        urls.addAll(fallbackDohServers)
        return urls.distinct()
    }

    private fun parseDohResponse(json: String): List<InetAddress> {
        val ips = mutableListOf<InetAddress>()
        try {
            val obj = org.json.JSONObject(json)
            val answer = obj.optJSONArray("Answer") ?: return ips
            for (i in 0 until answer.length()) {
                val entry = answer.getJSONObject(i)
                if (entry.optInt("type") == 1) { // A record
                    val data = entry.optString("data", "")
                    try {
                        ips.add(InetAddress.getByName(data))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return ips
    }
}
