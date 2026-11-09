package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import me.bubblespark.tidilo.config.AppTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSurfaceParityTest {
    @Test
    fun capturesExistingSurfaceFamilies() = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            for (theme in AppTheme.entries) for (dark in listOf(false, true)) for (scene in 0..7)
                for (blur in if (scene >= 4) listOf(false, true) else listOf(false)) {
                host.main { activity.setContent { ThemeSurfaceCatalogue(theme, dark, false, scene = scene, blurEnabled = blur) } }
                val root = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "catalogue-$scene" }
                assertTrue(root.boundsInWindow.width > 0f)
                assertTrue(root.boundsInWindow.height > 0f)
                if (scene == 0) host.await(activity) { host.text(it, "Settings fixture") }
                if (scene >= 4) delay(500)
                host.capture("theme-${theme.code}-$dark-$scene-$blur")
            }
        } finally {
            host.main { activity.finish() }
        }
    }
}
