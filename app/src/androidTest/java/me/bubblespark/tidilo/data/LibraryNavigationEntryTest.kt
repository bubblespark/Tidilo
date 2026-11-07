package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
    @Test fun domainDrillDownOffersBackAlongsideLibraryMenu(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val model = ViewModelProvider(activity)[LibraryViewModel::class.java]
        val prefs = host.context.getSharedPreferences("tidilo_library_prefs", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getString("library_filter_mode", null)
        val scopeJob = model.viewModelScope.coroutineContext[Job]
        try {
            host.main {
                model.setFilterMode(me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.DOMAIN)
                model.selectDomainFilter("navigation.invalid")
            }
            val back = host.await(activity) { host.description(it, host.context.getString(R.string.library_back_to_domains)) }
            host.main {
                assertTrue(host.nodes(activity).any { host.description(it, host.context.getString(R.string.navigation_open)) })
                val button = generateSequence(back) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                org.junit.Assert.assertNull(model.selectedDomainFilter.value)
            }
        } finally {
            host.main { activity.viewModelStore.clear(); activity.finish() }
            scopeJob?.cancelAndJoin()
            val restore = prefs.edit()
            if (previous == null) restore.remove("library_filter_mode") else restore.putString("library_filter_mode", previous)
            assertTrue(restore.commit())
            org.junit.Assert.assertEquals(previous, prefs.getString("library_filter_mode", null))
        }
    }

    @Test fun searchHistoryEndsWithoutObsoleteNavigationClearance(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val prefs = host.context.getSharedPreferences("tidilo_library_prefs", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getString("search_history", null)
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var model: LibraryViewModel
        var scopeJob: Job? = null
        try {
            val fixture = org.json.JSONArray((0..9).map { "Spacing fixture $it" }).toString()
            assertTrue(prefs.edit().putString("search_history", fixture).commit())
            host.main {
                model = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[LibraryViewModel::class.java]
                scopeJob = model.viewModelScope.coroutineContext[Job]
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
            host.main { store.clear() }
            scopeJob?.cancelAndJoin()
            val restore = prefs.edit()
            if (previous == null) restore.remove("search_history") else restore.putString("search_history", previous)
            val restored = restore.commit()
            host.main { activity.finish() }
            assertTrue("Search history restoration must complete before teardown", restored)
            org.junit.Assert.assertEquals(previous, prefs.getString("search_history", null))
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
