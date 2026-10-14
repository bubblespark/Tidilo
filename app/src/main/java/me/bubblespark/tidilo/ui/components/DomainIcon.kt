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
 * Material Symbols Outlined "Domain" (24dp) icon
 * https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/domain.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
 */
val DomainIcon: ImageVector
    get() {
        if (_domainIcon != null) {
            return _domainIcon!!
        }
        _domainIcon = ImageVector.Builder(
            name = "domain",
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
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(2f, 21f)
                verticalLineTo(3f)
                horizontalLineTo(12f)
                verticalLineTo(7f)
                horizontalLineTo(22f)
                verticalLineTo(21f)
                horizontalLineTo(2f)
                close()
                moveTo(4f, 19f)
                horizontalLineTo(6f)
                verticalLineTo(17f)
                horizontalLineTo(4f)
                verticalLineToRelative(2f)
                close()
                moveTo(4f, 15f)
                horizontalLineTo(6f)
                verticalLineTo(13f)
                horizontalLineTo(4f)
                verticalLineToRelative(2f)
                close()
                moveTo(4f, 11f)
                horizontalLineTo(6f)
                verticalLineTo(9f)
                horizontalLineTo(4f)
                verticalLineToRelative(2f)
                close()
                moveTo(4f, 7f)
                horizontalLineTo(6f)
                verticalLineTo(5f)
                horizontalLineTo(4f)
                verticalLineTo(7f)
                close()
                moveTo(8f, 19f)
                horizontalLineToRelative(2f)
                verticalLineTo(17f)
                horizontalLineTo(8f)
                verticalLineToRelative(2f)
                close()
                moveTo(8f, 15f)
                horizontalLineToRelative(2f)
                verticalLineTo(13f)
                horizontalLineTo(8f)
                verticalLineToRelative(2f)
                close()
                moveTo(8f, 11f)
                horizontalLineToRelative(2f)
                verticalLineTo(9f)
                horizontalLineTo(8f)
                verticalLineToRelative(2f)
                close()
                moveTo(8f, 7f)
                horizontalLineToRelative(2f)
                verticalLineTo(5f)
                horizontalLineTo(8f)
                verticalLineTo(7f)
                close()
                moveToRelative(4f, 12f)
                horizontalLineToRelative(8f)
                verticalLineTo(9f)
                horizontalLineTo(12f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineTo(12f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineTo(12f)
                verticalLineToRelative(2f)
                close()
                moveToRelative(4f, -6f)
                verticalLineTo(11f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineTo(16f)
                close()
                moveToRelative(0f, 4f)
                verticalLineTo(15f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineTo(16f)
                close()
            }
        }.build()
        return _domainIcon!!
    }

private var _domainIcon: ImageVector? = null
