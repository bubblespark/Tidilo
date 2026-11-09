package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import me.bubblespark.tidilo.ui.components.headerBackdropMask
import org.junit.Assert.*
import org.junit.Test

class HeaderBackdropMaskTest {
    @Test fun firstDrawAndResizeUseActualBoundsWithoutMeasuredState() {
        val mask = headerBackdropMask(18f, 6f)
        mask.applyTo(Size.Zero, Paint(), 1f)
        for (height in listOf(200, 320, 120)) {
            val bitmap = Bitmap.createBitmap(100, height, Bitmap.Config.ARGB_8888)
            try {
                val paint = Paint()
                mask.applyTo(Size(100f, height.toFloat()), paint, 1f)
                Canvas(bitmap.asImageBitmap()).drawRect(Rect(0f, 0f, 100f, height.toFloat()), paint)
                assertEquals("First draw must retain the opaque header body", 255, android.graphics.Color.alpha(bitmap.getPixel(50, 20)))
                assertTrue("The last pixel before the transparent edge must be almost clear", android.graphics.Color.alpha(bitmap.getPixel(50, height - 1)) <= 6)
                assertTrue("The dissolve region must retain an intermediate alpha", android.graphics.Color.alpha(bitmap.getPixel(50, height - 12)) in 40..210)
            } finally { bitmap.recycle() }
        }
    }
}
