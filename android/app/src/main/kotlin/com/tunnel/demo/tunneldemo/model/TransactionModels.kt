package com.tunnel.demo.tunneldemo.model

import java.io.Serializable
import java.util.UUID

enum class HttpMethod(val value: String) {
    GET("GET"), POST("POST"), PUT("PUT"), DELETE("DELETE"),
    PATCH("PATCH"), HEAD("HEAD"), OPTIONS("OPTIONS"),
    CONNECT("CONNECT"), TRACE("TRACE"), OTHER("OTHER");

    companion object {
        fun fromString(s: String): HttpMethod =
            entries.find { it.value == s.uppercase() } ?: OTHER
    }
}

enum class TransactionStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED, INTERCEPTED, REPLAYED
}

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val method: HttpMethod = HttpMethod.GET,
    val host: String = "",
    val path: String = "/",
    val fullUrl: String = "",
    val statusCode: Int = 0,
    val requestHeaders: MutableMap<String, String> = mutableMapOf(),
    val responseHeaders: MutableMap<String, String> = mutableMapOf(),
    val requestBody: String? = null,
    val requestBodyBytes: ByteArray? = null,
    val responseBody: String? = null,
    val responseBodyBytes: ByteArray? = null,
    val requestTime: Long = System.currentTimeMillis(),
    val responseTime: Long = 0,
    val contentLength: Long = 0,
    val isHttps: Boolean = false,
    val tlsVersion: String? = null,
    val cipherSuite: String? = null,
    val status: TransactionStatus = TransactionStatus.PENDING,
    val errorMessage: String? = null,
    val sourceIp: String = "",
    val destinationIp: String = "",
    val sourcePort: Int = 0,
    val destinationPort: Int = 0,
) : Serializable {
    val elapsed: Long get() = if (responseTime > 0) responseTime - requestTime else 0
    val statusCategory: Int get() = statusCode / 100
    val isSecure: Boolean get() = isHttps || destinationPort == 443

    fun toReplayRequest(): ReplayRequest = ReplayRequest(
        method = method,
        url = fullUrl.ifEmpty { "http://$host$path" },
        headers = requestHeaders.toMap(),
        body = requestBody
    )
}

data class ReplayRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String>,
    val body: String?
)

data class StatsData(
    val totalPackets: Long = 0,
    val httpPackets: Long = 0,
    val httpsConnections: Long = 0,
    val bytesTransferred: Long = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val activeConnections: Long = 0,
    val errorsCount: Long = 0,
    val uptimeMs: Long = 0,
    val isRunning: Boolean = false
)
