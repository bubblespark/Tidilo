package me.bubblespark.tidilo.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarHorizontalFabPosition
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.ChromeBlurBackground
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.rememberAppChromeBackdropInput
import me.bubblespark.tidilo.ui.components.screenActionInsets

private const val DefaultSpatialDamping = 0.8f
private const val DefaultSpatialStiffness = 380f
private const val FastSpatialDamping = 0.6f
private const val FastSpatialStiffness = 800f

@Composable
fun TidiloFloatingNavigation(
    selected: TopLevelDestination,
    visible: Boolean,
    onSelect: (TopLevelDestination) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visibilitySpec = remember {
        spring<IntOffset>(dampingRatio = DefaultSpatialDamping, stiffness = DefaultSpatialStiffness)
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(animationSpec = visibilitySpec) { it },
        exit = slideOutVertically(animationSpec = visibilitySpec) { it },
        modifier = modifier
    ) {
        val fabInteractionSource = remember { MutableInteractionSource() }
        val fabPressed by fabInteractionSource.collectIsPressedAsState()
        val fabCorner by animateDpAsState(
            targetValue = if (fabPressed) 28.dp else 16.dp,
            animationSpec = spring(dampingRatio = FastSpatialDamping, stiffness = FastSpatialStiffness),
            label = "floating_nav_fab_corner"
        )
        val fabScale by animateFloatAsState(
            targetValue = if (fabPressed) 0.94f else 1f,
            animationSpec = spring(dampingRatio = FastSpatialDamping, stiffness = FastSpatialStiffness),
            label = "floating_nav_fab_scale"
        )
        val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer

        HorizontalFloatingToolbar(
            expanded = true,
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onSave,
                    shape = RoundedCornerShape(fabCorner),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    interactionSource = fabInteractionSource,
                    modifier = Modifier
                        .size(56.dp)
                        .graphicsLayer {
                            scaleX = fabScale
                            scaleY = fabScale
                        }
                        .testTag("floating_nav_save")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.library_empty_save_url)
                    )
                }
            },
            modifier = Modifier
                .screenActionInsets()
                .padding(bottom = HeaderDefaults.FloatingNavigationOffset)
                .testTag("floating_nav"),
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = Color.Transparent,
                toolbarContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            contentPadding = PaddingValues(0.dp),
            shape = CircleShape,
            floatingActionButtonPosition = FloatingToolbarHorizontalFabPosition.End,
            expandedShadowElevation = 6.dp,
            collapsedShadowElevation = 6.dp
        ) {
            Box(
                modifier = Modifier
                    .height(HeaderDefaults.FloatingNavigationHeight)
                    .clip(CircleShape)
            ) {
                ChromeBlurBackground(
                    shape = CircleShape,
                    containerColor = surfaceContainer,
                    unavailableColor = surfaceContainer.copy(alpha = 0.85f),
                    input = rememberAppChromeBackdropInput(),
                    modifier = Modifier.matchParentSize()
                )
                Row(
                    modifier = Modifier
                        .padding(8.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopLevelDestination.entries.forEach { destination ->
                        FloatingNavigationItem(
                            destination = destination,
                            selected = destination == selected,
                            onClick = { onSelect(destination) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingNavigationItem(
    destination: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit
) {
    val label = stringResource(destination.labelRes)
    val tag = when (destination) {
        TopLevelDestination.DISCOVER -> "floating_nav_discover"
        TopLevelDestination.LIBRARY -> "floating_nav_library"
        TopLevelDestination.SEARCH -> "floating_nav_search"
    }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .height(48.dp)
            .widthIn(min = 48.dp)
            .clip(CircleShape)
            .background(containerColor)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick
            )
            .semantics {
                if (!selected) {
                    contentDescription = label
                }
            }
            .testTag(tag)
            .animateContentSize(
                animationSpec = spring<IntSize>(
                    dampingRatio = DefaultSpatialDamping,
                    stiffness = DefaultSpatialStiffness
                )
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
            contentDescription = null,
            tint = contentColor
        )
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
