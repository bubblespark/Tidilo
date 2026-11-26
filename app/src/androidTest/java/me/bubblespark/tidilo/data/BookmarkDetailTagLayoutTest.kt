package me.bubblespark.tidilo.data

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.formatClassificationBreadcrumb
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookmarkDetailTagLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val density = context.resources.displayMetrics.density
    private val token = UUID.randomUUID().toString()

    @Test fun classificationAndEntityRowsKeepTheirSpacing() = withDetail(listOf(
        row("entity", entities = listOf("UniqueEntity")),
        row("category", category = "Article"),
        row("subcategory", subcategory = "Tech"),
        row("private", isPrivate = true),
        row("empty")
    )) { rows, selected ->
        for (row in rows) {
            composeRule.runOnUiThread { selected.value = row.id }
            composeRule.waitForIdle()
            assertLayout(row)
        }
    }

    @Test fun tagDialogOkPersistsBreadcrumbAndEntitiesWithoutDetailSave() = withDetail(listOf(
        row("commit", category = "Article", subcategory = "Tech", entities = listOf("InitialEntity"))
    )) { rows, _ ->
        val row = rows.single()
        val titleBounds = assertLayout(row)
        click(string(R.string.detail_edit_tags))
        val inTagDialog = inWindowShowing(string(R.string.detail_tag_dialog_title))
        // Toggle the Private switch inside BookmarkTagEditDialog.
        click(isToggleable() and hasClickAction() and inTagDialog)
        // Add a new entity tag.
        click(string(R.string.detail_tag_add_entity))
        val inAddDialog = inWindowShowing(string(R.string.detail_add_entity_title))
        val newEntity = "AddedEntity"
        val field = composeRule.awaitNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText) and inAddDialog)
        composeRule.runOnUiThread { check(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(newEntity)) == true) }
        composeRule.awaitNode(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(newEntity)) and inAddDialog)
        click(string(R.string.common_ok), inAddDialog)
        // Confirm the parent BookmarkTagEditDialog.
        click(string(R.string.common_ok), inTagDialog)

        val updatedBreadcrumb = requireNotNull(formatClassificationBreadcrumb(
            localizedCategory(row), localizedSubcategory(row), true, string(R.string.detail_tag_private_label)))
        assertTrue("Updated classification breadcrumb with Private appears above title",
            bounds(hasText(updatedBreadcrumb)).top < titleBounds.top)
        assertTrue("Added entity chip appears below title", bounds(hasText(newEntity)).top > titleBounds.bottom)
        // Parent OK is the final persistence step: no Detail-level Save is needed.
        withTimeout(5_000) {
            while (true) {
                val stored = withContext(Dispatchers.IO) { library.db.bookmarkDao().getBookmarkById(row.id) }
                if (stored?.isPrivate == true && newEntity in stored.entities) break
                delay(100)
            }
        }
    }

    private lateinit var library: InMemoryLibrary

    private fun row(
        name: String,
        category: String? = null,
        subcategory: String? = null,
        isPrivate: Boolean = false,
        entities: List<String> = emptyList()
    ) = BookmarkEntity("$token-$name", "Entity-only spacing", "https://tag-$name-$token.invalid/article", "Summary",
        category = category, subcategory = subcategory, isPrivate = isPrivate,
        entities = entities, createdAt = 1, updatedAt = 1)

    private fun withDetail(
        rows: List<BookmarkEntity>,
        body: suspend (List<BookmarkEntity>, androidx.compose.runtime.MutableState<String>) -> Unit
    ): Unit = runBlocking {
        val caches = OfflineVisualCaches()
        library = withContext(Dispatchers.IO) { InMemoryLibrary(context).also { it.awaitReady() } }
        try {
            withContext(Dispatchers.IO) { library.db.bookmarkDao().insertAll(rows) }
            val vm = library.viewModel()
            val selected = mutableStateOf(rows.first().id)
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    Surface { BookmarkDetailScreen(selected.value, vm, {}) }
                }
            }
            body(rows, selected)
        } finally {
            caches.close()
            library.close()
        }
    }

    /** Asserts the classification, title, and entity rows of [row]; returns the title bounds. */
    private fun assertLayout(row: BookmarkEntity): Rect {
        val domainBounds = bounds(hasText(requireNotNull(row.domain), substring = true))
        val titleBounds = bounds(hasText(row.title))
        val expectedBreadcrumb = formatClassificationBreadcrumb(
            localizedCategory(row), localizedSubcategory(row), row.isPrivate, string(R.string.detail_tag_private_label))
        if (expectedBreadcrumb != null) {
            val breadcrumbBounds = bounds(hasText(expectedBreadcrumb))
            assertTrue("${row.id}: classification breadcrumb must sit between domain and title",
                breadcrumbBounds.top >= domainBounds.bottom - 2 && breadcrumbBounds.bottom <= titleBounds.top + 2)
        } else {
            val domainToTitleGap = (titleBounds.top - domainBounds.bottom) / density
            assertTrue("${row.id}: when classification is empty, title pulls up directly beneath domain without an empty row gap: $domainToTitleGap dp",
                domainToTitleGap in 2f..18f)
        }
        val belowTitleText = row.entities.firstOrNull() ?: string(R.string.detail_add_tags_placeholder)
        val titleToEntityGap = (bounds(hasText(belowTitleText)).top - titleBounds.bottom) / density
        assertTrue("${row.id}: entity row sits compactly below the title: $titleToEntityGap dp", titleToEntityGap in 6f..32f)
        composeRule.awaitNode(hasContentDescription(string(R.string.detail_edit_tags)))
        return titleBounds
    }

    private fun localizedCategory(row: BookmarkEntity) = row.category?.let { Category.toLocalized(it, context) }.orEmpty()
    private fun localizedSubcategory(row: BookmarkEntity) = row.subcategory?.let { Subcategory.toLocalized(it, context) }.orEmpty()

    private fun string(id: Int) = context.getString(id)

    private fun bounds(matcher: SemanticsMatcher): Rect = composeRule.awaitNode(matcher).boundsInRoot

    private fun click(label: String, scope: SemanticsMatcher? = null) =
        click((hasText(label) or hasContentDescription(label)).let { if (scope == null) it else it and scope })

    /** Clicks the enabled clickable ancestor of the newest node matching [matcher]. */
    private fun click(matcher: SemanticsMatcher) {
        var click: (() -> Boolean)? = null
        composeRule.waitUntil(8_000) {
            click = composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().asReversed().firstNotNullOfOrNull { node ->
                generateSequence(node) { it.parent }.firstOrNull { SemanticsActions.OnClick in it.config }
                    ?.takeIf { SemanticsProperties.Disabled !in it.config }
                    ?.config?.get(SemanticsActions.OnClick)?.action
            }
            click != null
        }
        composeRule.runOnUiThread { check(requireNotNull(click)()) }
        composeRule.waitForIdle()
    }
}
