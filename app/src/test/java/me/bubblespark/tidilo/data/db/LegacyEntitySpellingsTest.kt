package me.bubblespark.tidilo.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyEntitySpellingsTest {
    @Test fun binaryLexicalTieUsesUtf8OrderForSupplementarySpellings() {
        assertEquals(mapOf("a" to "\uFF21"), selectLegacyEntitySpellings(listOf(
            LegacyEntitySpelling("a", 1, "\uD835\uDC00"), LegacyEntitySpelling("b", 1, "\uFF21")
        )))
    }

    @Test fun distinctBookmarksOutvoteRepeatedOccurrences() {
        val rows = listOf(LegacyEntitySpelling("a", 20, "Open AI"), LegacyEntitySpelling("b", 30, "Open AI")) +
            List(10) { LegacyEntitySpelling("c", 1, "OpenAI") }
        assertEquals(mapOf("openai" to "Open AI"), selectLegacyEntitySpellings(rows))
    }

    @Test fun equalCountsChooseOldestContainingBookmark() {
        assertEquals(mapOf("openai" to "OpenAI"), selectLegacyEntitySpellings(listOf(
            LegacyEntitySpelling("a", 20, "Open AI"), LegacyEntitySpelling("b", 10, "OpenAI")
        )))
    }

    @Test fun equalCountsAndAgesChooseBinaryLexicalSpellingAndOmitEmptyKeys() {
        assertEquals(mapOf("openai" to "Open AI"), selectLegacyEntitySpellings(listOf(
            LegacyEntitySpelling("a", 10, "OpenAI"), LegacyEntitySpelling("b", 10, " #Open AI "),
            LegacyEntitySpelling("c", 1, " -_. ")
        )))
    }
}
