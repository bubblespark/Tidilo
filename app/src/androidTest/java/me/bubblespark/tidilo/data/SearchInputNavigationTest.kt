package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.TidiloApp
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.SearchScreen
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SearchInputNavigationTest {
    @Test fun typingAcrossEmptyQueryBoundaryKeepsInputFocused(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        lateinit var vm: LibraryViewModel
        var oldHistory = emptyList<String>()
        try {
            host.main {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                oldHistory = vm.searchHistory.value.filterNot(::isFixtureHistory)
                vm.onSearchQueryChanged("")
                activity.setContent { MaterialTheme { SearchScreen(vm) } }
            }
            host.await(activity) { it.config.getOrNull(SemanticsProperties.EditableText) != null }
            host.main { vm.triggerSearchFocus() }
            for (value in listOf("header typing", "", "another query")) {
                val input = host.await(activity) {
                    it.config.getOrNull(SemanticsProperties.EditableText) != null && it.config.getOrNull(SemanticsProperties.Focused) == true
                }
                host.main { assertTrue(input.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true) }
                delay(350)
                host.await(activity) {
                    it.config.getOrNull(SemanticsProperties.EditableText)?.text == value && it.config.getOrNull(SemanticsProperties.Focused) == true
                }
            }
            host.capture("search-body-input")
        } finally {
            host.main {
                activity.setContent { }
                vm.clearSearchHistory()
                oldHistory.asReversed().forEach(vm::addSearchHistory)
                activity.finish()
            }
        }
    }

    @Test fun historyKeepsBodyInputAndAcceptsReservedLookingQueries(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        lateinit var vm: LibraryViewModel
        var history = emptyList<String>()
        var query = ""
        try {
            host.main {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                history = vm.searchHistory.value.filterNot(::isFixtureHistory)
                query = vm.searchQuery.value
                vm.onSearchQueryChanged("")
                vm.clearSearchHistory()
                (0..8).forEach { vm.addSearchHistory("History $it") }
                vm.addSearchHistory("search_input")
                activity.setContent { MaterialTheme { Box(Modifier.size(360.dp, 350.dp)) { SearchScreen(vm) } } }
            }
            val initial = host.await(activity) { it.config.getOrNull(SemanticsProperties.EditableText) != null }.boundsInRoot.top
            host.await(activity) { host.text(it, "search_input") }
            host.scroll(activity, 1200f)
            delay(300)
            host.main { vm.triggerSearchFocus() }
            val input = host.await(activity) {
                it.config.getOrNull(SemanticsProperties.EditableText) != null && it.config.getOrNull(SemanticsProperties.Focused) == true
            }
            assertEquals(initial, input.boundsInRoot.top, 2f)
        } finally {
            host.main {
                vm.clearSearchHistory()
                history.asReversed().forEach(vm::addSearchHistory)
                vm.onSearchQueryChanged(query)
                activity.finish()
            }
        }
    }

    @Test fun bodyInputScrollsAndSearchActionRestoresFocusInListAndGrid(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val db = AppDatabase.getInstance(host.context)
        val token = "headersearch${UUID.randomUUID()}"
        val ids = (0 until 36).map { "$token-$it" }
        lateinit var vm: LibraryViewModel
        lateinit var oldMode: LibraryViewMode
        var oldQuery = ""
        var oldHistory = emptyList<String>()
        var phase = "setup"
        try {
            ids.forEach { db.bookmarkDao().insertOrUpdate(BookmarkEntity(it, "$token $it", "https://header.invalid/$it", "Summary", createdAt = 1, updatedAt = 1)) }
            host.main {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                oldMode = vm.viewMode.value
                oldQuery = vm.searchQuery.value
                oldHistory = vm.searchHistory.value.filterNot(::isFixtureHistory)
                vm.onSearchQueryChanged(token)
                activity.setContent { MaterialTheme { SearchScreen(vm) } }
            }
            for (mode in LibraryViewMode.entries) {
                phase = "$mode initial input"
                host.main { vm.setViewMode(mode) }
                val input = host.await(activity) { it.config.getOrNull(SemanticsProperties.EditableText) != null }
                val initialTop = input.boundsInRoot.top
                delay(600)
                host.scroll(activity, 1600f)
                delay(600)
                var stillAtTop = false
                host.main {
                    stillAtTop = host.nodes(activity).any { it.config.getOrNull(SemanticsProperties.EditableText) != null && it.boundsInRoot.top == initialTop }
                }
                assertFalse("Search input belongs to the scrolling body ($mode)", stillAtTop)
                host.main { vm.triggerSearchFocus() }
                phase = "$mode focused after scrolling"
                val focused = host.await(activity) {
                    it.config.getOrNull(SemanticsProperties.EditableText) != null && it.config.getOrNull(SemanticsProperties.Focused) == true
                }
                assertEquals(initialTop, focused.boundsInRoot.top, 2f)
                withTimeout(5000) {
                    var imeVisible = false
                    while (!imeVisible) {
                        host.main { imeVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                        if (!imeVisible) delay(30)
                    }
                }
                host.instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                delay(300)
                host.main { vm.triggerSearchFocus() }
                phase = "$mode focused after keyboard dismissal"
                host.await(activity) { it.config.getOrNull(SemanticsProperties.EditableText) != null && it.config.getOrNull(SemanticsProperties.Focused) == true }
                delay(350)
            }
        } catch (error: Throwable) {
            var diagnostic = ""
            host.main {
                diagnostic = host.nodes(activity).filter {
                    it.config.getOrNull(SemanticsProperties.EditableText) != null ||
                        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) != null
                }.joinToString { "${it.boundsInRoot}: ${it.config}" }
            }
            throw AssertionError("Search navigation phase: $phase; query=${vm.searchQuery.value}; nodes=$diagnostic", error)
        } finally {
            host.main {
                activity.setContent { }
                vm.clearSearchHistory()
                oldHistory.asReversed().forEach(vm::addSearchHistory)
                vm.setViewMode(oldMode)
                vm.onSearchQueryChanged(oldQuery)
                activity.finish()
            }
            ids.forEach { db.bookmarkDao().deleteById(it) }
        }
    }

    private fun isFixtureHistory(value: String) = value.matches(Regex("headersearch[0-9a-f-]{36}"))

    @Test fun openingSearchFromButtonRailAndTagSelectionFocusesInput(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        fun click(node: SemanticsNode) {
            var target = node
            while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
            check(target.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        lateinit var vm: LibraryViewModel
        var contentReady = false
        try {
            host.main {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent {
                    MaterialTheme {
                        TidiloApp(libraryViewModel = vm)
                        SideEffect { contentReady = true }
                    }
                }
            }
            withTimeout(3000) { while (!contentReady) delay(20) }

            // 1. Taps the library_search_action button, awaits SearchScreen, and asserts input is focused
            val searchAction = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_search_action" }
            host.main { click(searchAction) }
            val searchInput1 = host.await(activity) {
                it.config.getOrNull(SemanticsProperties.EditableText) != null && it.config.getOrNull(SemanticsProperties.Focused) == true
            }
            assertNotNull(searchInput1)

            // 2. Taps the Tag Selection button, awaits TagSelectionScreen, taps Back button, and asserts input is focused again
            val tagSelectionBtn = host.await(activity) {
                host.description(it, host.context.getString(R.string.search_tag_selection_content_desc))
            }
            host.main { click(tagSelectionBtn) }
            host.await(activity) { host.text(it, host.context.getString(R.string.tag_selection_title)) }
            val backFromTags = host.await(activity) {
                host.description(it, host.context.getString(R.string.tag_selection_back_and_apply))
            }
            host.main { click(backFromTags) }
            val searchInput2 = host.await(activity) {
                it.config.getOrNull(SemanticsProperties.EditableText) != null && it.config.getOrNull(SemanticsProperties.Focused) == true
            }
            assertNotNull(searchInput2)

            // 3. Navigates back to Library, opens the Navigation Rail, taps Search item, and asserts input is focused
            val backToLibrary = host.await(activity) {
                host.description(it, host.context.getString(R.string.common_back))
            }
            host.main { click(backToLibrary) }
            val railOpen = host.await(activity) {
                host.description(it, host.context.getString(R.string.navigation_open))
            }
            host.main { click(railOpen) }
            val railSearch = host.await(activity) {
                host.text(it, host.context.getString(R.string.nav_search))
            }
            host.main { click(railSearch) }
            val searchInput3 = host.await(activity) {
                it.config.getOrNull(SemanticsProperties.EditableText) != null && it.config.getOrNull(SemanticsProperties.Focused) == true
            }
            assertNotNull(searchInput3)
        } finally {
            host.main {
                activity.setContent { }
                activity.finish()
            }
        }
    }
}
