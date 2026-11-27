package me.bubblespark.tidilo.data

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.navigation.LibraryRadialMenu
import me.bubblespark.tidilo.ui.navigation.rememberLibraryRadialMenuState
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.AppThemeStyle
import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.SurfaceStyleOverride
import org.junit.Rule
import org.junit.Test

/** Haze rendering of the frosted search action: lower opacity must expose more of the captured body. */
class ThemeFrostedSearchRenderingTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun frostedSearchActionHonorsCustomOpacity() = runBlocking {
        var opacity by mutableFloatStateOf(1f)
        composeRule.setContent {
            val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
                SurfaceRole.PRIMARY_ACTION to SurfaceStyleOverride(containerColor = Color.Black, opacity = opacity)))
            ThemeConsumerScene(preset, blurEnabled = true) {
                TidiloScreenScaffold(header = {}, modifier = Modifier.size(220.dp), overlay = {
                    LibraryRadialMenu(rememberLibraryRadialMenuState(), LibraryFilterMode.NEWEST_FIRST, {}, {})
                }) { Box(Modifier.fillMaxSize().background(Color.Blue)) }
            }
        }
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val bounds = composeRule.awaitNode(hasTestTag("library_radial_trigger")).screenBounds()
        val x = bounds.center.x.toInt()
        val y = (bounds.top + 9f * density).toInt()
        val opaque = composeRule.sampleScreen("opaque search action") { android.graphics.Color.blue(it.getPixel(x, y)) }
        composeRule.runOnUiThread { opacity = .2f }
        composeRule.sampleScreen("lower opacity exposing more captured blue than $opaque",
            describe = { "blue=${android.graphics.Color.blue(it.getPixel(x, y))}" }) { frame ->
            android.graphics.Color.blue(frame.getPixel(x, y)).takeIf { it > opaque + 60 }
        }
        Unit
    }
}
