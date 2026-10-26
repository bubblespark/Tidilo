package me.bubblespark.tidilo.data.notifications

import me.bubblespark.tidilo.data.db.RssNotificationRunEntity

/** Only legacy periodic requests lack a durable scheduled boundary in their input. */
fun selectRssNotificationRun(
    unfinished: RssNotificationRunEntity?,
    matchingBoundary: RssNotificationRunEntity?,
    latest: RssNotificationRunEntity?,
    retryAttempt: Int,
    explicitBoundary: Boolean = false
): RssNotificationRunEntity? = unfinished ?: matchingBoundary ?: latest?.takeIf { retryAttempt > 0 && !explicitBoundary }
