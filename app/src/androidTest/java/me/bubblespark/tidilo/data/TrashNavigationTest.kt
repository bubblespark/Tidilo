package me.bubblespark.tidilo.data

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.TrashedBookmarkEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TrashNavigationTest {
    @Test fun railOpensNonInteractiveTrashListAndBackReturnsToLibrary(): Unit = runBlocking {
        val host = HeaderTestHost()
        val db = AppDatabase.getInstance(host.context)
        val id = "trash-navigation-${UUID.randomUUID()}"
        val title = "Trash navigation fixture $id"
        db.trashDao().insert(TrashedBookmarkEntity(id, title, "https://example.com/$id", "",
            createdAt = 1, updatedAt = 1, note = "", isNsfw = false, trashedAt = System.currentTimeMillis()))
        val screen = host.launch()
        try {
            clickTrashNode(host, host.await(screen) { host.description(it, host.context.getString(R.string.navigation_open)) })
            val search = host.await(screen) { host.text(it, host.context.getString(R.string.nav_search)) }
            val trash = host.await(screen) { host.text(it, host.context.getString(R.string.trash_title)) }
            assertTrue(trash.boundsInRoot.top > search.boundsInRoot.bottom)
            clickTrashNode(host, trash)
            val rowTitle = host.await(screen) { host.text(it, title) }
            host.main { assertFalse(generateSequence(rowTitle) { it.parent }.any { it.config.getOrNull(SemanticsActions.OnClick) != null }) }
            withTimeout(5000) {
                var outgoing = true
                while (outgoing) {
                    host.main { outgoing = host.nodes(screen).any { host.description(it, host.context.getString(R.string.navigation_open)) } }
                    if (outgoing) delay(20)
                }
            }
            clickTrashNode(host, host.await(screen) { host.description(it, host.context.getString(R.string.common_back)) })
            host.await(screen) { host.description(it, host.context.getString(R.string.navigation_open)) }
        } finally {
            host.main { screen.finish() }
            db.trashDao().deleteById(id)
        }
    }
}
