package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.data.EntityTagPolicy
import me.bubblespark.tidilo.data.entitySkeletonKey

/** Entities and replacements contain display spelling, never raw input. */
data class EntityTagDraft(val entities: List<String>, val replacements: Map<String, String> = emptyMap())
data class EntityTagConflict(val entered: String, val existing: String, val skeletonKey: String)

fun entityTagConflict(input: String, draft: EntityTagDraft, library: Map<String, String>): EntityTagConflict? {
    val entered = EntityTagPolicy.clean(input) ?: return null
    val key = entitySkeletonKey(entered)
    val existing = draft.replacements[key] ?: library[key]
        ?: draft.entities.firstOrNull { entitySkeletonKey(it) == key } ?: return null
    return if (entered == existing) null else EntityTagConflict(entered, existing, key)
}

fun applyEntityTagChoice(draft: EntityTagDraft, input: String, existing: String?, replaceAcrossLibrary: Boolean): EntityTagDraft {
    val entered = EntityTagPolicy.clean(input) ?: return draft
    val key = entitySkeletonKey(entered)
    val containsKey = draft.entities.any { entitySkeletonKey(it) == key }
    if (!containsKey && draft.entities.size >= EntityTagPolicy.MAX_ENTITIES) return draft
    val value = if (replaceAcrossLibrary) entered else existing ?: entered
    val entities = if (containsKey) draft.entities.map { if (entitySkeletonKey(it) == key) value else it }
        else draft.entities + value
    return EntityTagDraft(entities, if (replaceAcrossLibrary) draft.replacements + (key to value) else draft.replacements)
}
