package com.shine.music.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryApiTest {
    @Test
    fun `visitor can scan the NAS directory and browse discovered music`() = testApplication {
        val root = Files.createTempDirectory("shine-library-test")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("夜空中最亮的星 - 逃跑计划.mp3").writeBytes(byteArrayOf(0, 1, 2, 3))

        application {
            shineModule(AppConfig(root.resolve("data"), music, root.resolve("cache"), scanOnStart = false))
        }
        val api = createClient { install(ContentNegotiation) { json() } }

        val scan = api.post("/api/scans")
        val library = api.get("/api/library")

        assertEquals(HttpStatusCode.OK, scan.status)
        assertEquals(1, scan.body<ScanResult>().discovered)
        assertEquals(HttpStatusCode.OK, library.status)
        val page = library.body<TrackPage>()
        assertEquals(1, page.total)
        assertEquals("夜空中最亮的星", page.items.single().title)
        assertEquals("逃跑计划", page.items.single().artist)

        val unchanged = api.post("/api/scans").body<ScanResult>()
        assertEquals(0, unchanged.discovered)
        assertEquals(0, unchanged.updated)
        assertEquals(2, api.get("/api/scans").body<List<ScanResult>>().size)
    }

    @Test
    fun `library query and sorting apply before pagination`() = testApplication {
        val root = Files.createTempDirectory("shine-library-page-test")
        val music = root.resolve("music")
        Files.createDirectories(music)
        music.resolve("Zulu - Artist B.mp3").writeBytes(byteArrayOf(1))
        music.resolve("Alpha - Artist C.mp3").writeBytes(byteArrayOf(2))
        music.resolve("Middle - Artist A.mp3").writeBytes(byteArrayOf(3))

        application { shineModule(AppConfig(root.resolve("data"), music, root.resolve("cache"), scanOnStart = false)) }
        val api = createClient { install(ContentNegotiation) { json() } }
        api.post("/api/scans")

        val secondByTitle = api.get("/api/library?sort=title&offset=1&limit=1").body<TrackPage>()
        val filtered = api.get("/api/library?q=Artist%20A&sort=title&limit=1").body<TrackPage>()
        val quotedFilter = api.get("/api/library?q=%22Artist%20A&sort=title&limit=1")

        assertEquals(3, secondByTitle.total)
        assertEquals("Middle", secondByTitle.items.single().title)
        assertEquals(1, filtered.total)
        assertEquals("Middle", filtered.items.single().title)
        assertEquals(HttpStatusCode.OK, quotedFilter.status)
        assertEquals(0, quotedFilter.body<TrackPage>().total)
    }
}
