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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
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
    @Test fun cleanupAttemptsEveryStepAndPreservesPrimaryFailure(): Unit = runBlocking {
        val primary = AssertionError("Navigation assertion failed")
        val teardown = IllegalStateException("Activity teardown failed")
        val deletion = IllegalStateException("First row deletion failed")
        val attempts = mutableListOf<String>()
        cleanupNavigationTest(primary,
            { attempts += "activity"; throw teardown },
            { attempts += "scope"; delay(1) },
            { attempts += "first row"; throw deletion },
            { attempts += "second row" },
            { attempts += "preferences" })
        org.junit.Assert.assertEquals(listOf("activity", "scope", "first row", "second row", "preferences"), attempts)
        org.junit.Assert.assertArrayEquals(arrayOf(teardown, deletion), primary.suppressed)
    }

    @Test fun cleanupReportsFailuresAfterAttemptingRemainingSteps(): Unit = runBlocking {
        val first = IllegalStateException("First cleanup failed")
        val second = IllegalStateException("Second cleanup failed")
        var restored = false
        val failure = runCatching {
            cleanupNavigationTest(null, { throw first }, { throw second }, { restored = true })
        }.exceptionOrNull()
        assertTrue("Preference restoration must still be attempted", restored)
        org.junit.Assert.assertSame(first, failure)
        org.junit.Assert.assertArrayEquals(arrayOf(second), first.suppressed)
    }

    private suspend fun cleanupNavigationTest(primaryFailure: Throwable?, vararg steps: suspend () -> Unit) {
        var failure = primaryFailure
        withContext(NonCancellable) {
            steps.forEach { step ->
                try {
                    step()
                } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error
                    else if (previous !== error) previous.addSuppressed(error)
                }
            }
        }
        if (primaryFailure == null) failure?.let { throw it }
    }

    @Test fun domainDrillDownReplacesMenuWithUp(): Unit = runBlocking {
        verifyGroupNavigation(me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.DOMAIN)
    }

    @Test fun emptyRssDrillDownReplacesMenuWithUp(): Unit = runBlocking {
        verifyGroupNavigation(me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.RSS)
    }

    @Test fun emptyCollectionDrillDownReplacesMenuWithUp(): Unit = runBlocking {
        verifyGroupNavigation(me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.COLLECTION)
    }

    @Test fun groupNavigationSurvivesPeekAndDetailReturn(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val model = ViewModelProvider(activity)[LibraryViewModel::class.java]
        val prefs = host.context.getSharedPreferences("tidilo_library_prefs", android.content.Context.MODE_PRIVATE)
        val snapshot = listOf("library_filter_mode", "library_view_mode").associateWith { prefs.getString(it, null) }
        val scopeJob = model.viewModelScope.coroutineContext[Job]
        val dao = me.bubblespark.tidilo.data.db.AppDatabase.getInstance(host.context).bookmarkDao()
        val prefix = java.util.UUID.randomUUID().toString()
        val domain = "$prefix.invalid"
        val ids = (0..19).map { "$prefix-$it" }
        var primaryFailure: Throwable? = null
        try {
            ids.forEachIndexed { index, id -> dao.insertOrUpdate(me.bubblespark.tidilo.data.db.BookmarkEntity(
                id, "Group fixture $index", "https://$domain/$index", "Summary fixture $index",
                createdAt = index.toLong(), updatedAt = 1)) }
            host.main {
                model.setViewMode(me.bubblespark.tidilo.ui.screens.library.LibraryViewMode.GRID)
                model.setFilterMode(me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.DOMAIN)
                model.selectDomainFilter(domain)
            }
            host.await(activity) { host.text(it, "Group fixture 19") }
            delay(350)
            host.capture("navigation-domain-up")
            host.scroll(activity, 500f)
            delay(400)
            var visibleTitle = ""
            var visibleTop = 0f
            host.main {
                val visible = host.nodes(activity).filter {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Group fixture") } == true &&
                        it.boundsInRoot.top > 0f && it.boundsInRoot.bottom < activity.window.decorView.height
                }.minBy { it.boundsInRoot.top }
                visibleTitle = visible.config[SemanticsProperties.Text].single().text
                visibleTop = visible.boundsInRoot.top
                val action = checkNotNull(generateSequence(visible) { it.parent }
                    .mapNotNull { it.config.getOrNull(SemanticsActions.OnLongClick)?.action }.firstOrNull()) {
                    "The summary card must expose an invokable LongClick action"
                }
                assertTrue(action.invoke())
            }
            host.await(activity) { host.text(it, host.context.getString(R.string.detail_action_ai_summary)) }
            host.main { activity.onBackPressedDispatcher.onBackPressed() }
            delay(300)
            org.junit.Assert.assertEquals("Back must dismiss Peek before leaving the group", domain, model.selectedDomainFilter.value)
            host.main {
                org.junit.Assert.assertFalse(host.nodes(activity).any { host.text(it, host.context.getString(R.string.detail_action_ai_summary)) })
                val visible = host.nodes(activity).first { host.text(it, visibleTitle) }
                val action = checkNotNull(generateSequence(visible) { it.parent }
                    .mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.firstOrNull()) {
                    "The bookmark card must expose an invokable Click action to open Detail"
                }
                assertTrue(action.invoke())
            }
            host.await(activity) { host.description(it, host.context.getString(R.string.detail_save)) }
            delay(350)
            host.main { activity.onBackPressedDispatcher.onBackPressed() }
            host.await(activity) {
                host.text(it, visibleTitle) && kotlin.math.abs(it.boundsInRoot.top - visibleTop) <= 2f
            }
            host.main {
                org.junit.Assert.assertEquals(domain, model.selectedDomainFilter.value)
                val restored = host.nodes(activity).first { host.text(it, visibleTitle) }
                org.junit.Assert.assertEquals("Detail Back must retain the group scroll position", visibleTop, restored.boundsInRoot.top, 2f)
                activity.onBackPressedDispatcher.onBackPressed()
            }
            host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
            org.junit.Assert.assertNull(model.selectedDomainFilter.value)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            cleanupNavigationTest(primaryFailure,
                { host.main { activity.viewModelStore.clear() } },
                { host.main { activity.finish() } },
                { scopeJob?.cancelAndJoin() },
                *ids.map { id -> suspend { dao.deleteById(id) } }.toTypedArray(),
                {
                    val restore = prefs.edit()
                    snapshot.forEach { (key, value) -> if (value == null) restore.remove(key) else restore.putString(key, value) }
                    assertTrue("Group navigation preferences must be restored", restore.commit())
                })
        }
    }
    private suspend fun verifyGroupNavigation(mode: me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode) {
        val host = HeaderTestHost()
        val activity = host.launch()
        val model = ViewModelProvider(activity)[LibraryViewModel::class.java]
        val prefs = host.context.getSharedPreferences("tidilo_library_prefs", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getString("library_filter_mode", null)
        val scopeJob = model.viewModelScope.coroutineContext[Job]
        val id = java.util.UUID.randomUUID().toString()
        val collectionDao = me.bubblespark.tidilo.data.db.AppDatabase.getInstance(host.context).collectionDao()
        val backLabel = host.context.getString(when (mode) {
            me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.COLLECTION -> R.string.collection_back_to_collections
            me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.RSS -> R.string.rss_back_to_feeds
            else -> R.string.library_back_to_domains
        })
        fun selectGroup() {
            when (mode) {
                me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.COLLECTION -> model.selectCollectionFilter(id)
                me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.RSS -> model.selectRssFeedFilter(
                    me.bubblespark.tidilo.data.db.RssFeedEntity(id, "https://navigation.invalid/$id", "Navigation fixture"))
                else -> model.selectDomainFilter("navigation.invalid")
            }
        }
        fun assertCleared() {
            org.junit.Assert.assertNull(model.selectedDomainFilter.value)
            org.junit.Assert.assertNull(model.selectedRssFeedFilter.value)
            org.junit.Assert.assertNull(model.selectedCollectionId.value)
            org.junit.Assert.assertEquals(mode, model.filterMode.value)
        }
        var primaryFailure: Throwable? = null
        try {
            if (mode == me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.COLLECTION) {
                collectionDao.insert(me.bubblespark.tidilo.data.db.CollectionEntity(
                    id, "Navigation fixture", "", "folder", null, 0, 1, 1))
                kotlinx.coroutines.withTimeout(8000) {
                    while (model.collectionState.value.rows.none { it.collection.id == id }) delay(20)
                }
                host.main { model.setFilterMode(mode) }
                host.await(activity) { host.text(it, "Navigation fixture") }
            }
            host.main { model.setFilterMode(mode); selectGroup() }
            val back = host.await(activity) { host.description(it, backLabel) }
            host.main {
                org.junit.Assert.assertFalse("Group navigation must replace Menu with Up",
                    host.nodes(activity).any { host.description(it, host.context.getString(R.string.navigation_open)) })
                val action = checkNotNull(generateSequence(back) { it.parent }
                    .mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.firstOrNull()) {
                    "The group Up button must expose an invokable Click action"
                }
                assertTrue(action.invoke())
                assertCleared()
            }
            val menu = host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
            host.main {
                val action = checkNotNull(generateSequence(menu) { it.parent }
                    .mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.firstOrNull()) {
                    "The overview Menu button must expose an invokable Click action"
                }
                assertTrue(action.invoke())
            }
            host.await(activity) { host.text(it, host.context.getString(R.string.app_name)) }
            host.main { activity.onBackPressedDispatcher.onBackPressed() }
            delay(350)
            host.main { selectGroup() }
            host.await(activity) { host.description(it, backLabel) }
            host.main { activity.onBackPressedDispatcher.onBackPressed(); assertCleared() }
            host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            cleanupNavigationTest(primaryFailure,
                { host.main { activity.viewModelStore.clear() } },
                { host.main { activity.finish() } },
                { scopeJob?.cancelAndJoin() },
                { collectionDao.delete(id) },
                {
                    val restore = prefs.edit()
                    if (previous == null) restore.remove("library_filter_mode") else restore.putString("library_filter_mode", previous)
                    assertTrue("Library filter mode must be restored", restore.commit())
                    org.junit.Assert.assertEquals(previous, prefs.getString("library_filter_mode", null))
                })
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
