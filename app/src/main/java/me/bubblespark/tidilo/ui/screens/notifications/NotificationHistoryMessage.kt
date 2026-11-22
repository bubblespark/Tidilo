package me.bubblespark.tidilo.ui.screens.notifications

import androidx.annotation.StringRes
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.ImportNotificationMessages

/** Known kinds of persisted notification history, resolved from the stored message value. */
enum class HistoryKind(@StringRes val messageRes: Int?, val opensSavedPage: Boolean) {
    DUPLICATE_IN_LIBRARY(R.string.notification_history_duplicate_library, true),
    PROMOTED_TO_LIBRARY(R.string.notification_history_promoted, true),
    DUPLICATE_IN_QUEUE(R.string.notification_history_duplicate_queue, false),
    /** Legacy or unrecognized history; its stored message is displayed as is. */
    UNKNOWN(null, false)
}

fun historyKind(message: String): HistoryKind = when (message) {
    ImportNotificationMessages.DUPLICATE_IN_LIBRARY -> HistoryKind.DUPLICATE_IN_LIBRARY
    ImportNotificationMessages.PROMOTED_TO_LIBRARY -> HistoryKind.PROMOTED_TO_LIBRARY
    ImportNotificationMessages.DUPLICATE_IN_QUEUE -> HistoryKind.DUPLICATE_IN_QUEUE
    else -> HistoryKind.UNKNOWN
}
