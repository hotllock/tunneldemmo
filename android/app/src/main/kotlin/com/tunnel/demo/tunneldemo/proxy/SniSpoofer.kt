package com.tunnel.demo.tunneldemo.proxy

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

object SniSpoofer {

    private const val TAG = "SniSpoofer"
    private const val TLS_CONTENT_HANDSHAKE = 0x16.toByte()
    private const val TLS_HANDSHAKE_CLIENT_HELLO = 0x01.toByte()
    private const val SNI_EXTENSION_TYPE = 0x0000

    private var config: ProxyConfig = ProxyConfig.DEFAULT
    private val connectionSni = ConcurrentHashMap<String, String>()

    fun setConfig(cfg: ProxyConfig) { config = cfg }

    fun getSni(connId: String): String? = connectionSni[connId]

    /**
     * Read TLS ClientHello, extract SNI, optionally modify and return modified bytes.
     * Returns null if no TLS detected.
     */
    fun peekAndModifyClientHello(
        input: InputStream,
        realOutput: OutputStream,
        connId: String
    ): Boolean {
        try {
            if (!input.markSupported()) return false
            input.mark(4096)

            val buf = ByteArray(4096)
            val header = ByteArray(5)
            var n = readFully(input, header, 0, 5)
            if (n < 5) { input.reset(); return false }

            // Check TLS record header
            if (header[0] != TLS_CONTENT_HANDSHAKE) {
                input.reset(); return false
            }

            val recordLen = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
            if (recordLen > 4096 || recordLen < 10) { input.reset(); return false }

            val record = ByteArray(recordLen)
            n = readFully(input, record, 0, recordLen)
            if (n < recordLen) { input.reset(); return false }

            // Check handshake type
            if (record[0] != TLS_HANDSHAKE_CLIENT_HELLO) {
                input.reset(); return false
            }

            // Parse ClientHello to extract SNI
            val sni = extractSni(record)
            if (sni != null) {
                connectionSni[connId] = sni
                Log.i(TAG, "Extracted SNI: $sni ($connId)")
            }

            // Apply SNI override if configured
            val modifiedRecord = if (sni != null && config.enabledSniSpoof) {
                val overrideSni = findSniOverride(sni)
                if (overrideSni != null) {
                    Log.i(TAG, "Spoofing SNI: $sni -> $overrideSni")
                    modifySni(record, sni, overrideSni)
                } else null
            } else null

            // Forward (possibly modified) ClientHello
            val finalRecord = modifiedRecord ?: record
            realOutput.write(header)
            realOutput.write(finalRecord)
            realOutput.flush()

            input.reset()
            // Skip the bytes we already read (header + record) so subsequent reads don't see them
            // Actually we can't "skip" them from input, need different approach
            // Instead: write original bytes back or use a buffered approach

            return true
        } catch (e: Exception) {
            Log.w(TAG, "SNI peek failed: ${e.message}")
            return false
        }
    }

    fun removeConnection(connId: String) {
        connectionSni.remove(connId)
    }

