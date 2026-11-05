package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSurfaceParityTest {
    @Test
    fun capturesExistingSurfaceFamilies() = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            for (theme in AppTheme.entries) for (dark in listOf(false, true)) {
                host.main { activity.setContent { ThemeSurfaceCatalogue(theme, dark, false) } }
                val card = host.await(activity) { host.text(it, "Theme fixture") }
                val group = host.await(activity) { host.text(it, "Settings fixture") }
                assertTrue(card.boundsInWindow.width > 0f)
                assertTrue(group.boundsInWindow.height > 0f)
                host.capture("theme-${theme.code}-$dark")
            }
        } finally {
            host.main { activity.finish() }
        }
    }
}
