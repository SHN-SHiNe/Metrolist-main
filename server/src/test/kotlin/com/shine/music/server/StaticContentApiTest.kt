package com.shine.music.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticContentApiTest {
    @Test
    fun `html is revalidated while hashed assets are immutable`() = testApplication {
        val root = Files.createTempDirectory("shine-static-cache-test")
        application {
            shineModule(AppConfig(root.resolve("data"), root.resolve("music"), root.resolve("cache"), scanOnStart = false))
        }

        val html = client.get("/")
        assertEquals(HttpStatusCode.OK, html.status)
        assertTrue(html.headers[HttpHeaders.CacheControl].orEmpty().contains("no-cache"))

        val document = html.bodyAsText()
        val assetPath = Regex("(?:src|href)=\"(/assets/[^\"]+)\"").find(document)?.groupValues?.get(1)
        checkNotNull(assetPath) { "built web asset was not present in index.html" }
        val asset = client.get(assetPath)
        assertEquals(HttpStatusCode.OK, asset.status)
        assertTrue(asset.headers[HttpHeaders.CacheControl].orEmpty().contains("immutable"))
    }
}
