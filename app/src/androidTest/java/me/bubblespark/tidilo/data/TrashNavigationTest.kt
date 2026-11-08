package me.bubblespark.tidilo.data

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TrashNavigationTest {
    @Test fun railTrashDetailBackPreservesLibrary(): Unit = runBlocking {
        val host = HeaderTestHost()
        val db = AppDatabase.getInstance(host.context)
        val id = "trash-navigation-${UUID.randomUUID()}"
        val title = "Trash navigation fixture $id"
        db.trashDao().insert(TrashedBookmarkEntity(id, title, "https://example.com/$id", "Summary",
            createdAt = 1, updatedAt = 1, note = "memo", isNsfw = false, trashedAt = System.currentTimeMillis()))
        val screen = host.launch()
        var stage = "open rail"
        suspend fun awaitAbsent(predicate: (androidx.compose.ui.semantics.SemanticsNode) -> Boolean) {
            withTimeout(5000) {
                var present = true
                while (present) {
                    host.main { present = host.nodes(screen).any(predicate) }
                    if (present) delay(20)
                }
            }
        }
        try {
            clickTrashNode(host, host.await(screen) { host.description(it, host.context.getString(R.string.navigation_open)) })
            val search = host.await(screen) { host.text(it, host.context.getString(R.string.nav_search)) }
            val trash = host.await(screen) { host.text(it, host.context.getString(R.string.trash_title)) }
            val filters = host.await(screen) { host.text(it, host.context.getString(R.string.navigation_filters)) }
            assertTrue(trash.boundsInRoot.top > search.boundsInRoot.bottom)
            assertTrue(trash.boundsInRoot.bottom < filters.boundsInRoot.top)
            clickTrashNode(host, trash)
            stage = "Trash list"
            host.await(screen) { host.text(it, title) }
            withTimeout(5000) {
                var outgoingLibrary = true
                while (outgoingLibrary) {
                    host.main { outgoingLibrary = host.nodes(screen).any { host.description(it, host.context.getString(R.string.navigation_open)) } }
                    if (outgoingLibrary) delay(20)
                }
            }
            clickTrashNode(host, host.await(screen) { host.text(it, title) })
            stage = "Trash Detail"
            host.await(screen) { host.description(it, host.context.getString(R.string.bookmark_open_in_browser)) }
            awaitAbsent { it.config.getOrNull(SemanticsProperties.TestTag) == "trash_actions:$id" }
            host.main { assertFalse(host.nodes(screen).any { host.description(it, host.context.getString(R.string.detail_edit_tags)) }) }
            clickTrashNode(host, host.await(screen) { host.description(it, host.context.getString(R.string.common_back)) })
            stage = "Detail back to Trash"
            host.await(screen) { it.config.getOrNull(SemanticsProperties.TestTag) == "trash_actions:$id" }
            awaitAbsent { host.description(it, host.context.getString(R.string.bookmark_open_in_browser)) }
            clickTrashNode(host, host.await(screen) { host.description(it, host.context.getString(R.string.common_back)) })
            stage = "Trash back to Library"
            host.await(screen) { host.description(it, host.context.getString(R.string.navigation_open)) }
        } catch (error: Throwable) {
            throw AssertionError("Trash navigation failed during $stage", error)
        } finally {
            host.main { screen.finish() }
            db.trashDao().deleteById(id)
        }
    }
}
