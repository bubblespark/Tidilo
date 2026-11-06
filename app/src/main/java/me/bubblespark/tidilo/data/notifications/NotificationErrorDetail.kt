package me.bubblespark.tidilo.data.notifications

fun notificationErrorDetail(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() }?.take(500) ?: "Import failed"
