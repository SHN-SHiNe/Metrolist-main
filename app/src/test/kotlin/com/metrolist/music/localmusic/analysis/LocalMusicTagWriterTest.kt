/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.localmusic.analysis

import com.metrolist.music.localmusic.LocalMusicSourceTrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMusicTagWriterTest {
    @Test
    fun buildsId3TagWithManagedAnalysisFrames() {
        val tag = Id3AnalysisTagBuilder.build(result())
        val text = String(tag, Charsets.ISO_8859_1)

        assertTrue(text.startsWith("ID3"))
        assertTrue(text.contains("TBPM"))
        assertTrue(text.contains("TKEY"))
        assertTrue(text.contains("TXXX"))
        assertTrue(text.contains("VALENCE"))
        assertTrue(text.contains("TMOO"))
        assertTrue(text.contains("COMM"))
    }

    @Test
    fun builtAnalysisTagFramesAreParseable() {
        val frames = parseTag(Id3AnalysisTagBuilder.build(result()))

        assertEquals("128.25", frames["TBPM"])
        assertEquals("Am", frames["TKEY"])
        assertEquals("0.100", frames["TXXX:VALENCE"])
        assertEquals("0.200", frames["TXXX:ENERGY"])
        assertEquals("0.300", frames["TXXX:DANCEABILITY"])
        assertEquals("0.400", frames["TXXX:ACOUSTICNESS"])
        assertEquals("0.500", frames["TXXX:INSTRUMENTALNESS"])
        assertEquals("0.600", frames["TXXX:LIVENESS"])
        assertEquals("0.700", frames["TXXX:SPEECHINESS"])
        assertTrue(frames["TMOO"].orEmpty().contains("VALENCE:0.100"))
        assertTrue(frames["COMM::xxx"].orEmpty().contains("SPEECHINESS:0.700"))
    }

    @Test
    fun buildsVersionThreeTagsWithUtf16TextFrames() {
        val tag = Id3AnalysisTagBuilder.build(result(), ExistingId3Tag(version = 3, frames = emptyList()))
        val frames = parseTag(tag)

        assertEquals("Am", frames["TKEY"])
        assertEquals("0.100", frames["TXXX:VALENCE"])
    }

    @Test
    fun preservesUnmanagedFramesAndRemovesManagedFrames() {
        val unmanaged = rawTextFrame("TIT2", "Title")
        val managed = rawUserTextFrame("VALENCE", "0.999")
        val preserved = Id3AnalysisTagBuilder.preservedFrames(unmanaged + managed, version = 4)

        assertEquals(1, preserved.size)
        assertTrue(String(preserved.first(), Charsets.ISO_8859_1).contains("TIT2"))
        assertFalse(String(preserved.first(), Charsets.ISO_8859_1).contains("VALENCE"))
    }

    @Test
    fun preservesSourceTrackIdFrameWhenWritingAnalysisTags() {
        val sourceFrame = rawUserTextFrame(LocalMusicSourceTrackId.ID3_DESCRIPTION, "china_qq_12345")
        val tag = Id3AnalysisTagBuilder.build(result(), ExistingId3Tag(version = 4, frames = listOf(sourceFrame)))
        val frames = parseTag(tag)

        assertEquals("china_qq_12345", frames["TXXX:${LocalMusicSourceTrackId.ID3_DESCRIPTION}"])
        assertEquals("128.25", frames["TBPM"])
    }

    private fun result() =
        LocalMusicAnalysisResult(
            bpm = 128.25f,
            keyName = "Am",
            valence = 0.1f,
            energy = 0.2f,
            danceability = 0.3f,
            acousticness = 0.4f,
            instrumentalness = 0.5f,
            liveness = 0.6f,
            speechiness = 0.7f,
        )

    private fun parseTag(tag: ByteArray): Map<String, String> {
        assertTrue(String(tag, 0, 3, Charsets.ISO_8859_1) == "ID3")
        val version = tag[3].toInt() and 0xFF
        val tagSize = syncSafeInt(tag, 6)
        val values = linkedMapOf<String, String>()
        var offset = 10
        val end = 10 + tagSize
        while (offset + 10 <= end) {
            val frameId = String(tag, offset, 4, Charsets.ISO_8859_1)
            val frameSize = if (version == 4) syncSafeInt(tag, offset + 4) else int32(tag, offset + 4)
            if (frameSize <= 0 || offset + 10 + frameSize > tag.size) break
            val payload = tag.copyOfRange(offset + 10, offset + 10 + frameSize)
            when {
                frameId == "TXXX" -> {
                    val (description, text) = decodeUserTextFrame(payload)
                    values["TXXX:$description"] = text
                }
                frameId == "COMM" -> {
                    val (language, description, text) = decodeCommentFrame(payload)
                    values["COMM:$description:$language"] = text
                }
                frameId in setOf("TBPM", "TKEY", "TMOO") -> {
                    values[frameId] = decodeTextFrame(payload)
                }
            }
            offset += 10 + frameSize
        }
        return values
    }

    private fun decodeTextFrame(payload: ByteArray): String =
        decodeEncodedText(payload.copyOfRange(1, payload.size), payload[0].toInt() and 0xFF)

    private fun decodeUserTextFrame(payload: ByteArray): Pair<String, String> {
        val encoding = payload[0].toInt() and 0xFF
        val data = payload.copyOfRange(1, payload.size)
        val separator = findTerminator(data, encoding)
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        return decodeEncodedText(data.copyOfRange(0, separator), encoding) to
            decodeEncodedText(data.copyOfRange(separator + step, data.size), encoding)
    }

    private fun decodeCommentFrame(payload: ByteArray): Triple<String, String, String> {
        val encoding = payload[0].toInt() and 0xFF
        val language = String(payload, 1, 3, Charsets.ISO_8859_1)
        val data = payload.copyOfRange(4, payload.size)
        val separator = findTerminator(data, encoding)
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        return Triple(
            language,
            decodeEncodedText(data.copyOfRange(0, separator), encoding),
            decodeEncodedText(data.copyOfRange(separator + step, data.size), encoding),
        )
    }

    private fun decodeEncodedText(data: ByteArray, encoding: Int): String {
        val charset =
            when (encoding) {
                1 -> Charsets.UTF_16
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> Charsets.ISO_8859_1
            }
        return String(data.trimTrailingZeroes(), charset).replace("\u0000", "").trim()
    }

    private fun findTerminator(data: ByteArray, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var index = 0
            while (index + 1 < data.size) {
                if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) return index
                index += 2
            }
            return -1
        }
        return data.indexOf(0)
    }

    private fun ByteArray.trimTrailingZeroes(): ByteArray {
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) end--
        return copyOfRange(0, end)
    }

    private fun rawTextFrame(frameId: String, value: String): ByteArray {
        val payload = byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
        return rawFrame(frameId, payload)
    }

    private fun rawUserTextFrame(description: String, value: String): ByteArray {
        val payload =
            byteArrayOf(3) +
                description.toByteArray(Charsets.UTF_8) +
                byteArrayOf(0) +
                value.toByteArray(Charsets.UTF_8)
        return rawFrame("TXXX", payload)
    }

    private fun rawFrame(frameId: String, payload: ByteArray): ByteArray =
        frameId.toByteArray(Charsets.ISO_8859_1) +
            syncSafe(payload.size) +
            byteArrayOf(0, 0) +
            payload

    private fun syncSafe(value: Int): ByteArray =
        byteArrayOf(
            ((value shr 21) and 0x7F).toByte(),
            ((value shr 14) and 0x7F).toByte(),
            ((value shr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun int32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
