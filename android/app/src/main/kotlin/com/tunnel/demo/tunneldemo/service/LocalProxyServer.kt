package com.tunnel.demo.tunneldemo.service

import com.tunnel.demo.tunneldemo.model.*
import com.tunnel.demo.tunneldemo.native.TunEngineBridge
import kotlin.concurrent.thread
import com.tunnel.demo.tunneldemo.proxy.DnsResolver
import com.tunnel.demo.tunneldemo.proxy.ProxyConfig
import com.tunnel.demo.tunneldemo.proxy.SniSpoofer
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object LocalProxyServer {

    private const val PROXY_PORT = 8080
    private const val BACKLOG = 100
    private const val WORKER_THREADS = 16
    private const val BUFFER_SIZE = 65536
    private const val CONNECT_TIMEOUT_MS = 15000

    private var config: ProxyConfig = ProxyConfig.DEFAULT
    private val connIdCounter = AtomicLong(0)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val executor = ThreadPoolExecutor(
        WORKER_THREADS, WORKER_THREADS * 2,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(200),
        ThreadPoolExecutor.CallerRunsPolicy()
    )
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val transactionHistory = ConcurrentLinkedQueue<Transaction>()
    private val listeners = CopyOnWriteArrayList<(Transaction) -> Unit>()

    fun addListener(l: (Transaction) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Transaction) -> Unit) { listeners.remove(l) }

    private fun notifyTransaction(t: Transaction) { listeners.forEach { it(t) } }

    fun updateConfig(cfg: ProxyConfig) {
        config = cfg
        DnsResolver.setConfig(cfg)
        SniSpoofer.setConfig(cfg)
    }

    fun start(context: android.content.Context) {
        if (isRunning.getAndSet(true)) return
        DnsResolver.setConfig(config)
        SniSpoofer.setConfig(config)

        scope.launch {
            try {
                serverSocket = ServerSocket(PROXY_PORT, BACKLOG, Inet4Address.getByName("127.0.0.1"))
                serverSocket?.reuseAddress = true
                TunEngineBridge.nativeSetProxyPort(PROXY_PORT)

                while (isRunning.get() && !serverSocket!!.isClosed) {
                    try {
                        val client = serverSocket!!.accept()
                        client.soTimeout = CONNECT_TIMEOUT_MS
                        executor.execute { handleConn(client) }
                    } catch (e: IOException) { if (!isRunning.get()) break }
                }
            } catch (e: Exception) { isRunning.set(false) }
        }
    }

    fun stop() {
        isRunning.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdown()
        try { executor.awaitTermination(2, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    fun getTransactions(): List<Transaction> = transactionHistory.toList()
    fun clearTransactions() { transactionHistory.clear() }

    suspend fun replayRequest(transaction: Transaction): Transaction? = withContext(Dispatchers.IO) {
        try {
            val url = URL(transaction.fullUrl.ifEmpty { "http://${transaction.host}${transaction.path}" })
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = transaction.method.value
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CONNECT_TIMEOUT_MS
                transaction.requestHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                if (transaction.requestBody != null) { doOutput = true; outputStream.bufferedWriter().use { it.write(transaction.requestBody); it.flush() } }
            }
            val responseCode = connection.responseCode
            val responseHeaders = mutableMapOf<String, String>()
            connection.headerFields?.forEach { (k, vals) -> if (k != null && vals.isNotEmpty()) responseHeaders[k] = vals.joinToString(", ") }
            val body = try { connection.inputStream.bufferedReader().use { it.readText() } }
                catch (_: Exception) { connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "" }
            transaction.copy(statusCode = responseCode, responseHeaders = responseHeaders, responseBody = body, responseTime = System.currentTimeMillis(), status = TransactionStatus.REPLAYED)
        } catch (e: Exception) {
            transaction.copy(status = TransactionStatus.FAILED, errorMessage = e.message, responseTime = System.currentTimeMillis())
        }
    }

    private fun handleConn(client: Socket) {
        try {
            val input = BufferedInputStream(client.getInputStream(), BUFFER_SIZE)
            val output = BufferedOutputStream(client.getOutputStream(), BUFFER_SIZE)

            val firstLine = readLine(input) ?: run { client.close(); return }
            val parts = firstLine.split(" ")
            if (parts.size < 2) { client.close(); return }

            val method = parts[0].uppercase()
            val target = parts[1]
            val version = parts.getOrNull(2) ?: "HTTP/1.1"

            if (method == "CONNECT") handleConnectTunnel(client, input, output, target, version)
            else handleDirectHttp(client, input, output, method, target, version, firstLine)
        } catch (e: Exception) { try { client.close() } catch (_: Exception) {} }
    }

    // ---- CONNECT Tunnel (HTTPS / arbitrary TCP) ----

    private fun handleConnectTunnel(
        client: Socket, input: InputStream, output: OutputStream,
        target: String, version: String
    ) {
        val connId = "conn_${connIdCounter.incrementAndGet()}"
        val hostParts = target.trim('[', ']').split(":")
        val host = hostParts.getOrElse(0) { "" }
        val port = hostParts.getOrNull(1)?.toIntOrNull() ?: 443

        val transaction = Transaction(
            method = HttpMethod.CONNECT, host = host, path = "/",
            fullUrl = "https://$host:$port",
            requestHeaders = mutableMapOf("Host" to "$host:$port"),
            requestTime = System.currentTimeMillis(),
            isHttps = true, destinationPort = port,
            sourcePort = client.port, status = TransactionStatus.IN_PROGRESS
        )
        PacketStatsCollector.incrementHttps()

        var realSocket: Socket? = null
        var realInput: InputStream? = null
        var realOutput: OutputStream? = null

        try {
            // Resolve destination via custom DNS or hosts override
            realSocket = createOutboundSocket(host, port)

            // Send CONNECT OK
            output.write("HTTP/$version 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()

            val completed = transaction.copy(statusCode = 200, responseTime = System.currentTimeMillis(), status = TransactionStatus.COMPLETED)
            transactionHistory.add(completed)
            if (transactionHistory.size > 1000) transactionHistory.poll()
            notifyTransaction(completed)

            realInput = realSocket.getInputStream()
            realOutput = realSocket.getOutputStream()

            // Peek & modify TLS ClientHello if enabled
            if (config.enabledSniSpoof || config.spoofUserAgent) {
                SniSpoofer.peekAndModifyClientHello(input, realOutput, connId)
            }

            // Bidirectional tunnel relay
            val toRemote = thread {
                try {
                    val buf = ByteArray(BUFFER_SIZE)
                    while (client.isConnected && !client.isClosed && realSocket.isConnected && !realSocket.isClosed) {
                        val n = try { input.read(buf) } catch (_: Exception) { -1 }
                        if (n <= 0) break
                        PacketStatsCollector.addBytesUp(n.toLong())
                        realOutput?.write(buf, 0, n); realOutput?.flush()
                    }
                } catch (_: Exception) {}
            }

            val toClient = thread {
                try {
                    val buf = ByteArray(BUFFER_SIZE)
                    while (client.isConnected && !client.isClosed && realSocket.isConnected && !realSocket.isClosed) {
                        val n = try { realInput?.read(buf) } catch (_: Exception) { -1 }
                        if (n == null || n <= 0) break
                        PacketStatsCollector.addBytesDown(n.toLong())
                        output.write(buf, 0, n); output.flush()
                    }
                } catch (_: Exception) {}
            }

            try { toRemote.join(120000) } catch (_: Exception) {}
            try { toClient.join(120000) } catch (_: Exception) {}

        } catch (e: Exception) {
            val failed = transaction.copy(status = TransactionStatus.FAILED, errorMessage = e.message, responseTime = System.currentTimeMillis())
            transactionHistory.add(failed)
            notifyTransaction(failed)
            try { output.write("HTTP/$version 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush() } catch (_: Exception) {}
        } finally {
            SniSpoofer.removeConnection(connId)
            try { realSocket?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    // ---- Direct HTTP Proxy ----

    private fun handleDirectHttp(
        client: Socket, input: InputStream, output: OutputStream,
        method: String, target: String, version: String, firstLine: String
    ) {
        try {
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            var isChunked = false

            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                    if (key.equals("Content-Length", true)) contentLength = value.toIntOrNull() ?: 0
                    if (key.equals("Transfer-Encoding", true) && value.equals("chunked", true)) isChunked = true
                }
            }

            var bodyBytes = ByteArray(0)
            if (contentLength > 0) {
                bodyBytes = ByteArray(contentLength)
                var offset = 0
                while (offset < contentLength) { val n = input.read(bodyBytes, offset, contentLength - offset); if (n <= 0) break; offset += n }
            } else if (isChunked) {
                val baos = ByteArrayOutputStream()
                while (true) {
                    val line = readLine(input) ?: break
                    val size = line.trim().toIntOrNull(16) ?: 0
                    if (size == 0) break
                    val chunk = ByteArray(size)
                    var off = 0
                    while (off < size) { val n = input.read(chunk, off, size - off); if (n <= 0) break; off += n }
                    baos.write(chunk)
                    readLine(input)
                }
                bodyBytes = baos.toByteArray()
            }

            val host = headers["Host"] ?: ""
            val fullUrl = if (target.startsWith("http")) target else "http://$host$target"

            val transaction = Transaction(
                method = HttpMethod.fromString(method), host = host, path = target,
                fullUrl = fullUrl, requestHeaders = headers.toMutableMap(),
                requestBody = if (bodyBytes.isNotEmpty()) String(bodyBytes, Charsets.UTF_8) else null,
                requestBodyBytes = if (bodyBytes.isNotEmpty()) bodyBytes else null,
                requestTime = System.currentTimeMillis(), isHttps = false,
                destinationPort = 80, sourcePort = client.port,
                status = TransactionStatus.IN_PROGRESS
            )
            PacketStatsCollector.incrementHttp()

            var realSocket: Socket? = null
            try {
                val url = URL(fullUrl)
                val destPort = if (url.port > 0) url.port else 80

                // Resolve via custom DNS / hosts override
                realSocket = createOutboundSocket(url.host, destPort)

                val out = realSocket.getOutputStream()
                val realInput = realSocket.getInputStream()

                // Build forwarded request
                val sb = StringBuilder()
                val path = url.path.ifEmpty { "/" } + if (url.query != null) "?${url.query}" else ""

                // Apply User-Agent spoofing
                if (config.spoofUserAgent && config.customUserAgent.isNotBlank()) {
                    headers["User-Agent"] = config.customUserAgent
                }

                // Strip privacy headers
                for (h in config.stripHeaders) headers.remove(h)

                sb.append("$method $path HTTP/1.1\r\n")
                headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
                sb.append("\r\n")

                out.write(sb.toString().toByteArray())
                if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
                out.flush()

                // Read response
                val responseBaos = ByteArrayOutputStream()
                val buf = ByteArray(BUFFER_SIZE)
                var n: Int
                try { while (realInput.read(buf).also { n = it } > 0) { responseBaos.write(buf, 0, n); if (responseBaos.size() > 10 * 1024 * 1024) break } }
                catch (_: SocketTimeoutException) {}

                val responseBytes = responseBaos.toByteArray()
                output.write(responseBytes); output.flush()

                // Parse response
                val responseStr = String(responseBytes, Charsets.UTF_8)
                val respLines = responseStr.split("\r\n")
                val statusCode = respLines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
                val respHeaders = mutableMapOf<String, String>()
                var headerEnd = 0
                for ((i, line) in respLines.withIndex()) {
                    if (i == 0) continue
                    if (line.isBlank()) { headerEnd = i + 1; break }
                    val colon = line.indexOf(':')
                    if (colon > 0) respHeaders[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
                }
                val respBody = respLines.drop(headerEnd).joinToString("\r\n")

                val completed = transaction.copy(
                    statusCode = statusCode, responseHeaders = respHeaders,
                    responseBody = respBody, responseBodyBytes = respBody.toByteArray(),
                    responseTime = System.currentTimeMillis(), contentLength = responseBytes.size.toLong(),
                    status = TransactionStatus.COMPLETED
                )
                transactionHistory.add(completed)
                if (transactionHistory.size > 1000) transactionHistory.poll()
                notifyTransaction(completed)

            } catch (e: Exception) {
                val failed = transaction.copy(status = TransactionStatus.FAILED, errorMessage = e.message, responseTime = System.currentTimeMillis())
                transactionHistory.add(failed)
                notifyTransaction(failed)
                try { output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush() } catch (_: Exception) {}
            } finally { try { realSocket?.close() } catch (_: Exception) {} }

        } catch (e: Exception) {
            try { output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush() } catch (_: Exception) {}
        } finally { try { client.close() } catch (_: Exception) {} }
    }

    // ---- Socket creation with DNS, proxy chain, protect() ----

    private fun createOutboundSocket(host: String, port: Int): Socket {
        // 1. Resolve IP (custom DNS or hosts override)
        val resolvedIp = DnsResolver.resolve(host)
        val connectIp = resolvedIp ?: InetAddress.getByName(host)
        val connectHost = connectIp.hostAddress

        // 2. If upstream proxy is configured, connect through it
        if (config.useUpstreamProxy) {
            return connectViaUpstream(host, port, connectHost!!)
        }

        // 3. Direct connection
        val sock = Socket()
        InspectionVpnService.protectSocket(sock)
        sock.connect(InetSocketAddress(connectIp, port), CONNECT_TIMEOUT_MS)
        sock.soTimeout = CONNECT_TIMEOUT_MS
        return sock
    }

    private fun connectViaUpstream(targetHost: String, targetPort: Int, targetIp: String): Socket {
        val upstreamAddr = InetSocketAddress(config.upstreamHost, config.upstreamPort)
        val sock = Socket()
        InspectionVpnService.protectSocket(sock)
        sock.connect(upstreamAddr, CONNECT_TIMEOUT_MS)
        sock.soTimeout = CONNECT_TIMEOUT_MS

        val upOut = sock.getOutputStream()
        val upIn = sock.getInputStream()

        when (config.upstreamType.lowercase()) {
            "socks5" -> {
                // SOCKS5 handshake
                upOut.write(byteArrayOf(0x05, 0x01, 0x00)) // version, nmethods, no-auth
                upOut.flush()
                val resp = ByteArray(2)
                readFully(upIn, resp)
                if (resp[0].toInt() != 0x05 || resp[1].toInt() != 0x00) throw IOException("SOCKS5 auth failed")

                // CONNECT request
                val targetBytes = targetIp.split(".").map { it.toInt() and 0xFF }
                val request = byteArrayOf(
                    0x05, 0x01, 0x00, 0x01,
                    targetBytes[0].toByte(), targetBytes[1].toByte(),
                    targetBytes[2].toByte(), targetBytes[3].toByte(),
                    (targetPort shr 8 and 0xFF).toByte(), (targetPort and 0xFF).toByte()
                )
                upOut.write(request); upOut.flush()

                val reply = ByteArray(10)
                readFully(upIn, reply)
                if (reply[1] != 0x00.toByte()) throw IOException("SOCKS5 connect failed: ${reply[1]}")
            }

            "http" -> {
                // HTTP CONNECT to upstream
                val auth = if (config.upstreamUser.isNotBlank()) {
                    val credentials = "${config.upstreamUser}:${config.upstreamPass}"
                    "Proxy-Authorization: Basic ${java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())}\r\n"
                } else ""

                val connectReq = "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n${auth}\r\n"
                upOut.write(connectReq.toByteArray()); upOut.flush()

                val resp = ByteArray(1024)
                val n = readFullyUntil(upIn, resp, "\r\n\r\n")
                val respStr = String(resp, 0, n, Charsets.UTF_8)
                if (!respStr.contains("200")) throw IOException("HTTP upstream CONNECT failed: ${respStr.take(100)}")
            }
        }

        return sock
    }

    private fun readLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var prev = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (baos.size() > 0) baos.toString(Charsets.UTF_8.name()) else null
            if (prev == '\r'.toInt() && b == '\n'.toInt()) return baos.toString(Charsets.UTF_8.name())
            if (b != '\r'.toInt()) baos.write(b)
            prev = b
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        var total = 0
        while (total < len) {
            val n = input.read(buf, off + total, len - total)
            if (n < 0) throw EOFException()
            total += n
        }
    }

    private fun readFullyUntil(input: InputStream, buf: ByteArray, delimiter: String): Int {
        val delimBytes = delimiter.toByteArray()
        var total = 0
        while (total < buf.size) {
            val b = input.read()
            if (b < 0) break
            buf[total++] = b.toByte()
            if (total >= delimBytes.size) {
                var match = true
                for (i in 0 until delimBytes.size) {
                    if (buf[total - delimBytes.size + i] != delimBytes[i]) { match = false; break }
                }
                if (match) return total
            }
        }
        return total
    }
}
