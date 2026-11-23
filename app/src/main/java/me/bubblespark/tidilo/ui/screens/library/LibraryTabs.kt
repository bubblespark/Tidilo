package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.screenSystemInsets

private val LibraryTabHeight = 48.dp
private val LibraryTabTopGap = 4.dp
private val LibraryTabBottomGap = 8.dp

/** Height the tab row adds under the header's title row; the scaffold reserves it as top clearance. */
internal val LibraryTabsHeight = LibraryTabHeight + LibraryTabTopGap + LibraryTabBottomGap

private val libraryTabs = listOf(
    LibraryFilterMode.NEWEST_FIRST to R.string.library_tab_newest,
    LibraryFilterMode.DOMAIN to R.string.library_tab_domain,
    LibraryFilterMode.RSS to R.string.rss_title,
    LibraryFilterMode.COLLECTION to R.string.collection_title
)

/** Connected button group (§9): Newest / Domain / RSS / Collection; the selected segment becomes a pill. */
@Composable
internal fun LibraryTabs(selected: LibraryFilterMode, onSelect: (LibraryFilterMode) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.library_tabs_label)
    Row(
        modifier
            .fillMaxWidth()
            // Same horizontal safe area as the header's title row (cutouts, landscape navigation bar).
            .windowInsetsPadding(screenSystemInsets().only(WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 16.dp, top = LibraryTabTopGap, bottom = LibraryTabBottomGap)
            .selectableGroup()
            .semantics { contentDescription = label }
            .testTag("library_tabs"),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        libraryTabs.forEachIndexed { index, (mode, labelRes) ->
            ToggleButton(
                checked = mode == selected,
                onCheckedChange = { onSelect(mode) },
                modifier = Modifier.weight(1f).height(LibraryTabHeight).testTag("library_tab_${mode.name}"),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    libraryTabs.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
