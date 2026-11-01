package me.bubblespark.tidilo.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("""SELECT c.*, COUNT(b.id) AS count FROM collections c
        LEFT JOIN bookmark_collection_cross_ref r ON r.collectionId = c.id
        LEFT JOIN bookmarks b ON b.id = r.bookmarkId AND (:showNsfw OR NOT b.isNsfw)
        GROUP BY c.id ORDER BY c.createdAt DESC, c.id DESC""")
    fun observeCollections(showNsfw: Boolean): Flow<List<CollectionWithCount>>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observeCollection(id: String): Flow<CollectionEntity?>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getById(id: String): CollectionEntity?

    @Insert suspend fun insert(collection: CollectionEntity)
    @Update suspend fun update(collection: CollectionEntity): Int
    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("""SELECT b.* FROM bookmarks b JOIN bookmark_collection_cross_ref r ON b.id = r.bookmarkId
        WHERE r.collectionId = :collectionId AND (:showNsfw OR NOT b.isNsfw)
        ORDER BY b.createdAt DESC, b.id DESC""")
    fun bookmarks(collectionId: String, showNsfw: Boolean): PagingSource<Int, BookmarkEntity>

    @Query("""SELECT COUNT(*) FROM bookmarks b JOIN bookmark_collection_cross_ref r ON b.id = r.bookmarkId
        WHERE r.collectionId = :collectionId AND (:showNsfw OR NOT b.isNsfw)""")
    fun observeCount(collectionId: String, showNsfw: Boolean): Flow<Int>

    @Query("SELECT collectionId FROM bookmark_collection_cross_ref WHERE bookmarkId = :bookmarkId ORDER BY collectionId")
    fun observeMembershipIds(bookmarkId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembership(row: BookmarkCollectionCrossRef)

    @Query("SELECT collectionId FROM bookmark_collection_cross_ref WHERE bookmarkId = :bookmarkId")
    suspend fun membershipIds(bookmarkId: String): List<String>

    @Query("DELETE FROM bookmark_collection_cross_ref WHERE bookmarkId = :bookmarkId")
    suspend fun clearMemberships(bookmarkId: String)

    @Query("DELETE FROM bookmark_collection_cross_ref WHERE bookmarkId = :bookmarkId AND collectionId = :collectionId")
    suspend fun removeMembership(bookmarkId: String, collectionId: String)
}
