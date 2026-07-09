package com.metrolist.music.ui.screens.localmusic

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalMusicLongPressMenuTest {
    @Test
    fun localMusicCardsOpenSongMenuOnLongPress() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/screens/localmusic/LocalMusicScreen.kt")

        assertTrue(source.contains("val menuState = LocalMenuState.current"))
        assertTrue(source.contains("onLongClick = { onLongClick(localSong) }"))
        assertTrue(source.contains("SongMenu("))
        assertTrue(source.contains("originalSong = localSong.song"))
    }

    @Test
    fun localSongMenuCanDeleteLocalFileAndDatabaseRows() {
        val source = sourceFile("app/src/main/kotlin/com/metrolist/music/ui/menu/SongMenu.kt")

        assertTrue(source.contains("showDeleteLocalMusicDialog"))
        assertTrue(source.contains("删除本地文件"))
        assertTrue(source.contains("deleteLocalMusicFileAndDatabase("))
        assertTrue(source.contains("context.contentResolver.delete"))
        assertTrue(source.contains("deleteLocalMusicBySongId(localMusic.songId)"))
        assertTrue(source.contains("deleteSongById(localMusic.songId)"))
    }

    private fun sourceFile(relativePath: String): String {
        val candidates = mutableListOf<File>()
        var root: File? = File(System.getProperty("user.dir") ?: ".")
        while (root != null) {
            candidates += File(root, relativePath)
            candidates += File(root, relativePath.removePrefix("app/"))
            root = root.parentFile
        }

        return candidates
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("$relativePath not found")
    }
}
