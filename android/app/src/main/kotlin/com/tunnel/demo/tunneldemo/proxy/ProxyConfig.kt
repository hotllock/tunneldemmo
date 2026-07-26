package com.tunnel.demo.tunneldemo.proxy

data class ProxyConfig(
    val useCustomDns: Boolean = false,
    val dohUrl: String = "https://cloudflare-dns.com/dns-query",
    val hostsOverride: Map<String, String> = emptyMap(),
    val useUpstreamProxy: Boolean = false,
    val upstreamType: String = "socks5",
    val upstreamHost: String = "127.0.0.1",
    val upstreamPort: Int = 1080,
    val upstreamUser: String = "",
    val upstreamPass: String = "",
    val spoofUserAgent: Boolean = false,
    val customUserAgent: String = "Mozilla/5.0 (Android 14; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0",
    val stripHeaders: List<String> = listOf("X-Forwarded-For", "Via"),
    val enabledSniSpoof: Boolean = false,
    val sniOverride: Map<String, String> = emptyMap()
) {
    companion object {
        val DEFAULT = ProxyConfig()
    }
}
