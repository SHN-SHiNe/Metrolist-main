package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistNameSplitterTest {
    @Test
    fun splitsCommonCollaborationDelimiters() {
        assertEquals(listOf("ATB", "Topic", "A7S"), ArtistNameSplitter.split("ATB, Topic / A7S"))
        assertEquals(listOf("ATB", "Topic"), ArtistNameSplitter.split("ATB/Topic"))
        assertEquals(listOf("ATB", "Topic"), ArtistNameSplitter.split("ATB；Topic"))
        assertEquals(listOf("ATB", "Topic"), ArtistNameSplitter.split("ATB、Topic"))
    }

    @Test
    fun removesDuplicateArtistsCaseInsensitively() {
        assertEquals(listOf("ATB", "Topic"), ArtistNameSplitter.split("ATB, atb, Topic"))
    }

    @Test
    fun returnsUnknownArtistWhenNameIsBlank() {
        assertEquals(listOf("Unknown Artist"), ArtistNameSplitter.split(" "))
        assertEquals(listOf("Unknown Artist"), ArtistNameSplitter.split(null))
    }
}
