package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TidiloModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    tonalElevation: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layer = rememberOverlayBlurLayer(true)
    val surfaceModifier = if (layer == null) modifier else modifier
        .withNativeOverlayBackground(Modifier.overlayBlurBackground(layer, shape, containerColor))
    ModalBottomSheet(onDismissRequest = onDismissRequest,
        modifier = surfaceModifier, sheetState = sheetState,
        sheetGesturesEnabled = sheetGesturesEnabled, shape = shape,
        containerColor = if (layer == null) containerColor else Color.Transparent,
        contentColor = contentColorFor(containerColor),
        tonalElevation = if (layer == null) tonalElevation else 0.dp, content = content)
}
