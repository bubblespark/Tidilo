package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.ui.components.*
import me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPolicyRenderingTest {
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun everyOverlayFamilyHonorsEveryPolicyAndSavedChoice(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var policy by mutableStateOf(OverlayBlurPolicy.USER_CHOICE)
        var saved by mutableStateOf(false)
        var family by mutableIntStateOf(0)
        var controller: OverlayBlurController? = null
        try {
            host.main { activity.setContent { TidiloTheme {
                val setting = resolveOverlayBlur(policy, saved)
                CompositionLocalProvider(LocalOverlayBlurSetting provides setting) {
                    OverlayBlurHost(setting.enabled) {
                        val current = LocalOverlayBlurController.current
                        SideEffect { controller = current }
                        Box(Modifier.fillMaxSize().background(Color.Blue))
                        key(family) { when (family) {
                            0 -> TidiloAlertDialog({}, {}, title = { Text("Policy fixture") })
                            1 -> TidiloModalBottomSheet({}) { Box(Modifier.height(180.dp)) }
                            2 -> Box { TidiloDropdownMenu(true, {}) {
                                DropdownMenuItem(text = { Text("Policy fixture") }, onClick = {})
                            } }
                            3 -> {
                                val rail = rememberWideNavigationRailState()
                                LaunchedEffect(rail) { rail.expand() }
                                LibraryNavigationRail(rail, LibraryFilterMode.NEWEST_FIRST, {}, {}, {}, {}, {})
                            }
                        } }
                    }
                }
            } } }
            for (kind in 0..3) for (choice in listOf(false, true)) for (next in OverlayBlurPolicy.entries) {
                host.main { family = kind; saved = choice; policy = next }
                val expected = if (resolveOverlayBlur(next, choice).enabled) 1 else 0
                withTimeout(5000) {
                    var count = -1
                    while (count != expected) { host.main { count = controller?.activeCount ?: -1 }; if (count != expected) delay(30) }
                }
                host.main { assertEquals(choice, saved) }
            }
        } finally { host.main { activity.finish() } }
    }
}
