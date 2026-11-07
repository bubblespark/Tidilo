package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
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
            host.await(activity) { host.text(it, host.context.getString(R.string.library_header_title)) }
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
