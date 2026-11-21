package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.screenSystemInsets
import me.bubblespark.tidilo.ui.navigation.LibraryRadialMenuGeometry
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.ceil

internal val BookmarkGridHorizontalGap = 8.dp
internal val BookmarkGridVerticalGap = 10.dp
internal val BookmarkGridInfoSidePadding = 12.dp
internal val BookmarkGridInfoTopPadding = 8.dp
internal val BookmarkGridInfoBottomPadding = 12.dp
internal val BookmarkGridInfoGap = 4.dp
internal val BookmarkGridLikeTarget = 48.dp
private val BookmarkGridMinimumPreview = 24.dp

internal data class BookmarkGridSize(val columns: Int, val mediaHeightPx: Int)

internal data class BookmarkGridLayout(
    val columns: Int,
    val mediaHeight: Dp,
    val text: BookmarkGridTextMetrics
)

internal data class BookmarkGridTextMetrics(
    val labelStyle: TextStyle,
    val titleStyle: TextStyle,
    val labelHeight: Dp,
    val titleHeight: Dp
)

@Composable
internal fun rememberBookmarkGridTextMetrics(): BookmarkGridTextMetrics {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp)
    val titleStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    return remember(measurer, density, labelStyle, titleStyle) {
        with(density) {
            // Use Latin and Japanese glyphs to reserve space for titles in either script.
            BookmarkGridTextMetrics(
                labelStyle = labelStyle,
                titleStyle = titleStyle,
                labelHeight = measurer.measure("Agあ", labelStyle, maxLines = 1).size.height.toDp(),
                titleHeight = measurer.measure("Agあ\nAgあ\nAgあ", titleStyle, maxLines = 3).size.height.toDp()
            )
        }
    }
}

@Composable
internal fun BoxWithConstraintsScope.bookmarkGridLayout(
    contentPadding: PaddingValues,
    trailingPadding: Dp,
    leadingContentHeightPx: Int = 0,
    protectLibraryShortcut: Boolean = false
): BookmarkGridLayout {
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val text = rememberBookmarkGridTextMetrics()
    val insets = if (protectLibraryShortcut) screenSystemInsets() else null

    return with(density) {
        val left = contentPadding.calculateLeftPadding(direction).roundToPx()
        val right = constraints.maxWidth - contentPadding.calculateRightPadding(direction).roundToPx()
        val top = contentPadding.calculateTopPadding().roundToPx() + leadingContentHeightPx
        val safeBottom = constraints.maxHeight -
            (contentPadding.calculateBottomPadding() - trailingPadding).roundToPx()
        val shortcut = insets?.let {
            val diameter = HeaderDefaults.LibraryActionSize.roundToPx()
            val geometry = remember(density.density, diameter) { LibraryRadialMenuGeometry(density.density, diameter) }
            val margin = ((HeaderDefaults.LibraryActionClearance - HeaderDefaults.LibraryActionSize) / 2).roundToPx()
            val center = geometry.anchor(
                constraints.maxWidth, constraints.maxHeight,
                it.getRight(density, LayoutDirection.Ltr), it.getBottom(density), margin
            )
            val triggerLeft = center.x - diameter / 2
            val triggerTop = center.y - diameter / 2
            IntRect(triggerLeft, triggerTop, triggerLeft + diameter, triggerTop + diameter)
        }
        val titleOffset = BookmarkGridInfoTopPadding.roundToPx() + text.labelHeight.roundToPx() +
            BookmarkGridInfoGap.roundToPx()
        val infoHeight = titleOffset + text.titleHeight.roundToPx() +
            BookmarkGridInfoGap.roundToPx() + text.labelHeight.roundToPx() +
            BookmarkGridInfoBottomPadding.roundToPx()
        val size = calculateBookmarkGridLayout(
            contentWidth = (right - left).coerceAtLeast(0),
            minimumCardWidth = 2 * BookmarkGridInfoSidePadding.roundToPx() +
                ceil(9 * text.titleStyle.fontSize.toPx()).toInt(),
            horizontalGap = BookmarkGridHorizontalGap.roundToPx(),
            // In shallow windows the Like target can extend beside the domain, above the title.
            minimumMediaHeight = maxOf(
                BookmarkGridMinimumPreview.roundToPx(),
                BookmarkGridLikeTarget.roundToPx() - titleOffset
            ),
            infoHeight = infoHeight
        ) { cellWidth ->
            val firstLeft = if (direction == LayoutDirection.Ltr) left else right - cellWidth
            val bottom = if (shortcut != null && firstLeft < shortcut.right && firstLeft + cellWidth > shortcut.left) {
                minOf(safeBottom, shortcut.top)
            } else safeBottom
            bottom - top
        }
        BookmarkGridLayout(size.columns, size.mediaHeightPx.toDp(), text)
    }
}

