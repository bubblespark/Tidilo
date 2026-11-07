package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.TidiloApp
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.SearchScreen
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryNavigationEntryTest {
    @Test fun searchHistoryEndsWithoutObsoleteNavigationClearance(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val model = androidx.lifecycle.ViewModelProvider(activity)[LibraryViewModel::class.java]
        val previous = model.searchHistory.value
        val query = model.searchQuery.value
        try {
            host.main {
                model.onSearchQueryChanged("")
                model.clearSearchHistory()
                (0..9).reversed().forEach { model.addSearchHistory("Spacing fixture $it") }
                activity.setContent { MaterialTheme {
                    Box(Modifier.height(500.dp)) { SearchScreen(model) }
                } }
            }
            host.await(activity) { host.text(it, "Spacing fixture 0") }
            host.scroll(activity, 100000f)
            delay(500)
            val last = host.await(activity) { host.text(it, "Spacing fixture 9") }
            host.main {
                val grid = host.nodes(activity).first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                val gap = (grid.boundsInRoot.bottom - last.boundsInRoot.bottom) / activity.resources.displayMetrics.density
                assertTrue("Search must use safe insets rather than obsolete bar clearance: $gap dp", gap < 100f)
            }
        } finally {
            host.main {
                model.clearSearchHistory()
                previous.reversed().forEach(model::addSearchHistory)
                model.onSearchQueryChanged(query)
                activity.finish()
            }
        }
    }
    @Test fun railUtilitiesReachBottomAndHeaderStaysVisible(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            val menu = host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
            host.main {
                var target = menu
                while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
                target.config[SemanticsActions.OnClick].action?.invoke()
            }
            host.capture("rail-after-tap")
            val settings = host.await(activity) { host.text(it, host.context.getString(R.string.title_settings)) }
            host.main {
                val density = activity.resources.displayMetrics.density
                assertTrue("Settings belongs near the bottom of a tall rail",
                    settings.boundsInRoot.bottom > activity.window.decorView.height - 90f * density)
                assertTrue(host.nodes(activity).any { host.text(it, host.context.getString(R.string.app_name)) })
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun libraryExposesMenuInsteadOfSettings(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
            delay(300)
            host.main {
                assertTrue("Library must have its navigation menu",
                    host.nodes(activity).any { host.description(it, host.context.getString(R.string.navigation_open)) })
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun legacyImportEntryOpensNotificationList(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch("import")
        try {
            delay(700)
            host.main {
                assertTrue("Legacy import entry must open Notification",
                    host.nodes(activity).any { host.text(it, host.context.getString(R.string.settings_notification_title)) })
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun searchExposesBackInsteadOfRootNavigation(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            host.main {
                val model = ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent { MaterialTheme { SearchScreen(model) } }
            }
            host.await(activity) { host.text(it, host.context.getString(R.string.search_header_title)) }
            delay(300)
            host.main {
                assertTrue("Search must expose a Back action as a child of Library",
                    host.nodes(activity).any { host.description(it, host.context.getString(R.string.common_back)) })
            }
        } finally { host.main { activity.finish() } }
    }
}
