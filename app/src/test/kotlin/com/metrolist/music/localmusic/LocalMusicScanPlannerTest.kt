package com.metrolist.music.localmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMusicScanPlannerTest {
    @Test
    fun unchangedDocumentsAreSkippedWhileNewChangedAndMissingDocumentsAreSeparated() {
        val plan =
            LocalMusicScanPlanner.plan(
                currentDocuments =
                    listOf(
                        LocalMusicScanDocument(documentId = "same", fileSize = 100, dateModified = 10),
                        LocalMusicScanDocument(documentId = "changed", fileSize = 200, dateModified = 30),
                        LocalMusicScanDocument(documentId = "new", fileSize = 300, dateModified = 40),
                    ),
                existingDocuments =
                    listOf(
                        LocalMusicExistingDocument(
                            songId = "song_same",
                            documentId = "same",
                            fileSize = 100,
                            dateModified = 10,
                            durationSeconds = 180,
                            missingSince = null,
                        ),
                        LocalMusicExistingDocument(
                            songId = "song_changed",
                            documentId = "changed",
                            fileSize = 200,
                            dateModified = 20,
                            durationSeconds = 180,
                            missingSince = null,
                        ),
                        LocalMusicExistingDocument(
                            songId = "song_removed",
                            documentId = "removed",
                            fileSize = 400,
                            dateModified = 50,
                            durationSeconds = 180,
                            missingSince = null,
                        ),
                    ),
                minDurationSeconds = 30,
            )

        assertEquals(listOf("song_same"), plan.unchanged.map { it.songId })
        assertEquals(listOf("changed", "new"), plan.toImport.map { it.documentId })
        assertEquals(listOf("song_removed"), plan.missingSongIds)
    }

    @Test
    fun unchangedDocumentsBelowMinimumDurationAreMarkedMissingWithoutReimporting() {
        val plan =
            LocalMusicScanPlanner.plan(
                currentDocuments =
                    listOf(
                        LocalMusicScanDocument(documentId = "short", fileSize = 100, dateModified = 10),
                        LocalMusicScanDocument(documentId = "unknown", fileSize = 200, dateModified = 20),
                        LocalMusicScanDocument(documentId = "long", fileSize = 300, dateModified = 30),
                    ),
                existingDocuments =
                    listOf(
                        LocalMusicExistingDocument(
                            songId = "song_short",
                            documentId = "short",
                            fileSize = 100,
                            dateModified = 10,
                            durationSeconds = 12,
                            missingSince = null,
                        ),
                        LocalMusicExistingDocument(
                            songId = "song_unknown",
                            documentId = "unknown",
                            fileSize = 200,
                            dateModified = 20,
                            durationSeconds = -1,
                            missingSince = null,
                        ),
                        LocalMusicExistingDocument(
                            songId = "song_long",
                            documentId = "long",
                            fileSize = 300,
                            dateModified = 30,
                            durationSeconds = 240,
                            missingSince = null,
                        ),
                    ),
                minDurationSeconds = 30,
            )

        assertEquals(listOf("song_unknown", "song_long"), plan.unchanged.map { it.songId })
        assertEquals(emptyList<String>(), plan.toImport.map { it.documentId })
        assertEquals(listOf("song_short"), plan.missingSongIds)
    }

    @Test
    fun unchangedDocumentsWithIncompleteAnalysisAreReimportedToRefreshFileTags() {
        val plan =
            LocalMusicScanPlanner.plan(
                currentDocuments =
                    listOf(
                        LocalMusicScanDocument(documentId = "missing_key", fileSize = 100, dateModified = 10),
                        LocalMusicScanDocument(documentId = "complete", fileSize = 200, dateModified = 20),
                    ),
                existingDocuments =
                    listOf(
                        LocalMusicExistingDocument(
                            songId = "song_missing_key",
                            documentId = "missing_key",
                            fileSize = 100,
                            dateModified = 10,
                            durationSeconds = 180,
                            missingSince = null,
                            hasIncompleteAnalysis = true,
                        ),
                        LocalMusicExistingDocument(
                            songId = "song_complete",
                            documentId = "complete",
                            fileSize = 200,
                            dateModified = 20,
                            durationSeconds = 180,
                            missingSince = null,
                            hasIncompleteAnalysis = false,
                        ),
                    ),
                minDurationSeconds = 30,
            )

        assertEquals(listOf("missing_key"), plan.toImport.map { it.documentId })
        assertEquals(listOf("song_complete"), plan.unchanged.map { it.songId })
    }

    @Test
    fun emptyCurrentScanDoesNotMarkExistingDocumentsMissing() {
        val plan =
            LocalMusicScanPlanner.plan(
                currentDocuments = emptyList(),
                existingDocuments =
                    listOf(
                        LocalMusicExistingDocument(
                            songId = "song_one",
                            documentId = "one",
                            fileSize = 100,
                            dateModified = 10,
                            durationSeconds = 180,
                            missingSince = null,
                        ),
                        LocalMusicExistingDocument(
                            songId = "song_two",
                            documentId = "two",
                            fileSize = 200,
                            dateModified = 20,
                            durationSeconds = 240,
                            missingSince = null,
                        ),
                    ),
                minDurationSeconds = 30,
            )

        assertEquals(emptyList<String>(), plan.missingSongIds)
        assertEquals(true, plan.skippedMissingBecauseCurrentScanWasEmpty)
    }
}
