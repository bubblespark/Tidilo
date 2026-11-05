package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.theme.*
import org.junit.Assert.*
import org.junit.Test

class BookmarkCardBlurStyleTest {
    @Test fun resolvedCardRetainsBackgroundBlur() {
        val preset = AppThemeStyle.Default.copy(cardBlurRadius = 20.dp)
        val surface = resolveThemeSurfaces(AppTheme.DEFAULT, false, lightColorScheme(), preset)
            .resolve(SurfaceRole.BOOKMARK_CARD)
        assertEquals(20.dp, surface.selfBlurRadius)
        assertEquals(1f, surface.opacity)
    }

    @Test fun disabledBlurReturnsSameModifier() {
        val initial = Modifier
        assertSame(initial, initial.themeSurfaceBlur(0.dp))
    }

    @Test fun positiveBlurStillAppliesToTheBackgroundModifier() {
        val initial = Modifier
        assertNotSame(initial, initial.themeSurfaceBlur(20.dp))
    }
}
