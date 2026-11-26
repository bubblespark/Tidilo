package me.bubblespark.tidilo.data

import android.content.res.Configuration
import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.engagement.EngagementType
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
import org.junit.Rule
import org.junit.Test

class BookmarkSummaryPeekInteractionTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun previewUsesTheReadingHeadingForSavedText() {
        val bookmark = BookmarkItem(
            id = "summary-peek", title = "Saved article", url = "https://summary-peek.invalid",
            description = "Description imported from the page", tag = Tag.createEmpty()
        )
        OfflineVisualCaches().use {
            composeRule.setContent {
                TidiloTheme(dynamicColor = false, blurEnabled = false) {
                    BookmarkSummaryPeekOverlay(bookmark, headerHeight = 104.dp, onDismiss = {})
                }
            }
            composeRule.awaitNode(hasText(bookmark.description))
            val nodes = nodes()
            assertFalse(nodes.any { it.hasTextValue(context.getString(R.string.detail_action_ai_summary)) })
            assertTrue("The preview displays the localized reading heading",
                nodes.any { it.hasTextValue(context.getString(R.string.detail_section_summary)) })
            val japanese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.JAPANESE)
            })
            assertEquals("要約", japanese.getString(R.string.detail_section_summary))
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = composeRule.activity
        val token = UUID.randomUUID().toString().replace("-", "")
        val domain = "$token.invalid"
        val title = "Photo article $token"
        val blankTitle = "Empty article $token"
        val summary = (1..40).joinToString("\n") { "This is saved paragraph $it of the reading preview." }
        val openingLabel = context.getString(R.string.library_summary_peek_open)
        val readingTitle = context.getString(R.string.detail_section_summary)
        val dismissalLabel = context.getString(R.string.common_close)
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
        val id = rows.first().id
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) { InMemoryLibrary(context).also { it.awaitReady() } }
        try {
            withContext(Dispatchers.IO) { library.db.bookmarkDao().insertAll(rows) }
            lateinit var model: LibraryViewModel
            composeRule.runOnUiThread {
                model = library.viewModel()
                model.setViewMode(LibraryViewMode.GRID)
                if (libraryMode == null) {
                    model.onSearchQueryChanged(token)
                } else {
                    model.setFilterMode(libraryMode)
                    if (libraryMode == LibraryFilterMode.DOMAIN) model.selectDomainFilter(domain)
                }
            }
            composeRule.setContent {
                TidiloTheme(dynamicColor = false, blurEnabled = false) {
                    if (libraryMode == null) SearchScreen(model) else LibraryScreen(model)
                }
            }

            fun card(node: SemanticsNode): SemanticsNode = generateSequence(node) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            val inPreview = matcher("reading pane") { it.config.getOrNull(SemanticsProperties.PaneTitle) == readingTitle }

            val emptyCard = composeRule.awaitNode(hasText(blankTitle))
            assertFalse(card(emptyCard).config.contains(SemanticsActions.OnLongClick))

            fun openPreview(): Offset {
                val bookmarkCard = card(composeRule.awaitNode(hasText(title)))
                val anchor = bookmarkCard.boundsInWindow.topLeft
                val open = bookmarkCard.config[SemanticsActions.OnLongClick]
                assertEquals(openingLabel, open.label)
                composeRule.runOnUiThread { assertTrue(open.action?.invoke() == true) }
                composeRule.awaitNode(inPreview)
                val nodes = nodes(useUnmergedTree = false)
                assertTrue(nodes.none { it.hasTextValue(title) || it.hasTextValue(blankTitle) })
                assertEquals(1, nodes.flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
                    .count { it.label == dismissalLabel })
                assertTrue(nodes.any { it.hasTextValue(readingTitle) && it.config.contains(SemanticsProperties.Heading) })
                return anchor
            }

            suspend fun peeks() = withContext(Dispatchers.IO) {
                library.db.engagementDao().eventsFor(id).count { it.type == EngagementType.PEEK.code }
            }

            suspend fun awaitEngagement(count: Int) {
                withTimeout(8000) { while (peeks() != count) delay(100) }
            }

            fun assertReturnedTo(anchor: Offset) {
                composeRule.waitForIdle()
                val restored = card(composeRule.awaitNode(hasText(title))).boundsInWindow.topLeft
                assertEquals(anchor.x, restored.x, 1f)
                assertEquals(anchor.y, restored.y, 1f)
            }

            fun scrollPreviewToEnd() {
                val previewScroll = composeRule.awaitNode(matcher("preview scroll") {
                    it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                        generateSequence(it) { node -> node.parent }.any { node ->
                            node.config.getOrNull(SemanticsProperties.PaneTitle) == readingTitle
                        }
                })
                val sameNode = matcher("preview scroll ${previewScroll.id}") { it.id == previewScroll.id }
                composeRule.onNode(sameNode, useUnmergedTree = true).performTouchInput {
                    swipe(Offset(centerX, height * 0.75f), Offset(centerX, height * 0.4f), 200)
                }
                composeRule.awaitNode(sameNode and matcher("scrolled") {
                    val range = it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    range != null && range.value() > 0f
                })
                composeRule.runOnUiThread {
                    assertTrue(previewScroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 10_000f) == true)
                }
                composeRule.awaitNode(sameNode and matcher("scrolled to end") {
                    val range = it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    range != null && range.maxValue() > 0 && range.value() == range.maxValue()
                })
            }

            fun imeVisible(): Boolean {
                var visible = false
                composeRule.runOnUiThread {
                    visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                return visible
            }

            val firstAnchor = openPreview()
            awaitEngagement(1)
            scrollPreviewToEnd()
            val close = composeRule.awaitNode(matcher("close action") { node ->
                node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == dismissalLabel } == true
            })
            assertTrue("Close stays within the viewport after scrolling", close.boundsInWindow.height > 0f)
            composeRule.runOnUiThread {
                assertTrue(close.config[SemanticsActions.CustomActions].single { it.label == dismissalLabel }.action())
            }
            assertReturnedTo(firstAnchor)

            if (libraryMode == null) {
                composeRule.runOnUiThread { model.triggerSearchFocus() }
                composeRule.awaitNode(matcher("focused search field") {
                    it.config.getOrNull(SemanticsActions.SetText) != null &&
                        it.config.getOrNull(SemanticsProperties.Focused) == true
                })
                composeRule.waitUntil(8_000) { imeVisible() }
            }
            val secondAnchor = openPreview()
            awaitEngagement(2)
            if (libraryMode == null) {
                composeRule.waitUntil(8_000) { !imeVisible() }
                assertFalse("Search input loses focus when the preview opens", nodes().any {
                    it.config.getOrNull(SemanticsActions.SetText) != null &&
                        it.config.getOrNull(SemanticsProperties.Focused) == true
                })
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            } else {
                scrollPreviewToEnd()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
            assertReturnedTo(secondAnchor)

            val tapAnchor = openPreview()
            awaitEngagement(3)
            composeRule.onNode(inPreview, useUnmergedTree = true).performTouchInput { click(center) }
            assertReturnedTo(tapAnchor)

            if (libraryMode == null) {
                openPreview()
                awaitEngagement(4)
                composeRule.runOnUiThread { model.onSearchQueryChanged("missing$token") }
                composeRule.awaitNode(hasText(context.getString(R.string.search_header_title)))
                assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.PaneTitle) == readingTitle })
            }
            assertEquals(if (libraryMode == null) 4 else 3, peeks())
        } finally {
            caches.close()
            library.close()
        }
    }

    private fun matcher(description: String, predicate: (SemanticsNode) -> Boolean) = SemanticsMatcher(description, predicate)

    private fun nodes(useUnmergedTree: Boolean = true): List<SemanticsNode> =
        composeRule.onAllNodes(matcher("any") { true }, useUnmergedTree).fetchSemanticsNodes()

    private fun SemanticsNode.hasTextValue(value: String) =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
}
