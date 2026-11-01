package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class BookmarkCardBlurStyleTest {

    @Test
    fun bookmarkCardStyle_defaultBlurRadius_isZero() {
        val style = BookmarkCardStyle(
            containerColor = Color.White,
            rim = Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
            surfaceWash = null,
            ambientShadowColor = Color.Transparent,
            spotShadowColor = Color.Transparent
        )
        assertEquals(0.dp, style.blurRadius)
    }

    @Test
    fun bookmarkCardStyle_customBlurRadius_isRetained() {
        val style = BookmarkCardStyle(
            containerColor = Color.White,
            rim = Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
            surfaceWash = null,
            ambientShadowColor = Color.Transparent,
            spotShadowColor = Color.Transparent,
            blurRadius = 20.dp
        )
        assertEquals(20.dp, style.blurRadius)
    }

    @Test
    fun bookmarkCardSurfaceBlur_zeroBlur_returnsSameModifier() {
        val style = BookmarkCardStyle(
            containerColor = Color.White,
            rim = Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
            surfaceWash = null,
            ambientShadowColor = Color.Transparent,
            spotShadowColor = Color.Transparent,
            blurRadius = 0.dp
        )
        val initialModifier = Modifier
        val resultModifier = initialModifier.bookmarkCardSurfaceBlur(style)
        assertSame(initialModifier, resultModifier)
    }

    @Test
    fun bookmarkCardSurfaceBlur_positiveBlur_appliesModifier() {
        val style = BookmarkCardStyle(
            containerColor = Color.White,
            rim = Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
            surfaceWash = null,
            ambientShadowColor = Color.Transparent,
            spotShadowColor = Color.Transparent,
            blurRadius = 16.dp
        )
        val initialModifier = Modifier
        val resultModifier = initialModifier.bookmarkCardSurfaceBlur(style)
        assertNotSame(initialModifier, resultModifier)
    }
}
