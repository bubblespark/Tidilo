package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.service.TaggingResult
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SecondaryHeaderNavigationTest {
    @Test fun enlargedTagHeaderKeepsSubtitleBelowSingleLineTitle(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            host.main {
                val vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent { MaterialTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                        Box(Modifier.size(320.dp, 450.dp)) { TagSelectionScreen(vm, {}) }
                    }
                } }
            }
            val node = host.await(activity) { host.text(it, host.context.getString(R.string.tag_selection_title)) }
            host.main {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                assertEquals("Header title stays one line at 200%", 1, layouts.single().lineCount)
            }
            host.capture("tag-header-200")
        } finally { host.main { activity.finish() } }
    }

    @Test fun reviewHeaderSurvivesLoadingSuccessAndDiscardWithoutInference(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val db = AppDatabase.getInstance(host.context)
        val id = "header-review-${UUID.randomUUID()}"
        val generated = CompletableDeferred<Result<TaggingResult>>()
        var returned = false
        var calls = 0
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Header review", "https://header.invalid/$id", "Original summary", createdAt = 1, updatedAt = 1))
            host.main {
                val vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent { MaterialTheme { Box(Modifier.size(360.dp, 450.dp)) {
                    BookmarkAiReviewScreen(id, vm, { returned = true }, generateProposal = { _, _, _ -> calls++; generated.await() })
                } } }
            }
            host.await(activity) { host.text(it, host.context.getString(R.string.ai_review_loading_title)) }
            val label = host.context.getString(R.string.common_back)
            val before = host.await(activity) { host.description(it, label) }.boundsInRoot
            generated.complete(Result.success(TaggingResult("Proposal", "Proposal summary ".repeat(60), "Article", "Tech", listOf("Android"))))
            host.await(activity) { host.text(it, "Header review") }
            host.scroll(activity, 6000f)
            delay(400)
            val after = host.await(activity) { host.description(it, label) }
            assertEquals(before.top, after.boundsInRoot.top, 1f)
            host.main {
                var node = after
                while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node.parent)
                node.config[SemanticsActions.OnClick].action?.invoke()
            }
            assertFalse(returned)
            fun find(node: android.view.accessibility.AccessibilityNodeInfo, text: String): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.text?.toString() == text) return node
                return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let { child -> find(child, text) } }
            }
            val confirm = withTimeout(5000) {
                var node: android.view.accessibility.AccessibilityNodeInfo? = null
                while (node == null) {
                    host.instrumentation.uiAutomation.clearCache()
                    node = host.instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, host.context.getString(R.string.ai_review_dialog_discard_confirm)) }
                    if (node == null) delay(30)
                }
                node
            }
            var clickable = confirm
            while (!clickable.isClickable) clickable = requireNotNull(clickable.parent)
            assertTrue(clickable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            withTimeout(3000) { while (!returned) delay(30) }
            assertEquals(1, calls)
            assertEquals("Original summary", db.bookmarkDao().getBookmarkById(id)?.description)
        } finally { host.main { activity.finish() }; db.bookmarkDao().deleteById(id) }
    }

    @Test fun detailBackAndSaveRemainAvailableAfterScrolling(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val db = AppDatabase.getInstance(host.context)
        val id = "header-detail-${UUID.randomUUID()}"
        var returned = false
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Header detail", "https://header.invalid/$id", "Long summary ".repeat(60), createdAt = 1, updatedAt = 1))
            host.main {
                val vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent { MaterialTheme { Box(Modifier.size(360.dp, 450.dp)) { BookmarkDetailScreen(id, vm, { returned = true }) } } }
            }
            val label = host.context.getString(R.string.common_back)
            val before = host.await(activity) { host.description(it, label) }.boundsInRoot
            host.await(activity) { host.text(it, "Header detail") }
            host.scroll(activity, 6000f)
            delay(400)
            val after = host.await(activity) { host.description(it, label) }
            assertEquals(before.top, after.boundsInRoot.top, 1f)
            val note = host.await(activity) { it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main {
                assertTrue(note.config[SemanticsActions.SetText].action?.invoke(androidx.compose.ui.text.AnnotatedString("Saved from fixed header")) == true)
            }
            val save = host.await(activity) { host.text(it, host.context.getString(R.string.detail_save)) }
            delay(100)
            host.main {
                var node = save
                while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node.parent)
                assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            withTimeout(5000) { while (!returned) delay(30) }
            assertEquals("Saved from fixed header", db.bookmarkDao().getBookmarkById(id)?.note)
        } finally { host.main { activity.finish() }; db.bookmarkDao().deleteById(id) }
    }

    @Test fun tagSelectionBackRemainsFixedAndAppliesQuery(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        lateinit var vm: LibraryViewModel
        var returned = false
        var previous = ""
        try {
            host.main {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                previous = vm.searchQuery.value
                vm.onSearchQueryChanged("header-query")
                activity.setContent { MaterialTheme { Box(Modifier.size(360.dp, 350.dp)) { TagSelectionScreen(vm, { returned = true }) } } }
            }
            val label = host.context.getString(R.string.tag_selection_back_and_apply)
            val before = host.await(activity) { host.description(it, label) }.boundsInRoot
            host.scroll(activity, 3000f)
            delay(400)
            val after = host.await(activity) { host.description(it, label) }
            assertEquals(before.top, after.boundsInRoot.top, 1f)
            host.main {
                var node = after
                while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node.parent)
                node.config[SemanticsActions.OnClick].action?.invoke()
            }
            assertTrue(returned)
            assertEquals("header-query", vm.searchQuery.value)
        } finally { host.main { vm.onSearchQueryChanged(previous); activity.finish() } }
    }

    @Test fun missingDetailAndReviewKeepNavigationInHeader(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            for (review in listOf(false, true)) {
                host.main {
                    val vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                    activity.setContent { MaterialTheme {
                        if (review) BookmarkAiReviewScreen("missing-header-test", vm, {})
                        else BookmarkDetailScreen("missing-header-test", vm, {})
                    } }
                }
                host.await(activity) { host.text(it, host.context.getString(R.string.detail_item_not_found)) }
                val back = host.await(activity) { host.description(it, host.context.getString(R.string.common_back)) }
                assertTrue("Missing content still has a top navigation header", back.boundsInRoot.top < 200f * host.context.resources.displayMetrics.density)
            }
        } finally { host.main { activity.finish() } }
    }
}
