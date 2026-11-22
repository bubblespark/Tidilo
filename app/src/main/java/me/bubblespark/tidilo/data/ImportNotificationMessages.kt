package me.bubblespark.tidilo.data

/**
 * Message values persisted in `import_notifications.message`.
 *
 * Existing rows store these exact strings, so the values must never change. The Notification screen maps
 * them to localized text at display time.
 */
object ImportNotificationMessages {
    const val DUPLICATE_IN_LIBRARY = "Duplicate URL (Already in Library)"
    const val DUPLICATE_IN_QUEUE = "Duplicate URL (Already in Queue)"
    const val PROMOTED_TO_LIBRARY = "Promoted from RSS to Library"
}
