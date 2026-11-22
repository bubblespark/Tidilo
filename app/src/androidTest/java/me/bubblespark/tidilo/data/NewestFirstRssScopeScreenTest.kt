package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.BookmarkQueries
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Newest first "Include RSS" chip: scope, counts, full-span grid row, RSS cues, scroll reset, persistence. */
class NewestFirstRssScopeScreenTest {
    private val host = HeaderTestHost()

    @Test fun includeRssChipControlsNewestFirstScope(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val id = "scope267-${UUID.randomUUID()}"
        val feed = RssFeedEntity(id, "https://$id.invalid/rss", "Scope feed", enabled = false)
        // Seeded rows are newer than any device data, and RSS rows are the newest in the combined scope.
        val base = System.currentTimeMillis() + 10_000_000L
        val manual = (0 until 40).map {
            BookmarkEntity("$id-m$it", "$id manual $it", "https://manual-$id.invalid/$it", "", createdAt = base + it, updatedAt = 1)
        }
        val rss = (0 until 40).map {
            BookmarkEntity("$id-r$it", "$id rss $it", "https://rss-$id.invalid/$it", "", createdAt = base + 1_000 + it,
                updatedAt = 1, rssFeedId = feed.id, rssPublishedAt = 1)
        }
        val chipLabel = host.context.getString(R.string.library_include_rss_chip)
        val badgeLabel = host.context.getString(R.string.library_rss_source_label)
        val iconDescription = host.context.getString(R.string.library_rss_source_description)
        var activityToFinish: ComponentActivity? = null
        var restore: (() -> Unit)? = null
        lateinit var vm: LibraryViewModel
        try {
            db.rssDao().putFeed(feed)
            db.bookmarkDao().insertAll(manual + rss)
            val activity = host.launch()
            activityToFinish = activity
            host.main {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                val oldFilter = vm.filterMode.value
                val oldView = vm.viewMode.value
                val oldInclude = vm.includeRssInNewestFirst.value
                restore = { vm.setFilterMode(oldFilter); vm.setViewMode(oldView); vm.setIncludeRssInNewestFirst(oldInclude) }
                vm.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                vm.setViewMode(LibraryViewMode.LIST)
                vm.setIncludeRssInNewestFirst(false)
                activity.setContent { MaterialTheme { LibraryScreen(vm) } }
            }
            val visible = AppConfig.isPrivateContentVisible.value
            val manualCount = db.bookmarkDao().observeCount(BookmarkQueries.count("", visible, source = BookmarkSource.MANUAL)).first()
            val combinedCount = db.bookmarkDao().observeCount(BookmarkQueries.count("", visible, source = BookmarkSource.ALL)).first()
            val manualSubtitle = host.context.getString(R.string.library_subtitle_bookmarks, manualCount)
            val combinedSubtitle = host.context.getString(R.string.library_subtitle_bookmarks_with_rss, combinedCount)

            // OFF: manual-only list, chip unselected, manual subtitle.
            val chip = awaitChip(activity, chipLabel)
            host.main {
                assertEquals(Role.Checkbox, chip.config.getOrNull(SemanticsProperties.Role))
                assertEquals(false, chip.config.getOrNull(SemanticsProperties.Selected))
            }
            host.await(activity) { host.text(it, "$id manual 39") }
            host.await(activity) { host.text(it, manualSubtitle) }
            host.main { assertTrue(host.nodes(activity).none { host.text(it, "$id rss 39") || host.text(it, badgeLabel) }) }

            // ON: combined list, RSS badge on untagged RSS rows, combined subtitle.
            clickChip(activity, chipLabel)
            withTimeout(5000) { while (!vm.includeRssInNewestFirst.value) delay(30) }
            awaitChip(activity, chipLabel, selected = true)
            host.await(activity) { host.text(it, "$id rss 39") }
            host.await(activity) { host.text(it, badgeLabel) }
            host.await(activity) { host.text(it, combinedSubtitle) }

            // Grid: the row spans the grid width, so the first two cards sit below it; RSS icon is described.
            host.main { vm.setViewMode(LibraryViewMode.GRID) }
            delay(500)
            host.await(activity) { host.description(it, iconDescription) }
            awaitChip(activity, chipLabel, selected = true)
            host.await(activity) { host.text(it, "$id rss 38") }
            host.main {
                // Read every bound in one pass so no node is replaced between reads.
                val nodes = host.nodes(activity)
                val chipBottom = nodes.first { isChip(it, chipLabel, true) }.boundsInRoot.bottom
                val first = nodes.first { host.text(it, "$id rss 39") }
                val second = nodes.first { host.text(it, "$id rss 38") }
                assertTrue("First card must start below the scope row", first.boundsInRoot.top >= chipBottom)
                assertTrue("No card may share the scope row", second.boundsInRoot.top >= chipBottom)
            }

            // Scope change returns the active layout to the top.
            val scrolled = scrollNode(activity)
            host.main { assertTrue(scrolled.config[SemanticsActions.ScrollBy].action?.invoke(0f, 1500f) == true) }
            withTimeout(5000) { while (axisValue(scrolled) <= 0f) delay(30) }
            host.main { vm.setIncludeRssInNewestFirst(false) }
            awaitChip(activity, chipLabel, selected = false)
            withTimeout(8000) { while (axisValue(scrollNode(activity)) != 0f) delay(30) }
            host.main { assertTrue(host.nodes(activity).none { host.text(it, "$id rss 39") }) }
        } finally {
            withContext(NonCancellable) {
                try {
                    activityToFinish?.let { activity ->
                        host.main {
                            try { activity.setContent { } } finally {
                                try { restore?.invoke() } finally { activity.finish() }
                            }
                        }
                    }
                } finally {
                    try {
                        (manual + rss).forEach { db.bookmarkDao().deleteById(it.id) }
                    } finally {
                        db.openHelper.writableDatabase.execSQL("DELETE FROM rss_feeds WHERE id = ?", arrayOf(feed.id))
                    }
                }
            }
        }
    }

