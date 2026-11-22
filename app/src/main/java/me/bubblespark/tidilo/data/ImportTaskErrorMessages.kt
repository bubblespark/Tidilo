package me.bubblespark.tidilo.data

/**
 * Fixed parts of diagnostics persisted in `import_tasks.errorMessage`.
 *
 * Existing rows store these values, and the Notification screen classifies failures by them, so the values
 * must never change.
 */
object ImportTaskErrorMessages {
    /** Prefix of a local bookmark save failure, followed by `: ` and the underlying exception message. */
    const val SAVE_FAILURE_PREFIX = "Bookmark persistence failed"
}
