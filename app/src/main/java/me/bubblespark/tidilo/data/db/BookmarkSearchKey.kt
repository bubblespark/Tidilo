package me.bubblespark.tidilo.data.db

internal val supplementaryIgnoreCase = "\uD801\uDC00".equals("\uD801\uDC28", ignoreCase = true)

/**
 * Produces a case-insensitive comparison key without expanding characters or tokenizing text.
 * Uppercase-then-lowercase folding preserves distinctions such as sharp S versus "ss" while
 * treating dotted/dotless I and final sigma like Kotlin's ignoreCase comparison.
 */
internal fun bookmarkSearchKey(value: String): String = buildString(value.length) {
    // Android compares UTF-16 units; newer desktop JDKs also fold supplementary letters.
    // Follow the running String implementation used by Kotlin's existing search.
    if (!supplementaryIgnoreCase) {
        value.forEach { append(Character.toLowerCase(Character.toUpperCase(it))) }
        return@buildString
    }
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        appendCodePoint(Character.toLowerCase(Character.toUpperCase(codePoint)))
        offset += Character.charCount(codePoint)
    }
}
