package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.data.db.bookmarkSearchKey
import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkSearchKeyTest {
    @Test
    fun substringKeysPreserveCurrentCaseInsensitiveMatching() {
        val examples = listOf(
            "Android", "ANDROID", "日本語の記事", "İstanbul", "istanbul", "ıstanbul",
            "Σίσυφος", "σίσυφοσ", "ς", "σ", "Kelvin", "kelvin", "Straße", "STRASSE",
            "École", "école", "e\u0301cole", "%_\\", "#", "ＡＢＣ", "ａｂｃ",
            "\uD801\uDC00", "\uD801\uDC28", "https://EXAMPLE.com/記事"
        )
        for (text in examples) {
            for (query in examples + listOf("", "I", "s", "σ", "%", "_", "記事", "cole")) {
                assertEquals(
                    "text=$text query=$query",
                    text.contains(query, ignoreCase = true),
                    bookmarkSearchKey(text).contains(bookmarkSearchKey(query))
                )
            }
        }
    }

    @Test
    fun caseMappingsAgreeForEveryUnicodeCodePoint() {
        for (point in 0..Character.MAX_CODE_POINT) {
            if (point in 0xD800..0xDFFF) continue
            val original = String(Character.toChars(point))
            for (variant in intArrayOf(Character.toUpperCase(point), Character.toLowerCase(point))) {
                val other = String(Character.toChars(variant))
                assertEquals(
                    "U+${point.toString(16)} versus U+${variant.toString(16)}",
                    original.equals(other, ignoreCase = true),
                    bookmarkSearchKey(original) == bookmarkSearchKey(other)
                )
            }
        }
    }
}
