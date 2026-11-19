package me.bubblespark.tidilo.data

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.BookmarkSummaryPeekOverlay
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.SearchScreen
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkSummaryPeekInteractionTest {
    @Test
    fun previewUsesTheReadingHeadingForSavedText(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val bookmark = BookmarkItem(
            id = "summary-peek", title = "Saved article", url = "https://summary-peek.invalid",
            description = "Description imported from the page", tag = Tag.createEmpty()
        )
        try {
            host.main {
                activity.setContent {
                    TidiloTheme(blurEnabled = false) {
                        BookmarkSummaryPeekOverlay(bookmark, headerHeight = 104.dp, onDismiss = {})
                    }
                }
            }
            host.await(activity) { host.text(it, bookmark.description) }
            host.main {
                val nodes = host.nodes(activity)
                assertFalse(nodes.any { host.text(it, activity.getString(R.string.detail_action_ai_summary)) })
                check(nodes.any { host.text(it, activity.getString(R.string.detail_section_summary)) })
            }
            val japanese = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                setLocale(Locale.JAPANESE)
            })
            assertEquals("要約", japanese.getString(R.string.detail_section_summary))
        } finally {
            host.main { activity.finish() }
        }
    }

    @Test
    fun libraryGridPreviewReturnsToTheSameCard(): Unit = runBlocking {
        exerciseGrid(LibraryFilterMode.NEWEST_FIRST)
    }

    @Test
    fun drillDownBackDismissesThePreviewBeforeLeavingTheGrid(): Unit = runBlocking {
        exerciseGrid(LibraryFilterMode.DOMAIN)
    }

    @Test
    fun searchGridPreviewDismissesOnQueryChange(): Unit = runBlocking {
        exerciseGrid(null)
    }

    private suspend fun exerciseGrid(libraryMode: LibraryFilterMode?) {
        val host = HeaderTestHost()
        val activity = host.launch()
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        val token = UUID.randomUUID().toString().replace("-", "")
        val domain = "$token.invalid"
        val title = "Photo article $token"
        val blankTitle = "Empty article $token"
        val summary = (1..40).joinToString("\n") { "This is saved paragraph $it of the reading preview." }
        val openingLabel = activity.getString(R.string.library_summary_peek_open)
        val readingTitle = activity.getString(R.string.detail_section_summary)
        val dismissalLabel = activity.getString(R.string.common_close)
        val rows = (0..7).map { index ->
            BookmarkEntity(
                id = "$token-$index",
                title = when (index) {
                    0 -> title
                    1 -> blankTitle
                    else -> "Another article $index $token"
                },
                url = "https://$domain/$index",
                description = if (index == 0) summary else "",
                domain = domain,
                createdAt = System.currentTimeMillis() + 100_000 - index,
                updatedAt = 1
            )
        }
        lateinit var model: LibraryViewModel
        var previousView: LibraryViewMode? = null
        var previousFilter: LibraryFilterMode? = null
        var blurEnabled by mutableStateOf(false)
        val id = rows.first().id
        try {
            dao.insertAll(rows)
            host.main {
                model = ViewModelProvider(activity)[LibraryViewModel::class.java]
                previousView = model.viewMode.value
                previousFilter = model.filterMode.value
                model.setViewMode(LibraryViewMode.GRID)
                if (libraryMode == null) {
                    model.onSearchQueryChanged(token)
                } else {
                    model.setFilterMode(libraryMode)
                    if (libraryMode == LibraryFilterMode.DOMAIN) model.selectDomainFilter(domain)
                }
                activity.setContent {
                    TidiloTheme(blurEnabled = blurEnabled) {
                        if (libraryMode == null) SearchScreen(model) else LibraryScreen(model)
                    }
                }
            }

            fun card(node: SemanticsNode): SemanticsNode = generateSequence(node) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.OnClick) != null }

            val emptyCard = host.await(activity) { host.text(it, blankTitle) }
            host.main { assertFalse(card(emptyCard).config.contains(SemanticsActions.OnLongClick)) }

            suspend fun openPreview(): Offset {
                val titleNode = host.await(activity) { host.text(it, title) }
                var anchor = Offset.Zero
                host.main {
                    val bookmarkCard = card(titleNode)
                    anchor = bookmarkCard.boundsInWindow.topLeft
                    val open = bookmarkCard.config[SemanticsActions.OnLongClick]
                    assertEquals(openingLabel, open.label)
                    assertTrue(open.action?.invoke() == true)
                }
                host.await(activity) { it.config.getOrNull(SemanticsProperties.PaneTitle) == readingTitle }
                host.main {
                    val nodes = host.nodes(activity, useUnmergedTree = false)
                    assertTrue(nodes.none { host.text(it, title) || host.text(it, blankTitle) })
                    assertEquals(1, nodes.flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
                        .count { it.label == dismissalLabel })
                    assertTrue(nodes.any { host.text(it, readingTitle) && it.config.contains(SemanticsProperties.Heading) })
                }
                return anchor
            }

            suspend fun awaitEngagement(count: Int) {
                withTimeout(8000) { dao.observeById(id).first { it?.summaryPeekCount == count } }
            }

            suspend fun assertReturnedTo(anchor: Offset) {
                val titleNode = host.await(activity) { host.text(it, title) }
                host.main {
                    val restored = card(titleNode).boundsInWindow.topLeft
                    assertEquals(anchor.x, restored.x, 1f)
                    assertEquals(anchor.y, restored.y, 1f)
                }
            }

            suspend fun scrollPreviewToEnd() {
                val previewScroll = host.await(activity) {
                    it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                        generateSequence(it) { node -> node.parent }.any { node ->
                            node.config.getOrNull(SemanticsProperties.PaneTitle) == readingTitle
                        }
                }
                val scrollBounds = previewScroll.boundsInWindow
                val dragStart = Offset(scrollBounds.center.x, scrollBounds.top + scrollBounds.height * 0.75f)
                val dragEnd = Offset(dragStart.x, scrollBounds.top + scrollBounds.height * 0.4f)
                touch(host, dragStart, dragEnd)
                host.await(activity) {
                    val range = it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    it.id == previewScroll.id && range != null && range.value() > 0f
                }
                host.main { assertTrue(previewScroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 10_000f) == true) }
                host.await(activity) {
                    val range = it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    it.id == previewScroll.id && range != null && range.maxValue() > 0 && range.value() == range.maxValue()
                }
            }

            val firstAnchor = openPreview()
            awaitEngagement(1)
            scrollPreviewToEnd()
            val close = host.await(activity) { node ->
                node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == dismissalLabel } == true
            }
            host.main {
                assertTrue("Close stays within the viewport after scrolling", close.boundsInWindow.height > 0f)
                assertTrue(close.config[SemanticsActions.CustomActions].single { it.label == dismissalLabel }.action())
            }
            assertReturnedTo(firstAnchor)

            host.main { blurEnabled = true }
            val secondAnchor = openPreview()
            awaitEngagement(2)
            scrollPreviewToEnd()
            host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertReturnedTo(secondAnchor)

            val tapAnchor = openPreview()
            awaitEngagement(3)
            val pane = host.await(activity) { it.config.getOrNull(SemanticsProperties.PaneTitle) == readingTitle }
            touch(host, pane.boundsInWindow.center)
            assertReturnedTo(tapAnchor)

            if (libraryMode == null) {
                openPreview()
                awaitEngagement(4)
                host.main { model.onSearchQueryChanged("missing$token") }
                host.await(activity) { host.text(it, activity.getString(R.string.search_header_title)) }
                host.main { assertTrue(host.nodes(activity).none { it.config.getOrNull(SemanticsProperties.PaneTitle) == readingTitle }) }
            }
            assertEquals(if (libraryMode == null) 4 else 3, dao.getBookmarkById(id)?.summaryPeekCount)
        } finally {
            host.main {
                previousView?.let(model::setViewMode)
                previousFilter?.let(model::setFilterMode)
                activity.setContent {}
                activity.finish()
            }
            rows.forEach { dao.deleteById(it.id) }
        }
    }

    private suspend fun touch(host: HeaderTestHost, start: Offset, end: Offset = start) {
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, position: Offset) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, position.x, position.y, 0)
            try { host.instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, start)
        if (start != end) {
            for (step in 1..8) {
                delay(16)
                send(MotionEvent.ACTION_MOVE, start + (end - start) * (step / 8f))
            }
        }
        send(MotionEvent.ACTION_UP, end)
    }
}
