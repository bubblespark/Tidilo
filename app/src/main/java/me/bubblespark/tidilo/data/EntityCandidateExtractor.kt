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

        fun default(): EntityCandidateExtractor {
            return runCatching {
                val segmenter = IcuTextSegmenter()
                segmenter.segment("test")
                EntityCandidateExtractor(segmenter)
            }.getOrElse {
                EntityCandidateExtractor(StandardTextSegmenter())
            }
        }
    }

    /**
     * Extracts an ordered, de-duplicated list of candidate skeleton keys from [title] and [description].
     *
     * Title (up to [MAX_DESCRIPTION_CHARS]) is processed first, followed by description tokens (up to [MAX_DESCRIPTION_CHARS]).
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

        processSection(title.take(MAX_DESCRIPTION_CHARS))
        description?.take(MAX_DESCRIPTION_CHARS)?.let { processSection(it) }

        return keys.toList()
    }

    /**
     * Extracts an ordered, de-duplicated list of 1-token word suggestions from [title] and [description],
     * preserving the original surface spelling of the first encountered occurrence per skeleton key.
     */
    fun extractSuggestionTokens(title: String, description: String?): List<String> {
        val seenKeys = LinkedHashSet<String>()
        val result = mutableListOf<String>()

        fun processSection(text: String) {
            if (text.isEmpty() || result.size >= MAX_CANDIDATE_KEYS) return
            val tokens = segmenter.segment(text).take(MAX_TOKENS_PER_SECTION)
            for (rawToken in tokens) {
                val clean = EntityTagPolicy.clean(rawToken) ?: continue
                val key = entitySkeletonKey(clean)
                if (key.isNotEmpty() && seenKeys.add(key)) {
                    result.add(clean)
                    if (result.size >= MAX_CANDIDATE_KEYS) return
                }
            }
        }

        return runCatching {
            processSection(title.take(MAX_DESCRIPTION_CHARS))
            description?.take(MAX_DESCRIPTION_CHARS)?.let { processSection(it) }
            result.toList()
        }.getOrDefault(emptyList())
    }
}
