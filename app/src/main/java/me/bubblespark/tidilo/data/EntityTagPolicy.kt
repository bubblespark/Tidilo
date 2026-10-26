package me.bubblespark.tidilo.data

/**
 * Shared non-UI policy for normalizing, deduplicating, and bounding entity tags.
 *
 * Entity tags adhere to the following rules:
 * - Leading and trailing whitespace is trimmed.
 * - A single leading '#' prefix is removed, followed by trimming again.
 * - Blank entries are ignored.
 * - Deduplication is case-insensitive, preserving insertion order and the original casing
 *   of the first encountered occurrence.
 * - The list size is capped at [MAX_ENTITIES].
 */
object EntityTagPolicy {
    const val MAX_ENTITIES = 10

    /**
     * Cleans an entity tag by trimming whitespace and removing one leading '#' prefix.
     * Returns null if the resulting string is blank or if the input is null.
     */
    fun clean(entity: String?): String? {
        if (entity == null) return null
        val normalized = entity.trim().removePrefix("#").trim()
        return normalized.takeIf { it.isNotBlank() }
    }

    /**
     * Normalizes a raw entity tag, or returns an empty string if blank.
     */
    fun normalize(entity: String): String =
        clean(entity).orEmpty()

    /**
     * Adds an entity tag to the current list if non-blank, not already present (case-insensitive),
     * and the list size is strictly below [MAX_ENTITIES].
     * Preserves insertion order and the original casing of the first added occurrence.
     */
    fun addEntity(current: List<String>, newEntity: String): List<String> {
        val clean = clean(newEntity) ?: return current
        if (current.size >= MAX_ENTITIES) return current
        if (current.any { it.equals(clean, ignoreCase = true) }) return current
        return current + clean
    }

    /**
     * Toggles an entity tag in the current list: removes if present (case-insensitive),
     * or adds if absent and list size is below [MAX_ENTITIES].
     */
    fun toggleEntity(current: List<String>, entity: String): List<String> {
        val clean = clean(entity) ?: return current
        return if (current.any { it.equals(clean, ignoreCase = true) }) {
            current.filterNot { it.equals(clean, ignoreCase = true) }
        } else if (current.size < MAX_ENTITIES) {
            current + clean
        } else {
            current
        }
    }

    /**
     * Normalizes an iterable of raw entity strings:
     * trims whitespace, strips leading '#', filters out blanks, deduplicates case-insensitively
     * keeping the first encountered casing and order, capped at [limit] (default [MAX_ENTITIES]).
     */
    fun normalizeEntities(rawEntities: Iterable<String>, limit: Int = MAX_ENTITIES): List<String> {
        val result = mutableListOf<String>()
        for (raw in rawEntities) {
            val clean = clean(raw) ?: continue
            if (result.size >= limit) break
            if (result.none { it.equals(clean, ignoreCase = true) }) {
                result.add(clean)
            }
        }
        return result
    }
}
