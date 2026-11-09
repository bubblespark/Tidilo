package me.bubblespark.tidilo.data

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import me.bubblespark.tidilo.ui.components.TidiloFrostedHeader
import me.bubblespark.tidilo.ui.components.TidiloIconToggleButton
import me.bubblespark.tidilo.ui.components.TidiloModalBottomSheet
import me.bubblespark.tidilo.ui.components.TidiloDropdownMenu
import me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.data.db.CollectionEntity
import me.bubblespark.tidilo.data.db.CollectionWithCount
import me.bubblespark.tidilo.ui.screens.library.CollectionCard
import me.bubblespark.tidilo.ui.screens.library.BookmarkGridCard
import me.bubblespark.tidilo.ui.screens.library.BookmarkTagSection
import me.bubblespark.tidilo.ui.screens.settings.RssSettingsContent
import me.bubblespark.tidilo.ui.screens.settings.RssRegistrationDialog
import me.bubblespark.tidilo.ui.screens.settings.StorageLoad
import me.bubblespark.tidilo.data.rss.RssSettings

/** Deterministic real-component fixtures; never uses stored bookmark content. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ThemeSurfaceCatalogue(
    theme: AppTheme,
    darkTheme: Boolean,
    dynamicColor: Boolean,
    fontScale: Float = 1f,
    width: Dp = 360.dp,
    scene: Int = 0,
    blurEnabled: Boolean = true,
) {
    val density = LocalDensity.current.density
    CompositionLocalProvider(
        LocalDensity provides Density(density, fontScale),
        LocalAppThemeStyle provides AppThemeStyle.forTheme(theme)
    ) {
        TidiloTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, theme = theme, blurEnabled = blurEnabled) {
            if (scene >= 4) Box(Modifier.fillMaxSize().background(Color(0xFF265C89)))
            Column(Modifier.width(width).heightIn(min = 60.dp).testTag("catalogue-$scene")
                .background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                when (scene) {
                0 -> {
                BookmarkItemCard(
                    bookmark = BookmarkItem("theme-fixture", "Theme fixture", "", "Fixture summary",
                        Tag("Article", "Tech", listOf("Compose")), createdAt = 0L)
                )
                SettingsGroup { Text("Settings fixture", Modifier.padding(20.dp)) }
                }
                1 -> {
                    CollectionCard(CollectionWithCount(CollectionEntity("catalogue", "Collection fixture", "Description",
                        "folder", null, 0, 0L, 0L), 3), onClick = {})
                    BookmarkGridCard(BookmarkItem("grid-fixture", "Grid fixture", "", "Summary", Tag(), createdAt = 0L))
                }
                2 -> {
                    TidiloFrostedHeader(title = { Text("Fixture header") })
                    BookmarkTagSection(Tag("Article", "Tech", listOf("Compose")), "Article", "Tech", isNsfw = true)
                    TidiloIconToggleButton(true, {}, SurfaceRole.LIKE_ACTION) { Text("Like") }
                }
                3 -> RssSettingsContent(StorageLoad.Ready(emptyList()), RssSettings(), false, null, true,
                    onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> },
                    onDelete = {}, onFetch = {}, onDismissError = {})
                4 -> RssRegistrationDialog(2, false, onConfirm = {}, onDismiss = {})
                5 -> TidiloModalBottomSheet({}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Text("Sheet fixture", Modifier.height(220.dp).padding(24.dp))
                }
                6 -> Box { TidiloDropdownMenu(true, {}) {
                    DropdownMenuItem(text = { Text("Menu fixture") }, onClick = {})
                } }
                7 -> {
                    val rail = rememberWideNavigationRailState()
                    LaunchedEffect(rail) { rail.expand() }
                    LibraryNavigationRail(rail, LibraryFilterMode.NEWEST_FIRST, {}, {}, {}, {}, {})
                }
                }
            }
        }
    }
}
