package me.bubblespark.tidilo.data

/**
 * Shared non-UI policy for normalizing, deduplicating, and bounding entity tags.
 *
 * Entity tags adhere to the following rules:
 * - Leading and trailing whitespace is trimmed.
 * - A single leading '#' prefix is removed, followed by trimming again.
 * - Entries with empty skeleton keys are ignored.
 * - Deduplication uses skeleton identity, preserving insertion order and the original spelling
 *   of the first encountered occurrence.
 * - The list size is capped at [MAX_ENTITIES].
 */
object EntityTagPolicy {
    const val MAX_ENTITIES = 10

    /**
     * Cleans an entity tag by trimming whitespace and removing one leading '#' prefix.
     * Returns null if the skeleton key is empty or if the input is null.
     */
    fun clean(entity: String?): String? {
        if (entity == null) return null
        val normalized = entity.trim().removePrefix("#").trim()
        return normalized.takeIf { entitySkeletonKey(it).isNotEmpty() }
    }

    /**
     * Normalizes a raw entity tag, or returns an empty string if blank.
     */
    fun normalize(entity: String): String =
        clean(entity).orEmpty()

    /**
     * Adds an entity tag to the current list if non-blank, not already present by skeleton identity,
     * and the list size is strictly below [MAX_ENTITIES].
     * Preserves insertion order and the original casing of the first added occurrence.
     */
    fun addEntity(current: List<String>, newEntity: String): List<String> {
        val clean = clean(newEntity) ?: return current
        if (current.size >= MAX_ENTITIES) return current
        val key = entitySkeletonKey(clean)
        if (current.any { entitySkeletonKey(it) == key }) return current
        return current + clean
    }

    /**
     * Toggles an entity tag in the current list: removes if present by skeleton identity,
     * or adds if absent and list size is below [MAX_ENTITIES].
     */
    fun toggleEntity(current: List<String>, entity: String): List<String> {
        val clean = clean(entity) ?: return current
        val key = entitySkeletonKey(clean)
        return if (current.any { entitySkeletonKey(it) == key }) {
            current.filterNot { entitySkeletonKey(it) == key }
        } else if (current.size < MAX_ENTITIES) {
            current + clean
        } else {
            current
        }
    }

    /**
     * Normalizes an iterable of raw entity strings:
     * trims whitespace, strips leading '#', filters out blanks, deduplicates by skeleton identity
     * keeping the first encountered casing and order, capped at [limit] (default [MAX_ENTITIES]).
     */
    fun normalizeEntities(rawEntities: Iterable<String>, limit: Int = MAX_ENTITIES): List<String> {
        val result = mutableListOf<String>()
        val keys = mutableSetOf<String>()
        for (raw in rawEntities) {
            val clean = clean(raw) ?: continue
            if (result.size >= limit) break
            if (keys.add(entitySkeletonKey(clean))) {
                result.add(clean)
            }
        }
        return result
    }
}
