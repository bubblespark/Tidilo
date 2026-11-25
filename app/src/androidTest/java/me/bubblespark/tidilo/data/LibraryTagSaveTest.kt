package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies SQLite atomicity and single-UPDATE trigger behavior for bookmark tag commits. */
class LibraryTagSaveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun updateBookmarkTagsWritesBookmarksOnceAndUpdatesPrivateAndEntities(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val id = "tag-save-ok"
        val sql = db.openHelper.writableDatabase
        try {
            db.bookmarkDao().insertOrUpdate(
                BookmarkEntity(id, "Tag save", "https://$id.invalid", "", createdAt = 1, updatedAt = 1)
            )
            sql.execSQL("CREATE TABLE tag_save_count (n INTEGER)")
            sql.execSQL(
                "CREATE TRIGGER tag_save_count_t AFTER UPDATE ON bookmarks " +
                    "WHEN NEW.id = '$id' BEGIN INSERT INTO tag_save_count VALUES (1); END"
            )
            val repository = BookmarkRepository(db, autoInitialize = false)

            repository.updateBookmarkTags(id, Tag("Tool", "Tech", listOf("Kotlin")), isPrivate = true)

            val row = requireNotNull(db.bookmarkDao().getBookmarkById(id))
            assertTrue(row.isPrivate)
            assertEquals(listOf("Kotlin"), row.entities)
            sql.query("SELECT COUNT(*) FROM tag_save_count").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun failedUpdateTriggerLeavesTagsAndPrivateUnchanged(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val id = "tag-save-fail"
        val sql = db.openHelper.writableDatabase
        try {
            db.bookmarkDao().insertOrUpdate(
                BookmarkEntity(id, "Tag fail", "https://$id.invalid", "", createdAt = 1, updatedAt = 1)
            )
            sql.execSQL(
                "CREATE TRIGGER tag_fail_t BEFORE UPDATE ON bookmarks " +
                    "WHEN NEW.id = '$id' BEGIN SELECT RAISE(ABORT, 'injected'); END"
            )
            val repository = BookmarkRepository(db, autoInitialize = false)

            val failure = runCatching {
                repository.updateBookmarkTags(id, Tag("Tool", "Tech", listOf("Kotlin")), isPrivate = true)
            }.exceptionOrNull()

            assertTrue(failure != null)
            val row = requireNotNull(db.bookmarkDao().getBookmarkById(id))
            assertFalse(row.isPrivate)
            assertTrue(row.entities.isEmpty())
        } finally {
            db.close()
        }
    }
}
