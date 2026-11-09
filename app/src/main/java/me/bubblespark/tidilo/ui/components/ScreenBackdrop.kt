package me.bubblespark.tidilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.bubblespark.tidilo.ui.theme.LocalOverlayBlurSetting

/** A screen's body is the only input; its foreground chrome is never captured here. */
@Stable
internal class ScreenBackdropState(val haze: HazeState)

internal val LocalScreenBackdropState = staticCompositionLocalOf<ScreenBackdropState?> { null }

@Composable
internal fun ScreenBackdropScope(content: @Composable (ScreenBackdropState) -> Unit) {
    val haze = rememberHazeState()
    val state = remember(haze) { ScreenBackdropState(haze) }
    CompositionLocalProvider(LocalScreenBackdropState provides state) { content(state) }
}

@Composable
internal fun Modifier.screenBackdropSource(state: ScreenBackdropState): Modifier =
    if (LocalView.current.isHardwareAccelerated) hazeSource(state.haze) else this

@Composable
internal fun rememberScreenBackdropInput(visible: Boolean = true): HazeInput.Sources? {
    val state = LocalScreenBackdropState.current
    val enabled = visible && state != null && LocalOverlayBlurController.current != null &&
        LocalOverlayBlurSetting.current.enabled && LocalView.current.isHardwareAccelerated
    return remember(state, enabled) {
        if (!enabled || state == null) null
        else HazeInput.Sources(state.haze, retention = HazeSourceRetention.ClearWhenUnavailable)
    }
}
