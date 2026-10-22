package me.bubblespark.tidilo.data.db

import androidx.paging.PagingSource
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for bookmarks table.
 */
@Dao
interface BookmarkDao {

    @RawQuery(observedEntities = [BookmarkEntity::class, BookmarkEntityOccurrence::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getBookmarkById(id: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun getBookmarkByUrl(url: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRow(bookmark: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOccurrences(rows: List<BookmarkEntityOccurrence>)

    @Query("DELETE FROM bookmark_entities WHERE bookmarkId = :id")
    suspend fun deleteOccurrences(id: String)

    @Transaction
    suspend fun insertOrUpdate(bookmark: BookmarkEntity) {
        val normalized = bookmark.withSearchFields()
        insertRow(normalized)
        deleteOccurrences(bookmark.id)
        insertOccurrences(bookmark.logicalTag().entities.mapIndexed { index, value ->
            BookmarkEntityOccurrence(bookmark.id, index, value, bookmarkSearchKey(value))
        })
    }

    @Transaction
    suspend fun insertAll(bookmarks: List<BookmarkEntity>) {
        bookmarks.forEach { insertOrUpdate(it) }
    }

    @Transaction
    suspend fun update(bookmark: BookmarkEntity) {
        if (getBookmarkById(bookmark.id) != null) insertOrUpdate(bookmark)
    }

    @Query("UPDATE bookmarks SET note = :note, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNote(id: String, note: String, updatedAt: Long)

    @Transaction
    suspend fun updateFields(
        id: String, description: String, category: String?, subcategory: String?,
        entities: List<String>, updatedAt: Long
    ) {
        val current = getBookmarkById(id) ?: return
        insertOrUpdate(current.copy(description = description, category = category,
            subcategory = subcategory, entities = entities, updatedAt = updatedAt))
    }

    @Transaction
    suspend fun updateNoteAndTag(
        id: String, note: String, category: String?, subcategory: String?,
        entities: List<String>, isNsfw: Boolean, updatedAt: Long
    ) {
        val current = getBookmarkById(id) ?: return
        insertOrUpdate(current.copy(note = note, category = category, subcategory = subcategory,
            entities = entities, isNsfw = isNsfw, updatedAt = updatedAt))
    }

    @Query("UPDATE bookmarks SET isNsfw = :isNsfw WHERE id IN (:ids)")
    suspend fun updateNsfwByIds(ids: List<String>, isNsfw: Boolean)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM bookmarks WHERE id = :id OR url = :url")
    suspend fun deleteByIdOrUrl(id: String, url: String)

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun getCount(): Int

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAll()
    @Query("SELECT * FROM bookmarks WHERE (:showNsfw OR NOT isNsfw) ORDER BY createdAt DESC, id DESC")
    fun timeline(showNsfw: Boolean): PagingSource<Int, BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE domain = :domain AND (:showNsfw OR NOT isNsfw) ORDER BY createdAt DESC, id DESC")
    fun byDomain(domain: String, showNsfw: Boolean): PagingSource<Int, BookmarkEntity>

    @RawQuery(observedEntities = [BookmarkEntity::class, BookmarkEntityOccurrence::class])
    fun search(query: SupportSQLiteQuery): PagingSource<Int, BookmarkEntity>

    @RawQuery(observedEntities = [BookmarkEntity::class, BookmarkEntityOccurrence::class])
    fun tagCounts(query: SupportSQLiteQuery): Flow<List<TagCount>>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    fun observeById(id: String): Flow<BookmarkEntity?>

    @Query("SELECT domain, COUNT(*) AS count FROM bookmarks WHERE domain IS NOT NULL AND (:showNsfw OR NOT isNsfw) GROUP BY domain")
    fun domainCounts(showNsfw: Boolean): Flow<List<DomainCount>>

    @Query("SELECT DISTINCT domain FROM bookmarks WHERE domain IS NOT NULL ORDER BY domain")
    suspend fun distinctDomains(): List<String>

    @Query("SELECT DISTINCT domain FROM bookmarks WHERE domain IS NOT NULL ORDER BY domain")
    fun observeDistinctDomains(): Flow<List<String>>

    @Query("SELECT url FROM bookmarks WHERE domain = :domain AND (url > :afterUrl OR :afterUrl IS NULL) GROUP BY url ORDER BY url LIMIT :limit")
    suspend fun domainUrls(domain: String, afterUrl: String?, limit: Int): List<String>

    @Query("UPDATE bookmarks SET isNsfw = :isNsfw WHERE domain = :domain")
    suspend fun updateDomainNsfw(domain: String, isNsfw: Boolean)

}
