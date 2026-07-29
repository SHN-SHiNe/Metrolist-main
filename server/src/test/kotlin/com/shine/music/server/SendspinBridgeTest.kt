package com.shine.music.server

import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendspinBridgeTest {
    @Test
    fun `HTTP readiness request is bounded when bridge accepts but never responds`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val releaseConnection = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val accepted = executor.submit {
            server.accept().use {
                releaseConnection.await(2, TimeUnit.SECONDS)
            }
        }
        val bridge = HttpSendspinBridge(
            baseUrl = "http://127.0.0.1:${server.localPort}",
            requestTimeout = Duration.ofMillis(75),
        )
        var ready = true

        val elapsed = try {
            measureTimeMillis { ready = bridge.ready() }
        } finally {
            releaseConnection.countDown()
            server.close()
            runCatching { accepted.get(1, TimeUnit.SECONDS) }
            executor.shutdownNow()
        }

        assertFalse(ready)
        assertTrue(elapsed < 1_000, "bridge request took ${elapsed}ms")
    }
}
