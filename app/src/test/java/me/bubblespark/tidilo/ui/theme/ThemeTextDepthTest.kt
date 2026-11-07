package me.bubblespark.tidilo.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import me.bubblespark.tidilo.config.AppTheme
import org.junit.Assert.*
import org.junit.Test

class ThemeTextDepthTest {
    @Test fun depthIsOptInAndPreservesTextStyle() {
        val base = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
        for (dark in listOf(false, true)) {
            val raised = resolveRaisedTextStyle(base, AppTheme.MODERN, dark, true, 2f)
            val shadow = requireNotNull(raised.shadow)
            assertEquals(Offset(0f, 2f), shadow.offset)
            assertEquals(2f, shadow.blurRadius, 0f)
            assertEquals(if (dark) 0.30f else 0.20f, shadow.color.alpha, 0.01f)
            assertEquals(base, raised.copy(shadow = base.shadow))
            assertSame(base, resolveRaisedTextStyle(base, AppTheme.MODERN, dark, false, 2f))
            assertSame(base, resolveRaisedTextStyle(base, AppTheme.DEFAULT, dark, true, 2f))
        }
    }
}
