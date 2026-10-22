package me.bubblespark.tidilo.data.db

/**
 * Produces a case-insensitive comparison key without expanding characters or tokenizing text.
 * Uppercase-then-lowercase folding preserves distinctions such as sharp S versus "ss" while
 * treating dotted/dotless I and final sigma like Kotlin's ignoreCase comparison.
 */
internal fun bookmarkSearchKey(value: String): String = buildString(value.length) {
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        appendCodePoint(Character.toLowerCase(Character.toUpperCase(codePoint)))
        offset += Character.charCount(codePoint)
    }
}
