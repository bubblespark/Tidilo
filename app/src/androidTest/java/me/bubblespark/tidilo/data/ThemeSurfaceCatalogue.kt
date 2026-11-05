package me.bubblespark.tidilo.data

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.BookmarkItemCard
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.screens.settings.SettingsGroup
import me.bubblespark.tidilo.ui.theme.AppThemeStyle
import me.bubblespark.tidilo.ui.theme.LocalAppThemeStyle
import me.bubblespark.tidilo.ui.theme.TidiloTheme

/** Deterministic real-component fixtures; never uses stored bookmark content. */
@Composable
internal fun ThemeSurfaceCatalogue(
    theme: AppTheme,
    darkTheme: Boolean,
    dynamicColor: Boolean,
    fontScale: Float = 1f,
    width: Dp = 360.dp
) {
    val density = LocalDensity.current.density
    CompositionLocalProvider(
        LocalDensity provides Density(density, fontScale),
        LocalAppThemeStyle provides AppThemeStyle.forTheme(theme)
    ) {
        TidiloTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
            Column(Modifier.width(width).background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                BookmarkItemCard(
                    bookmark = BookmarkItem("theme-fixture", "Theme fixture", "", "Fixture summary",
                        Tag("Article", "Tech", listOf("Compose")), createdAt = 0L)
                )
                SettingsGroup { Text("Settings fixture", Modifier.padding(20.dp)) }
            }
        }
    }
}
