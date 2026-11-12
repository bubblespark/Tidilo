package me.bubblespark.tidilo.data

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsNode

internal fun SemanticsNode.fullWindowBounds(): Rect =
    Rect(positionInWindow, Size(size.width.toFloat(), size.height.toFloat()))
