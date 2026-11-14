package me.bubblespark.tidilo.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkCollectionCrossRef
import me.bubblespark.tidilo.data.db.CollectionEntity
import java.util.UUID

class CollectionRepository(private val database: AppDatabase) {
    private val dao = database.collectionDao()
    fun observeCollections(showPrivate: Boolean) = dao.observeCollections(showPrivate)
    fun observeCollection(id: String) = dao.observeCollection(id)
    fun observeMembershipIds(bookmarkId: String) = dao.observeMembershipIds(bookmarkId)
    fun observeCount(collectionId: String, showPrivate: Boolean) = dao.observeCount(collectionId, showPrivate)
    fun pagedBookmarks(collectionId: String, showPrivate: Boolean) =
        Pager(PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 60,
            maxSize = 150, enablePlaceholders = false)) { dao.bookmarks(collectionId, showPrivate) }
            .flow.map { page -> page.map { it.toBookmarkItem() } }

    suspend fun create(id: String, draft: CollectionDraft, now: Long): String = withContext(Dispatchers.IO) {
        require(UUID.fromString(id).toString() == id)
        val value = CollectionPolicy.normalize(draft)
        dao.insert(CollectionEntity(id, value.title, value.description, value.presetIconId,
            value.customSymbol, value.colorIndex, now, now))
        id
    }

    suspend fun update(id: String, draft: CollectionDraft, now: Long): Boolean = withContext(Dispatchers.IO) {
        val value = CollectionPolicy.normalize(draft)
        database.withTransaction {
            val old = dao.getById(id) ?: return@withTransaction false
            dao.update(old.copy(title = value.title, description = value.description,
                presetIconId = value.presetIconId, customSymbol = value.customSymbol,
                colorIndex = value.colorIndex, updatedAt = now)) > 0
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) { dao.delete(id) > 0 }

    /** Validates every target before replacing anything; a failure rolls back the old membership. */
    suspend fun setMemberships(bookmarkId: String, collectionIds: Set<String>): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (database.bookmarkDao().getBookmarkById(bookmarkId) == null ||
                collectionIds.any { dao.getById(it) == null }) return@withTransaction false
            dao.clearMemberships(bookmarkId)
            collectionIds.forEach { dao.insertMembership(BookmarkCollectionCrossRef(bookmarkId, it)) }
            true
        }
    }

    suspend fun addMembership(bookmarkId: String, collectionId: String) = changeMembership(bookmarkId, collectionId, true)
    suspend fun removeMembership(bookmarkId: String, collectionId: String) = changeMembership(bookmarkId, collectionId, false)
    suspend fun toggleMembership(bookmarkId: String, collectionId: String) = changeMembership(bookmarkId, collectionId, null)

    private suspend fun changeMembership(bookmarkId: String, collectionId: String, add: Boolean?): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (database.bookmarkDao().getBookmarkById(bookmarkId) == null || dao.getById(collectionId) == null) return@withTransaction false
            val shouldAdd = add ?: (collectionId !in dao.membershipIds(bookmarkId))
            if (shouldAdd) dao.insertMembership(BookmarkCollectionCrossRef(bookmarkId, collectionId))
            else dao.removeMembership(bookmarkId, collectionId)
            true
        }
    }
}
