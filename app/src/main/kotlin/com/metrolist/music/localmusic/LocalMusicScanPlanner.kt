package com.metrolist.music.localmusic

data class LocalMusicScanDocument(
    val documentId: String,
    val fileSize: Long?,
    val dateModified: Long?,
)

data class LocalMusicExistingDocument(
    val songId: String,
    val documentId: String,
    val fileSize: Long?,
    val dateModified: Long?,
    val durationSeconds: Int,
    val missingSince: Long?,
    val hasIncompleteAnalysis: Boolean = false,
)

data class UnchangedLocalMusicDocument(
    val songId: String,
    val documentId: String,
    val wasMissing: Boolean,
)

data class LocalMusicScanPlan(
    val toImport: List<LocalMusicScanDocument>,
    val unchanged: List<UnchangedLocalMusicDocument>,
    val missingSongIds: List<String>,
    val skippedMissingBecauseCurrentScanWasEmpty: Boolean = false,
)

object LocalMusicScanPlanner {
    fun plan(
        currentDocuments: List<LocalMusicScanDocument>,
        existingDocuments: List<LocalMusicExistingDocument>,
        minDurationSeconds: Int,
    ): LocalMusicScanPlan {
        val existingByDocumentId = existingDocuments.associateBy { it.documentId }
        val currentByDocumentId = currentDocuments.associateBy { it.documentId }
        val toImport = mutableListOf<LocalMusicScanDocument>()
        val unchanged = mutableListOf<UnchangedLocalMusicDocument>()

        currentDocuments.forEach { current ->
            val existing = existingByDocumentId[current.documentId]
            when {
                existing == null -> toImport += current
                !existing.hasSameFingerprint(current) -> toImport += current
                !existing.isAllowedByDuration(minDurationSeconds) -> Unit
                existing.hasIncompleteAnalysis -> toImport += current
                else -> unchanged += UnchangedLocalMusicDocument(existing.songId, current.documentId, existing.missingSince != null)
            }
        }

        val currentScanWasEmptyButHadExistingDocuments = currentDocuments.isEmpty() && existingDocuments.any { it.missingSince == null }
        val missingSongIds =
            if (currentScanWasEmptyButHadExistingDocuments) {
                emptyList()
            } else {
                existingDocuments
                    .filter { it.missingSince == null }
                    .filter { existing ->
                        val current = currentByDocumentId[existing.documentId]
                        current == null ||
                            (
                                !existing.isAllowedByDuration(minDurationSeconds) &&
                                    existing.hasSameFingerprint(current)
                                )
                    }.map { it.songId }
            }

        return LocalMusicScanPlan(
            toImport = toImport,
            unchanged = unchanged,
            missingSongIds = missingSongIds,
            skippedMissingBecauseCurrentScanWasEmpty = currentScanWasEmptyButHadExistingDocuments,
        )
    }

    private fun LocalMusicExistingDocument.hasSameFingerprint(current: LocalMusicScanDocument): Boolean =
        fileSize == current.fileSize && dateModified == current.dateModified

    private fun LocalMusicExistingDocument.isAllowedByDuration(minDurationSeconds: Int): Boolean =
        minDurationSeconds <= 0 || durationSeconds < 0 || durationSeconds >= minDurationSeconds
}
