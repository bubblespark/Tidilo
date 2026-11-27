package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import me.bubblespark.tidilo.config.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in visual catalogue: run with `-e captureThemeCatalogue true` to write one capture per scene. */
class ThemeSurfaceParityTest {
    companion object {
        @BeforeClass @JvmStatic fun requireOptIn() = Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("captureThemeCatalogue") == "true"
        )
    }

    @get:Rule val composeRule = createComposeRule()

    private data class Shot(val theme: AppTheme, val dark: Boolean, val scene: Int, val blur: Boolean)

    @Test
    fun capturesExistingSurfaceFamilies() {
        val shots = AppTheme.entries.flatMap { theme ->
            listOf(false, true).flatMap { dark ->
                (0..7).flatMap { scene ->
                    (if (scene >= 4) listOf(false, true) else listOf(false)).map { blur -> Shot(theme, dark, scene, blur) }
                }
            }
        }
        var shot by mutableStateOf(shots.first())
        composeRule.setContent {
            ThemeSurfaceCatalogue(shot.theme, shot.dark, false, scene = shot.scene, blurEnabled = shot.blur)
        }
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "theme-catalogue").apply { mkdirs() }
        for (next in shots) {
            composeRule.runOnUiThread { shot = next }
            val root = composeRule.awaitNode(hasTestTag("catalogue-${next.scene}"))
            assertTrue(root.boundsInWindow.width > 0f)
            assertTrue(root.boundsInWindow.height > 0f)
            if (next.scene == 0) composeRule.awaitNode(hasText("Settings fixture"))
            composeRule.waitForIdle()
            val file = File(dir, "theme-${next.theme.code}-${next.dark}-${next.scene}-${next.blur}.png")
            captureScreen { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }
}
