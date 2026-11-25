package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.service.TaggingResult
import me.bubblespark.tidilo.ui.screens.library.AI_REVIEW_STATUS_ERROR_TAG
import me.bubblespark.tidilo.ui.screens.library.AI_REVIEW_STATUS_INFO_TAG
import me.bubblespark.tidilo.ui.screens.library.BookmarkAiReviewScreen
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.TagSelectionScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SecondaryHeaderNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = composeRule.activity

    /** Runs [block] against an in-memory Library seeded with [seed], with offline visual caches. */
    private fun withLibrary(vararg seed: BookmarkEntity, block: suspend (InMemoryLibrary) -> Unit): Unit = runBlocking {
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) {
            InMemoryLibrary(context.applicationContext).also {
                it.awaitReady()
                if (seed.isNotEmpty()) it.db.bookmarkDao().insertAll(seed.toList())
            }
        }
        try { block(library) } finally {
            caches.close()
            library.close()
        }
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = composeRule.setContent {
        TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { content() }
    }

    private fun await(matcher: SemanticsMatcher, unmerged: Boolean = false): SemanticsNode {
        composeRule.waitUntil(8_000) { composeRule.onAllNodes(matcher, unmerged).fetchSemanticsNodes().isNotEmpty() }
        return composeRule.onAllNodes(matcher, unmerged).fetchSemanticsNodes().first()
    }

    private fun backBounds(label: String): Rect = await(hasContentDescription(label) and hasClickAction()).boundsInRoot

    private fun clickBack(label: String) {
        composeRule.onAllNodes(hasContentDescription(label) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun scroll(pixels: Float) {
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))[0]
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, pixels) }
        composeRule.waitForIdle()
    }

    @Test fun enlargedTagHeaderKeepsSubtitleBelowSingleLineTitle() = withLibrary { library ->
        val vm = library.viewModel()
        show {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.size(320.dp, 450.dp)) { TagSelectionScreen(vm, {}) }
            }
        }
        val node = await(hasText(context.getString(R.string.tag_selection_title)), unmerged = true)
        composeRule.runOnUiThread {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertEquals("Header title stays one line at 200%", 1, layouts.single().lineCount)
        }
    }

    @Test fun reviewHeaderSurvivesLoadingSuccessAndDiscardWithoutInference() = withLibrary(
        BookmarkEntity("header-review", "Header review", "https://header.invalid/review", "Original summary", createdAt = 1, updatedAt = 1)
    ) { library ->
        val generated = CompletableDeferred<Result<TaggingResult>>()
        var returned = false
        var calls = 0
        val vm = library.viewModel()
        show {
            Box(Modifier.size(360.dp, 450.dp)) {
                BookmarkAiReviewScreen("header-review", vm, { returned = true }, generateProposal = { _, _, _ -> calls++; generated.await() })
            }
        }
        val loadingTitle = context.getString(R.string.ai_review_loading_title)
        await(hasText(loadingTitle), unmerged = true)
        composeRule.onAllNodes(hasTestTag(AI_REVIEW_STATUS_INFO_TAG) or hasTestTag(AI_REVIEW_STATUS_ERROR_TAG), useUnmergedTree = true)
            .fetchSemanticsNodes().let { assertTrue("Review status icon should be absent", it.isEmpty()) }
        val label = context.getString(R.string.common_back)
        val before = backBounds(label)
        generated.complete(Result.success(TaggingResult("Proposal", "Proposal summary ".repeat(60), "Article", "Tech", listOf("Android"))))
        await(hasText("Header review"), unmerged = true)
        scroll(6000f)
        assertEquals(before.top, backBounds(label).top, 1f)
        clickBack(label)
        assertFalse(returned)
        val confirm = hasText(context.getString(R.string.ai_review_dialog_discard_confirm)) and hasClickAction()
        await(confirm)
        composeRule.onAllNodes(confirm)[0].performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(3_000) { returned }
        assertEquals(1, calls)
        val description = withContext(Dispatchers.IO) { library.db.bookmarkDao().getBookmarkById("header-review")?.description }
        assertEquals("Original summary", description)
    }

    @Test fun detailBackRemainsFixedAndNoteAutosavesWhenLeaving() = withLibrary(
        BookmarkEntity("header-detail", "Header detail", "https://header.invalid/detail", "Long summary ".repeat(60), createdAt = 1, updatedAt = 1)
    ) { library ->
        var returned = false
        val shown = mutableStateOf(true)
        val vm = library.viewModel()
        show {
            if (shown.value) Box(Modifier.size(360.dp, 450.dp)) { BookmarkDetailScreen("header-detail", vm, { returned = true }) }
        }
        val label = context.getString(R.string.common_back)
        val before = backBounds(label)
        await(hasText("Header detail"), unmerged = true)
        scroll(6000f)
        assertEquals(before.top, backBounds(label).top, 1f)
        val note = await(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        val back = await(hasContentDescription(label) and hasClickAction())
        composeRule.runOnUiThread {
            assertTrue(note.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Saved from fixed header")) == true)
        }
        // Leave right after typing: Back needs no confirmation and disposal flushes the pending note.
        composeRule.runOnUiThread {
            assertTrue(back.config[SemanticsActions.OnClick].action?.invoke() == true)
            shown.value = false
        }
        composeRule.waitUntil(5_000) { returned }
        withTimeout(5_000) {
            while (withContext(Dispatchers.IO) { library.db.bookmarkDao().getBookmarkById("header-detail")?.note } != "Saved from fixed header") {
                delay(100)
            }
        }
    }

    @Test fun tagSelectionBackRemainsFixedAndAppliesQuery() = withLibrary { library ->
        var returned = false
        val vm = library.viewModel()
        composeRule.runOnUiThread { vm.onSearchQueryChanged("header-query") }
        show { Box(Modifier.size(360.dp, 350.dp)) { TagSelectionScreen(vm, { returned = true }) } }
        val label = context.getString(R.string.tag_selection_back_and_apply)
        val before = backBounds(label)
        scroll(3000f)
        assertEquals(before.top, backBounds(label).top, 1f)
        clickBack(label)
        assertTrue(returned)
        assertEquals("header-query", vm.searchQuery.value)
    }

    @Test fun missingDetailAndReviewKeepNavigationInHeader() = withLibrary { library ->
        val review = mutableStateOf(false)
        val vm = library.viewModel()
        show {
            if (review.value) BookmarkAiReviewScreen("missing-header-test", vm, {})
            else BookmarkDetailScreen("missing-header-test", vm, {})
        }
        for (isReview in listOf(false, true)) {
            composeRule.runOnIdle { review.value = isReview }
            await(hasText(context.getString(R.string.detail_item_not_found)), unmerged = true)
            val back = backBounds(context.getString(R.string.common_back))
            assertTrue("Missing content still has a top navigation header (review=$isReview)",
                back.top < 200f * context.resources.displayMetrics.density)
        }
    }
}
