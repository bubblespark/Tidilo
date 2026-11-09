package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@Composable
fun TidiloDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var contentPresent by remember { mutableStateOf(false) }
    val layer = rememberOverlayBlurLayer(expanded || contentPresent)
    CompositionLocalProvider(LocalContentColor provides contentColorFor(containerColor)) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest,
            modifier = modifier.overlayBlurBackground(layer, shape, containerColor), offset = offset,
            scrollState = scrollState, properties = properties, shape = shape,
            containerColor = if (layer == null) containerColor else Color.Transparent,
            tonalElevation = if (layer == null) tonalElevation else 0.dp,
            shadowElevation = shadowElevation, border = border) {
            DisposableEffect(Unit) {
                contentPresent = true
                onDispose { contentPresent = false }
            }
            content()
        }
    }
}
