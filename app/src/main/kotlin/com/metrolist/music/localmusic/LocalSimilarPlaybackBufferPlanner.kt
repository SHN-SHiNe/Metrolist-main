package com.metrolist.music.localmusic

data class LocalSimilarPlaybackBufferItem<T>(
    val id: String,
    val value: T,
)

data class LocalSimilarPlaybackBufferPlan<T>(
    val items: List<T>,
    val currentIndex: Int,
)

enum class LocalSimilarPlaybackBufferUpdateStrategy {
    PRESERVE_CURRENT_ITEM,
    REPLACE_TIMELINE,
}

object LocalSimilarPlaybackBufferPlanner {
    fun <T> plan(
        current: LocalSimilarPlaybackBufferItem<T>,
        previous: LocalSimilarPlaybackBufferItem<T>?,
        next: LocalSimilarPlaybackBufferItem<T>?,
    ): LocalSimilarPlaybackBufferPlan<T> =
        plan(
            current = current,
            previousItems = listOfNotNull(previous),
            next = next,
        )

    fun <T> plan(
        current: LocalSimilarPlaybackBufferItem<T>,
        previousItems: List<LocalSimilarPlaybackBufferItem<T>>,
        next: LocalSimilarPlaybackBufferItem<T>?,
    ): LocalSimilarPlaybackBufferPlan<T> {
        val items = mutableListOf<T>()
        var currentIndex = 0
        val usedIds = mutableSetOf<String>()

        previousItems.forEach { previous ->
            if (previous.id != current.id && usedIds.add(previous.id)) {
                items += previous.value
            }
        }

        currentIndex = items.size
        items += current.value
        usedIds += current.id

        if (next != null && next.id != current.id && usedIds.add(next.id)) {
            items += next.value
        }

        return LocalSimilarPlaybackBufferPlan(
            items = items,
            currentIndex = currentIndex,
        )
    }

    fun updateStrategy(
        currentPlayerIds: List<String>,
        currentPlayerIndex: Int,
        plannedIds: List<String>,
        plannedCurrentIndex: Int,
    ): LocalSimilarPlaybackBufferUpdateStrategy {
        val currentId = currentPlayerIds.getOrNull(currentPlayerIndex)
        val plannedCurrentId = plannedIds.getOrNull(plannedCurrentIndex)
        return if (currentId != null && currentId == plannedCurrentId) {
            LocalSimilarPlaybackBufferUpdateStrategy.PRESERVE_CURRENT_ITEM
        } else {
            LocalSimilarPlaybackBufferUpdateStrategy.REPLACE_TIMELINE
        }
    }
}
