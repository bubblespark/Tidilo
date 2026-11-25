package me.bubblespark.tidilo.data

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.BookmarkItemCard
import me.bubblespark.tidilo.ui.screens.library.EntityTagPreview
import me.bubblespark.tidilo.ui.screens.library.LibraryStore
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.RepositoryLibraryStore
import me.bubblespark.tidilo.ui.screens.library.SearchScreen
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BookmarkListPreviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var oldDiscoverer: ((String) -> DomainIconManager.HtmlDiscoveryResult)? = null
    private var oldDownloader: ((String) -> DomainIconManager.DownloadResult)? = null
    private var oldOgDownloader: (suspend (String) -> ByteArray?)? = null

    @Before
    fun stubMediaCaches() {
        oldDiscoverer = DomainIconManager.htmlDiscoverer
        oldDownloader = DomainIconManager.httpDownloader
        oldOgDownloader = OgImageManager.imageDownloader
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
        OgImageManager.imageDownloader = { null }
    }

    @After
    fun restoreMediaCaches() {
        DomainIconManager.htmlDiscoverer = oldDiscoverer
        DomainIconManager.httpDownloader = oldDownloader
        OgImageManager.imageDownloader = oldOgDownloader
    }

    private fun createInMemoryHarness(): Triple<AppDatabase, String, LibraryViewModel> {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val prefsName = "preview-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val repository = BookmarkRepository(db, autoInitialize = false)
        runBlocking { repository.retryInitializationAndAwait() }
        val store = RepositoryLibraryStore(
            repository,
            CollectionRepository(db),
            DomainPolicyStore.getInstance(context),
            prefs
        )
        val viewModel = LibraryViewModel(store, SavedStateHandle(), MutableStateFlow(true))
        return Triple(db, prefsName, viewModel)
    }

    @Test
    fun tagPreviewReportsContentWidthWithUnboundedHorizontalSpace() {
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    EntityTagPreview { repeat(4) { Text("Preview tag ${it + 1}") } }
                }
            }
        }
        composeRule.waitForIdle()
        val first = composeRule.onNodeWithText("Preview tag 1", useUnmergedTree = true).fetchSemanticsNode()
        val last = composeRule.onNodeWithText("Preview tag 4", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(first.boundsInRoot.top, last.boundsInRoot.top, 1f)
        assertTrue(last.boundsInRoot.left > first.boundsInRoot.left)
    }

    @Test
    fun modernDetailEditorShowsAnUnbrokenTagInFull() {
        val (db, prefsName, viewModel) = createInMemoryHarness()
        val id = UUID.randomUUID().toString()
        val tag = "PreviewTag${id.take(8)}".repeat(8)
        try {
            runBlocking {
                db.bookmarkDao().insertOrUpdate(
                    BookmarkEntity(
                        id, "Long tag fixture", "https://$id.invalid", "Saved summary",
                        entities = listOf(tag), createdAt = 1, updatedAt = 1
                    )
                )
            }
            composeRule.setContent {
                val density = LocalDensity.current.density
                TidiloTheme(theme = AppTheme.MODERN, blurEnabled = false, dynamicColor = false) {
                    CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                        BookmarkDetailScreen(id, viewModel, {})
                    }
                }
            }
            val edit = context.getString(R.string.detail_edit_tags)
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(edit)), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription(edit, useUnmergedTree = true).performClick()
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(SemanticsMatcher("wrapped tag") {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == tag } == true &&
                        textLayout(it).lineCount > 1
                }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val editorText = composeRule.onNode(SemanticsMatcher("wrapped tag") {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == tag } == true &&
                    textLayout(it).lineCount > 1
            }, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(!textLayout(editorText).hasVisualOverflow)
        } finally {
            db.close()
            context.deleteSharedPreferences(prefsName)
        }
    }

    @Test
    fun searchUpdatesTheCaptionWhenTheSameRowMatchesAnotherField() {
        val (db, prefsName, viewModel) = createInMemoryHarness()
        val token = UUID.randomUUID().toString().replace("-", "")
        val title = "Search caption fixture title$token shared$token"
        val row = BookmarkEntity(
            id = token, title = title, url = "https://preview.invalid/shared$token",
            description = "Summary shared$token", category = "Article",
            entities = listOf("Tag${token}Partial"), createdAt = 1, updatedAt = 1
        )
        try {
            runBlocking { db.bookmarkDao().insertOrUpdate(row) }
            viewModel.setViewMode(LibraryViewMode.LIST)
            viewModel.onSearchQueryChanged("title$token")
            composeRule.setContent {
                val density = LocalDensity.current.density
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                        SearchScreen(viewModel, modifier = Modifier.width(320.dp))
                    }
                }
            }
            fun caption(field: Int) = context.getString(field)
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(hasText(caption(R.string.search_match_title)), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.runOnUiThread { viewModel.onSearchQueryChanged("#Tag$token") }
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(SemanticsMatcher("entity tag match") {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == caption(R.string.search_match_entity_tags) } == true
                }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(title, useUnmergedTree = true).assertExists()
            composeRule.onNodeWithText(caption(R.string.search_match_title), useUnmergedTree = true).assertDoesNotExist()

            composeRule.runOnUiThread { viewModel.onSearchQueryChanged("shared$token\u3000#Tag$token #Article") }
            val allFields = listOf(
                R.string.search_match_title, R.string.detail_section_summary, R.string.search_match_url,
                R.string.search_match_classification, R.string.search_match_entity_tags
            ).joinToString(context.getString(R.string.search_match_separator)) { context.getString(it) }
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(SemanticsMatcher("all fields caption") {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == allFields } == true
                }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val captionLayout = textLayout(composeRule.onNodeWithText(allFields, useUnmergedTree = true).fetchSemanticsNode())
            assertTrue(captionLayout.lineCount > 1)
            assertTrue(!captionLayout.hasVisualOverflow)
            val titleNode = composeRule.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(textLayout(titleNode).layoutInput.text.spanStyles.isNotEmpty())
        } finally {
            db.close()
            context.deleteSharedPreferences(prefsName)
        }
    }

    @Test
    fun longContentAndTagsFitThePreviewAtLargeFontSizes() {
        val title = "Android preview ".repeat(30)
        val summary = "日本語の長いブックマーク要約".repeat(40)
        val tags = List(10) { "Entity${it}WithAnUnbrokenName".repeat(4) }
        val bookmark = BookmarkItem("preview", title, "https://preview.invalid", summary, Tag(null, null, tags))
        composeRule.setContent {
            val density = LocalDensity.current.density
            TidiloTheme(darkTheme = false, dynamicColor = false, theme = AppTheme.DEFAULT, blurEnabled = false) {
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    Box(Modifier.width(320.dp)) {
                        BookmarkItemCard(bookmark, wordKeywords = listOf("Android", "日本語"))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val overflow = context.resources.getQuantityString(R.plurals.bookmark_remaining_entity_tags, 8, 8)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(SemanticsMatcher("overflow placed") {
                it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == overflow } == true && it.layoutInfo.isPlaced
            }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        for (text in listOf(title, summary)) {
            val node = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            val layout = textLayout(node)
            assertEquals(3, layout.lineCount)
            assertTrue(layout.isLineEllipsized(2))
            assertTrue(layout.layoutInput.text.spanStyles.isNotEmpty())
        }
        val visible = tags.filter { tag ->
            composeRule.onAllNodes(SemanticsMatcher("tag placed") {
                it.layoutInfo.isPlaced && it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == tag } == true
            }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(tags.take(2), visible)
    }

    private fun textLayout(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
        return layouts.single()
    }
}
