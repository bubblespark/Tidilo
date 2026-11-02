package me.bubblespark.tidilo.data

/**
 * Extracts candidate skeleton keys from title and description text using a sliding window.
 */
class EntityCandidateExtractor(
    private val segmenter: TextSegmenter = IcuTextSegmenter()
) {
    companion object {
        const val MAX_WINDOW = 6
        const val MAX_DESCRIPTION_CHARS = 1000
        const val MAX_TOKENS_PER_SECTION = 300
        const val MAX_CANDIDATE_KEYS = 500
    }

    /**
     * Extracts an ordered, de-duplicated list of candidate skeleton keys from [title] and [description].
     *
     * Title tokens are processed first, followed by description tokens (up to [MAX_DESCRIPTION_CHARS]).
     * Each section is capped at [MAX_TOKENS_PER_SECTION] tokens.
     * Sliding windows of size 1..6 are formed in descending window size order per start index.
     * Output is capped at [MAX_CANDIDATE_KEYS].
     */
    fun extractCandidateKeys(title: String, description: String?): List<String> {
        val keys = LinkedHashSet<String>()

        fun processSection(text: String) {
            if (text.isEmpty() || keys.size >= MAX_CANDIDATE_KEYS) return
            val tokens = segmenter.segment(text).take(MAX_TOKENS_PER_SECTION)
            for (start in tokens.indices) {
                val maxLen = minOf(MAX_WINDOW, tokens.size - start)
                for (len in maxLen downTo 1) {
                    val slice = tokens.subList(start, start + len).joinToString("")
                    val key = entitySkeletonKey(slice)
                    if (key.isNotEmpty()) {
                        keys.add(key)
                        if (keys.size >= MAX_CANDIDATE_KEYS) return
                    }
                }
            }
        }

        processSection(title)
        description?.take(MAX_DESCRIPTION_CHARS)?.let { processSection(it) }

        return keys.toList()
    }
}
