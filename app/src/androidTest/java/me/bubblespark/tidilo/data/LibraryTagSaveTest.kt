package me.bubblespark.tidilo.data

import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.screens.library.TagSaveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Tag-dialog commits are owned by LibraryViewModel so their state survives Detail recreation. */
class LibraryTagSaveTest {
    private val host = HeaderTestHost()

    @Test fun commitReportsSavingThenSavedOnceAndIsConsumed(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val id = "tag-save-${UUID.randomUUID()}"
        val counter = "tag_save_count_${id.takeLast(8).replace("-", "")}"
        val activity = host.launch()
        val sql = db.openHelper.writableDatabase
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Tag save", "https://$id.invalid", "", createdAt = 1, updatedAt = 1))
            sql.execSQL("CREATE TABLE IF NOT EXISTS $counter (n INTEGER)")
            sql.execSQL("CREATE TRIGGER ${counter}_t AFTER UPDATE ON bookmarks WHEN NEW.id = '$id' BEGIN INSERT INTO $counter VALUES (1); END")
            lateinit var model: LibraryViewModel
            host.main {
                model = ViewModelProvider(activity)[LibraryViewModel::class.java]
                model.commitBookmarkTags(id, Tag("Tool", "Tech", listOf("Kotlin")), true, emptyMap())
                assertEquals(TagSaveStatus.Saving, model.tagSaves.value[id])
                // A second OK while the first commit is running is ignored.
                model.commitBookmarkTags(id, Tag("Tool", "Tech", listOf("Kotlin")), true, emptyMap())
            }
            withTimeout(5000) { while (model.tagSaves.value[id] != TagSaveStatus.Saved) delay(20) }
            val row = requireNotNull(db.bookmarkDao().getBookmarkById(id))
            assertTrue(row.isPrivate)
            assertEquals(listOf("Kotlin"), row.entities)
            sql.query("SELECT COUNT(*) FROM $counter").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            host.main { model.consumeTagSave(id) }
            assertNull(model.tagSaves.value[id])
        } finally {
            sql.execSQL("DROP TRIGGER IF EXISTS ${counter}_t")
            sql.execSQL("DROP TABLE IF EXISTS $counter")
            host.main { activity.finish() }
            db.bookmarkDao().deleteById(id)
        }
    }

    @Test fun failedCommitReportsFailedAndLeavesTagsUnchanged(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val id = "tag-fail-${UUID.randomUUID()}"
        val trigger = "tag_fail_${id.takeLast(8).replace("-", "")}"
        val activity = host.launch()
        val sql = db.openHelper.writableDatabase
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Tag fail", "https://$id.invalid", "", createdAt = 1, updatedAt = 1))
            sql.execSQL("CREATE TRIGGER $trigger BEFORE UPDATE ON bookmarks WHEN NEW.id = '$id' BEGIN SELECT RAISE(ABORT, 'injected'); END")
            lateinit var model: LibraryViewModel
            host.main {
                model = ViewModelProvider(activity)[LibraryViewModel::class.java]
                model.commitBookmarkTags(id, Tag("Tool", "Tech", listOf("Kotlin")), true, emptyMap())
            }
            withTimeout(5000) { while (model.tagSaves.value[id] != TagSaveStatus.Failed) delay(20) }
            val row = requireNotNull(db.bookmarkDao().getBookmarkById(id))
            assertEquals(false, row.isPrivate)
            assertTrue(row.entities.isEmpty())
            host.main { model.consumeTagSave(id) }
            assertNull(model.tagSaves.value[id])
        } finally {
            sql.execSQL("DROP TRIGGER IF EXISTS $trigger")
            host.main { activity.finish() }
            db.bookmarkDao().deleteById(id)
        }
    }
}
