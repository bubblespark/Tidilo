package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.BookmarkItemCard
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.SearchScreen
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkListPreviewTest {
    @Test fun modernDetailEditorShowsAnUnbrokenTagInFull(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val id = UUID.randomUUID().toString()
        val tag = "PreviewTag${id.take(8)}".repeat(8)
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        try {
            dao.insertOrUpdate(BookmarkEntity(
                id, "Long tag fixture", "https://$id.invalid", "Saved summary",
                entities = listOf(tag), createdAt = 1, updatedAt = 1
            ))
            host.main {
                val viewModel = ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent {
                    val density = LocalDensity.current.density
                    TidiloTheme(theme = AppTheme.MODERN, blurEnabled = false) {
                        CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                            BookmarkDetailScreen(id, viewModel, {})
                        }
                    }
                }
            }
            val edit = activity.getString(R.string.detail_edit_tags)
            host.await(activity) { host.description(it, edit) }
            host.main {
                val button = host.nodes(activity).first { host.description(it, edit) }
                val click = generateSequence(button) { it.parent }
                    .mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.first()
                assertTrue(click())
            }
            host.await(activity) { host.text(it, tag) && textLayout(it).lineCount > 1 }
            host.main {
                val editorText = host.nodes(activity).first { host.text(it, tag) && textLayout(it).lineCount > 1 }
                assertTrue(!textLayout(editorText).hasVisualOverflow)
            }
        } finally {
            host.main { activity.finish() }
            dao.deleteById(id)
        }
    }

    @Test fun searchUpdatesTheCaptionWhenTheSameRowMatchesAnotherField(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val token = UUID.randomUUID().toString().replace("-", "")
        val title = "Search caption fixture title$token shared$token"
        val row = BookmarkEntity(
            id = token, title = title, url = "https://preview.invalid/shared$token",
            description = "Summary shared$token", category = "Article",
            entities = listOf("Tag${token}Partial"), createdAt = 1, updatedAt = 1
        )
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        lateinit var viewModel: LibraryViewModel
        var previousMode: LibraryViewMode? = null
        try {
            dao.insertOrUpdate(row)
            host.main {
                viewModel = ViewModelProvider(activity)[LibraryViewModel::class.java]
                previousMode = viewModel.viewMode.value
                viewModel.setViewMode(LibraryViewMode.LIST)
                viewModel.onSearchQueryChanged("title$token")
                activity.setContent {
                    val density = LocalDensity.current.density
                    TidiloTheme {
                        CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                            SearchScreen(viewModel, modifier = Modifier.width(320.dp))
                        }
                    }
                }
            }
            fun caption(field: Int) = activity.getString(R.string.search_match_fields, activity.getString(field))
            host.await(activity) { host.text(it, caption(R.string.search_match_title)) }
            host.main { viewModel.onSearchQueryChanged("#Tag$token") }
            host.await(activity) { host.text(it, caption(R.string.search_match_entity_tags)) }
            host.main {
                val nodes = host.nodes(activity)
                assertTrue(nodes.any { host.text(it, title) })
                assertTrue(nodes.none { host.text(it, caption(R.string.search_match_title)) })
            }
            host.main { viewModel.onSearchQueryChanged("shared$token\u3000#Tag$token #Article") }
            val fields = listOf(
                R.string.search_match_title, R.string.detail_section_summary, R.string.search_match_url,
                R.string.search_match_classification, R.string.search_match_entity_tags
            ).joinToString(activity.getString(R.string.search_match_separator)) { activity.getString(it) }
            val allFields = activity.getString(R.string.search_match_fields, fields)
            host.await(activity) { host.text(it, allFields) }
            host.main {
                val nodes = host.nodes(activity)
                val captionLayout = textLayout(nodes.first { host.text(it, allFields) })
                assertTrue(captionLayout.lineCount > 1)
                assertTrue(!captionLayout.hasVisualOverflow)
                assertTrue(textLayout(nodes.first { host.text(it, title) }).layoutInput.text.spanStyles.isNotEmpty())
            }
        } finally {
            host.main {
                previousMode?.let(viewModel::setViewMode)
                activity.finish()
            }
            dao.deleteById(row.id)
        }
    }

    @Test fun longContentAndTagsFitThePreviewAtLargeFontSizes(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val title = "Android preview ".repeat(30)
        val summary = "日本語の長いブックマーク要約".repeat(40)
        val tags = List(10) { "Entity${it}WithAnUnbrokenName".repeat(4) }
        val bookmark = BookmarkItem("preview", title, "https://preview.invalid", summary, Tag(null, null, tags))
        try {
            for (theme in listOf(AppTheme.DEFAULT, AppTheme.MODERN)) for (dark in listOf(false, true)) {
                host.main {
                    activity.setContent {
                        val density = LocalDensity.current.density
                        TidiloTheme(darkTheme = dark, dynamicColor = false, theme = theme, blurEnabled = false) {
                            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                                Box(Modifier.width(320.dp)) {
                                    BookmarkItemCard(bookmark, wordKeywords = listOf("Android", "日本語"))
                                }
                            }
                        }
                    }
                }
                host.await(activity) { host.text(it, title) }
                val overflow = activity.resources.getQuantityString(R.plurals.bookmark_remaining_entity_tags, 8, 8)
                host.await(activity) { host.text(it, overflow) && it.layoutInfo.isPlaced }
                host.main {
                    val nodes = host.nodes(activity).filter { it.layoutInfo.isPlaced }
                    for (text in listOf(title, summary)) {
                        val node = nodes.first { host.text(it, text) }
                        val layout = textLayout(node)
                        assertEquals(3, layout.lineCount)
                        assertTrue(layout.isLineEllipsized(2))
                        assertTrue(layout.layoutInput.text.spanStyles.isNotEmpty())
                    }
                    val visible = tags.filter { tag -> nodes.any { host.text(it, tag) } }
                    assertEquals(tags.take(2), visible)
                }
            }
        } finally {
            host.main { activity.finish() }
        }
    }

    private fun textLayout(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
        return layouts.single()
    }
}
