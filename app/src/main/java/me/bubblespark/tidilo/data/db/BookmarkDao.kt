package me.bubblespark.tidilo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for bookmarks table.
 */
@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarksFlow(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    suspend fun getAllBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getBookmarkById(id: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun getBookmarkByUrl(url: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(bookmark: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bookmarks: List<BookmarkEntity>)

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Query("UPDATE bookmarks SET note = :note, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNote(id: String, note: String, updatedAt: Long)

    @Query("UPDATE bookmarks SET description = :description, category = :category, subcategory = :subcategory, entities = :entities, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateFields(
        id: String,
        description: String,
        category: String?,
        subcategory: String?,
        entities: List<String>,
        updatedAt: Long
    )

    @Query("UPDATE bookmarks SET note = :note, category = :category, subcategory = :subcategory, entities = :entities, isNsfw = :isNsfw, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNoteAndTag(
        id: String,
        note: String,
        category: String?,
        subcategory: String?,
        entities: List<String>,
        isNsfw: Boolean,
        updatedAt: Long
    )

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
}
