package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.TidiloApp
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.data.engagement.EngagementType
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The app shell (`TidiloApp`) with the Discover preview flag: floating toolbar, layers, back, and the trimmed rail. */
class DiscoverNavigationShellTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val db = AppDatabase.getInstance(context)

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun await(matcher: SemanticsMatcher): SemanticsNode {
        composeRule.waitUntil(8_000) { nodes(matcher).isNotEmpty() }
        return nodes(matcher).first()
    }

    private fun awaitAbsent(matcher: SemanticsMatcher) = composeRule.waitUntil(8_000) { nodes(matcher).isEmpty() }

    private fun tag(value: String) = hasTestTag(value)
    private fun text(id: Int) = hasText(context.getString(id))
    private fun description(id: Int) = hasContentDescription(context.getString(id))
    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    private fun click(node: SemanticsNode) {
        var target = node
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
        composeRule.runOnUiThread { check(target.config[SemanticsActions.OnClick].action?.invoke() == true) }
        composeRule.waitForIdle()
    }

    private fun pressSystemBack() {
        check(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        composeRule.waitForIdle()
    }

    private fun scroll(pixels: Float) {
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
            .onFirst()
            .performSemanticsAction(SemanticsActions.ScrollBy) { check(it(0f, pixels)) }
        composeRule.waitForIdle()
    }

    private fun launchShell(setupModel: (LibraryViewModel) -> Unit = {}): LibraryViewModel {
        lateinit var model: LibraryViewModel
        composeRule.runOnUiThread {
            // MainActivity draws edge to edge; the shell's toolbar insets depend on it.
            composeRule.activity.enableEdgeToEdge()
            model = ViewModelProvider(composeRule.activity)[LibraryViewModel::class.java]
            setupModel(model)
        }
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                TidiloApp(libraryViewModel = model)
            }
        }
        return model
    }

    /** Runs [block] with offline visual caches, then removes rows under its id prefix and restores Library prefs. */
    private fun shellTest(block: suspend (prefix: String) -> Unit): Unit = runBlocking {
        val prefix = "shell-${UUID.randomUUID()}-"
        val prefs = context.getSharedPreferences("tidilo_library_prefs", Context.MODE_PRIVATE)
        val savedPrefs = PREF_KEYS.associateWith { prefs.getString(it, null) }
        val caches = OfflineVisualCaches()
        try {
            block(prefix)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val sql = db.openHelper.writableDatabase
                    sql.execSQL("DELETE FROM bookmarks WHERE id LIKE ?", arrayOf("$prefix%"))
                    sql.execSQL("DELETE FROM engagement_events WHERE bookmarkId LIKE ?", arrayOf("$prefix%"))
                } finally {
                    val editor = prefs.edit()
                    savedPrefs.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
                    editor.commit()
                    caches.close()
                }
            }
        }
    }

    private suspend fun seed(vararg bookmarks: BookmarkEntity) =
        withContext(Dispatchers.IO) { db.bookmarkDao().insertAll(bookmarks.toList()) }

    private suspend fun detailEvents(id: String) = withContext(Dispatchers.IO) {
        db.engagementDao().eventsFor(id).filter { it.type == EngagementType.DETAIL.code }
    }

    @Test
    fun openingDetailFromDiscoverRecordsOneDetailEventWithTheShelf() = shellTest { prefix ->
        val id = "${prefix}detail"
        seed(BookmarkEntity(id = id, title = "Detail Fixture", url = "https://detail.invalid/$id", description = "",
            createdAt = System.currentTimeMillis(), updatedAt = 1L))
        launchShell()
        click(await(tag("discover_card_$id")))
        awaitAbsent(tag("discover_list"))
        withTimeout(8_000) { while (detailEvents(id).isEmpty()) delay(100) }
        // A second, duplicate write would land within this window.
        repeat(5) {
            delay(100)
            assertEquals(1, detailEvents(id).size)
        }
        assertEquals(listOf(EngagementSurface.DISCOVER_JUST_SAVED.code), detailEvents(id).map { it.surface })
    }

    @Test
    fun backFromDetailReturnsToTheSameDiscoverCard() = shellTest { prefix ->
        val id = "${prefix}return"
        seed(BookmarkEntity(id = id, title = "Return Fixture", url = "https://detail.invalid/$id", description = "",
            createdAt = System.currentTimeMillis(), updatedAt = 1L))
        launchShell()
        click(await(tag("discover_card_$id")))
        awaitAbsent(tag("discover_list"))
        pressSystemBack()
        await(tag("discover_card_$id"))
        awaitAbsent(description(R.string.common_back))
    }

    @Test
    fun startsOnDiscoverWithToolbarAndFab() = shellTest {
        launchShell()
        await(tag("discover_header"))
        await(tag("floating_nav_discover") and selected)
        await(tag("floating_nav_save"))
    }

    @Test
    fun toolbarSwitchesLayersAndBackReturnsToDiscover() = shellTest {
        launchShell()
        await(tag("discover_header"))

        click(await(tag("floating_nav_library")))
        await(text(R.string.library_header_title))
        await(tag("floating_nav_library") and selected)

        pressSystemBack()
        await(tag("discover_header"))
        await(tag("floating_nav_discover") and selected)

        click(await(tag("floating_nav_search")))
        await(text(R.string.search_header_title))

        click(await(description(R.string.common_back)))
        await(tag("discover_header"))
        await(tag("floating_nav_discover") and selected)
    }

    @Test
    fun libraryDrillDownBackClearsDrillDownFirst() = shellTest { prefix ->
        val id = "${prefix}drilldown"
        val domain = "drilldown.invalid"
        seed(BookmarkEntity(id = id, title = "Drilldown Fixture", url = "https://$domain/$id", description = "Summary",
            createdAt = System.currentTimeMillis(), updatedAt = 1L))
        val model = launchShell()
        click(await(tag("floating_nav_library")))
        await(text(R.string.library_header_title))

        composeRule.runOnIdle {
            model.setFilterMode(LibraryFilterMode.DOMAIN)
            model.selectDomainFilter(domain)
        }
        await(hasText(domain))

        // First back clears the domain drill-down and stays on Library.
        pressSystemBack()
        composeRule.waitUntil(5_000) { model.selectedDomainFilter.value == null }
        await(tag("floating_nav_library") and selected)

        // Second back returns to Discover.
        pressSystemBack()
        await(tag("discover_header"))
        await(tag("floating_nav_discover") and selected)
    }

    @Test
    fun fabOpensManualImportFromDiscoverAndLibrary() = shellTest {
        launchShell()
        await(tag("discover_header"))
        click(await(tag("floating_nav_save")))
        await(text(R.string.import_dialog_title))
        click(await(text(R.string.common_cancel)))
        awaitAbsent(text(R.string.import_dialog_title))

        click(await(tag("floating_nav_library")))
        await(text(R.string.library_header_title))
        click(await(tag("floating_nav_save")))
        await(text(R.string.import_dialog_title))
        click(await(text(R.string.common_cancel)))
    }

    @Test
    fun railFromDiscoverIsTrimmed() = shellTest {
        launchShell()
        await(tag("discover_header"))
        click(await(description(R.string.navigation_open)))

        await(text(R.string.trash_title))
        await(text(R.string.title_settings))
        await(description(R.string.settings_notification_title))

        assertTrue("Trimmed rail must omit Search", nodes(text(R.string.nav_search)).isEmpty())
    }

    @Test
    fun scrollHidesAndRestoresToolbar() = shellTest { prefix ->
        val now = System.currentTimeMillis()
        seed(*Array(16) { index ->
            BookmarkEntity(id = "$prefix$index", title = "Scroll Fixture $index", url = "https://scroll.invalid/$prefix$index",
                description = "Summary $index", createdAt = now + index, updatedAt = 1L)
        })
        launchShell { it.setFilterMode(LibraryFilterMode.NEWEST_FIRST) }
        click(await(tag("floating_nav_library")))
        // Discover's "Just saved" also lists the fixtures, so wait until Discover has left first.
        awaitAbsent(tag("discover_list"))
        await(SemanticsMatcher("scroll fixture title") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Scroll Fixture") } == true
        })
        await(tag("floating_nav"))

        scroll(600f)
        awaitAbsent(tag("floating_nav"))

        scroll(-400f)
        await(tag("floating_nav"))
    }

    @Test
    fun toolbarClearsSystemBarsAndLastItem() = shellTest {
        launchShell()
        val toolbar = await(tag("floating_nav"))
        var rootHeight = 0
        var navBarBottom = 0
        var density = 1f
        composeRule.runOnIdle {
            val decor = composeRule.activity.window.decorView
            rootHeight = decor.height
            navBarBottom = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            density = composeRule.activity.resources.displayMetrics.density
        }
        val expectedMaxBottom = rootHeight - navBarBottom - 16f * density + 2f
        assertTrue(
            "Toolbar bottom (${toolbar.boundsInRoot.bottom}) must clear nav bar ($navBarBottom) + 16dp (max=$expectedMaxBottom)",
            toolbar.boundsInRoot.bottom <= expectedMaxBottom
        )
        assertTrue("FloatingNavigationClearance must be >= 96dp", HeaderDefaults.FloatingNavigationClearance.value >= 96f)
    }

    @Test
    fun detailHidesToolbar() = shellTest { prefix ->
        val id = "${prefix}toolbar"
        val title = "Detail Toolbar Fixture"
        seed(BookmarkEntity(id = id, title = title, url = "https://detail.invalid/$id", description = "Summary",
            createdAt = System.currentTimeMillis() + 50_000L, updatedAt = 1L))
        launchShell { it.setFilterMode(LibraryFilterMode.NEWEST_FIRST) }
        click(await(tag("floating_nav_library")))
        // Discover's "Just saved" also lists the fixture, so wait until Discover has left first.
        awaitAbsent(tag("discover_list"))
        click(await(hasText(title)))

        awaitAbsent(tag("floating_nav"))

        click(await(description(R.string.common_back)))
        await(tag("floating_nav"))
    }

    private companion object {
        val PREF_KEYS = listOf("library_filter_mode", "library_view_mode")
    }
}
