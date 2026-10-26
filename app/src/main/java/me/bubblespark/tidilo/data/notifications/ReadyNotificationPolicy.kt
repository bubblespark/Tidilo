package me.bubblespark.tidilo.data.notifications

enum class ReadyNotificationAction { POST, UPDATE, CANCEL, NONE }

/** A dismissed notification stays dismissed until the queue falls below the threshold. */
class ReadyNotificationPolicy(var aboveThreshold: Boolean = false) {
    private var lastCount: Int? = null

    fun update(count: Int, notificationVisible: Boolean): ReadyNotificationAction {
        val previous = aboveThreshold
        val unchanged = lastCount == count
        lastCount = count
        aboveThreshold = count > 10
        return when {
            !aboveThreshold -> ReadyNotificationAction.CANCEL
            !previous -> ReadyNotificationAction.POST
            notificationVisible && !unchanged -> ReadyNotificationAction.UPDATE
            else -> ReadyNotificationAction.NONE
        }
    }
}

fun notificationErrorDetail(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() }?.take(500) ?: "Import failed"
