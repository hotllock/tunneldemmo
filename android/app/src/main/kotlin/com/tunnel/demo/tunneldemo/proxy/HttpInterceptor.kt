package com.tunnel.demo.tunneldemo.proxy

import com.tunnel.demo.tunneldemo.model.*
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentLinkedQueue

class HttpInterceptor {

    private val interceptedRequests = ConcurrentLinkedQueue<InterceptedRequest>()

    data class InterceptedRequest(
        val id: String,
        val requestBytes: ByteArray,
        val timestamp: Long,
        val clientAddress: String = ""
    )

    fun captureRequest(requestBytes: ByteArray, clientAddr: String): InterceptedRequest {
        val req = InterceptedRequest(
            id = java.util.UUID.randomUUID().toString(),
            requestBytes = requestBytes,
            timestamp = System.currentTimeMillis(),
            clientAddress = clientAddr
        )
        interceptedRequests.add(req)
        if (interceptedRequests.size > 500) interceptedRequests.poll()
        return req
    }

    fun parseRequest(data: ByteArray): Transaction? {
        return try {
            val request = String(data, Charsets.UTF_8)
            val lines = request.split("\r\n")
            if (lines.isEmpty()) return null

            val requestLine = lines[0].split(" ")
            if (requestLine.size < 2) return null

            val method = HttpMethod.fromString(requestLine[0])
            val urlPath = requestLine[1]
            val headers = mutableMapOf<String, String>()
            val headerLines = lines.drop(1)
            var bodyStart = -1

            for ((i, line) in headerLines.withIndex()) {
                if (line.isBlank()) {
                    bodyStart = i + 1
                    break
                }
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim()] =
                        line.substring(colon + 1).trim()
                }
            }

            val host = headers["Host"] ?: ""
            val body = if (bodyStart > 0 && bodyStart < headerLines.size) {
                headerLines.drop(bodyStart).joinToString("\r\n")
            } else null

            Transaction(
                id = java.util.UUID.randomUUID().toString(),
                method = method,
                host = host,
                path = urlPath,
                fullUrl = if (urlPath.startsWith("http")) urlPath
                else "http://$host$urlPath",
                requestHeaders = headers,
                requestBody = body,
                requestBodyBytes = body?.toByteArray(),
                requestTime = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }

    fun modifyRequest(
        transaction: Transaction,
        newHeaders: Map<String, String>? = null,
        newBody: String? = null,
        newMethod: HttpMethod? = null,
        newPath: String? = null
    ): Transaction {
        return transaction.copy(
            method = newMethod ?: transaction.method,
            path = newPath ?: transaction.path,
            requestHeaders = if (newHeaders != null) {
                (transaction.requestHeaders + newHeaders).toMutableMap()
            } else transaction.requestHeaders,
            requestBody = newBody ?: transaction.requestBody,
            requestBodyBytes = (newBody ?: transaction.requestBody)?.toByteArray(),
            status = TransactionStatus.INTERCEPTED
        )
    }

    fun rebuildRequestBytes(transaction: Transaction): ByteArray {
        val sb = StringBuilder()
        sb.append("${transaction.method.value} ${transaction.path} HTTP/1.1\r\n")
        transaction.requestHeaders.forEach { (k, v) ->
            sb.append("$k: $v\r\n")
        }
        if (transaction.requestBody != null) {
            sb.append("Content-Length: ${transaction.requestBody!!.toByteArray().size}\r\n")
        }
        sb.append("\r\n")
        if (transaction.requestBody != null) {
            sb.append(transaction.requestBody)
        }
        return sb.toString().toByteArray()
    }
}
