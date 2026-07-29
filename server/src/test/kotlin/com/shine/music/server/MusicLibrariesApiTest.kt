package com.shine.music.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MusicLibrariesApiTest {
    @Test
    fun `visitor can add scan and filter a mounted music library`() = testApplication {
        val root = Files.createTempDirectory("shine-multi-library-test")
        val music = root.resolve("music")
        val libraryRoot = root.resolve("libraries")
        val usb = libraryRoot.resolve("usb")
        Files.createDirectories(music)
        Files.createDirectories(usb)
        usb.resolve("Portable Song - USB Artist.mp3").writeBytes(byteArrayOf(1, 2, 3))

        application {
            shineModule(
                AppConfig(
                    dataDir = root.resolve("data"),
                    musicDir = music,
                    cacheDir = root.resolve("cache"),
                    scanOnStart = false,
                    libraryDir = libraryRoot,
                ),
            )
        }
        val api = createClient { install(ContentNegotiation) { json() } }

        val created = api.post("/api/libraries") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                """{"name":"随身硬盘","path":"${usb.toString().replace("\\", "\\\\")}","deviceType":"usb","readOnly":true,"enabled":true,"downloadTarget":false}""",
            )
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val libraryId = created.body<JsonObject>().getValue("id").jsonPrimitive.content

        assertEquals(HttpStatusCode.OK, api.post("/api/libraries/$libraryId/scan").status)
        val page = api.get("/api/library?libraryId=$libraryId").body<JsonObject>()
        val tracks = page.getValue("items").jsonArray
        assertEquals(1, tracks.size)
        assertEquals("Portable Song", tracks.single().jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(libraryId, tracks.single().jsonObject.getValue("libraryId").jsonPrimitive.content)

        val libraries = api.get("/api/libraries").body<JsonArray>()
        assertEquals(2, libraries.size)
        assertEquals("随身硬盘", libraries.single { it.jsonObject.getValue("id").jsonPrimitive.content == libraryId }.jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `offline device scan keeps its tracks in the shared catalog`() = testApplication {
        val root = Files.createTempDirectory("shine-offline-library-test")
        val music = root.resolve("music")
        val libraryRoot = root.resolve("libraries")
        val network = libraryRoot.resolve("network")
        Files.createDirectories(music)
        Files.createDirectories(network)
        network.resolve("Remote Song - NAS Artist.mp3").writeBytes(byteArrayOf(1))

        application {
            shineModule(AppConfig(root.resolve("data"), music, root.resolve("cache"), scanOnStart = false, libraryDir = libraryRoot))
        }
        val api = createClient { install(ContentNegotiation) { json() } }
        val created = api.post("/api/libraries") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"name":"书房 NAS","path":"${network.toString().replace("\\", "\\\\")}","deviceType":"network","readOnly":true}""")
        }.body<JsonObject>()
        val libraryId = created.getValue("id").jsonPrimitive.content
        api.post("/api/libraries/$libraryId/scan")

        Files.move(network, root.resolve("network-disconnected"), StandardCopyOption.ATOMIC_MOVE)
        val scan = api.post("/api/libraries/$libraryId/scan").body<JsonObject>()
        val page = api.get("/api/library?libraryId=$libraryId").body<JsonObject>()
        val library = api.get("/api/libraries").body<JsonArray>()
            .single { it.jsonObject.getValue("id").jsonPrimitive.content == libraryId }.jsonObject

        assertEquals("offline", scan.getValue("status").jsonPrimitive.content)
        assertEquals(1, page.getValue("total").jsonPrimitive.content.toInt())

        assertEquals("offline", library.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `read only library refuses to move tracks to trash`() = testApplication {
        val root = Files.createTempDirectory("shine-read-only-library-test")
        val music = root.resolve("music")
        val libraryRoot = root.resolve("libraries")
        val usb = libraryRoot.resolve("read-only-usb")
        Files.createDirectories(music)
        Files.createDirectories(usb)
        val audio = usb.resolve("Protected Song - USB Artist.mp3")
        audio.writeBytes(byteArrayOf(1))

        application {
            shineModule(AppConfig(root.resolve("data"), music, root.resolve("cache"), scanOnStart = false, libraryDir = libraryRoot))
        }
        val api = createClient { install(ContentNegotiation) { json() } }
        val libraryId = api.post("/api/libraries") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"name":"只读 U 盘","path":"${usb.toString().replace("\\", "\\\\")}","deviceType":"usb","readOnly":true}""")
        }.body<JsonObject>().getValue("id").jsonPrimitive.content
        api.post("/api/libraries/$libraryId/scan")
        val trackId = api.get("/api/library?libraryId=$libraryId").body<JsonObject>()
            .getValue("items").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content

        val deletion = api.delete("/api/library/$trackId")

        assertEquals(HttpStatusCode.BadRequest, deletion.status)
        assertEquals("library_read_only", deletion.body<JsonObject>().getValue("error").jsonPrimitive.content)
        assertEquals(true, Files.exists(audio))
    }

    @Test
    fun `empty mount placeholder does not erase a previously populated library`() = testApplication {
        val root = Files.createTempDirectory("shine-empty-mount-test")
        val music = root.resolve("music")
        val libraryRoot = root.resolve("libraries")
        val mount = libraryRoot.resolve("mounted-device")
        Files.createDirectories(music)
        Files.createDirectories(mount)
        val audio = mount.resolve("Mounted Song - Artist.mp3")
        audio.writeBytes(byteArrayOf(1))

        application {
            shineModule(AppConfig(root.resolve("data"), music, root.resolve("cache"), scanOnStart = false, libraryDir = libraryRoot))
        }
        val api = createClient { install(ContentNegotiation) { json() } }
        val libraryId = api.post("/api/libraries") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"name":"网络挂载点","path":"${mount.toString().replace("\\", "\\\\")}","deviceType":"network","readOnly":true}""")
        }.body<JsonObject>().getValue("id").jsonPrimitive.content
        api.post("/api/libraries/$libraryId/scan")
        Files.delete(audio)

        val scan = api.post("/api/libraries/$libraryId/scan").body<JsonObject>()
        val page = api.get("/api/library?libraryId=$libraryId").body<JsonObject>()

        assertEquals("offline", scan.getValue("status").jsonPrimitive.content)
        assertEquals(1, page.getValue("total").jsonPrimitive.content.toInt())

        val confirmed = api.post("/api/libraries/$libraryId/scan?allowEmpty=true").body<JsonObject>()
        val emptyPage = api.get("/api/library?libraryId=$libraryId").body<JsonObject>()
        assertEquals("completed", confirmed.getValue("status").jsonPrimitive.content)
        assertEquals(0, emptyPage.getValue("total").jsonPrimitive.content.toInt())
    }

    @Test
    fun `visitor can disable a library without deleting its catalog entries`() = testApplication {
        val root = Files.createTempDirectory("shine-disable-library-test")
        val music = root.resolve("music")
        val libraryRoot = root.resolve("libraries")
        val usb = libraryRoot.resolve("usb")
        Files.createDirectories(music)
        Files.createDirectories(usb)
        usb.resolve("Keep Me - Artist.mp3").writeBytes(byteArrayOf(1))

        application {
            shineModule(AppConfig(root.resolve("data"), music, root.resolve("cache"), scanOnStart = false, libraryDir = libraryRoot))
        }
        val api = createClient { install(ContentNegotiation) { json() } }
        val escapedPath = usb.toString().replace("\\", "\\\\")
        val libraryId = api.post("/api/libraries") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"name":"移动硬盘","path":"$escapedPath","deviceType":"usb","readOnly":true}""")
        }.body<JsonObject>().getValue("id").jsonPrimitive.content
        api.post("/api/libraries/$libraryId/scan")

        val update = api.put("/api/libraries/$libraryId") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"name":"离线硬盘","path":"$escapedPath","deviceType":"usb","readOnly":true,"enabled":false,"downloadTarget":false}""")
        }
        val scan = api.post("/api/libraries/$libraryId/scan").body<JsonObject>()
        val page = api.get("/api/library?libraryId=$libraryId").body<JsonObject>()

        assertEquals(HttpStatusCode.OK, update.status)
        assertEquals("disabled", update.body<JsonObject>().getValue("status").jsonPrimitive.content)
        assertEquals("disabled", scan.getValue("status").jsonPrimitive.content)
        assertEquals(1, page.getValue("total").jsonPrimitive.content.toInt())
    }
}
