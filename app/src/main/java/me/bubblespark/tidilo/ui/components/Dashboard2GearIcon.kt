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
 * Material Symbols Outlined: Dashboard 2 Gear (24dp, weight 400, FILL 0, GRAD 0, ROND 50)
 * Source: https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/dashboard_2_gear.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
 */
val Dashboard2GearIcon: ImageVector
    get() {
        if (_dashboard2Gear != null) {
            return _dashboard2Gear!!
        }
        _dashboard2Gear = ImageVector.Builder(
            name = "dashboard_2_gear",
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
                moveTo(11f, 11f)
                verticalLineTo(4f)
                horizontalLineTo(22f)
                verticalLineToRelative(7f)
                horizontalLineTo(11f)
                close()
                moveTo(2f, 20f)
                verticalLineTo(13f)
                horizontalLineTo(12f)
                verticalLineToRelative(7f)
                horizontalLineTo(2f)
                close()
                moveTo(2f, 11f)
                verticalLineTo(4f)
                horizontalLineTo(9f)
                verticalLineToRelative(7f)
                horizontalLineTo(2f)
                close()
                moveTo(13f, 9f)
                horizontalLineToRelative(7f)
                verticalLineTo(6f)
                horizontalLineTo(13f)
                verticalLineTo(9f)
                close()
                moveTo(4f, 18f)
                horizontalLineToRelative(6f)
                verticalLineTo(15f)
                horizontalLineTo(4f)
                verticalLineToRelative(3f)
                close()
                moveTo(4f, 9f)
                horizontalLineTo(7f)
                verticalLineTo(6f)
                horizontalLineTo(4f)
                verticalLineTo(9f)
                close()
                moveToRelative(9f, 0f)
                close()
                moveToRelative(-3f, 6f)
                close()
                moveTo(7f, 9f)
                close()
                moveTo(17f, 22f)
                lineTo(16.7f, 20.5f)
                quadTo(16.4f, 20.38f, 16.14f, 20.24f)
                quadTo(15.88f, 20.1f, 15.6f, 19.9f)
                lineToRelative(-1.45f, 0.45f)
                lineToRelative(-1f, -1.7f)
                lineToRelative(1.15f, -1f)
                quadTo(14.25f, 17.33f, 14.25f, 17f)
                reflectiveQuadTo(14.3f, 16.35f)
                lineToRelative(-1.15f, -1f)
                lineToRelative(1f, -1.7f)
                lineTo(15.6f, 14.1f)
                quadToRelative(0.27f, -0.2f, 0.54f, -0.34f)
                reflectiveQuadTo(16.7f, 13.5f)
                lineTo(17f, 12f)
                horizontalLineToRelative(2f)
                lineToRelative(0.3f, 1.5f)
                quadToRelative(0.3f, 0.13f, 0.56f, 0.26f)
                reflectiveQuadTo(20.4f, 14.1f)
                lineToRelative(1.45f, -0.45f)
                lineToRelative(1f, 1.7f)
                lineToRelative(-1.15f, 1f)
                quadToRelative(0.05f, 0.32f, 0.05f, 0.65f)
                reflectiveQuadTo(21.7f, 17.65f)
                lineToRelative(1.15f, 1f)
                lineToRelative(-1f, 1.7f)
                lineTo(20.4f, 19.9f)
                quadToRelative(-0.28f, 0.2f, -0.54f, 0.34f)
                reflectiveQuadTo(19.3f, 20.5f)
                lineTo(19f, 22f)
                horizontalLineTo(17f)
                close()
                moveToRelative(2.41f, -3.59f)
                quadTo(20f, 17.83f, 20f, 17f)
                reflectiveQuadTo(19.41f, 15.59f)
                reflectiveQuadTo(18f, 15f)
                reflectiveQuadToRelative(-1.41f, 0.59f)
                reflectiveQuadTo(16f, 17f)
                reflectiveQuadToRelative(0.59f, 1.41f)
                reflectiveQuadTo(18f, 19f)
                reflectiveQuadToRelative(1.41f, -0.59f)
                close()
            }
        }.build()
        return _dashboard2Gear!!
    }

private var _dashboard2Gear: ImageVector? = null
