package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.RepositoryLibraryStore
import me.bubblespark.tidilo.ui.screens.library.SearchScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SearchInputNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
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
        val prefsName = "search-nav-${UUID.randomUUID()}"
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

    private fun focusedEditableMatcher() = SemanticsMatcher("focused editable text") {
        it.config.getOrNull(SemanticsProperties.EditableText) != null &&
            it.config.getOrNull(SemanticsProperties.Focused) == true
    }

    @Test
    fun typingAcrossEmptyQueryBoundaryKeepsInputFocused() = runComposeUiTest {
        val (db, prefsName, vm) = createInMemoryHarness()
        try {
            setContent {
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    SearchScreen(vm)
                }
            }
            waitUntil(timeoutMillis = 5000) {
                onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            runOnUiThread { vm.triggerSearchFocus() }
            for (value in listOf("header typing", "", "another query")) {
                waitUntil(timeoutMillis = 5000) {
                    onAllNodes(focusedEditableMatcher(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                onNode(focusedEditableMatcher(), useUnmergedTree = true).performTextReplacement(value)
                waitUntil(timeoutMillis = 5000) {
                    onAllNodes(SemanticsMatcher("text=$value focused") {
                        it.config.getOrNull(SemanticsProperties.EditableText)?.text == value &&
                            it.config.getOrNull(SemanticsProperties.Focused) == true
                    }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
            }
        } finally {
            db.close()
            context.deleteSharedPreferences(prefsName)
        }
    }

    @Test
    fun historyKeepsBodyInputAndAcceptsReservedLookingQueries() = runComposeUiTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val prefsName = "search-nav-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val repository = BookmarkRepository(db, autoInitialize = false)
        runBlocking { repository.retryInitializationAndAwait() }
        val store = RepositoryLibraryStore(
            repository,
            CollectionRepository(db),
            DomainPolicyStore.getInstance(context),
            prefs
        )
        runBlocking {
            store.saveSearchHistory(listOf("search_input") + (8 downTo 0).map { "History $it" })
        }
        val vm = LibraryViewModel(store, SavedStateHandle(), MutableStateFlow(true))
        try {
            setContent {
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    Box(Modifier.size(360.dp, 350.dp)) {
                        SearchScreen(vm)
                    }
                }
            }
            waitUntil(timeoutMillis = 5000) {
                onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodes(hasText("search_input"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val initial = onNode(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            val scrollable = SemanticsMatcher("scrollable search body") {
                it.config.getOrNull(SemanticsActions.ScrollBy) != null
            }
            onAllNodes(scrollable, useUnmergedTree = true)[0]
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1200f) }
            waitForIdle()
            runOnUiThread { vm.triggerSearchFocus() }
            waitUntil(timeoutMillis = 5000) {
                onAllNodes(focusedEditableMatcher(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val input = onNode(focusedEditableMatcher(), useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(initial, input.boundsInRoot.top, 2f)
        } finally {
            db.close()
            context.deleteSharedPreferences(prefsName)
        }
    }

    @Test
    fun bodyInputScrollsAndSearchActionRestoresFocusInListAndGrid() = runComposeUiTest {
        val (db, prefsName, vm) = createInMemoryHarness()
        val token = "headersearch${UUID.randomUUID()}"
        val rows = (0 until 24).map {
            BookmarkEntity("$token-$it", "$token $it", "https://header.invalid/$it", "Summary", createdAt = 1, updatedAt = 1)
        }
        var hostView: View? = null
        try {
            runBlocking { db.bookmarkDao().insertAll(rows) }
            vm.onSearchQueryChanged(token)
            setContent {
                hostView = LocalView.current
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    SearchScreen(vm)
                }
            }
            val scrollable = SemanticsMatcher("scrollable search results") {
                it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                    (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
            }
            val resultLoaded = SemanticsMatcher("loaded search result") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("$token ") } == true
            }
            for (mode in LibraryViewMode.entries) {
                runOnUiThread { vm.setViewMode(mode) }
                waitForIdle()
                waitUntil(timeoutMillis = 5000) {
                    onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodes(resultLoaded, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodes(scrollable, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                val initialTop = onNode(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
                onAllNodes(scrollable, useUnmergedTree = true)[0]
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1600f) }
                waitUntil(timeoutMillis = 5000) {
                    onAllNodes(scrollable, useUnmergedTree = true).fetchSemanticsNodes()
                        .any { (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value() ?: 0f) > 0f }
                }
                waitForIdle()
                val stillAtTop = onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                    .fetchSemanticsNodes().any { it.boundsInRoot.top == initialTop }
                assertFalse("Search input belongs to the scrolling body ($mode)", stillAtTop)

                runOnUiThread { vm.triggerSearchFocus() }
                waitUntil(timeoutMillis = 5000) {
                    onAllNodes(focusedEditableMatcher(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                val focused = onNode(focusedEditableMatcher(), useUnmergedTree = true).fetchSemanticsNode()
                assertEquals(
                    "Search input should return to initial position ($mode)",
                    initialTop,
                    focused.boundsInRoot.top,
                    2f
                )

                waitUntil(timeoutMillis = 5000) {
                    val view = hostView ?: return@waitUntil false
                    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                waitUntil(timeoutMillis = 5000) {
                    val view = hostView ?: return@waitUntil false
                    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false
                }
                runOnUiThread { vm.triggerSearchFocus() }
                waitUntil(timeoutMillis = 5000) {
                    onAllNodes(focusedEditableMatcher(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                waitUntil(timeoutMillis = 5000) {
                    val view = hostView ?: return@waitUntil false
                    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                waitUntil(timeoutMillis = 5000) {
                    val view = hostView ?: return@waitUntil false
                    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false
                }
                runOnUiThread { hostView?.clearFocus() }
                waitForIdle()
            }
        } finally {
            db.close()
            context.deleteSharedPreferences(prefsName)
        }
    }

    private fun collectNodes(activity: ComponentActivity): List<SemanticsNode> {
        val roots = mutableListOf<ViewRootForTest>()
        fun findRoots(view: View) {
            when (view) {
                is ViewRootForTest -> roots.add(view)
                is ViewGroup -> for (i in 0 until view.childCount) findRoots(view.getChildAt(i))
            }
        }
        WindowInspector.getGlobalWindowViews()
            .ifEmpty { listOf(activity.window.decorView) }
            .forEach(::findRoots)
        val out = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            out.add(node)
            node.children.forEach(::visit)
        }
        roots.distinct().forEach { root ->
            runCatching { visit(root.semanticsOwner.unmergedRootSemanticsNode) }
        }
        return out
    }

    private suspend fun awaitSemantics(activity: ComponentActivity, timeoutMs: Long = 8000, predicate: (SemanticsNode) -> Boolean): SemanticsNode =
        withTimeout(timeoutMs) {
            var found: SemanticsNode? = null
            while (found == null) {
                instrumentation.runOnMainSync { found = collectNodes(activity).firstOrNull(predicate) }
                if (found == null) delay(100)
            }
            requireNotNull(found)
        }

    private fun clickOwner(node: SemanticsNode) {
        instrumentation.runOnMainSync {
            val target = generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            check(target.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }

    private fun hasDesc(node: SemanticsNode, desc: String) =
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(desc) == true

    private fun hasExactText(node: SemanticsNode, value: String) =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true

    @Test
    fun openingSearchFromButtonRailAndTagSelectionFocusesInput(): Unit = runBlocking {
        runOpeningSearchFromButtonRailAndTagSelectionE2e()
    }

    @Test
    fun persistedSearchHistorySurvivesButtonRailAndDetailE2e(): Unit = runBlocking {
        val libraryPrefs = context.getSharedPreferences(LIBRARY_PREFS_NAME, Context.MODE_PRIVATE)
        val hadSavedHistory = libraryPrefs.contains(SEARCH_HISTORY_KEY)
        val savedHistoryJson = libraryPrefs.getString(SEARCH_HISTORY_KEY, null)
        val expectedHistory = listOf("persisted alpha", "persisted beta")

        try {
            libraryPrefs.edit()
                .putString(SEARCH_HISTORY_KEY, JSONArray(expectedHistory).toString())
                .commit()

            runOpeningSearchFromButtonRailAndTagSelectionE2e()

            val persistedJson = libraryPrefs.getString(SEARCH_HISTORY_KEY, null)
            val persisted = persistedJson?.let { raw ->
                val array = JSONArray(raw)
                List(array.length()) { array.getString(it) }
            }.orEmpty()
            assertEquals("E2E test must preserve persisted search history", expectedHistory, persisted)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                libraryPrefs.edit().apply {
                    if (hadSavedHistory) putString(SEARCH_HISTORY_KEY, savedHistoryJson) else remove(SEARCH_HISTORY_KEY)
                }.commit()
            }
        }
    }

    private suspend fun runOpeningSearchFromButtonRailAndTagSelectionE2e() {
        val oldBlur = AppConfig.overlayBlurEnabled.value
        val libraryPrefs = context.getSharedPreferences(LIBRARY_PREFS_NAME, Context.MODE_PRIVATE)
        val hadSavedHistory = libraryPrefs.contains(SEARCH_HISTORY_KEY)
        val savedHistoryJson = libraryPrefs.getString(SEARCH_HISTORY_KEY, null)
        val db = AppDatabase.getInstance(context)
        val prefix = "search-detail-${UUID.randomUUID()}"
        // Rows left by an aborted earlier run must not share these titles.
        val roundtrip = "Detail Roundtrip ${prefix.takeLast(8)}"
        val fixtureRows = (0..15).map { index ->
            BookmarkEntity(
                id = "$prefix-$index",
                title = "$roundtrip $index",
                url = "https://roundtrip.invalid/$index",
                description = "Summary",
                createdAt = System.currentTimeMillis() + index,
                updatedAt = 1
            )
        }
        lateinit var vm: LibraryViewModel
        var vmReady = false
        var oldMode: LibraryViewMode? = null
        var oldQuery = ""

        try {
            AppConfig.setOverlayBlurEnabled(false)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                scenario.onActivity {
                    activity = it
                    vm = ViewModelProvider(it)[LibraryViewModel::class.java]
                    vmReady = true
                    oldMode = vm.viewMode.value
                    oldQuery = vm.searchQuery.value
                    vm.setViewMode(LibraryViewMode.LIST)
                    vm.onSearchQueryChanged("")
                }
                var phase = "init"
                try {
                    // 1. Floating toolbar -> Search
                    phase = "toolbar search"
                    val toolbarSearch = awaitSemantics(activity) {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "floating_nav_search"
                    }
                    clickOwner(toolbarSearch)
                    phase = "search focus 1"
                    awaitSemantics(activity) {
                        it.config.getOrNull(SemanticsProperties.EditableText) != null &&
                            it.config.getOrNull(SemanticsProperties.Focused) == true
                    }

                    // 2. Tag Selection round-trip
                    phase = "tag selection"
                    val tagSelectionDesc = activity.getString(R.string.search_tag_selection_content_desc)
                    val tagSelectionTitle = activity.getString(R.string.tag_selection_title)
                    val tagBackDesc = activity.getString(R.string.tag_selection_back_and_apply)
                    clickOwner(awaitSemantics(activity) { hasDesc(it, tagSelectionDesc) })
                    awaitSemantics(activity) { hasExactText(it, tagSelectionTitle) }
                    clickOwner(awaitSemantics(activity) { hasDesc(it, tagBackDesc) })
                    phase = "search focus 2"
                    awaitSemantics(activity) {
                        it.config.getOrNull(SemanticsProperties.EditableText) != null &&
                            it.config.getOrNull(SemanticsProperties.Focused) == true
                    }

                    // 3. Back to Discover -> toolbar Search again
                    phase = "back to discover"
                    val backDesc = activity.getString(R.string.common_back)
                    clickOwner(awaitSemantics(activity) { hasDesc(it, backDesc) })
                    phase = "toolbar search again"
                    clickOwner(awaitSemantics(activity) {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "floating_nav_search" && it.boundsInWindow.height > 0
                    })
                    phase = "search focus 3"
                    awaitSemantics(activity) {
                        it.config.getOrNull(SemanticsProperties.EditableText) != null &&
                            it.config.getOrNull(SemanticsProperties.Focused) == true
                    }

                    // 4. Detail round-trip
                    phase = "detail fixtures"
                    db.bookmarkDao().insertAll(fixtureRows)
                    instrumentation.runOnMainSync { vm.onSearchQueryChanged(roundtrip) }
                    awaitSemantics(activity) { hasExactText(it, "$roundtrip 15") }
                    phase = "scroll"
                    val scrollNode = awaitSemantics(activity) {
                        it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                            (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
                    }
                    instrumentation.runOnMainSync {
                        check(scrollNode.config[SemanticsActions.ScrollBy].action?.invoke(0f, 600f) == true)
                    }
                    withTimeout(5000) {
                        while (true) {
                            var scrolled = false
                            instrumentation.runOnMainSync {
                                scrolled = (collectNodes(activity).firstOrNull {
                                    it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                                        (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value() ?: 0f) > 0f
                                } != null)
                            }
                            if (scrolled) break
                            delay(100)
                        }
                    }
                    instrumentation.waitForIdleSync()

                    phase = "open detail"
                    var visibleTitle = ""
                    var visibleTop = 0f
                    lateinit var targetNode: SemanticsNode
                    instrumentation.runOnMainSync {
                        val first = collectNodes(activity).filter { node ->
                            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(roundtrip) } == true &&
                                node.boundsInRoot.top > 0f && node.boundsInRoot.bottom < activity.window.decorView.height
                        }.minBy { it.boundsInRoot.top }
                        visibleTitle = first.config[SemanticsProperties.Text].single().text
                        visibleTop = first.boundsInRoot.top
                        targetNode = first
                    }
                    clickOwner(targetNode)
                    val detailEditDesc = activity.getString(R.string.detail_edit_tags)
                    awaitSemantics(activity) { hasDesc(it, detailEditDesc) }

                    phase = "detail return"
                    instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
                    withTimeout(5000) {
                        while (true) {
                            var detailStillVisible = true
                            instrumentation.runOnMainSync {
                                detailStillVisible = collectNodes(activity).any { hasDesc(it, detailEditDesc) }
                            }
                            if (!detailStillVisible) break
                            delay(100)
                        }
                    }
                    val restored = awaitSemantics(activity) {
                        hasExactText(it, visibleTitle) && it.boundsInRoot.top > 0f && it.boundsInRoot.height > 0f
                    }
                    assertEquals("Back from Detail must restore the same bookmark position", visibleTop, restored.boundsInRoot.top, 2f)

                    phase = "detail focus check"
                    var inputAfterDetail: SemanticsNode? = null
                    var imeVisible = false
                    instrumentation.runOnMainSync {
                        inputAfterDetail = collectNodes(activity).firstOrNull {
                            it.config.getOrNull(SemanticsProperties.EditableText) != null
                        }
                        imeVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    assertNotNull(inputAfterDetail)
                    assertFalse(
                        "Search input must not be focused when returning from Bookmark Detail",
                        inputAfterDetail?.config?.getOrNull(SemanticsProperties.Focused) == true
                    )
                    assertFalse("IME must remain hidden when returning from Bookmark Detail", imeVisible)
                } catch (error: Throwable) {
                    throw AssertionError("Search route phase: $phase", error)
                } finally {
                    if (vmReady) {
                        instrumentation.runOnMainSync {
                            oldMode?.let(vm::setViewMode)
                            vm.onSearchQueryChanged(oldQuery)
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM bookmarks WHERE id LIKE ?", arrayOf("$prefix%"))
                libraryPrefs.edit().apply {
                    if (hadSavedHistory) putString(SEARCH_HISTORY_KEY, savedHistoryJson) else remove(SEARCH_HISTORY_KEY)
                }.commit()
            }
            AppConfig.setOverlayBlurEnabled(oldBlur)
        }
    }

    companion object {
        private const val LIBRARY_PREFS_NAME = "tidilo_library_prefs"
        private const val SEARCH_HISTORY_KEY = "search_history"
    }
}
