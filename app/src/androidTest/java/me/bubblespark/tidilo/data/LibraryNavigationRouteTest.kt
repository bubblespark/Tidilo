package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
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

    /** Runs [block] with blur and visual caches pinned for `MainActivity`, restoring them afterwards. */
    private fun e2e(block: suspend () -> Unit): Unit = runBlocking {
        val oldBlur = AppConfig.overlayBlurEnabled.value
        val caches = OfflineVisualCaches()
        try {
            AppConfig.setOverlayBlurEnabled(false)
            block()
        } finally {
            AppConfig.setOverlayBlurEnabled(oldBlur)
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
        val peekSheet = hasTestTag("discover_peek_sheet")
        fun cardTag(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.TestTag)
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
            // The app starts on Discover; the toolbar opens the Library layer with the selected domain.
            invokeOnAncestor(await("the Library toolbar item", hasTestTag("floating_nav_library")), SemanticsActions.OnClick, "Click")
            await("bookmark cards in the selected domain", hasTestTag("library_card_${prefix}19"))
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))[0]
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 500f) }
            composeRule.waitForIdle()
            val visible = nodes(SemanticsMatcher("group card") { node ->
                cardTag(node)?.startsWith("library_card_$prefix") == true
            }).filter { it.boundsInRoot.top > 0f && it.boundsInRoot.bottom < windowHeight }.minBy { it.boundsInRoot.top }
            val visibleCard = checkNotNull(cardTag(visible))
            val visibleTop = visible.boundsInRoot.top
            invokeOnAncestor(visible, SemanticsActions.OnLongClick, "LongClick")
            await("the Peek sheet after long-pressing the card", peekSheet)
            // System Back reaches the sheet's own window first.
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            composeRule.waitUntil(8_000) { nodes(peekSheet).isEmpty() }
            assertEquals("Back must dismiss Peek before leaving the group", domain, model.selectedDomainFilter.value)
            invokeOnAncestor(nodes(hasTestTag(visibleCard)).first(), SemanticsActions.OnClick, "Click")
            await("Bookmark Detail after tapping the card", hasContentDescription(context.getString(R.string.detail_edit_tags)))
            launched.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("the card at its previous position after returning from Detail", SemanticsMatcher("restored card") { node ->
                cardTag(node) == visibleCard && abs(node.boundsInRoot.top - visibleTop) <= 2f
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
