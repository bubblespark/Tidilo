package me.bubblespark.tidilo.data

import org.junit.Assert.*
import org.junit.Test

class CollectionSymbolTest {
    @Test fun compositeSymbolsAndLimitsUseWholeGraphemes() {
        for (symbol in listOf("👨‍👩‍👧‍👦", "👍🏽", "🇯🇵", "e\u0301")) {
            assertEquals(symbol, CollectionPolicy.lastSymbol("ab$symbol"))
            assertEquals(symbol.repeat(40), CollectionPolicy.limitGraphemes(symbol.repeat(41), 40))
        }
        assertEquals("日".repeat(100), CollectionPolicy.limitGraphemes("日".repeat(101), 100))
        assertEquals("", CollectionPolicy.lastSymbol("  "))
    }

    @Test fun validationRejectsInvalidValuesAndNormalizesOptionalFields() {
        val draft = CollectionDraft(" Title ", "a\r\nb\nc", "folder", "ab👍🏽", 7)
        val result = CollectionPolicy.normalize(draft)
        assertEquals("Title", result.title)
        assertEquals("a\nb c", result.description)
        assertEquals("👍🏽", result.customSymbol)
        assertEquals("", CollectionPolicy.normalize(draft.copy(description = "", customSymbol = null)).description)
        for (bad in listOf(draft.copy(title = "  "), draft.copy(title = "a".repeat(41)), draft.copy(description = "a".repeat(101)), draft.copy(colorIndex = -1), draft.copy(colorIndex = 8), draft.copy(presetIconId = "unknown"))) {
            assertThrows(IllegalArgumentException::class.java) { CollectionPolicy.normalize(bad) }
        }
    }
}
