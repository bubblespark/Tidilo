package me.bubblespark.tidilo.data

/** Durable result of article acquisition, independent of the current Settings value. */
sealed interface ImportOutcome {
    val url: String

    data class QueuedForAi(val task: ImportTask) : ImportOutcome {
        override val url: String get() = task.url
    }

    data class BookmarkSaved(val bookmarkId: String, override val url: String) : ImportOutcome
}
