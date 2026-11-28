package me.bubblespark.tidilo.data

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
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
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** One real portrait → landscape → portrait round trip keeps the leading grid bookmark. */
class AdaptiveBookmarkGridRotationTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rotationKeepsTheLeadingBookmark(): Unit = runBlocking {
        val prefix = "rotation-${UUID.randomUUID()}-"
        val now = System.currentTimeMillis()
        val bookmarks = List(24) { index ->
            BookmarkEntity("$prefix$index", "$TITLE_PREFIX$index", "https://rotation.invalid/$prefix$index", "Summary",
                createdAt = now + 60_000L - index, updatedAt = now)
        }
        val db = AppDatabase.getInstance(context)
        val prefs = context.getSharedPreferences("tidilo_library_prefs", Context.MODE_PRIVATE)
        val savedPrefs = PREF_KEYS.associateWith { prefs.getString(it, null) }
        val oldBlur = AppConfig.overlayBlurEnabled.value
        val caches = OfflineVisualCaches()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            AppConfig.setOverlayBlurEnabled(false)
            withContext(Dispatchers.IO) { db.bookmarkDao().insertAll(bookmarks) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                ViewModelProvider(it)[LibraryViewModel::class.java].apply {
                    setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                    setViewMode(LibraryViewMode.GRID)
                }
            }
            // The app starts on Discover; the toolbar opens the Library layer.
            composeRule.waitUntil(8_000) { nodes(hasTestTag("floating_nav_library")).isNotEmpty() }
            composeRule.onNode(hasTestTag("floating_nav_library"), useUnmergedTree = true).performClick()
            var bodyTop = awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            awaitTitle(0)
            val startRow = awaitTopRow("initial portrait row", bodyTop) { 0 in it }
            val bookmark = 8
            // Newest first leads with its full-span scope row, so bookmark N is lazy item N + 1.
            scrollToIndex(bookmark + 1)
            awaitTitle(bookmark)
            // Move close to the bottom of the taller portrait row before rotating.
            scrollBy(cardFor(titleNode(bookmark)).size.height * 0.9f)

            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            bodyTop = awaitOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
            val landscapeRow = awaitTopRow("landscape anchor $bookmark", bodyTop) { bookmark in it }
            assertTrue("Rotation must change the leading row width", landscapeRow.size > startRow.size)

            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            bodyTop = awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            awaitTopRow("portrait anchor ${landscapeRow.first()}", bodyTop) { landscapeRow.first() in it }
        } finally {
            scenario?.close()
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    db.openHelper.writableDatabase.execSQL("DELETE FROM bookmarks WHERE id LIKE ?", arrayOf("$prefix%"))
                } finally {
                    val editor = prefs.edit()
                    savedPrefs.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
                    editor.commit()
                    AppConfig.setOverlayBlurEnabled(oldBlur)
                    caches.close()
                }
            }
        }
    }

    /** Waits for [orientation], then returns the top of the card area below the header, in window pixels. */
    private fun awaitOrientation(scenario: ActivityScenario<MainActivity>, orientation: Int): Float {
        try {
            composeRule.waitUntil(8_000) { context.resources.configuration.orientation == orientation }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Expected orientation $orientation, observed ${context.resources.configuration.orientation}", timeout)
        }
        composeRule.waitForIdle()
        var bodyTop = 0f
        scenario.onActivity { activity ->
            val topInset = activity.window.decorView.rootWindowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            ).top
            val clearance = HeaderDefaults.ContentHeight + HeaderDefaults.FadeHeight + HeaderDefaults.ClearanceGap
            bodyTop = topInset + clearance.value * activity.resources.displayMetrics.density
        }
        return bodyTop
    }

    private fun awaitTitle(index: Int) {
        try {
            composeRule.waitUntil(8_000) { nodes(hasText("$TITLE_PREFIX$index")).any { it.boundsInRoot.height > 0 } }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Bookmark $index did not become visible", timeout)
        }
    }

    private fun awaitTopRow(stage: String, bodyTop: Float, predicate: (List<Int>) -> Boolean): List<Int> {
        var row = emptyList<Int>()
        try {
            composeRule.waitUntil(8_000) {
                row = topRow(bodyTop)
                row.isNotEmpty() && predicate(row)
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$stage did not settle. Last top row was $row", timeout)
        }
        return row
    }

    private fun topRow(bodyTop: Float): List<Int> {
        val visibleCards = nodes(ROTATION_TITLE).mapNotNull { title ->
            val index = title.config[SemanticsProperties.Text].firstNotNullOfOrNull {
                it.text.removePrefix(TITLE_PREFIX).toIntOrNull()
            } ?: return@mapNotNull null
            val bounds = cardFor(title).boundsInRoot
            if (bounds.height > 0 && bounds.top >= bodyTop - 2f) index to bounds.top else null
        }
        val rowTop = visibleCards.minOfOrNull { it.second } ?: return emptyList()
        return visibleCards.filter { abs(it.second - rowTop) < 2 }.map { it.first }.sorted()
    }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun titleNode(index: Int): SemanticsNode = nodes(hasText("$TITLE_PREFIX$index")).first()

    private fun cardFor(title: SemanticsNode): SemanticsNode =
        generateSequence(title) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }

    private fun scrollToIndex(index: Int) {
        composeRule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(index)
        composeRule.waitForIdle()
    }

    private fun scrollBy(pixels: Float) {
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
            .onFirst()
            .performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, pixels)) }
        composeRule.waitForIdle()
    }

    private companion object {
        const val TITLE_PREFIX = "Rotation card "
        val PREF_KEYS = listOf("library_filter_mode", "library_view_mode")
        val ROTATION_TITLE = SemanticsMatcher("rotation card title") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(TITLE_PREFIX) } == true
        }
    }
}
