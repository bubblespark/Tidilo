package me.bubblespark.tidilo.data

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.engagement.EngagementType
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.BookmarkItemCard
import me.bubblespark.tidilo.ui.screens.library.Tag

class BookmarkEngagementUiTest {
    // Empty: the E2E test below launches MainActivity, which a rule-owned activity would otherwise cover.
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun cardHeartTapsDoNotOpenDetail() = ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clicks = AtomicInteger()
        val likes = AtomicInteger()
        val grid = mutableStateOf(false)
        val item = BookmarkItem(id = "heart-test", title = "Card", url = "https://example.com",
            description = "Summary", tag = Tag.createEmpty())
        OfflineVisualCaches().use {
            scenario.onActivity { activity ->
                activity.setContent {
                    TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                        if (grid.value) BookmarkGridCardFixture(item, onClick = { clicks.incrementAndGet() }, onToggleLike = { likes.incrementAndGet() })
                        else BookmarkItemCard(item, onClick = { clicks.incrementAndGet() }, onToggleLike = { likes.incrementAndGet() })
                    }
                }
            }
            for (isGrid in listOf(false, true)) {
                composeRule.runOnUiThread { grid.value = isGrid }
                composeRule.waitForIdle()
                composeRule.onNodeWithContentDescription(context.getString(me.bubblespark.tidilo.R.string.bookmark_not_liked))
                    .performClick()
                composeRule.waitForIdle()
                assertTrue("A heart tap must not open its card", clicks.get() == 0)
                assertTrue("A heart tap must toggle Like", likes.get() == if (isGrid) 2 else 1)
            }
        }
    }

    @Test fun toolbarLikeChangesImmediatelyAndCollectionDoesNotChangeLike(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.getInstance(context)
        val id = UUID.randomUUID().toString()
        val previousBlur = AppConfig.overlayBlurEnabled.value
        val caches = OfflineVisualCaches()
        var activity: ComponentActivity? = null
        suspend fun stored() = withContext(Dispatchers.IO) { requireNotNull(db.bookmarkDao().getBookmarkById(id)) }
        suspend fun detailViews() = withContext(Dispatchers.IO) {
            db.engagementDao().eventsFor(id).count { it.type == EngagementType.DETAIL.code }
        }
        try {
            AppConfig.setOverlayBlurEnabled(false)
            withContext(Dispatchers.IO) {
                db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Toolbar test", "https://example.com/$id", "Summary", createdAt = 1, updatedAt = 1))
            }
            val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
            activity = host
            instrumentation.runOnMainSync {
                val vm = ViewModelProvider(host)[LibraryViewModel::class.java]
                host.setContent { TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { BookmarkDetailScreen(id, vm, {}) } }
            }
            // The rule drives MainActivity's composition on its test clock, so every wait goes through it.
            fun node(description: String) = try {
                composeRule.awaitNode(hasContentDescription(description))
            } catch (e: ComposeTimeoutException) { throw AssertionError("Missing toolbar control: $description", e) }
            fun click(node: SemanticsNode) {
                val action = generateSequence(node) { it.parent }.first { SemanticsActions.OnClick in it.config }
                    .config[SemanticsActions.OnClick].action
                composeRule.runOnUiThread { assertTrue(action?.invoke() == true) }
                composeRule.waitForIdle()
            }
            /** Polls the shared database off the main thread every 100ms, letting the composition settle in between. */
            suspend fun awaitStored(condition: suspend () -> Boolean) = withTimeout(10_000) {
                while (true) {
                    composeRule.waitForIdle()
                    if (condition()) break
                    delay(100)
                }
            }
            assertFalse(stored().isLiked)
            click(node("Choose collections"))
            composeRule.awaitNode(hasText("Choose collections"))
            assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            composeRule.waitForIdle()
            assertFalse(stored().isLiked)
            click(node("Like"))
            awaitStored { stored().isLiked }
            node("Unlike")
            awaitStored { detailViews() > 0 }
            assertTrue(detailViews() == 1)
            instrumentation.runOnMainSync { host.moveTaskToBack(true) }
            withTimeout(10000) { while (host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) delay(100) }
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            node("Unlike")
            // Negative window: a second DETAIL view would be recorded within this time.
            composeRule.waitForIdle()
            delay(1000)
            composeRule.waitForIdle()
            assertTrue("Returning to the same detail entry must not count a second view", detailViews() == 1)
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            withContext(NonCancellable + Dispatchers.IO) { db.bookmarkDao().deleteById(id) }
            AppConfig.setOverlayBlurEnabled(previousBlur)
            caches.close()
        }
    }
}
