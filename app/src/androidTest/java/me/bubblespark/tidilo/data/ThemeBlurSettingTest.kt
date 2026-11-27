package me.bubblespark.tidilo.data

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.ui.screens.settings.SettingsScreen
import me.bubblespark.tidilo.ui.screens.settings.ThemeBlurSetting
import me.bubblespark.tidilo.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThemeBlurSettingTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    @Test fun themeDialogTogglePersistsAndStaysOpen() {
        val prefs = context.getSharedPreferences("tidilo_app_config_prefs", Context.MODE_PRIVATE)
        val hadKey = prefs.contains("overlay_blur_enabled")
        val old = AppConfig.overlayBlurEnabled.value
        try {
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { SettingsScreen({}, {}) }
            }
            composeRule.onNodeWithText(context.getString(R.string.settings_theme_title), useUnmergedTree = true).performClick()
            composeRule.awaitNode(switch)
            composeRule.onNode(switch, useUnmergedTree = true).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(context.getString(R.string.settings_theme_dialog_title), useUnmergedTree = true).assertExists()
            assertEquals(!old, AppConfig.overlayBlurEnabled.value)
            assertEquals(!old, prefs.getBoolean("overlay_blur_enabled", old))
        } finally {
            composeRule.runOnUiThread { AppConfig.setOverlayBlurEnabled(old) }
            if (!hadKey) prefs.edit().remove("overlay_blur_enabled").commit()
        }
    }

    @Test fun fixedPoliciesDisableTheSwitchAndKeepSavedChoice() {
        var policy by mutableStateOf(OverlayBlurPolicy.USER_CHOICE)
        var saved by mutableStateOf(false)
        composeRule.setContent { MaterialTheme {
            ThemeBlurSetting(resolveOverlayBlur(policy, saved), { saved = it })
        } }
        val staleAction = composeRule.awaitNode(switch).config[SemanticsActions.OnClick].action
        for ((fixed, checked) in listOf(OverlayBlurPolicy.FORCE_ON to true, OverlayBlurPolicy.FORCE_OFF to false)) {
            composeRule.runOnUiThread { policy = fixed }
            val state = if (checked) ToggleableState.On else ToggleableState.Off
            composeRule.awaitNode(switch and SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled) and
                SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state))
            composeRule.runOnUiThread { staleAction?.invoke() }
            composeRule.waitForIdle()
            assertFalse(saved)
        }
        composeRule.runOnUiThread { policy = OverlayBlurPolicy.USER_CHOICE }
        composeRule.awaitNode(switch and isEnabled())
        composeRule.onNode(switch and isEnabled(), useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertTrue(saved)
    }
}
