package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.screens.settings.ThemeBlurSetting
import me.bubblespark.tidilo.ui.theme.*
import org.junit.Assert.*
import org.junit.Test

class ThemeBlurSettingTest {
    @Test fun themeDialogTogglePersistsAndStaysOpen(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val prefs = host.context.getSharedPreferences("tidilo_app_config_prefs", android.content.Context.MODE_PRIVATE)
        val hadKey = prefs.contains("overlay_blur_enabled")
        val old = me.bubblespark.tidilo.config.AppConfig.overlayBlurEnabled.value
        try {
            host.main { activity.setContent {
                val enabled by me.bubblespark.tidilo.config.AppConfig.overlayBlurEnabled.collectAsState()
                TidiloTheme(blurEnabled = enabled) {
                    me.bubblespark.tidilo.ui.screens.settings.SettingsScreen({}, {})
                }
            } }
            val theme = host.await(activity) { host.text(it, host.context.getString(me.bubblespark.tidilo.R.string.settings_theme_title)) }
            host.main {
                val clickable = generateSequence(theme) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                clickable.config[SemanticsActions.OnClick].action!!.invoke()
            }
            val row = host.await(activity) { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
            host.main { row.config[SemanticsActions.OnClick].action!!.invoke() }
            host.await(activity) { host.text(it, host.context.getString(me.bubblespark.tidilo.R.string.settings_theme_dialog_title)) }
            host.main { assertEquals(!old, me.bubblespark.tidilo.config.AppConfig.overlayBlurEnabled.value) }
            assertEquals(!old, prefs.getBoolean("overlay_blur_enabled", old))
        } finally {
            host.main { activity.finish(); me.bubblespark.tidilo.config.AppConfig.setOverlayBlurEnabled(old) }
            if (!hadKey) prefs.edit().remove("overlay_blur_enabled").commit()
        }
    }

    @Test fun fixedPoliciesDisableTheSwitchAndKeepSavedChoice(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var policy by mutableStateOf(OverlayBlurPolicy.USER_CHOICE)
        var saved by mutableStateOf(false)
        try {
            host.main { activity.setContent { MaterialTheme {
                ThemeBlurSetting(resolveOverlayBlur(policy, saved), { saved = it })
            } } }
            val row = host.await(activity) { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
            var staleAction: (() -> Boolean)? = null
            host.main { staleAction = row.config[SemanticsActions.OnClick].action }
            for ((fixed, checked) in listOf(OverlayBlurPolicy.FORCE_ON to true, OverlayBlurPolicy.FORCE_OFF to false)) {
                host.main { policy = fixed }
                val fixedRow = host.await(activity) {
                    it.config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                        it.config.contains(SemanticsProperties.Disabled) &&
                        it.config.getOrNull(SemanticsProperties.ToggleableState) ==
                        if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
                }
                host.main {
                    assertTrue(fixedRow.config.contains(SemanticsProperties.Disabled))
                    staleAction?.invoke()
                    assertFalse(saved)
                }
            }
            host.main { policy = OverlayBlurPolicy.USER_CHOICE }
            val editable = host.await(activity) { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                !it.config.contains(SemanticsProperties.Disabled) }
            host.main { editable.config[SemanticsActions.OnClick].action!!.invoke(); assertTrue(saved) }
        } finally { host.main { activity.finish() } }
    }
}
