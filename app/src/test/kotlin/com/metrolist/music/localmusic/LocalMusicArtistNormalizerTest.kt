package com.metrolist.music.localmusic

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalMusicArtistNormalizerTest {
    @Test
    fun readsCompositeArtistsInsideTransaction() {
        val source = normalizerSource()
        val functionIndex = source.indexOf("suspend fun MusicDatabase.normalizeCompositeLocalArtists()")
        assertTrue("normalizer function must exist", functionIndex >= 0)

        val body = source.substring(
            functionIndex,
            source.indexOf("\n}\n\nprivate fun", startIndex = functionIndex),
        )
        val transactionIndex = body.indexOf("withTransaction")
        val compositeReadIndex = body.indexOf("compositeLocalArtists()")

        assertTrue("normalizer must use a transaction", transactionIndex >= 0)
        assertTrue("normalizer must read composite artists", compositeReadIndex >= 0)
        assertTrue(
            "composite artist reads must stay inside the transaction so Room never hits the main thread",
            transactionIndex < compositeReadIndex,
        )
    }

    private fun normalizerSource(): String {
        val candidates = mutableListOf<File>()
        var root: File? = File(System.getProperty("user.dir") ?: ".")
        while (root != null) {
            candidates += File(root, "app/src/main/kotlin/com/metrolist/music/localmusic/LocalMusicArtistNormalizer.kt")
            candidates += File(root, "src/main/kotlin/com/metrolist/music/localmusic/LocalMusicArtistNormalizer.kt")
            root = root.parentFile
        }

        return candidates
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("LocalMusicArtistNormalizer.kt not found")
    }
}
