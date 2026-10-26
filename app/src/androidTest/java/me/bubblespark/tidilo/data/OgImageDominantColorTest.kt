package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OgImageDominantColorTest {
    @Test fun actualBitmapsFromSameDomainHaveIndependentAndReactiveColors(): Unit = runBlocking {
        val amber = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val cyan = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        try {
            amber.eraseColor(android.graphics.Color.rgb(245, 170, 20))
            cyan.eraseColor(android.graphics.Color.rgb(20, 190, 240))
            val first = requireNotNull(OgImageManager.getDominantColor("palette.example", "amber", amber.asImageBitmap()))
            val second = requireNotNull(OgImageManager.getDominantColor("palette.example", "cyan", cyan.asImageBitmap()))
            assertTrue(first.red > first.blue + 0.3f)
            assertTrue(second.blue > second.red + 0.3f)
            assertEquals(first, OgImageManager.getDominantColor("palette.example", "amber", amber.asImageBitmap()))
            amber.eraseColor(android.graphics.Color.rgb(20, 190, 240))
            val changed = OgImageManager.getDominantColor("palette.example", "amber", amber.asImageBitmap())
            assertEquals(second, changed)
        } finally {
            OgImageManager.clearMemoryCache()
            amber.recycle()
            cyan.recycle()
        }
    }
}
