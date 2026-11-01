package me.bubblespark.tidilo.data

import android.icu.text.BreakIterator

data class CollectionDraft(
    val title: String = "",
    val description: String = "",
    val presetIconId: String? = "folder",
    val customSymbol: String? = null,
    val colorIndex: Int = 0
)

object CollectionPolicy {
    val presetIds = listOf("folder", "schedule", "star", "work", "code", "lightbulb", "palette", "favorite")

    fun normalize(draft: CollectionDraft): CollectionDraft {
        val title = draft.title.trim()
        require(title.isNotEmpty() && limitGraphemes(title, 40) == title)
        require(draft.colorIndex in 0..7)
        require(draft.presetIconId == null || draft.presetIconId in presetIds)
        val description = twoLines(draft.description)
        require(limitGraphemes(description, 100) == description)
        return draft.copy(title = title, description = description,
            customSymbol = lastSymbol(draft.customSymbol.orEmpty()).ifBlank { null })
    }

    fun twoLines(input: String): String {
        val lines = input.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return lines.first() + if (lines.size > 1) "\n" + lines.drop(1).joinToString(" ") else ""
    }

    fun lastSymbol(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        val end = iterator.last()
        return text.substring(iterator.previous(), end)
    }

    fun limitGraphemes(input: String, max: Int): String {
        require(max >= 0)
        val iterator = BreakIterator.getCharacterInstance().apply { setText(input) }
        var end = iterator.first()
        repeat(max) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return input
            end = next
        }
        return input.substring(0, end)
    }
}
