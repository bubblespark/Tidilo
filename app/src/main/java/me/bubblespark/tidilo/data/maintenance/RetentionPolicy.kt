package me.bubblespark.tidilo.data.maintenance

internal const val RSS_UNREAD_DAYS = 10
internal const val RSS_READ_DAYS = 30
internal const val RETENTION_DAY_MS = 86_400_000L

internal fun rssBookmarkExpired(createdAt: Long, lastInteractedAt: Long?, now: Long): Boolean {
    val start = lastInteractedAt ?: createdAt
    val days = if (lastInteractedAt == null) RSS_UNREAD_DAYS else RSS_READ_DAYS
    return start <= now && start <= now - days * RETENTION_DAY_MS
}

internal fun ogImageExpired(savedAt: Long, latestReferenceAt: Long?, now: Long): Boolean {
    val cutoff = now - 7 * RETENTION_DAY_MS
    return savedAt > 0 && savedAt <= cutoff && (latestReferenceAt == null || latestReferenceAt <= cutoff)
}
