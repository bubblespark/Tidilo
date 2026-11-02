package me.bubblespark.tidilo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcuTextSegmenterTest {
    private val icuSegmenter = IcuTextSegmenter()
    private val standardSegmenter = StandardTextSegmenter()

    @Test
    fun segmentsEnglishText() {
        val tokens = icuSegmenter.segment("Jetpack Compose for Android 16")
        assertEquals(listOf("Jetpack", "Compose", "for", "Android", "16"), tokens)
    }

    @Test
    fun segmentsJapaneseTextWithoutWhitespace() {
        val tokens = icuSegmenter.segment("東京タワーの観光案内")
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.all { token ->
            token.isNotEmpty() && token.all { !it.isWhitespace() } && token.any { it.isLetterOrDigit() }
        })
    }

    @Test
    fun filtersPunctuationAndWhitespace() {
        val tokens = icuSegmenter.segment("Hello, World! #test")
        assertEquals(listOf("Hello", "World", "test"), tokens)
        assertFalse(tokens.any { token ->
            token.contains(",") || token.contains("!") || token.contains("#") || token.contains(" ")
        })
    }

    @Test
    fun parityWithStandardSegmenterOnEnglishText() {
        val text = "Jetpack Compose for Android 16: Modern UI toolkit."
        val icuTokens = icuSegmenter.segment(text)
        val standardTokens = standardSegmenter.segment(text)
        assertEquals(standardTokens, icuTokens)
    }
}
