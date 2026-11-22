package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.SurfaceVariant
import me.bubblespark.tidilo.ui.theme.ThemeSurfaceState
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces

/** Lazy item key and content type of the Newest first scope row. */
internal const val NewestFirstScopeKey = "newest_first_scope"

private val NewestFirstScopeMinTouchTarget = 48.dp

/**
 * Height of the Newest first scope row: the chip touch target, or its single-line label when larger.
 * Derived from text metrics so the Grid height budget does not depend on a measured layout pass.
 */
@Composable
internal fun rememberNewestFirstScopeRowHeight(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge
    return remember(measurer, density, style) {
        val labelHeight = with(density) { measurer.measure("Agあ", style, maxLines = 1).size.height.toDp() }
        max(NewestFirstScopeMinTouchTarget, labelHeight)
    }
}

/** Scrolling Newest first row with the single "Include RSS" source-scope chip. */
@Composable
internal fun NewestFirstScopeRow(
    includeRss: Boolean,
    onIncludeRssChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val surfaces = currentThemeSurfaces()
    val unselected = surfaces.resolve(SurfaceRole.FILTER_CHIP, SurfaceVariant.COMPACT, ThemeSurfaceState(selected = false))
    val selected = surfaces.resolve(SurfaceRole.FILTER_CHIP, SurfaceVariant.COMPACT, ThemeSurfaceState(selected = true))
    Row(
        modifier = modifier.fillMaxWidth().height(rememberNewestFirstScopeRowHeight()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = includeRss,
            onClick = { onIncludeRssChange(!includeRss) },
            label = { Text(stringResource(R.string.library_include_rss_chip)) },
            leadingIcon = if (includeRss) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else null,
            shape = if (includeRss) selected.shape else unselected.shape,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = unselected.containerColor,
                labelColor = unselected.contentColor,
                iconColor = unselected.contentColor,
                selectedContainerColor = selected.containerColor,
                selectedLabelColor = selected.contentColor,
                selectedLeadingIconColor = selected.contentColor
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = includeRss,
                borderColor = unselected.borderColor,
                selectedBorderColor = selected.borderColor,
                borderWidth = unselected.borderWidth,
                selectedBorderWidth = selected.borderWidth
            )
        )
    }
}