    @Test fun includeRssPersistsAcrossViewModelsAndFilterModes() {
        lateinit var first: LibraryViewModel
        var original = false
        host.main {
            first = LibraryViewModel()
            original = first.includeRssInNewestFirst.value
        }
        try {
            host.main {
                val oldFilter = first.filterMode.value
                first.setIncludeRssInNewestFirst(!original)
                first.setFilterMode(LibraryFilterMode.RSS)
                first.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                assertEquals(!original, first.includeRssInNewestFirst.value)
                assertEquals(!original, LibraryViewModel().includeRssInNewestFirst.value)
                first.setFilterMode(oldFilter)
            }
        } finally {
            host.main { first.setIncludeRssInNewestFirst(original) }
        }
    }

    private suspend fun awaitChip(activity: ComponentActivity, label: String, selected: Boolean? = null): SemanticsNode =
        host.await(activity) { isChip(it, label, selected) }

    /** The row moves between the loading and loaded layouts, so find and click it in one main-thread pass. */
    private suspend fun clickChip(activity: ComponentActivity, label: String) = withTimeout(8000) {
        var clicked = false
        while (!clicked) {
            host.main {
                val node = host.nodes(activity).firstOrNull { isChip(it, label, null) }
                clicked = node?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
            }
            if (!clicked) delay(30)
        }
    }

    private fun isChip(node: SemanticsNode, label: String, selected: Boolean?): Boolean =
        node.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
            (selected == null || node.config.getOrNull(SemanticsProperties.Selected) == selected) &&
            generateSequence(listOf(node)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }
                .flatten().any { host.text(it, label) }

    private suspend fun scrollNode(activity: ComponentActivity): SemanticsNode = host.await(activity) {
        it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
            (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
    }

    private fun axisValue(node: SemanticsNode): Float =
        node.config[SemanticsProperties.VerticalScrollAxisRange].value()
}
