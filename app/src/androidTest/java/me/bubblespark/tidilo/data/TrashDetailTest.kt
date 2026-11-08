package me.bubblespark.tidilo.data

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.ui.screens.library.*
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class TrashDetailTest {
    private suspend fun fixture(block: suspend (HeaderTestHost, ComponentActivity, TrashedBookmarkEntity) -> Unit) {
        val host = HeaderTestHost()
        val db = AppDatabase.getInstance(host.context)
        val id = "trash-detail-${UUID.randomUUID()}"
        val row = BookmarkEntity(id, "Trash detail fixture", "https://$id.invalid/page", "Saved summary",
            category = "Article", subcategory = "Tech", entities = listOf("Kotlin"),
            createdAt = 1, updatedAt = 2, note = "Saved memo", browserOpenCount = 3, detailViewCount = 4,
            lastInteractedAt = 9, ogImageUrl = "https://$id.invalid/image.png")
        var activity: ComponentActivity? = null
        try {
            db.bookmarkDao().insertOrUpdate(row)
            db.moveBookmarkToTrash(id, System.currentTimeMillis(), 100)
            val snapshot = requireNotNull(db.trashDao().getById(id))
            activity = host.launch()
            val screen = activity
            host.main {
                val model = ViewModelProvider(screen)[LibraryViewModel::class.java]
                screen.setContent { TidiloTheme { BookmarkDetailScreen(id, model, {}, mode = BookmarkDetailMode.Trash) } }
            }
            block(host, screen, snapshot)
        } finally {
            activity?.let { host.main { it.finish() } }
            db.trashDao().deleteById(id)
            db.bookmarkDao().deleteById(id)
        }
    }

    @Test fun trashDetailShowsSnapshotWithoutEditors() = runBlocking { fixture { host, screen, row ->
        host.await(screen) { host.text(it, row.title) }
        host.await(screen) { host.text(it, "Saved summary") }
        host.main {
            for (resource in listOf(R.string.detail_edit_tags, R.string.detail_action_ai_summary,
                R.string.bookmark_collection_action, R.string.detail_action_delete)) {
                assertFalse(host.nodes(screen).any { host.description(it, host.context.getString(resource)) })
            }
            assertFalse(host.nodes(screen).any { host.text(it, host.context.getString(R.string.detail_save)) })
            assertTrue(host.nodes(screen).any { host.text(it, "Kotlin") })
        }
        host.scroll(screen, 1000f)
        val note = host.await(screen) { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Saved memo" }
        host.main {
            assertNull(note.config.getOrNull(SemanticsProperties.Disabled))
            assertNull(note.config.getOrNull(SemanticsActions.SetText))
            assertNotNull(note.config.getOrNull(SemanticsActions.SetSelection))
            assertTrue(note.config[SemanticsActions.SetSelection].action?.invoke(0, 5, false) == true)
        }
        assertEquals(row, AppDatabase.getInstance(host.context).trashDao().getById(row.id))
    } }

    @Test fun browserButtonOpensSavedUrlWithoutChangingEngagement() = runBlocking { fixture { host, screen, row ->
        host.await(screen) { host.text(it, row.title) }
        val monitor = host.instrumentation.addMonitor(IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("https")
            addDataAuthority(android.net.Uri.parse(row.url).host, null)
            addDataPath("/page", android.os.PatternMatcher.PATTERN_LITERAL)
        }, ActivityResult(Activity.RESULT_OK, null), true)
        try {
            val browser = host.await(screen) { host.description(it, host.context.getString(R.string.bookmark_open_in_browser)) }
            host.main {
                var target = browser
                while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
                assertTrue(target.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            assertTrue(host.instrumentation.checkMonitorHit(monitor, 1))
            assertEquals(row, AppDatabase.getInstance(host.context).trashDao().getById(row.id))
            assertNull(AppDatabase.getInstance(host.context).bookmarkDao().getBookmarkById(row.id))
        } finally { host.instrumentation.removeMonitor(monitor) }
    } }

    @Test fun trashDetailNeverRequestsOgImage() = runBlocking {
        val previous = OgImageManager.imageDownloader
        val requests = AtomicInteger()
        OgImageManager.imageDownloader = { url -> if (url.contains("trash-detail-") && url.endsWith("image.png")) { requests.incrementAndGet(); null } else previous?.invoke(url) }
        try { fixture { host, screen, row ->
            host.await(screen) { host.text(it, row.title) }
            delay(300)
            assertEquals(0, requests.get())
            assertEquals(row, AppDatabase.getInstance(host.context).trashDao().getById(row.id))
        } } finally { OgImageManager.imageDownloader = previous }
    }

    @Test fun missingTrashItemShowsUnavailableState(): Unit = runBlocking {
        val host = HeaderTestHost()
        val screen = host.launch()
        try {
            host.main {
                val model = ViewModelProvider(screen)[LibraryViewModel::class.java]
                screen.setContent { TidiloTheme { BookmarkDetailScreen("absent-${UUID.randomUUID()}", model, {}, mode = BookmarkDetailMode.Trash) } }
            }
            host.await(screen) { host.text(it, host.context.getString(R.string.detail_item_not_found)) }
        } finally { host.main { screen.finish() } }
    }
}
