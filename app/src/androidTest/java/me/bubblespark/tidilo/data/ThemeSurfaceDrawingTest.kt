package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.components.ResolvedTidiloSurface
import me.bubblespark.tidilo.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual painted pixels: background ownership cannot be proven by modifier identity. */
class ThemeSurfaceDrawingTest {
    @Test fun decoratedZeroElevationDrawsOpaqueFillAndBorder() = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val fill = Color(0xFF995533)
        try {
            host.main {
                activity.setContent {
                    MaterialTheme {
                        Box(Modifier.padding(30.dp)) {
                            ResolvedTidiloSurface(
                                ResolvedSurfaceStyle(containerColor = fill, treatment = SurfaceTreatment.DECORATED,
                                    elevation = 0.dp, borderWidth = 1.dp, borderColor = Color.Black),
                                Modifier.size(180.dp, 100.dp).testTag("painted-surface")
                            ) { Text("Sharp content") }
                        }
                    }
                }
            }
            val node = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "painted-surface" }
            host.instrumentation.waitForIdleSync()
            kotlinx.coroutines.delay(350)
            val bitmap = host.instrumentation.uiAutomation.takeScreenshot()
            try {
                val bounds = node.boundsInWindow
                assertEquals(fill.toArgb(), bitmap.getPixel(bounds.center.x.toInt(), (bounds.bottom - 25).toInt()))
                assertTrue((0..3).any { offset ->
                    android.graphics.Color.red(bitmap.getPixel(bounds.center.x.toInt(), bounds.top.toInt() + offset)) < 70
                })
                assertTrue(host.nodes(activity).any { host.text(it, "Sharp content") })
            } finally { bitmap.recycle() }
        } finally { host.main { activity.finish() } }
    }
}
