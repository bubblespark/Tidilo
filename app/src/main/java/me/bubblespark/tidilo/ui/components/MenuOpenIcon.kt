package me.bubblespark.tidilo.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Material Symbols Outlined: Menu Open
 * https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/menu_open.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
 */
public val MenuOpenIcon: ImageVector
    get() {
        if (_menu_open != null) {
            return _menu_open!!
        }
        _menu_open = ImageVector.Builder(
            name = "menu_open",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                stroke = null,
                strokeAlpha = 1f,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.Companion.NonZero,
            ) {
                moveTo(3f, 18f)
                verticalLineTo(16f)
                horizontalLineTo(16f)
                verticalLineToRelative(2f)
                horizontalLineTo(3f)
                close()
                moveTo(19.6f, 17f)
                lineToRelative(-5f, -5f)
                lineToRelative(5f, -5f)
                lineTo(21f, 8.4f)
                lineTo(17.4f, 12f)
                lineTo(21f, 15.6f)
                lineTo(19.6f, 17f)
                close()
                moveTo(3f, 13f)
                verticalLineTo(11f)
                horizontalLineTo(13f)
                verticalLineToRelative(2f)
                horizontalLineTo(3f)
                close()
                moveTo(3f, 8f)
                verticalLineTo(6f)
                horizontalLineTo(16f)
                verticalLineTo(8f)
                horizontalLineTo(3f)
                close()
            }
        }.build()
        return _menu_open!!
    }

private var _menu_open: ImageVector? = null
