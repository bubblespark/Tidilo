package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R

internal const val MAX_ENTITY_PREVIEW_ROWS = 3
internal val ENTITY_TAG_HORIZONTAL_GAP = 6.dp
internal val ENTITY_TAG_VERTICAL_GAP = 8.dp

@Composable
internal fun EntityTagPreview(modifier: Modifier = Modifier, chips: @Composable () -> Unit) {
    val resources = LocalResources.current
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val chipConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val horizontalGap = ENTITY_TAG_HORIZONTAL_GAP.roundToPx()
        val verticalGap = ENTITY_TAG_VERTICAL_GAP.roundToPx()
        val labelPadding = 4.dp
        val horizontalPadding = 2 * labelPadding.roundToPx()
        val labelWidth = if (constraints.hasBoundedWidth) (width - horizontalPadding).coerceAtLeast(0) else width
        val tags = subcompose(0, chips).map { it.measure(chipConstraints) }
        val labels = mutableMapOf<Int, OverflowLabel>()
        fun measureLabel(text: String): Int = textMeasurer.measure(
            text = text, style = labelStyle, softWrap = false, maxLines = 1
        ).size.width
        val visible = visibleEntityTagCount(tags.map { it.width }, width, horizontalGap) { remaining ->
            val full = resources.getQuantityString(R.plurals.bookmark_remaining_entity_tags, remaining, remaining)
            val fullWidth = measureLabel(full)
            val label = if (fullWidth <= labelWidth) {
                OverflowLabel(full, full, fullWidth + horizontalPadding)
            } else {
                val compact = resources.getString(R.string.bookmark_remaining_entity_tags_compact, remaining)
                OverflowLabel(compact, full, (measureLabel(compact) + horizontalPadding).coerceAtMost(width))
            }
            labels[remaining] = label
            label.width
        }
        val overflow = labels[tags.size - visible]?.let { label ->
            subcompose("overflow") {
                val semantics = if (label.text == label.accessibleText) Modifier else Modifier.clearAndSetSemantics {
                    text = AnnotatedString(label.accessibleText)
                }
                Text(
                    text = label.text,
                    modifier = semantics.heightIn(min = 26.dp)
                        .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = labelPadding),
                    color = labelColor,
                    style = labelStyle,
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }.single().measure(chipConstraints)
        }
        val children = tags.take(visible) + listOfNotNull(overflow)
        val rows = entityTagRows(children.map { it.width }, width, horizontalGap)
        val rowHeights = rows.map { row -> row.maxOf { children[it].height } }
        val height = rowHeights.sum() + verticalGap * (rows.size - 1).coerceAtLeast(0)
        val layoutWidth = if (constraints.hasBoundedWidth) width else constraints.constrainWidth(
            rows.maxOfOrNull { row -> row.sumOf { children[it].width } + horizontalGap * (row.last - row.first) } ?: 0
        )
        layout(layoutWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var top = 0
            rows.forEachIndexed { index, row ->
                var start = 0
                for (childIndex in row) {
                    val child = children[childIndex]
                    child.placeRelative(start, top + (rowHeights[index] - child.height) / 2)
                    start += child.width + horizontalGap
                }
                top += rowHeights[index] + verticalGap
            }
        }
    }
}

private data class OverflowLabel(val text: String, val accessibleText: String, val width: Int)

internal fun visibleEntityTagCount(
    widths: List<Int>,
    maxWidth: Int,
    gap: Int,
    overflowWidth: (Int) -> Int
): Int {
    val tagRows = entityTagRows(widths, maxWidth, gap)
    if (tagRows.size <= MAX_ENTITY_PREVIEW_ROWS) return widths.size
    val tagsBeforeOverflow = tagRows[MAX_ENTITY_PREVIEW_ROWS - 1].last + 1
    for (visible in tagsBeforeOverflow downTo 0) {
        val candidate = widths.take(visible) + overflowWidth(widths.size - visible)
        if (entityTagRows(candidate, maxWidth, gap).size <= MAX_ENTITY_PREVIEW_ROWS) return visible
    }
    return 0
}

internal fun entityTagRows(widths: List<Int>, maxWidth: Int, gap: Int): List<IntRange> = buildList {
    if (widths.isEmpty()) return@buildList
    var start = 0
    var used = widths.first()
    for (index in 1 until widths.size) {
        val width = widths[index]
        if (used + gap + width > maxWidth) {
            add(start until index)
            start = index
            used = width
        } else {
            used += gap + width
        }
    }
    add(start until widths.size)
}
