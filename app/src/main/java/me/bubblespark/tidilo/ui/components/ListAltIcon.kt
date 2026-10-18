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
 * Material Symbols Outlined: List Alt (24dp, weight 400, FILL 0, GRAD 0, ROND 50)
 * Source: https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/list_alt.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
 */
val ListAltIcon: ImageVector
    get() {
        if (_listAlt != null) {
            return _listAlt!!
        }
        _listAlt = ImageVector.Builder(
            name = "list_alt",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
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
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(8.71f, 16.71f)
                quadTo(9f, 16.43f, 9f, 16f)
                reflectiveQuadTo(8.71f, 15.29f)
                reflectiveQuadTo(8f, 15f)
                quadTo(7.58f, 15f, 7.29f, 15.29f)
                reflectiveQuadTo(7f, 16f)
                reflectiveQuadToRelative(0.29f, 0.71f)
                reflectiveQuadTo(8f, 17f)
                reflectiveQuadTo(8.71f, 16.71f)
                close()
                moveToRelative(0f, -4f)
                quadTo(9f, 12.43f, 9f, 12f)
                reflectiveQuadTo(8.71f, 11.29f)
                reflectiveQuadTo(8f, 11f)
                quadTo(7.58f, 11f, 7.29f, 11.29f)
                reflectiveQuadTo(7f, 12f)
                reflectiveQuadToRelative(0.29f, 0.71f)
                reflectiveQuadTo(8f, 13f)
                reflectiveQuadTo(8.71f, 12.71f)
                close()
                moveToRelative(0f, -4f)
                quadTo(9f, 8.42f, 9f, 8f)
                quadTo(9f, 7.57f, 8.71f, 7.29f)
                reflectiveQuadTo(8f, 7f)
                quadTo(7.58f, 7f, 7.29f, 7.29f)
                reflectiveQuadTo(7f, 8f)
                quadTo(7f, 8.42f, 7.29f, 8.71f)
                reflectiveQuadTo(8f, 9f)
                reflectiveQuadTo(8.71f, 8.71f)
                close()
                moveTo(11f, 17f)
                horizontalLineToRelative(6f)
                verticalLineTo(15f)
                horizontalLineTo(11f)
                verticalLineToRelative(2f)
                close()
                moveToRelative(0f, -4f)
                horizontalLineToRelative(6f)
                verticalLineTo(11f)
                horizontalLineTo(11f)
                verticalLineToRelative(2f)
                close()
                moveTo(11f, 9f)
                horizontalLineToRelative(6f)
                verticalLineTo(7f)
                horizontalLineTo(11f)
                verticalLineTo(9f)
                close()
                moveTo(5f, 21f)
                quadTo(4.18f, 21f, 3.59f, 20.41f)
                reflectiveQuadTo(3f, 19f)
                verticalLineTo(5f)
                quadTo(3f, 4.17f, 3.59f, 3.59f)
                reflectiveQuadTo(5f, 3f)
                horizontalLineTo(19f)
                quadToRelative(0.83f, 0f, 1.41f, 0.59f)
                reflectiveQuadTo(21f, 5f)
                verticalLineTo(19f)
                quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                reflectiveQuadTo(19f, 21f)
                horizontalLineTo(5f)
                close()
                moveTo(5f, 19f)
                horizontalLineTo(19f)
                verticalLineTo(5f)
                horizontalLineTo(5f)
                verticalLineTo(19f)
                close()
                moveTo(5f, 5f)
                verticalLineTo(19f)
                verticalLineTo(5f)
                close()
            }
        }.build()
        return _listAlt!!
    }

private var _listAlt: ImageVector? = null
