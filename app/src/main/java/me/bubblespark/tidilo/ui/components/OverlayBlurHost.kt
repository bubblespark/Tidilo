package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import dev.chrisbanes.haze.*
import me.bubblespark.tidilo.ui.theme.LocalOverlayBlurSetting

internal class OverlayBlurController(val state: HazeState) {
    val registry = OverlayLayerRegistry()
    var enabled = false
    var activeCount by mutableIntStateOf(0)
    fun register(token: Long) { registry.register(token); activeCount = registry.activeCount }
    fun unregister(token: Long) { registry.unregister(token); activeCount = registry.activeCount }
}

internal val LocalOverlayBlurController = staticCompositionLocalOf<OverlayBlurController?> { null }

@Composable
fun OverlayBlurHost(enabled: Boolean, content: @Composable () -> Unit) {
    val state = rememberHazeState()
    val controller = remember(state) { OverlayBlurController(state) }
    controller.enabled = enabled
    val supported = LocalView.current.isHardwareAccelerated
    CompositionLocalProvider(LocalOverlayBlurController provides controller) {
        // Keep the source attached across window changes; Haze records only on effect demand.
        Box(if (supported) Modifier.hazeSource(state, zIndex = 0f, key = 0L)
            else Modifier, propagateMinConstraints = true) { content() }
    }
}

internal data class OverlayBlurLayer(val token: Long, val sourceModifier: Modifier, val input: HazeInput.Sources)

@Composable
internal fun rememberOverlayBlurLayer(visible: Boolean): OverlayBlurLayer? {
    val controller = LocalOverlayBlurController.current
    val enabled = visible && controller?.enabled == true && LocalOverlayBlurSetting.current.enabled && LocalView.current.isHardwareAccelerated
    val token = remember(controller, visible) { if (visible) controller?.registry?.allocate() else null }
    DisposableEffect(controller, token, enabled) {
        if (enabled && token != null) controller.register(token)
        onDispose { if (enabled && token != null) controller.unregister(token) }
    }
    return remember(controller, token, enabled) {
        if (!enabled || token == null) null
        else OverlayBlurLayer(token, Modifier.hazeSource(controller.state, token.toFloat(), token),
            HazeInput.Sources(controller.state,
                selection = HazeSourceSelection.All.where { it.zIndex < token.toFloat() },
                retention = HazeSourceRetention.ClearWhenUnavailable))
    }
}
