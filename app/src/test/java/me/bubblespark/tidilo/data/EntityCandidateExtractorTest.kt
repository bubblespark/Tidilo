package me.bubblespark.tidilo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityCandidateExtractorTest {
    private val extractor = EntityCandidateExtractor(StandardTextSegmenter())

    @Test
    fun extractsSingleWordCandidates() {
        val candidates = extractor.extractCandidateKeys("Kotlin", null)
        assertEquals(listOf("kotlin"), candidates)
    }

    @Test
    fun extractsMultiWordWindowsUpToSix() {
        val candidates = extractor.extractCandidateKeys("Jetpack Compose", null)
        assertEquals(listOf("jetpackcompose", "jetpack", "compose"), candidates)
        assertTrue(candidates.contains("jetpackcompose"))
    }

    @Test
    fun filtersWhitespaceAndPunctuationOnlyTokens() {
        val emptyCandidates = extractor.extractCandidateKeys(" ... --- ,, !? \t\n ", null)
        assertTrue(emptyCandidates.isEmpty())

        val candidates = extractor.extractCandidateKeys("Hello, World! #test", null)
        assertFalse(candidates.any { it.contains(",") || it.contains("!") || it.contains("#") || it.contains(" ") })
        assertTrue(candidates.contains("hello"))
        assertTrue(candidates.contains("world"))
        assertTrue(candidates.contains("test"))
        assertTrue(candidates.contains("helloworld"))
    }

    @Test
    fun candidateOrderIsDeterministic() {
        val candidates = extractor.extractCandidateKeys(
            title = "Alpha Beta",
            description = "Gamma Delta Alpha"
        )
        val expected = listOf(
            "alphabeta", "alpha", "beta",
            "gammadeltaalpha", "gammadelta", "gamma",
            "deltaalpha", "delta"
        )
        assertEquals(expected, candidates)
    }

    @Test
    fun capsDescriptionLength() {
        val prefix = "a ".repeat(500)
        val description = prefix + "secretword"
        val candidates = extractor.extractCandidateKeys(title = "Title", description = description)
        assertFalse(candidates.contains("secretword"))
    }

    @Test
    fun capsTokensPerSection() {
        // When specialtoken is at index 299 (the 300th token), it is within the 300-token limit.
        val titleWithinCap = "a ".repeat(299) + "specialtoken"
        val candidatesWithinCap = extractor.extractCandidateKeys(titleWithinCap, null)
        assertTrue(candidatesWithinCap.contains("specialtoken"))

        // When specialtoken is at index 300 (the 301st token), it exceeds the 300-token limit and is dropped.
        val titleExceedingCap = "a ".repeat(300) + "specialtoken"
        val candidatesExceedingCap = extractor.extractCandidateKeys(titleExceedingCap, null)
        assertFalse(candidatesExceedingCap.contains("specialtoken"))
    }

    @Test
    fun capsCandidateKeysAtFiveHundred() {
        val words = (0 until 200).map { "word$it" }
        val title = words.joinToString(" ")
        val candidates = extractor.extractCandidateKeys(title = title, description = null)
        assertEquals(500, candidates.size)
    }

    @Test
    fun handlesEmptyAndNullInputs() {
        assertTrue(extractor.extractCandidateKeys("", null).isEmpty())
        assertTrue(extractor.extractCandidateKeys("   ", "").isEmpty())
        assertTrue(extractor.extractCandidateKeys("   ", "   \t\n").isEmpty())
    }

    @Test
    fun windowSizeCapsAtSix() {
        val title = "One Two Three Four Five Six Seven"
        val candidates = extractor.extractCandidateKeys(title, null)
        assertTrue(candidates.contains("onetwothreefourfivesix"))
        assertTrue(candidates.contains("twothreefourfivesixseven"))
        assertFalse(candidates.contains("onetwothreefourfivesixseven"))
    }

    @Test
    fun capsTitleLength() {
        val longPrefix = "a".repeat(1000)
        val title = longPrefix + "Target"
        val candidates = extractor.extractCandidateKeys(title, null)
        assertFalse(candidates.contains("target"))
    }

    @Test
    fun symbolOnlyTagLimitation() {
        val candidates = extractor.extractCandidateKeys("C++", null)
        assertEquals(listOf("c"), candidates)

        val csharp = extractor.extractCandidateKeys("C#", null)
        assertEquals(listOf("c"), csharp)
    }

    @Test
    fun extractSuggestionTokensPreservesSurfaceSpellingAndDeduplicatesBySkeletonKey() {
        val tokens = extractor.extractSuggestionTokens(
            title = "iPhone 16 Pro: Apple's New Flagship",
            description = "The iphone 16 pro introduces new camera features from APPLE."
        )
        assertEquals(
            listOf("iPhone", "16", "Pro", "Apple's", "New", "Flagship", "The", "introduces", "camera", "features", "from", "APPLE"),
            tokens
        )
    }

    @Test
    fun extractSuggestionTokensDeduplicatesCaseVariantsKeepingFirstSeen() {
        val tokens = extractor.extractSuggestionTokens(
            title = "Jetpack Compose",
            description = "jetpack COMPOSE and Kotlin"
        )
        assertEquals(listOf("Jetpack", "Compose", "and", "Kotlin"), tokens)
    }

    @Test
    fun extractSuggestionTokensKeepsSingleCharAndNumericTokens() {
        val tokens = extractor.extractSuggestionTokens(
            title = "iPhone 16 R 1",
            description = null
        )
        assertEquals(listOf("iPhone", "16", "R", "1"), tokens)
    }

    @Test
    fun extractSuggestionTokensHandlesEmptyAndBlankInputs() {
        assertTrue(extractor.extractSuggestionTokens("", null).isEmpty())
        assertTrue(extractor.extractSuggestionTokens("   ... !?  ", " --- ").isEmpty())
    }

    @Test
    fun extractSuggestionTokensRespectsSectionCaps() {
        val longPrefix = "a".repeat(1000)
        val tokens = extractor.extractSuggestionTokens(title = longPrefix + "Target", description = null)
        assertFalse(tokens.contains("Target"))
    }
}