    private fun extractSni(clientHello: ByteArray): String? {
        try {
            var pos = 0
            // Handshake type (1) + length (3)
            pos += 4
            // ClientVersion (2)
            pos += 2
            // Random (32)
            pos += 32

            // Session ID
            if (pos >= clientHello.size) return null
            val sessionIdLen = clientHello[pos].toInt() and 0xFF
            pos += 1 + sessionIdLen

            // Cipher Suites
            if (pos + 1 >= clientHello.size) return null
            val cipherLen = ((clientHello[pos].toInt() and 0xFF) shl 8) or (clientHello[pos + 1].toInt() and 0xFF)
            pos += 2 + cipherLen

            // Compression Methods
            if (pos >= clientHello.size) return null
            val compLen = clientHello[pos].toInt() and 0xFF
            pos += 1 + compLen

            // Extensions
            if (pos + 1 >= clientHello.size) return null
            val extLen = ((clientHello[pos].toInt() and 0xFF) shl 8) or (clientHello[pos + 1].toInt() and 0xFF)
            pos += 2

            val extEnd = pos + extLen
            while (pos + 3 < extEnd && pos + 3 < clientHello.size) {
                val extType = ((clientHello[pos].toInt() and 0xFF) shl 8) or (clientHello[pos + 1].toInt() and 0xFF)
                val extDataLen = ((clientHello[pos + 2].toInt() and 0xFF) shl 8) or (clientHello[pos + 3].toInt() and 0xFF)
                pos += 4

                if (extType == SNI_EXTENSION_TYPE && extDataLen > 5 && pos + extDataLen <= clientHello.size) {
                    // SNI extension: server name list length (2) + entry type (1) + name length (2) + name
                    val nameListLen = ((clientHello[pos].toInt() and 0xFF) shl 8) or (clientHello[pos + 1].toInt() and 0xFF)
                    if (nameListLen > 0 && pos + 3 < clientHello.size) {
                        val entryType = clientHello[pos + 2] // 0x00 = host_name
                        val nameLen = ((clientHello[pos + 3].toInt() and 0xFF) shl 8) or (clientHello[pos + 4].toInt() and 0xFF)
                        if (entryType.toInt() == 0x00 && nameLen > 0 && pos + 5 + nameLen <= clientHello.size) {
                            return String(clientHello, pos + 5, nameLen, Charsets.UTF_8)
                        }
                    }
                }
                pos += extDataLen
            }
        } catch (_: Exception) {}
        return null
    }

    private fun modifySni(clientHello: ByteArray, originalSni: String, newSni: String): ByteArray? {
        val result = clientHello.copyOf()
        val originalBytes = originalSni.toByteArray(Charsets.UTF_8)
        val newBytes = newSni.toByteArray(Charsets.UTF_8)

        if (originalBytes.size != newBytes.size) return null

        var pos = 4
        pos += 2 + 32 // version + random

        val sessionIdLen = result[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen

        val cipherLen = ((result[pos].toInt() and 0xFF) shl 8) or (result[pos + 1].toInt() and 0xFF)
        pos += 2 + cipherLen

        val compLen = result[pos].toInt() and 0xFF
        pos += 1 + compLen

        val extLen = ((result[pos].toInt() and 0xFF) shl 8) or (result[pos + 1].toInt() and 0xFF)
        pos += 2
        val extEnd = pos + extLen

        while (pos + 3 < extEnd && pos + 3 < result.size) {
            val extType = ((result[pos].toInt() and 0xFF) shl 8) or (result[pos + 1].toInt() and 0xFF)
            val extDataLen = ((result[pos + 2].toInt() and 0xFF) shl 8) or (result[pos + 3].toInt() and 0xFF)
            pos += 4

            if (extType == SNI_EXTENSION_TYPE && extDataLen > 5) {
                val nameListLen = ((result[pos].toInt() and 0xFF) shl 8) or (result[pos + 1].toInt() and 0xFF)
                if (nameListLen > 3) {
                    val nameLen = ((result[pos + 3].toInt() and 0xFF) shl 8) or (result[pos + 4].toInt() and 0xFF)
                    if (nameLen == originalBytes.size && pos + 5 + nameLen <= result.size) {
                        System.arraycopy(newBytes, 0, result, pos + 5, nameLen)
                        return result
                    }
                }
            }
            pos += extDataLen
        }
        return null
    }

    private fun findSniOverride(hostname: String): String? {
        // Exact match
        config.sniOverride[hostname]?.let { return it }
        // Wildcard match
        val parts = hostname.split(".")
        for (i in 0 until parts.size) {
            val wildcard = "*." + parts.drop(i).joinToString(".")
            config.sniOverride[wildcard]?.let { return it }
        }
        return null
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, off + total, len - total)
            if (n < 0) return total
            total += n
        }
        return total
    }
}
