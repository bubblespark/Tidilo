package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.TidiloApp
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LibraryNavigationInteractionTest {
    private fun click(node: SemanticsNode) {
        var target = node
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
        check(target.config[SemanticsActions.OnClick].action?.invoke() == true)
    }

    @Test fun searchStaysCompactWhileScrollingAndBackRestoresLibrary(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        val prefix = "navigation-${UUID.randomUUID()}"
        val ids = (0..15).map { "$prefix-$it" }
        lateinit var model: LibraryViewModel
        var initialized = false
        var contentReady = false
        var stage = "creating fixtures"
        var previous = LibraryFilterMode.NEWEST_FIRST
        try {
            ids.forEachIndexed { index, id -> dao.insertOrUpdate(BookmarkEntity(id, "Navigation fixture $index",
                "https://navigation.invalid/$id", "Summary", createdAt = System.currentTimeMillis() + index, updatedAt = 1)) }
            host.main {
                model = ViewModelProvider(activity)[LibraryViewModel::class.java]
                initialized = true
                previous = model.filterMode.value
                model.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                activity.setContent { MaterialTheme {
                    TidiloApp(libraryViewModel = model)
                    SideEffect { contentReady = true }
                } }
            }
            withTimeout(3000) { while (!contentReady) delay(20) }
            stage = "waiting for the Library Search action"
            val before = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_search_action" }.boundsInRoot
            stage = "waiting for Library fixtures"
            host.await(activity) { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Navigation fixture") } == true }
            val libraryTitle = host.await(activity) { host.text(it, host.context.getString(R.string.library_header_title)) }
            host.main { click(libraryTitle) }
            // Paging can retain its visible key across fixture invalidations; this test checks Search return position.
            host.await(activity) { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Navigation fixture") } == true }
            host.scroll(activity, 500f)
            delay(400)
            val action = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_search_action" }
            var firstVisibleTitle = ""
            var firstVisibleTop = 0f
            host.main {
                val first = host.nodes(activity).filter { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Navigation fixture") } == true &&
                        node.boundsInRoot.top > 0f && node.boundsInRoot.bottom < activity.window.decorView.height
                }.minBy { it.boundsInRoot.top }
                firstVisibleTitle = first.config[SemanticsProperties.Text].single().text
                firstVisibleTop = first.boundsInRoot.top
                val after = action.boundsInRoot
                val density = activity.resources.displayMetrics.density
                assertEquals(56f * density, after.width, 1f)
                assertEquals(before.top, after.top, 1f)
                assertEquals(before.width, after.width, 1f)
                click(action)
            }
            stage = "opening Search"
            host.await(activity) { host.text(it, host.context.getString(R.string.search_header_title)) }
            withTimeout(3000) {
                var searchOnly = false
                while (!searchOnly) {
                    host.main { searchOnly = host.nodes(activity).none { it.config.getOrNull(SemanticsProperties.TestTag) == "library_search_action" } }
                    if (!searchOnly) delay(20)
                }
            }
            host.main {
                assertFalse(host.nodes(activity).any { it.config.getOrNull(SemanticsProperties.TestTag) == "library_search_action" })
            }
            stage = "returning from Search"
            val back = host.await(activity) { host.description(it, host.context.getString(R.string.common_back)) }
            host.main { click(back) }
            host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_search_action" }
            assertEquals(LibraryFilterMode.NEWEST_FIRST, model.filterMode.value)
            delay(500)
            stage = "restoring the visible bookmark"
            val restored = host.await(activity) { host.text(it, firstVisibleTitle) }
            assertEquals("Back must restore the same bookmark position", firstVisibleTop, restored.boundsInRoot.top, 2f)
        } catch (error: Exception) {
            host.capture("scroll-failure")
            throw AssertionError("Navigation test failed while $stage; filter=${model.filterMode.value}; fixture=${dao.getBookmarkById(ids.last())?.title}; initialized=${model.isInitialized.value}", error)
        }
        finally {
            host.main { if (initialized) model.setFilterMode(previous); activity.finish() }
            ids.forEach { dao.deleteById(it) }
        }
    }

    @Test fun drawerOpensNotificationsAndManualImportWithoutRootTabs(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            var menu = host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
            host.main { click(menu) }
            val notifications = host.await(activity) { host.description(it, host.context.getString(R.string.settings_notification_title)) }
            delay(700)
            host.capture("rail-open")
            host.main { click(notifications) }
            host.await(activity) { host.text(it, host.context.getString(R.string.settings_notification_title)) }
            delay(600)
            val back = host.await(activity) { host.description(it, host.context.getString(R.string.common_back)) }
            host.main { click(back) }
            menu = host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
            delay(600)
            host.capture("rail-closed")
            host.main { click(menu) }
            val manual = host.await(activity) { host.text(it, host.context.getString(R.string.navigation_manual_import)) }
            host.main { click(manual) }
            host.await(activity) { host.text(it, host.context.getString(R.string.import_dialog_title)) }
            val cancel = host.await(activity) { host.text(it, host.context.getString(R.string.common_cancel)) }
            host.main { click(cancel) }
            host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
        } finally { host.main { activity.finish() } }
    }
}
