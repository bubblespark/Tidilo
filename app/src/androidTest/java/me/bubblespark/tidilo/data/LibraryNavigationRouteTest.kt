package me.bubblespark.tidilo.data

import android.content.Context
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real `MainActivity` group navigation across Summary Peek and Detail. */
class LibraryNavigationRouteTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun await(description: String, matcher: SemanticsMatcher): SemanticsNode {
        try {
            composeRule.waitUntil(8_000) { nodes(matcher).isNotEmpty() }
        } catch (error: Throwable) {
            throw AssertionError("Timed out waiting for $description", error)
        }
        return nodes(matcher).first()
    }

    private fun invokeOnAncestor(node: SemanticsNode, action: SemanticsPropertyKey<AccessibilityAction<() -> Boolean>>, name: String) {
        val invoke = checkNotNull(generateSequence(node) { it.parent }.mapNotNull { it.config.getOrNull(action)?.action }.firstOrNull()) {
            "The card must expose an invokable $name action"
        }
        composeRule.runOnUiThread { assertTrue(invoke()) }
        composeRule.waitForIdle()
    }

    /** Runs [block] with blur, the Discover preview, and visual caches pinned for `MainActivity`, restoring them afterwards. */
    private fun e2e(block: suspend () -> Unit): Unit = runBlocking {
        val oldBlur = AppConfig.overlayBlurEnabled.value
        val oldPreview = AppConfig.discoverPreviewEnabled.value
        val caches = OfflineVisualCaches()
        try {
            AppConfig.setOverlayBlurEnabled(false)
            AppConfig.setDiscoverPreviewEnabled(false)
            block()
        } finally {
            AppConfig.setOverlayBlurEnabled(oldBlur)
            AppConfig.setDiscoverPreviewEnabled(oldPreview)
            caches.close()
        }
    }

    @Test fun groupNavigationSurvivesPeekAndDetailReturn() = e2e {
        val token = UUID.randomUUID().toString()
        val prefix = "group-$token-"
        val domain = "group-$token.invalid"
        val db = AppDatabase.getInstance(context)
        val prefs = context.getSharedPreferences("tidilo_library_prefs", Context.MODE_PRIVATE)
        val savedPrefs = listOf("library_filter_mode", "library_view_mode").associateWith { prefs.getString(it, null) }
        val summaryTitle = context.getString(R.string.detail_section_summary)
        val isSummaryPeek = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, summaryTitle)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            withContext(Dispatchers.IO) {
                db.bookmarkDao().insertAll((0..19).map {
                    BookmarkEntity("$prefix$it", "Group fixture $it", "https://$domain/$it", "Summary fixture $it",
                        createdAt = it.toLong(), updatedAt = 1)
                })
            }
            val launched = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
            lateinit var model: LibraryViewModel
            var windowHeight = 0
            launched.onActivity {
                windowHeight = it.window.decorView.height
                model = ViewModelProvider(it)[LibraryViewModel::class.java].apply {
                    setViewMode(LibraryViewMode.GRID)
                    setFilterMode(LibraryFilterMode.DOMAIN)
                    selectDomainFilter(domain)
                }
            }
            await("bookmark cards in the selected domain", hasText("Group fixture 19"))
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))[0]
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 500f) }
            composeRule.waitForIdle()
            val visible = nodes(SemanticsMatcher("group title") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Group fixture") } == true
            }).filter { it.boundsInRoot.top > 0f && it.boundsInRoot.bottom < windowHeight }.minBy { it.boundsInRoot.top }
            val visibleTitle = visible.config[SemanticsProperties.Text].single().text
            val visibleTop = visible.boundsInRoot.top
            invokeOnAncestor(visible, SemanticsActions.OnLongClick, "LongClick")
            await("Summary Peek after long-pressing the card", isSummaryPeek)
            launched.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
            assertEquals("Back must dismiss Peek before leaving the group", domain, model.selectedDomainFilter.value)
            assertFalse("Back must dismiss Summary Peek", nodes(isSummaryPeek).isNotEmpty())
            invokeOnAncestor(nodes(hasText(visibleTitle)).first(), SemanticsActions.OnClick, "Click")
            await("Bookmark Detail after tapping the card", hasContentDescription(context.getString(R.string.detail_edit_tags)))
            launched.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("the card at its previous position after returning from Detail", SemanticsMatcher("restored card") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == visibleTitle } == true &&
                    abs(node.boundsInRoot.top - visibleTop) <= 2f
            })
            assertEquals(domain, model.selectedDomainFilter.value)
            launched.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("the Library menu after leaving the selected domain", hasContentDescription(context.getString(R.string.navigation_open)))
            assertNull(model.selectedDomainFilter.value)
        } finally {
            val closeFailure = runCatching { scenario?.close() }.exceptionOrNull()
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val sql = db.openHelper.writableDatabase
                    sql.execSQL("DELETE FROM bookmarks WHERE id LIKE ?", arrayOf("$prefix%"))
                    sql.execSQL("DELETE FROM engagement_events WHERE bookmarkId LIKE ?", arrayOf("$prefix%"))
                } finally {
                    val editor = prefs.edit()
                    savedPrefs.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
                    check(editor.commit()) { "Failed to restore Library preferences" }
                }
            }
            closeFailure?.let { throw it }
        }
    }
}
