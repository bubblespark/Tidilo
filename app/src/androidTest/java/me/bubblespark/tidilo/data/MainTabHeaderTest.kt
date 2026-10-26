package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabHeaderTest {
    @Test fun actionCentersStayFixedAcrossRootAndDrillDownTitles(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        lateinit var vm: LibraryViewModel
        lateinit var oldMode: LibraryFilterMode
        try {
            host.main {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                oldMode = vm.filterMode.value
                activity.setContent { MaterialTheme { LibraryScreen(vm) } }
            }
            val centers = mutableListOf<Float>()
            for (title in listOf<String?>(null, "IGN", "IGN PC Articles", "あ".repeat(40))) {
                host.main {
                    vm.setFilterMode(if (title == null) LibraryFilterMode.NEWEST_FIRST else LibraryFilterMode.RSS)
                    vm.selectRssFeedFilter(title?.let { RssFeedEntity("header-test", "https://header.invalid/rss", it, enabled = false) })
                }
                delay(450)
                val action = host.await(activity) { host.description(it, host.context.getString(R.string.title_settings)) }
                host.main { centers += action.boundsInRoot.center.y }
                if (title == null || title == "IGN PC Articles") host.capture(if (title == null) "library-root" else "rss-drilldown")
            }
            assertEquals("Titles must not move action icons vertically", centers.min(), centers.max(), 1f)
        } finally {
            host.main { vm.selectRssFeedFilter(null); vm.setFilterMode(oldMode); activity.finish() }
        }
    }
}
