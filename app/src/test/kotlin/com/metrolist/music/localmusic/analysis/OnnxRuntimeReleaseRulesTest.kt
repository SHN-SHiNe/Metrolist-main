package com.metrolist.music.localmusic.analysis

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnnxRuntimeReleaseRulesTest {
    @Test
    fun releaseBuildKeepsOnnxRuntimeClassesForNativeCallbacks() {
        val rules = sourceFile("app/proguard-rules.pro")

        assertTrue(rules.contains("-keep class ai.onnxruntime.** { *; }"))
        assertTrue(rules.contains("-keepclassmembers class ai.onnxruntime.**"))
        assertTrue(rules.contains("-dontwarn ai.onnxruntime.**"))
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
