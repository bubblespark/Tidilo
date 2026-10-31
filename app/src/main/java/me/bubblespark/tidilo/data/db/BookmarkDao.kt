package me.bubblespark.tidilo.data.db

import androidx.paging.PagingSource
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.bubblespark.tidilo.data.EntityTagPolicy
import me.bubblespark.tidilo.data.entitySkeletonKey

/**
 * Canonicalizing persistence boundary for active bookmarks.
 * Entity lists contain display values cleaned once at raw input, never raw prefixed strings.
 */
@Dao
abstract class BookmarkDao {
    @Query("UPDATE bookmarks SET isLiked = NOT isLiked, lastInteractedAt = CASE WHEN isLiked = 0 THEN :now ELSE lastInteractedAt END WHERE id = :id")
    abstract suspend fun toggleLike(id: String, now: Long)

    @Query("UPDATE bookmarks SET browserOpenCount = browserOpenCount + 1, lastInteractedAt = :now WHERE id = :id")
    abstract suspend fun recordBrowserOpen(id: String, now: Long)

    @Query("UPDATE bookmarks SET detailViewCount = detailViewCount + 1, lastInteractedAt = :now WHERE id = :id")
    abstract suspend fun recordDetailView(id: String, now: Long)

    @Query("UPDATE bookmarks SET summaryPeekCount = summaryPeekCount + 1, lastInteractedAt = :now WHERE id = :id")
    abstract suspend fun recordSummaryPeek(id: String, now: Long)

    @Query("SELECT (SELECT COUNT(*) FROM bookmarks) AS active, (SELECT COUNT(*) FROM trashed_bookmarks) AS trash")
    abstract fun observeCollectionCounts(): Flow<me.bubblespark.tidilo.data.CollectionCounts>

