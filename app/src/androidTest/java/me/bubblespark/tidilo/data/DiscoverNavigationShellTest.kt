package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.data.engagement.EngagementType
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import android.accessibilityservice.AccessibilityService
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.TidiloApp
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverNavigationShellTest {
    private fun clickableNode(node: SemanticsNode): SemanticsNode {
        var target = node
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
        return target
    }

    private fun click(node: SemanticsNode) {
        check(clickableNode(node).config[SemanticsActions.OnClick].action?.invoke() == true)
    }

    private fun tag(node: SemanticsNode, value: String): Boolean =
        node.config.getOrNull(SemanticsProperties.TestTag) == value

    private fun isSelected(node: SemanticsNode): Boolean =
        node.config.getOrNull(SemanticsProperties.Selected) == true

    private suspend fun awaitAbsent(
        host: HeaderTestHost,
        activity: androidx.activity.ComponentActivity,
        predicate: (SemanticsNode) -> Boolean
    ) {
        withTimeout(8000) {
            var absent = false
            while (!absent) {
                host.main { absent = host.nodes(activity).none(predicate) }
                if (!absent) delay(30)
            }
        }
    }

    private fun pressSystemBack(host: HeaderTestHost) {
        check(host.instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
    }

    private suspend fun launchShell(
        host: HeaderTestHost,
        discoverPreviewEnabled: Boolean,
        setupModel: (LibraryViewModel) -> Unit = {}
    ): Pair<androidx.activity.ComponentActivity, LibraryViewModel> {
        val activity = host.launch()
        lateinit var model: LibraryViewModel
        var ready = false
        host.main {
            model = ViewModelProvider(activity)[LibraryViewModel::class.java]
            setupModel(model)
            activity.setContent {
                TidiloTheme {
                    TidiloApp(
                        libraryViewModel = model,
                        discoverPreviewEnabled = discoverPreviewEnabled
                    )
                    SideEffect { ready = true }
                }
            }
        }
        withTimeout(5000) { while (!ready) delay(20) }
        return activity to model
    }

    @Test
    fun openingDetailFromDiscoverRecordsOneDetailEventWithTheShelf(): Unit = runBlocking {
        val host = HeaderTestHost()
        val database = AppDatabase.getInstance(host.context)
        val id = "shell-detail-${UUID.randomUUID()}"
        database.bookmarkDao().insertOrUpdate(
            BookmarkEntity(id = id, title = "Detail Fixture", url = "https://detail.invalid/$id", description = "",
                createdAt = System.currentTimeMillis(), updatedAt = 1L)
        )
        val (activity, _) = launchShell(host, discoverPreviewEnabled = true)
        try {
            val card = host.await(activity) { tag(it, "discover_card_$id") }
            host.main { click(card) }
            awaitAbsent(host, activity) { tag(it, "discover_list") }
            withTimeout(8000) {
                while (database.engagementDao().eventsFor(id).none { it.type == EngagementType.DETAIL.code }) delay(50)
            }
            delay(500) // a second, duplicate write would land within this window
            val details = database.engagementDao().eventsFor(id).filter { it.type == EngagementType.DETAIL.code }
            assertEquals(listOf(EngagementSurface.DISCOVER_JUST_SAVED.code), details.map { it.surface })
        } finally {
            host.main { activity.finish() }
            database.bookmarkDao().deleteById(id)
        }
    }

    @Test
    fun startsOnDiscoverWithToolbarAndFab(): Unit = runBlocking {
        val host = HeaderTestHost()
        val (activity, _) = launchShell(host, discoverPreviewEnabled = true)
        try {
            host.await(activity) { tag(it, "discover_header") }
            val discoverTab = host.await(activity) { tag(it, "floating_nav_discover") }
            assertTrue("Discover tab must be selected on start", isSelected(discoverTab))
            host.await(activity) { tag(it, "floating_nav_save") }
            host.main {
                assertFalse(
                    "Radial trigger must be absent when preview is enabled",
                    host.nodes(activity).any { tag(it, "library_radial_trigger") }
                )
            }
        } finally {
            host.main { activity.finish() }
        }
    }

    @Test
    fun toolbarSwitchesLayersAndBackReturnsToDiscover(): Unit = runBlocking {
        val host = HeaderTestHost()
        val (activity, _) = launchShell(host, discoverPreviewEnabled = true)
        try {
            host.await(activity) { tag(it, "discover_header") }

            val libraryTab = host.await(activity) { tag(it, "floating_nav_library") }
            host.main { click(libraryTab) }
            host.await(activity) { host.text(it, host.context.getString(R.string.library_header_title)) }
            val selectedLibrary = host.await(activity) { tag(it, "floating_nav_library") && isSelected(it) }
            assertTrue(isSelected(selectedLibrary))

            pressSystemBack(host)
            host.await(activity) { tag(it, "discover_header") }
            host.await(activity) { tag(it, "floating_nav_discover") && isSelected(it) }

            val searchTab = host.await(activity) { tag(it, "floating_nav_search") }
            host.main { click(searchTab) }
            host.await(activity) { host.text(it, host.context.getString(R.string.search_header_title)) }

            val searchBack = host.await(activity) { host.description(it, host.context.getString(R.string.common_back)) }
            host.main { click(searchBack) }
            host.await(activity) { tag(it, "discover_header") }
            host.await(activity) { tag(it, "floating_nav_discover") && isSelected(it) }
        } finally {
            host.main { activity.finish() }
        }
    }

    @Test
    fun libraryDrillDownBackClearsDrillDownFirst(): Unit = runBlocking {
        val host = HeaderTestHost()
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        val id = "shell-drilldown-${UUID.randomUUID()}"
        val domain = "drilldown.invalid"
        dao.insertOrUpdate(
            BookmarkEntity(
                id = id,
                title = "Drilldown Fixture",
                url = "https://$domain/$id",
                description = "Summary",
                createdAt = System.currentTimeMillis(),
                updatedAt = 1L
            )
        )
        var previousMode = LibraryFilterMode.NEWEST_FIRST
        var previousDomain: String? = null
        val (activity, model) = launchShell(host, discoverPreviewEnabled = true) {
            previousMode = it.filterMode.value
            previousDomain = it.selectedDomainFilter.value
        }
        try {
            val libraryTab = host.await(activity) { tag(it, "floating_nav_library") }
            host.main { click(libraryTab) }
            host.await(activity) { host.text(it, host.context.getString(R.string.library_header_title)) }

            host.main {
                model.setFilterMode(LibraryFilterMode.DOMAIN)
                model.selectDomainFilter(domain)
            }
            host.await(activity) { host.text(it, domain) }

            // First back clears the domain drill-down and stays on Library.
            pressSystemBack(host)
            withTimeout(5000) {
                while (model.selectedDomainFilter.value != null) delay(30)
            }
            host.await(activity) { tag(it, "floating_nav_library") && isSelected(it) }

            // Second back returns to Discover.
            pressSystemBack(host)
            host.await(activity) { tag(it, "discover_header") }
            host.await(activity) { tag(it, "floating_nav_discover") && isSelected(it) }
        } finally {
            host.main {
                model.selectDomainFilter(previousDomain)
                model.setFilterMode(previousMode)
                activity.finish()
            }
            dao.deleteById(id)
        }
    }

    @Test
    fun fabOpensManualImportFromDiscoverAndLibrary(): Unit = runBlocking {
        val host = HeaderTestHost()
        val (activity, _) = launchShell(host, discoverPreviewEnabled = true)
        try {
            host.await(activity) { tag(it, "discover_header") }
            val fabOnDiscover = host.await(activity) { tag(it, "floating_nav_save") }
            host.main { click(fabOnDiscover) }
            host.await(activity) { host.text(it, host.context.getString(R.string.import_dialog_title)) }
            val cancel1 = host.await(activity) { host.text(it, host.context.getString(R.string.common_cancel)) }
            host.main { click(cancel1) }
            awaitAbsent(host, activity) { host.text(it, host.context.getString(R.string.import_dialog_title)) }

            val libraryTab = host.await(activity) { tag(it, "floating_nav_library") }
            host.main { click(libraryTab) }
            host.await(activity) { host.text(it, host.context.getString(R.string.library_header_title)) }
            val fabOnLibrary = host.await(activity) { tag(it, "floating_nav_save") }
            host.main { click(fabOnLibrary) }
            host.await(activity) { host.text(it, host.context.getString(R.string.import_dialog_title)) }
            val cancel2 = host.await(activity) { host.text(it, host.context.getString(R.string.common_cancel)) }
            host.main { click(cancel2) }
        } finally {
            host.main { activity.finish() }
        }
    }

    @Test
    fun railFromDiscoverIsTrimmed(): Unit = runBlocking {
        val host = HeaderTestHost()
        val (activity, _) = launchShell(host, discoverPreviewEnabled = true)
        try {
            host.await(activity) { tag(it, "discover_header") }
            val menu = host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
            host.main { click(menu) }

            host.await(activity) { host.text(it, host.context.getString(R.string.trash_title)) }
            host.await(activity) { host.text(it, host.context.getString(R.string.title_settings)) }
            host.await(activity) { host.description(it, host.context.getString(R.string.settings_notification_title)) }

            host.main {
                val railNodes = host.nodes(activity)
                assertFalse(
                    "Trimmed rail must omit Search",
                    railNodes.any { host.text(it, host.context.getString(R.string.nav_search)) }
                )
                assertFalse(
                    "Trimmed rail must omit Manual Import",
                    railNodes.any { host.text(it, host.context.getString(R.string.navigation_manual_import)) }
                )
                assertFalse(
                    "Trimmed rail must omit Filters (the Library button group replaces them)",
                    railNodes.any { host.text(it, host.context.getString(R.string.navigation_filters)) }
                )
            }
        } finally {
            host.main { activity.finish() }
        }
    }

    @Test
    fun scrollHidesAndRestoresToolbar(): Unit = runBlocking {
        val host = HeaderTestHost()
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        val prefix = "shell-scroll-${UUID.randomUUID()}"
        val ids = (0..15).map { "$prefix-$it" }
        ids.forEachIndexed { index, id ->
            dao.insertOrUpdate(
                BookmarkEntity(
                    id = id,
                    title = "Scroll Fixture $index",
                    url = "https://scroll.invalid/$id",
                    description = "Summary $index",
                    createdAt = System.currentTimeMillis() + index,
                    updatedAt = 1L
                )
            )
        }
        var previousMode = LibraryFilterMode.NEWEST_FIRST
        val (activity, model) = launchShell(host, discoverPreviewEnabled = true) {
            previousMode = it.filterMode.value
            it.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
        }
        try {
            val libraryTab = host.await(activity) { tag(it, "floating_nav_library") }
            host.main { click(libraryTab) }
            // Discover's "Just saved" also lists the fixtures, so wait until Discover has left first.
            awaitAbsent(host, activity) { tag(it, "discover_list") }
            host.await(activity) {
                it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.startsWith("Scroll Fixture") } == true
            }
            host.await(activity) { tag(it, "floating_nav") }

            host.scroll(activity, 600f)
            awaitAbsent(host, activity) { tag(it, "floating_nav") }

            host.scroll(activity, -400f)
            host.await(activity) { tag(it, "floating_nav") }
        } finally {
            host.main {
                model.setFilterMode(previousMode)
                activity.finish()
            }
            ids.forEach { dao.deleteById(it) }
        }
    }

    @Test
    fun toolbarClearsSystemBarsAndLastItem(): Unit = runBlocking {
        val host = HeaderTestHost()
        val (activity, _) = launchShell(host, discoverPreviewEnabled = true)
        try {
            val toolbar = host.await(activity) { tag(it, "floating_nav") }
            var rootHeight = 0
            var navBarBottom = 0
            var density = 1f
            host.main {
                rootHeight = activity.window.decorView.height
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                navBarBottom = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
                density = activity.resources.displayMetrics.density
            }
            val expectedMaxBottom = rootHeight - navBarBottom - 16f * density + 2f
            assertTrue(
                "Toolbar bottom (${toolbar.boundsInRoot.bottom}) must clear nav bar ($navBarBottom) + 16dp (max=$expectedMaxBottom)",
                toolbar.boundsInRoot.bottom <= expectedMaxBottom
            )
            assertTrue(
                "FloatingNavigationClearance must be >= 96dp",
                HeaderDefaults.FloatingNavigationClearance.value >= 96f
            )
        } finally {
            host.main { activity.finish() }
        }
    }

    @Test
    fun detailHidesToolbar(): Unit = runBlocking {
        val host = HeaderTestHost()
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        val id = "shell-detail-${UUID.randomUUID()}"
        val title = "Detail Toolbar Fixture $id"
        dao.insertOrUpdate(
            BookmarkEntity(
                id = id,
                title = title,
                url = "https://detail.invalid/$id",
                description = "Summary",
                createdAt = System.currentTimeMillis() + 50_000L,
                updatedAt = 1L
            )
        )
        var previousMode = LibraryFilterMode.NEWEST_FIRST
        val (activity, model) = launchShell(host, discoverPreviewEnabled = true) {
            previousMode = it.filterMode.value
            it.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
        }
        try {
            val libraryTab = host.await(activity) { tag(it, "floating_nav_library") }
            host.main { click(libraryTab) }
            // Discover's "Just saved" also lists the fixture, so wait until Discover has left first.
            awaitAbsent(host, activity) { tag(it, "discover_list") }
            val item = host.await(activity) { host.text(it, title) }
            host.main { click(item) }

            awaitAbsent(host, activity) { tag(it, "floating_nav") }

            val back = host.await(activity) { host.description(it, host.context.getString(R.string.common_back)) }
            host.main { click(back) }
            host.await(activity) { tag(it, "floating_nav") }
        } finally {
            host.main {
                model.setFilterMode(previousMode)
                activity.finish()
            }
            dao.deleteById(id)
        }
    }

    @Test
    fun flagOffKeepsRadialShell(): Unit = runBlocking {
        val host = HeaderTestHost()
        val (activity, _) = launchShell(host, discoverPreviewEnabled = false)
        try {
            host.await(activity) { host.text(it, host.context.getString(R.string.library_header_title)) }
            host.await(activity) { tag(it, "library_radial_trigger") }
            host.main {
                assertFalse(
                    "Floating toolbar must be absent when preview flag is off",
                    host.nodes(activity).any { tag(it, "floating_nav") }
                )
            }
        } finally {
            host.main { activity.finish() }
        }
    }
}
