package com.shine.music.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthApiTest {
    @Test
    fun `visitor can verify that the NAS service is healthy`() = testApplication {
        val root = Files.createTempDirectory("shine-health-test")
        application {
            shineModule(
                AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache")),
                clock = { 1_725_000_000_000 },
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) { json() }
        }
        val response = apiClient.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            HealthResponse("ok", "0.1.0", 1_725_000_000_000),
            response.body(),
        )
    }

    @Test
    fun `readiness fails when the Sendspin bridge is unavailable`() = testApplication {
        val root = Files.createTempDirectory("shine-health-sendspin-test")
        val unavailableBridge = object : SendspinBridge by InMemorySendspinBridge() {
            override suspend fun ready() = false
        }
        application {
            shineModule(
                AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache")),
                clock = { 1_725_000_000_001 },
                sendspinBridge = unavailableBridge,
            )
        }

        val apiClient = createClient {
            install(ContentNegotiation) { json() }
        }
        val response = apiClient.get("/api/health")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(
            HealthResponse("degraded", "0.1.0", 1_725_000_000_001),
            response.body(),
        )
    }

    @Test
    fun `readiness timeout bounds a wedged bridge`() = runBlocking {
        val wedgedBridge = object : SendspinBridge by InMemorySendspinBridge() {
            override suspend fun ready(): Boolean {
                delay(5_000)
                return true
            }
        }
        var ready = true

        val elapsed = measureTimeMillis {
            ready = wedgedBridge.isReadyWithin(timeoutMillis = 25)
        }

        assertFalse(ready)
        assertTrue(elapsed < 1_000, "readiness took ${elapsed}ms")
    }
}
