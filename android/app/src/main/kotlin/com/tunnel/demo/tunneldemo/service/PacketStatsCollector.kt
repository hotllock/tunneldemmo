package com.tunnel.demo.tunneldemo.service

import com.tunnel.demo.tunneldemo.model.StatsData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

object PacketStatsCollector {

    private val packetsProcessed = AtomicLong(0)
    private val httpRequests = AtomicLong(0)
    private val httpsConnections = AtomicLong(0)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val activeConnections = AtomicLong(0)
    private val errorsCount = AtomicLong(0)
    private var startTimeMs = 0L

    @Volatile
    var isRunning = false
        private set

    fun reset() {
        packetsProcessed.set(0)
        httpRequests.set(0)
        httpsConnections.set(0)
        bytesUp.set(0)
        bytesDown.set(0)
        activeConnections.set(0)
        errorsCount.set(0)
        startTimeMs = 0L
        isRunning = false
    }

    fun start() {
        reset()
        startTimeMs = System.currentTimeMillis()
        isRunning = true
    }

    fun incrementPackets() = packetsProcessed.incrementAndGet()
    fun incrementHttp() = httpRequests.incrementAndGet()
    fun incrementHttps() = httpsConnections.incrementAndGet()
    fun incrementErrors() = errorsCount.incrementAndGet()

    fun addBytesUp(n: Long) = bytesUp.addAndGet(n)
    fun addBytesDown(n: Long) = bytesDown.addAndGet(n)

    fun incrementConnections() = activeConnections.incrementAndGet()
    fun decrementConnections() = activeConnections.decrementAndGet()

    fun getActiveConnections(): Long = activeConnections.get()
    fun getPacketsProcessed(): Long = packetsProcessed.get()

    fun getSnapshot(): StatsData = StatsData(
        totalPackets = packetsProcessed.get(),
        httpPackets = httpRequests.get(),
        httpsConnections = httpsConnections.get(),
        bytesTransferred = bytesUp.get() + bytesDown.get(),
        bytesUp = bytesUp.get(),
        bytesDown = bytesDown.get(),
        activeConnections = activeConnections.get(),
        errorsCount = errorsCount.get(),
        uptimeMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0,
        isRunning = isRunning
    )

    fun statsFlow(intervalMs: Long = 500): Flow<StatsData> = flow {
        while (true) {
            emit(getSnapshot())
            delay(intervalMs)
        }
    }
}
