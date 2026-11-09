package me.bubblespark.tidilo.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.rememberOverlayBlurLayer
import me.bubblespark.tidilo.ui.components.overlayBlurBackground
import me.bubblespark.tidilo.ui.components.withNativeOverlayBackground
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode

private val navigationFilters = listOf(
    Triple(LibraryFilterMode.NEWEST_FIRST, R.string.bottom_bar_newest_first, Icons.Default.Schedule),
    Triple(LibraryFilterMode.DOMAIN, R.string.bottom_bar_domain, Icons.Default.Language),
    Triple(LibraryFilterMode.RSS, R.string.rss_title, Icons.Default.RssFeed),
    Triple(LibraryFilterMode.COLLECTION, R.string.collection_title, Icons.Default.CollectionsBookmark)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryNavigationRail(state: WideNavigationRailState, selectedFilter: LibraryFilterMode,
    onSelectFilter: (LibraryFilterMode) -> Unit, onSearch: () -> Unit, onNotifications: () -> Unit,
    onManualImport: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier, onTrash: () -> Unit = {}) {
    var contentPresent by remember { mutableStateOf(false) }
    val layer = rememberOverlayBlurLayer(state.targetValue == WideNavigationRailValue.Expanded || contentPresent)
    val shape = WideNavigationRailDefaults.modalExpandedShape
    val colors = WideNavigationRailDefaults.colors()
    val surfaceModifier = if (layer == null) modifier else modifier
        .withNativeOverlayBackground(Modifier.overlayBlurBackground(layer, shape, colors.modalContainerColor))
    ModalWideNavigationRail(state = state, hideOnCollapse = true,
        expandedShape = shape,
        colors = if (layer == null) colors else colors.copy(modalContainerColor = Color.Transparent),
        modifier = surfaceModifier) {
        DisposableEffect(Unit) {
            contentPresent = true
            onDispose { contentPresent = false }
        }
        Column(Modifier.fillMaxHeight().widthIn(min = 260.dp, max = 320.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onNotifications) {
                    Icon(painterResource(R.drawable.ic_notifications), stringResource(R.string.settings_notification_title))
                }
            }
            HorizontalDivider()
            BoxWithConstraints(Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(12.dp)) {
                    NavigationDrawerItem(label = { Text(stringResource(R.string.nav_search)) }, selected = false,
                        onClick = onSearch, icon = { Icon(Icons.Default.Search, null) })
                    NavigationDrawerItem(label = { Text(stringResource(R.string.trash_title)) }, selected = false,
                        onClick = onTrash, icon = { Icon(Icons.Default.Delete, null) })
                    Text(stringResource(R.string.navigation_filters), Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    navigationFilters.forEach { (mode, label, icon) ->
                        NavigationDrawerItem(label = { Text(stringResource(label)) }, selected = selectedFilter == mode,
                            onClick = { onSelectFilter(mode) }, icon = { Icon(icon, null) })
                    }
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.height(32.dp))
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    NavigationDrawerItem(label = { Text(stringResource(R.string.navigation_manual_import)) }, selected = false,
                        onClick = onManualImport, icon = { Icon(Icons.Default.AddLink, null) })
                    NavigationDrawerItem(label = { Text(stringResource(R.string.title_settings)) }, selected = false,
                        onClick = onSettings, icon = { Icon(Icons.Default.Settings, null) })
                }
            }
        }
    }
}
