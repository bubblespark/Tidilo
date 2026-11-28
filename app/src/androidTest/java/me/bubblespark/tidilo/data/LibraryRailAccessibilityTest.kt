package me.bubblespark.tidilo.data

import android.content.Context
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryRailAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun compactRailAtLargeFontScrollsUtilitiesButKeepsHeader() {
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    val state = rememberWideNavigationRailState()
                    LaunchedEffect(Unit) { state.expand() }
                    LibraryNavigationRail(
                        state,
                        {}, {},
                        Modifier.height(420.dp).testTag("rail_host")
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val appName = context.getString(R.string.app_name)
        val settingsLabel = context.getString(R.string.title_settings)
        val before = composeRule.onNodeWithText(appName, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        composeRule.onNodeWithText(settingsLabel, useUnmergedTree = true).performScrollTo()
        composeRule.waitForIdle()

        val settings = composeRule.onNodeWithText(settingsLabel, useUnmergedTree = true).fetchSemanticsNode()
        val after = composeRule.onNodeWithText(appName, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val railBounds = composeRule.onNodeWithTag("rail_host", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertEquals(before.top, after.top, 1f)
        assertTrue(settings.boundsInRoot.bottom <= railBounds.bottom)
        assertTrue(settings.boundsInRoot.top >= after.bottom)
    }
}
