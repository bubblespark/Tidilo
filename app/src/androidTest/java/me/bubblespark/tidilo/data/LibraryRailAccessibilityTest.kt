package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import org.junit.Assert.*
import org.junit.Test

class LibraryRailAccessibilityTest {
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun compactRailAtLargeFontScrollsUtilitiesButKeepsHeader(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            host.main { activity.setContent { MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    val state = rememberWideNavigationRailState()
                    LaunchedEffect(Unit) { state.expand() }
                    LibraryNavigationRail(state, LibraryFilterMode.NEWEST_FIRST, {}, {}, {}, {}, {}, Modifier.height(420.dp))
                }
            } } }
            val title = host.await(activity) { host.text(it, host.context.getString(R.string.app_name)) }
            val before = title.boundsInRoot
            val search = host.await(activity) { host.text(it, host.context.getString(R.string.nav_search)) }
            host.main {
                val scroll = generateSequence(search) { it.parent }.first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 5000f)
            }
            delay(400)
            val settings = host.await(activity) { host.text(it, host.context.getString(R.string.title_settings)) }
            val after = host.await(activity) { host.text(it, host.context.getString(R.string.app_name)) }
            assertEquals(before.top, after.boundsInRoot.top, 1f)
            assertTrue(settings.boundsInRoot.bottom <= activity.window.decorView.height)
            assertTrue(settings.boundsInRoot.top >= after.boundsInRoot.bottom)
            host.capture("rail-large-font")
        } finally { host.main { activity.finish() } }
    }
}
