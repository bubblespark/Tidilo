package me.bubblespark.tidilo.data

/** Durable result of article acquisition, independent of the current Settings value. */
sealed interface ImportOutcome {
    val url: String

    data class BookmarkSaved(val bookmarkId: String, override val url: String) : ImportOutcome
}