    @RawQuery(observedEntities = [BookmarkEntity::class, BookmarkEntityOccurrence::class])
    abstract fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    abstract suspend fun getBookmarkById(id: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    abstract suspend fun getBookmarkByUrl(url: String): BookmarkEntity?

    @Query("UPDATE bookmarks SET rssFeedId = NULL WHERE id = :id")
    abstract suspend fun clearRssFeedId(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRow(bookmark: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRows(bookmarks: List<BookmarkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertOccurrences(rows: List<BookmarkEntityOccurrence>)

    @Query("DELETE FROM bookmark_entities WHERE bookmarkId = :id")
    protected abstract suspend fun deleteOccurrences(id: String)

    @Query("DELETE FROM bookmark_entities WHERE bookmarkId IN (:bookmarkIds)")
    protected abstract suspend fun deleteOccurrencesForBookmarks(bookmarkIds: List<String>)

    @Query("SELECT value FROM bookmark_entities WHERE skeletonKey = :skeletonKey ORDER BY bookmarkId, position LIMIT 1")
    abstract suspend fun canonicalEntityValue(skeletonKey: String): String?

    /** Resolves already-cleaned display values; never strip a canonical leading hash again. */
    @Transaction
    open suspend fun canonicalizeEntities(entities: List<String>): List<String> =
        resolveEntities(entities, mutableMapOf())

    private suspend fun resolveEntities(entities: List<String>, canonical: MutableMap<String, String>): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (value in entities) {
            val key = entitySkeletonKey(value)
            if (key.isEmpty() || !seen.add(key)) continue
            if (result.size == EntityTagPolicy.MAX_ENTITIES) break
            val spelling = canonical[key] ?: (canonicalEntityValue(key) ?: value).also { canonical[key] = it }
            result += spelling
        }
        return result
    }

    @Transaction
    open suspend fun insertOrUpdate(bookmark: BookmarkEntity) {
        val normalized = bookmark.copy(entities = canonicalizeEntities(bookmark.entities)).withSearchFields()
        insertRow(normalized)
        deleteOccurrences(bookmark.id)
        insertOccurrences(occurrences(normalized))
    }

    @Transaction
    open suspend fun insertAll(bookmarks: List<BookmarkEntity>) {
        val canonical = mutableMapOf<String, String>()
        bookmarks.chunked(500).forEach { chunk ->
            val normalized = chunk.associateBy { it.id }.values.map {
                it.copy(entities = resolveEntities(it.entities, canonical)).withSearchFields()
            }
            insertRows(normalized)
            deleteOccurrencesForBookmarks(normalized.map { it.id })
            val occurrences = normalized.flatMap { occurrences(it) }
            if (occurrences.isNotEmpty()) insertOccurrences(occurrences)
        }
    }

    private fun occurrences(bookmark: BookmarkEntity): List<BookmarkEntityOccurrence> =
        bookmark.entities.mapIndexed { index, value ->
            BookmarkEntityOccurrence(bookmark.id, index, value, bookmarkSearchKey(value))
        }

    /** Resolves while the previous URL owner still contributes its canonical spelling. */
    @Transaction
    open suspend fun insertReplacingUrl(bookmark: BookmarkEntity) {
        val canonical = bookmark.copy(entities = canonicalizeEntities(bookmark.entities))
        deleteByIdOrUrl(bookmark.id, bookmark.url)
        insertOrUpdate(canonical)
    }

    @Query("SELECT id, entities FROM bookmarks WHERE id IN (SELECT bookmarkId FROM bookmark_entities WHERE skeletonKey = :skeletonKey) AND (:afterId IS NULL OR id > :afterId) ORDER BY id LIMIT 500")
    protected abstract suspend fun entityRowsForReplacement(skeletonKey: String, afterId: String?): List<BookmarkEntityTags>

    @Update(entity = BookmarkEntity::class)
    protected abstract suspend fun updateEntityRows(rows: List<BookmarkEntityTagUpdate>)

    /** Replacements contain cleaned display spellings, including any meaningful leading hash. */
    @Transaction
    open suspend fun replaceEntitySpellings(replacements: Map<String, String>, updatedAt: Long) {
        for ((key, value) in replacements) {
            require(value == value.trim() && key.isNotEmpty() && entitySkeletonKey(value) == key) {
                "Replacement spelling must match its entity key"
            }
        }
        for ((key, value) in replacements) {
            var afterId: String? = null
            while (true) {
                val rows = entityRowsForReplacement(key, afterId)
                if (rows.isEmpty()) break
                val occurrences = mutableListOf<BookmarkEntityOccurrence>()
                val updates = mutableListOf<BookmarkEntityTagUpdate>()
                for (row in rows) {
                    val entities = row.entities.map { if (entitySkeletonKey(it) == key) value else it }
                        .distinctBy(::entitySkeletonKey)
                    if (entities == row.entities) continue
                    updates += BookmarkEntityTagUpdate(row.id, entities, updatedAt)
                    entities.forEachIndexed { position, entity ->
                        occurrences += BookmarkEntityOccurrence(row.id, position, entity, bookmarkSearchKey(entity))
                    }
                }
                if (updates.isNotEmpty()) {
                    updateEntityRows(updates)
                    deleteOccurrencesForBookmarks(updates.map { it.id })
                    insertOccurrences(occurrences)
                }
                afterId = rows.last().id
            }
        }
    }

    @Transaction
    open suspend fun update(bookmark: BookmarkEntity) {
        if (getBookmarkById(bookmark.id) != null) insertOrUpdate(bookmark)
    }

    @Query("UPDATE bookmarks SET note = :note, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateNote(id: String, note: String, updatedAt: Long)

    @Transaction
    open suspend fun updateFields(
        id: String, description: String, category: String?, subcategory: String?,
        entities: List<String>, updatedAt: Long
    ) {
        val current = getBookmarkById(id) ?: return
        insertOrUpdate(current.copy(description = description, category = category,
            subcategory = subcategory, entities = entities, updatedAt = updatedAt))
    }

    @Transaction
    open suspend fun updateNoteAndTag(
        id: String, note: String, category: String?, subcategory: String?,
        entities: List<String>, isNsfw: Boolean, updatedAt: Long
    ) {
        val current = getBookmarkById(id) ?: return
        insertOrUpdate(current.copy(note = note, category = category, subcategory = subcategory,
            entities = entities, isNsfw = isNsfw, updatedAt = updatedAt))
    }

    @Query("DELETE FROM bookmarks WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM bookmarks WHERE id = :id OR url = :url")
    abstract suspend fun deleteByIdOrUrl(id: String, url: String)

    @Query("SELECT COUNT(*) FROM bookmarks")
    abstract suspend fun getCount(): Int

    @Query("DELETE FROM bookmarks")
    abstract suspend fun deleteAll()
    @Query("SELECT * FROM bookmarks WHERE (:showNsfw OR NOT isNsfw) ORDER BY createdAt DESC, id DESC")
    abstract fun timeline(showNsfw: Boolean): PagingSource<Int, BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE domain = :domain AND (:showNsfw OR NOT isNsfw) ORDER BY createdAt DESC, id DESC")
    abstract fun byDomain(domain: String, showNsfw: Boolean): PagingSource<Int, BookmarkEntity>

    @RawQuery(observedEntities = [BookmarkEntity::class, BookmarkEntityOccurrence::class])
    abstract fun search(query: SupportSQLiteQuery): PagingSource<Int, BookmarkEntity>

    @RawQuery(observedEntities = [BookmarkEntity::class, BookmarkEntityOccurrence::class])
    abstract fun tagCounts(query: SupportSQLiteQuery): Flow<List<TagCount>>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    abstract fun observeById(id: String): Flow<BookmarkEntity?>

    @Query("SELECT domain, COUNT(*) AS count FROM bookmarks WHERE domain IS NOT NULL AND (:showNsfw OR NOT isNsfw) AND (:source=0 OR (:source=1 AND rssFeedId IS NULL) OR (:source=2 AND rssFeedId IS NOT NULL)) GROUP BY domain")
    abstract fun domainCounts(showNsfw: Boolean, source: Int = 0): Flow<List<DomainCount>>

    @Query("SELECT DISTINCT domain FROM bookmarks WHERE domain IS NOT NULL ORDER BY domain")
    abstract suspend fun distinctDomains(): List<String>

    @Query("SELECT DISTINCT domain FROM bookmarks WHERE domain IS NOT NULL ORDER BY domain")
    abstract fun observeDistinctDomains(): Flow<List<String>>

    @Query("UPDATE bookmarks SET isNsfw = :isNsfw WHERE domain = :domain")
    abstract suspend fun updateDomainNsfw(domain: String, isNsfw: Boolean)

}

/** Narrow projection for targeted entity rewrites; no bookmark content is loaded. */
data class BookmarkEntityTags(val id: String, val entities: List<String>)

/** Partial Room update preserves every column unrelated to entity spelling. */
data class BookmarkEntityTagUpdate(val id: String, val entities: List<String>, val updatedAt: Long)
