package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationSummaryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun collectionCountsObserveBothTablesAndIncludePrivate() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.bookmarkDao()
            assertEquals(CollectionCounts(0, 0), dao.observeCollectionCounts().first())
            dao.insertAll(
                listOf(
                    BookmarkEntity("public", "Public", "https://summary.example/1", "", createdAt = 1, updatedAt = 1),
                    BookmarkEntity("adult", "Adult", "https://summary.example/2", "", createdAt = 2, updatedAt = 2, isPrivate = true)
                )
            )
            assertEquals(CollectionCounts(2, 0), dao.observeCollectionCounts().first())
            coroutineScope {
                val changed = async {
                    withTimeout(5000) { dao.observeCollectionCounts().first { it == CollectionCounts(1, 1) } }
                }
                BookmarkRepository(database, autoInitialize = false).moveToTrash("adult", 3, 100)
                assertEquals(CollectionCounts(1, 1), changed.await())
            }
            database.trashDao().deleteById("adult")
            assertEquals(CollectionCounts(1, 0), dao.observeCollectionCounts().first())
        } finally { database.close() }
    }
}
