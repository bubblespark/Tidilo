package me.bubblespark.tidilo.ui.screens.library

/**
 * Tracks which fields the user has edited and still owes a write, independently of their values.
 *
 * Intent is never inferred from values: reverting a field to an earlier value is still an edit. Each edit
 * bumps the field's version; a successful write clears a field only if no newer edit happened since the
 * write's snapshot was taken.
 */
internal class EditedFields<F : Any>(restored: Collection<F> = emptyList()) {
    private var nextVersion = 0L
    private val versions = LinkedHashMap<F, Long>().apply { restored.forEach { put(it, ++nextVersion) } }

    val fields: Set<F> get() = versions.keys.toSet()
    val isEmpty: Boolean get() = versions.isEmpty()

    fun isEdited(field: F): Boolean = field in versions

    /** Marks [changed] as edited with a fresh version. */
    fun markEdited(changed: Collection<F>) {
        changed.forEach { versions[it] = ++nextVersion }
    }

    /** The edited fields with the versions a write is about to persist. */
    fun snapshot(): Map<F, Long> = versions.toMap()

    /** Clears every snapshot field that has not been edited again since [snapshot] was taken. */
    fun onWritten(snapshot: Map<F, Long>) {
        snapshot.forEach { (field, version) -> if (versions[field] == version) versions.remove(field) }
    }
}
