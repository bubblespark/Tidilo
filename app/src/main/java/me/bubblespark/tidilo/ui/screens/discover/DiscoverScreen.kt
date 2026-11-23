package me.bubblespark.tidilo.ui.screens.discover

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.ScreenBackdropScope
import me.bubblespark.tidilo.ui.components.StatusBarScrim
import me.bubblespark.tidilo.ui.components.screenBackdropSource
import me.bubblespark.tidilo.ui.components.screenSystemInsets

private const val PLATE_ASPECT_RATIO = 1.91f
private val SkeletonRowPlateWidth = 280.dp
private const val SkeletonCarouselRowCount = 4
private const val SkeletonPlatesPerRow = 4

@Composable
fun DiscoverScreen(
    onOpenNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val systemPadding = screenSystemInsets().asPaddingValues()
    val startInset = systemPadding.calculateStartPadding(layoutDirection)
    val endInset = systemPadding.calculateEndPadding(layoutDirection)
    val scrimThresholdPx = with(density) { 8.dp.roundToPx() }
    val scrolled by remember(listState, scrimThresholdPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > scrimThresholdPx
        }
    }

    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    }
    val dateText = remember(locale) {
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
        SimpleDateFormat(pattern, locale).format(Date())
    }
    val greeting = stringResource(R.string.discover_greeting_growing)

    ScreenBackdropScope { backdrop ->
        Box(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .screenBackdropSource(backdrop),
                contentPadding = PaddingValues(
                    top = systemPadding.calculateTopPadding() + 8.dp,
                    bottom = HeaderDefaults.FloatingNavigationClearance + systemPadding.calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item(key = "discover_header", contentType = "header") {
                    DiscoverHeader(
                        dateText = dateText,
                        greeting = greeting,
                        onOpenNavigation = onOpenNavigation,
                        modifier = Modifier.padding(start = startInset + 8.dp, end = endInset + 16.dp)
                    )
                }
                item(key = "discover_skeleton_hero", contentType = "skeleton_hero") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = startInset + 16.dp, end = endInset + 16.dp)
                            .aspectRatio(PLATE_ASPECT_RATIO)
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clearAndSetSemantics {}
                    )
                }
                items(
                    count = SkeletonCarouselRowCount,
                    key = { row -> "discover_skeleton_row_$row" },
                    contentType = { "skeleton_row" }
                ) { row ->
                    LazyRow(
                        contentPadding = PaddingValues(
                            start = startInset + 16.dp,
                            end = endInset + 16.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clearAndSetSemantics {}
                    ) {
                        items(
                            count = SkeletonPlatesPerRow,
                            key = { index -> "discover_skeleton_${row}_$index" }
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(SkeletonRowPlateWidth)
                                    .aspectRatio(PLATE_ASPECT_RATIO)
                                    .clip(MaterialTheme.shapes.large)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            )
                        }
                    }
                }
            }

            StatusBarScrim(
                visible = scrolled,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
fun DiscoverHeader(
    dateText: String,
    greeting: String,
    onOpenNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("discover_header")
            .semantics { heading() },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(
            onClick = onOpenNavigation,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = stringResource(R.string.navigation_open),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = dateText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
