package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R

/** One stable navigation action; only its decorative glyph participates in the transition. */
@Composable
internal fun LibraryNavigationButton(
    isDrillDown: Boolean,
    backContentDescription: String,
    onOpenNavigation: () -> Unit,
    onNavigateUp: () -> Unit
) {
    val description = if (isDrillDown) backContentDescription else stringResource(R.string.navigation_open)
    IconButton(
        onClick = { if (isDrillDown) onNavigateUp() else onOpenNavigation() },
        modifier = Modifier.padding(end = 4.dp).size(48.dp).semantics { contentDescription = description }
    ) {
        Crossfade(
            targetState = isDrillDown,
            animationSpec = tween(220, easing = EaseInOutCubic),
            label = "library_navigation_icon"
        ) { drillDown ->
            Icon(
                imageVector = if (drillDown) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
