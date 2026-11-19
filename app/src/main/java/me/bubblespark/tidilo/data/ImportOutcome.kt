package me.bubblespark.tidilo.data

/** Final result of an import attempt, independent of the current Settings value. */
sealed interface ImportOutcome {
    val url: String

    data class BookmarkSaved(val bookmarkId: String, override val url: String) : ImportOutcome
    data class SavedDuplicate(val bookmarkId: String, override val url: String) : ImportOutcome
    data class AlreadySaving(override val url: String) : ImportOutcome
    data class Failed(override val url: String, val retryTaskId: String? = null) : ImportOutcome
}