internal fun calculateBookmarkGridLayout(
    contentWidth: Int,
    minimumCardWidth: Int,
    horizontalGap: Int,
    minimumMediaHeight: Int,
    infoHeight: Int,
    availableHeight: (cellWidth: Int) -> Int
): BookmarkGridSize {
    val maxColumns = ((contentWidth + horizontalGap) / (minimumCardWidth + horizontalGap))
        .coerceAtLeast(1)
    fun candidate(columns: Int): Pair<Int, Int> {
        val rowWidth = contentWidth - (columns - 1) * horizontalGap
        val cellWidth = (rowWidth + columns - 1) / columns
        val mediaHeight = maxOf(minimumMediaHeight, (cellWidth * 9 + 8) / 16)
        return mediaHeight to (availableHeight(cellWidth) - infoHeight)
    }
    for (columns in minOf(2, maxColumns) until maxColumns) {
        val (mediaHeight, mediaBudget) = candidate(columns)
        if (mediaHeight <= mediaBudget) return BookmarkGridSize(columns, mediaHeight)
    }
    val (mediaHeight, mediaBudget) = candidate(maxColumns)
    return BookmarkGridSize(maxColumns, minOf(mediaHeight, maxOf(minimumMediaHeight, mediaBudget)))
}

internal class BookmarkGridAnchor(
    var generation: String = "",
    var viewport: IntSize = IntSize.Zero,
    var columns: Int = 0,
    var mediaHeight: Float = 0f,
    var bookmarkKey: String? = null,
    var textConfiguration: String = ""
) {
    fun clear() {
        bookmarkKey = null
    }
}

private val BookmarkGridAnchorSaver = listSaver<BookmarkGridAnchor, Any>(
    save = {
        listOf(it.generation, it.viewport.width, it.viewport.height, it.columns, it.mediaHeight,
            it.bookmarkKey.orEmpty(), it.textConfiguration)
    },
    restore = {
        BookmarkGridAnchor(
            generation = it[0] as String,
            viewport = IntSize(it[1] as Int, it[2] as Int),
            columns = it[3] as Int,
            mediaHeight = it[4] as Float,
            bookmarkKey = (it[5] as String).ifEmpty { null },
            textConfiguration = it.getOrNull(6) as? String ?: ""
        )
    }
)

@Composable
internal fun rememberBookmarkGridAnchor(): BookmarkGridAnchor =
    rememberSaveable(saver = BookmarkGridAnchorSaver) { BookmarkGridAnchor() }

internal fun shouldRestoreBookmarkGridAnchor(
    anchor: BookmarkGridAnchor,
    generation: String,
    viewport: IntSize,
    textConfiguration: String,
    columns: Int,
    mediaHeight: Float
): Boolean {
    val textChanged = anchor.textConfiguration != textConfiguration
    val environmentChanged = anchor.viewport != viewport || textChanged
    val rowsChanged = textChanged || anchor.columns != columns || anchor.mediaHeight > mediaHeight
    return anchor.bookmarkKey != null && anchor.generation == generation && environmentChanged && rowsChanged
}

@Composable
internal fun RestoreBookmarkGridAnchor(
    state: LazyGridState,
    anchor: BookmarkGridAnchor,
    generation: String,
    layout: BookmarkGridLayout,
    leadingItems: Int = 0,
    indexOfBookmark: (String) -> Int
) {
    val viewport = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val localeTags = LocalConfiguration.current.locales.toLanguageTags()
    val textConfiguration = "${density.density}:${density.fontScale}:$localeTags"
    val lookupIndex by rememberUpdatedState(indexOfBookmark)
    LaunchedEffect(state, anchor, generation, viewport, textConfiguration, layout.columns, layout.mediaHeight) {
        val previousKey = anchor.bookmarkKey
        // A keyboard-only media resize keeps the native offset and does not interrupt scrolling.
        if (previousKey != null && shouldRestoreBookmarkGridAnchor(
                anchor, generation, viewport, textConfiguration, layout.columns, layout.mediaHeight.value
            )
        ) {
            val index = lookupIndex(previousKey)
            if (index >= 0) state.requestScrollToItem(index + leadingItems)
        }
        if (anchor.generation != generation) anchor.clear()
        anchor.generation = generation
        anchor.viewport = viewport
        anchor.textConfiguration = textConfiguration
        anchor.columns = layout.columns
        anchor.mediaHeight = layout.mediaHeight.value
        snapshotFlow {
            val visible = state.layoutInfo.visibleItemsInfo
            val first = state.firstVisibleItemIndex
            val atStart = first == 0 && state.firstVisibleItemScrollOffset == 0
            if (visible.isEmpty()) null else if (atStart) "" else {
                visible.firstOrNull { it.index == maxOf(first, leadingItems) }?.key as? String ?: ""
            }
        }.filterNotNull().collect { anchor.bookmarkKey = it.ifEmpty { null } }
    }
}
