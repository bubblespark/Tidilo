package me.bubblespark.tidilo.data.maintenance

internal const val RETENTION_DAY_MS = 86_400_000L

internal fun rssBookmarkExpired(createdAt: Long, lastInteractedAt: Long?, now: Long): Boolean {
    val start = lastInteractedAt ?: createdAt
    val days = if (lastInteractedAt == null) 10 else 30
    return start <= now && start <= now - days * RETENTION_DAY_MS
}

internal fun ogImageExpired(savedAt: Long, latestReferenceAt: Long?, now: Long): Boolean {
    val cutoff = now - 7 * RETENTION_DAY_MS
    return savedAt > 0 && savedAt <= cutoff && (latestReferenceAt == null || latestReferenceAt <= cutoff)
}
