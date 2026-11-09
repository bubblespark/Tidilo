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
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.bubblespark.tidilo.ui.theme.LocalOverlayBlurSetting

/** A screen's body is the only input; its foreground chrome is never captured here. */
@Stable
internal class ScreenBackdropState(val haze: HazeState) {
    val sourceKey = Any()
}

internal val LocalScreenBackdropState = staticCompositionLocalOf<ScreenBackdropState?> { null }
private val LocalScreenBackdropHaze = staticCompositionLocalOf<HazeState?> { null }
private object ScreenCanvasSourceKey

/** Share stationary canvas capture while selecting each destination's body independently. */
@Composable
internal fun ScreenBackdropHost(content: @Composable () -> Unit) {
    val state = rememberHazeState()
    CompositionLocalProvider(LocalScreenBackdropHaze provides state, content = content)
}

@Composable
internal fun Modifier.screenCanvasSource(): Modifier {
    val state = LocalScreenBackdropHaze.current ?: return this
    return if (LocalView.current.isHardwareAccelerated) hazeSource(state, zIndex = 0f, key = ScreenCanvasSourceKey) else this
}

@Composable
internal fun ScreenBackdropScope(content: @Composable (ScreenBackdropState) -> Unit) {
    val haze = LocalScreenBackdropHaze.current ?: rememberHazeState()
    val state = remember(haze) { ScreenBackdropState(haze) }
    CompositionLocalProvider(LocalScreenBackdropState provides state) { content(state) }
}

@Composable
internal fun Modifier.screenBackdropSource(state: ScreenBackdropState): Modifier =
    if (LocalView.current.isHardwareAccelerated) hazeSource(state.haze, zIndex = 1f, key = state.sourceKey) else this

@Composable
internal fun rememberScreenBackdropInput(visible: Boolean = true): HazeInput.Sources? {
    val state = LocalScreenBackdropState.current
    val enabled = visible && state != null && LocalOverlayBlurController.current != null &&
        LocalOverlayBlurSetting.current.enabled && LocalView.current.isHardwareAccelerated
    return remember(state, enabled) {
        if (!enabled || state == null) null
        else HazeInput.Sources(state.haze,
            selection = HazeSourceSelection.All.where { it.key === state.sourceKey || it.key === ScreenCanvasSourceKey },
            retention = HazeSourceRetention.ClearWhenUnavailable)
    }
}
