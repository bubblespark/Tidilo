package me.bubblespark.tidilo.data

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.ui.components.LocalOverlayBlurController
import me.bubblespark.tidilo.ui.components.OverlayBlurController
import me.bubblespark.tidilo.ui.components.OverlayBlurHost
import me.bubblespark.tidilo.ui.components.TidiloAlertDialog
import me.bubblespark.tidilo.ui.components.TidiloDropdownMenu
import me.bubblespark.tidilo.ui.components.TidiloModalBottomSheet
import me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.LocalOverlayBlurSetting
import me.bubblespark.tidilo.ui.theme.OverlayBlurPolicy
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import me.bubblespark.tidilo.ui.theme.resolveOverlayBlur
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OverlayPolicyRenderingTest {
    @get:Rule val composeRule = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun everyOverlayFamilyHonorsEveryPolicyAndSavedChoice() {
        var policy by mutableStateOf(OverlayBlurPolicy.USER_CHOICE)
        var saved by mutableStateOf(false)
        var family by mutableIntStateOf(0)
        var controller: OverlayBlurController? = null
        composeRule.setContent {
            TidiloTheme {
                val setting = resolveOverlayBlur(policy, saved)
                CompositionLocalProvider(LocalOverlayBlurSetting provides setting) {
                    OverlayBlurHost(setting.enabled) {
                        val current = LocalOverlayBlurController.current
                        SideEffect { controller = current }
                        Box(Modifier.fillMaxSize().background(Color.Blue))
                        key(family) {
                            when (family) {
                                0 -> TidiloAlertDialog({}, {}, title = { Text("Policy fixture") })
                                1 -> TidiloModalBottomSheet({}) { Box(Modifier.height(180.dp)) }
                                2 -> Box {
                                    TidiloDropdownMenu(true, {}) {
                                        DropdownMenuItem(text = { Text("Policy fixture") }, onClick = {})
                                    }
                                }
                                3 -> {
                                    val rail = rememberWideNavigationRailState()
                                    LaunchedEffect(rail) { rail.expand() }
                                    LibraryNavigationRail(rail, LibraryFilterMode.NEWEST_FIRST, {}, {}, {}, {}, {})
                                }
                            }
                        }
                    }
                }
            }
        }
        val transitions = listOf(
            OverlayBlurPolicy.USER_CHOICE to false,
            OverlayBlurPolicy.USER_CHOICE to true,
            OverlayBlurPolicy.FORCE_ON to false,
            OverlayBlurPolicy.FORCE_OFF to true
        )
        for (kind in 0..3) {
            for ((next, choice) in transitions) {
                composeRule.runOnUiThread {
                    family = kind
                    saved = choice
                    policy = next
                }
                val expected = if (resolveOverlayBlur(next, choice).enabled) 1 else 0
                composeRule.waitUntil(5_000) {
                    var count = -1
                    composeRule.runOnUiThread { count = controller?.activeCount ?: -1 }
                    count == expected
                }
                composeRule.runOnUiThread { assertEquals(choice, saved) }
            }
        }
    }
}
