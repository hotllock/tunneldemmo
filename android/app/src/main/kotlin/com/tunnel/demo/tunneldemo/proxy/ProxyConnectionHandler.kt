package com.tunnel.demo.tunneldemo.proxy

import com.tunnel.demo.tunneldemo.model.Transaction
import com.tunnel.demo.tunneldemo.model.TransactionStatus
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.concurrent.ConcurrentHashMap

class ProxyConnectionHandler(
    private val clientSocket: SocketChannel,
    private val selector: Selector
) {
    private val readBuffer = ByteBuffer.allocateDirect(65536)
    private val writeBuffer = ByteBuffer.allocateDirect(65536)
    private var remoteChannel: SocketChannel? = null
    private var remoteAddress: InetSocketAddress? = null
    private var requestBytes = ByteArrayOutputStream()
    private var responseBytes = ByteArrayOutputStream()
    private var transaction: Transaction? = null
    private var state = HandlerState.CONNECTING

    enum class HandlerState {
        CONNECTING, READING_REQUEST, CONNECTING_REMOTE,
        FORWARDING, READING_RESPONSE, COMPLETED, CLOSED
    }

    suspend fun handle(): Transaction? = withContext(Dispatchers.IO) {
        try {
            clientSocket.configureBlocking(false)
            clientSocket.register(selector, SelectionKey.OP_READ, this@ProxyConnectionHandler)

            state = HandlerState.READING_REQUEST

            // Read HTTP request
            while (state == HandlerState.READING_REQUEST) {
                selector.select(100)
                val key = clientSocket.keyFor(selector) ?: break
                if (!key.isReadable) continue

                readBuffer.clear()
                val n = clientSocket.read(readBuffer)
                if (n <= 0) { state = HandlerState.CLOSED; break }

                readBuffer.flip()
                val data = ByteArray(readBuffer.remaining())
                readBuffer.get(data)
                requestBytes.write(data)

                if (isRequestComplete(requestBytes.toByteArray())) break
            }

            if (state == HandlerState.CLOSED) return@withContext null

            val rawRequest = requestBytes.toByteArray()
            val interceptor = HttpInterceptor()
            val parsed = interceptor.parseRequest(rawRequest) ?: return@withContext null
            transaction = parsed.copy(status = TransactionStatus.IN_PROGRESS)

            val host = parsed.host
            val port = parsed.destinationPort.let { if (it > 0) it else if (parsed.isHttps) 443 else 80 }

            remoteAddress = InetSocketAddress(host, port)
            remoteChannel = SocketChannel.open().apply {
                configureBlocking(false)
                connect(remoteAddress)
                register(selector, SelectionKey.OP_CONNECT, this@ProxyConnectionHandler)
            }
            state = HandlerState.CONNECTING_REMOTE

            // Wait for connection
            while (state == HandlerState.CONNECTING_REMOTE) {
                selector.select(500)
                val key = remoteChannel?.keyFor(selector) ?: break
                if (key.isConnectable) {
                    remoteChannel?.finishConnect()
                    state = HandlerState.FORWARDING
                    break
                }
            }

            // Forward request
            if (state == HandlerState.FORWARDING) {
                val forwardData = interceptor.rebuildRequestBytes(parsed)
                val buf = ByteBuffer.wrap(forwardData)
                while (buf.hasRemaining()) {
                    remoteChannel?.write(buf)
                }
                remoteChannel?.register(selector, SelectionKey.OP_READ, this@ProxyConnectionHandler)
                state = HandlerState.READING_RESPONSE
            }

            // Read response
            while (state == HandlerState.READING_RESPONSE) {
                selector.select(200)
                val key = remoteChannel?.keyFor(selector) ?: break
                if (!key.isReadable) {
                    if (key.isWritable) continue
                    break
                }

                readBuffer.clear()
                val n = remoteChannel?.read(readBuffer) ?: -1
                if (n <= 0) break

                readBuffer.flip()
                val data = ByteArray(readBuffer.remaining())
                readBuffer.get(data)
                responseBytes.write(data)

                // Forward to client
                writeBuffer.clear()
                writeBuffer.put(data)
                writeBuffer.flip()
                while (writeBuffer.hasRemaining()) {
                    clientSocket.write(writeBuffer)
                }
            }

            state = HandlerState.COMPLETED
            val response = parseResponse(responseBytes.toByteArray())
            transaction?.copy(
                statusCode = response.first,
                responseBody = response.second,
                responseTime = System.currentTimeMillis(),
                status = TransactionStatus.COMPLETED
            )
        } catch (e: Exception) {
            e.printStackTrace()
            state = HandlerState.CLOSED
            transaction?.copy(
                status = TransactionStatus.FAILED,
                errorMessage = e.message,
                responseTime = System.currentTimeMillis()
            )
        } finally {
            close()
        }
    }

    private fun isRequestComplete(data: ByteArray): Boolean {
        val str = String(data, Charsets.UTF_8)
        val headerEnd = str.indexOf("\r\n\r\n")
        if (headerEnd < 0) return data.size > 65536

        val headers = str.substring(0, headerEnd)
        val contentLen = headers.lines()
            .find { it.startsWith("Content-Length:", true) }
            ?.split(":")?.getOrNull(1)?.trim()?.toIntOrNull() ?: 0

        val bodyStart = headerEnd + 4
        val currentBodyLen = data.size - bodyStart
        return currentBodyLen >= contentLen
    }

    private fun parseResponse(data: ByteArray): Pair<Int, String> {
        val str = String(data, Charsets.UTF_8)
        val lines = str.split("\r\n")
        val statusLine = lines.firstOrNull() ?: return 0 to ""
        val parts = statusLine.split(" ")
        val code = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val headerEnd = str.indexOf("\r\n\r\n")
        val body = if (headerEnd >= 0) str.substring(headerEnd + 4) else ""
        return code to body
    }

    fun close() {
        try { clientSocket.close() } catch (_: Exception) {}
        try { remoteChannel?.close() } catch (_: Exception) {}
        try { key?.cancel() } catch (_: Exception) {}
    }

    private var key: SelectionKey? = null
    fun setKey(k: SelectionKey) { key = k }
}
