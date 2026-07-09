package com.metrolist.music.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NavigationBuilderArtistRouteTest {
    @Test
    fun artistOnlineRouteUsesDedicatedArtistWorksScreen() {
        val source = navigationBuilderSource()
        val routeIndex = source.indexOf("route = \"artist/{artistId}/online?name={name}\"")
        assertTrue("artist online route must exist", routeIndex >= 0)

        val routeBlock = source.substring(routeIndex, source.indexOf("\n    composable(", startIndex = routeIndex + 1))
        assertTrue(routeBlock.contains("ArtistOnlineScreen("))
        assertFalse(routeBlock.contains("ChinaSearchScreen("))
    }

    private fun navigationBuilderSource(): String {
        val candidates =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .flatMap { root ->
                    sequenceOf(
                        File(root, "app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt"),
                        File(root, "src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt"),
                    )
                }

        return candidates
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("NavigationBuilder.kt not found")
    }
}
