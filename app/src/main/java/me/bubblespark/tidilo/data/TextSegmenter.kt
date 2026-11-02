package me.bubblespark.tidilo.data

import android.icu.text.BreakIterator as IcuBreakIterator
import android.icu.util.ULocale
import java.text.BreakIterator as StandardBreakIterator
import java.util.Locale

/**
 * Segments natural language text into word tokens, discarding whitespace and punctuation.
 */
interface TextSegmenter {
    fun segment(text: String): List<String>
}

/**
 * Android ICU BreakIterator implementation using ROOT locale for language-agnostic segmentation.
 * Available natively on Android API 34+.
 */
class IcuTextSegmenter : TextSegmenter {
    override fun segment(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val iterator = IcuBreakIterator.getWordInstance(ULocale.ROOT)
        iterator.setText(text)
        val tokens = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != IcuBreakIterator.DONE) {
            val ruleStatus = iterator.ruleStatus
            if (ruleStatus != IcuBreakIterator.WORD_NONE) {
                val token = text.substring(start, end)
                if (token.any { it.isLetterOrDigit() }) {
                    tokens.add(token)
                }
            }
            start = end
            end = iterator.next()
        }
        return tokens
    }
}

/**
 * Standard Java BreakIterator implementation using ROOT locale.
 * Intended for JVM unit tests where Android ICU is not mocked.
 */
class StandardTextSegmenter : TextSegmenter {
    override fun segment(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val iterator = StandardBreakIterator.getWordInstance(Locale.ROOT)
        iterator.setText(text)
        val tokens = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != StandardBreakIterator.DONE) {
            val token = text.substring(start, end)
            if (token.any { it.isLetterOrDigit() }) {
                tokens.add(token)
            }
            start = end
            end = iterator.next()
        }
        return tokens
    }
}
