package me.bubblespark.tidilo.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
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

    private fun entityFor(id: String, draft: CollectionDraft, now: Long): CollectionEntity {
        require(UUID.fromString(id).toString() == id)
        val value = CollectionPolicy.normalize(draft)
        return CollectionEntity(id, value.title, value.description, value.presetIconId,
            value.customSymbol, value.colorIndex, now, now)
    }

    suspend fun hasBookmark(bookmarkId: String): Boolean = withContext(Dispatchers.IO) {
        database.bookmarkDao().getBookmarkById(bookmarkId) != null
    }

    suspend fun create(id: String, draft: CollectionDraft, now: Long): String = withContext(Dispatchers.IO) {
        dao.insert(entityFor(id, draft, now))
        id
    }

    /**
     * Atomically creates a new collection and assigns it to [bookmarkId] in one database transaction.
     * If [id] already exists from a prior committed creation whose membership was subsequently removed,
     * reconciles the collection row and restores the membership.
     * Returns false without creating an orphan collection when the target bookmark no longer exists.
     */
    suspend fun createAndAssign(id: String, draft: CollectionDraft, bookmarkId: String, now: Long): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            val entity = entityFor(id, draft, now)
            database.withTransaction {
                if (database.bookmarkDao().getBookmarkById(bookmarkId) == null) return@withTransaction false
                val existing = dao.getById(id)
                if (existing == null) {
                    dao.insert(entity)
                } else {
                    dao.update(entity.copy(createdAt = existing.createdAt))
                }
                dao.insertMembership(BookmarkCollectionCrossRef(bookmarkId, id))
                true
            }
        }

    /** Completes even if the editor closes mid-write, so an autosaved draft is never half-applied. */
    suspend fun update(id: String, draft: CollectionDraft, now: Long): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        val value = CollectionPolicy.normalize(draft)
        database.withTransaction {
            val old = dao.getById(id) ?: return@withTransaction false
            dao.update(old.copy(title = value.title, description = value.description,
                presetIconId = value.presetIconId, customSymbol = value.customSymbol,
                colorIndex = value.colorIndex, updatedAt = now)) > 0
        }
    }

    /**
     * Autosave write of exactly the fields the user edited. Every other column keeps the current row, so a
     * concurrent change to another field (committed before or while this write runs) is never overwritten.
     * Returns the stored values after the write, or null when the collection is gone.
     */
    suspend fun updateFields(id: String, draft: CollectionDraft, fields: Set<CollectionField>, now: Long): CollectionDraft? =
        withContext(NonCancellable + Dispatchers.IO) {
            val value = CollectionPolicy.normalize(draft)
            database.withTransaction {
                val old = dao.getById(id) ?: return@withTransaction null
                val current = CollectionDraft(old.title, old.description, old.presetIconId, old.customSymbol, old.colorIndex)
                val merged = current.withFields(value, fields)
                val updated = dao.update(old.copy(title = merged.title, description = merged.description,
                    presetIconId = merged.presetIconId, customSymbol = merged.customSymbol,
                    colorIndex = merged.colorIndex, updatedAt = now)) > 0
                if (updated) merged else null
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

    /** Each checkbox change is persisted on its own and survives the sheet closing mid-write. */
    private suspend fun changeMembership(bookmarkId: String, collectionId: String, add: Boolean?): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        database.withTransaction {
            if (database.bookmarkDao().getBookmarkById(bookmarkId) == null || dao.getById(collectionId) == null) return@withTransaction false
            val shouldAdd = add ?: (collectionId !in dao.membershipIds(bookmarkId))
            if (shouldAdd) dao.insertMembership(BookmarkCollectionCrossRef(bookmarkId, collectionId))
            else dao.removeMembership(bookmarkId, collectionId)
            true
        }
    }

    internal companion object {
        /**
         * Shared by every collection editor autosaver, so writes from a closing and a recreated editor keep
         * their order. Autosavers enter it on `Dispatchers.Main.immediate` when a write starts, before any
         * dispatch to IO.
         */
        val editorWriteLock = Mutex()
    }
}
