package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R

internal const val MAX_ENTITY_PREVIEW_ROWS = 3
internal val ENTITY_TAG_HORIZONTAL_GAP = 6.dp
internal val ENTITY_TAG_VERTICAL_GAP = 8.dp

@Composable
internal fun EntityTagPreview(modifier: Modifier = Modifier, chips: @Composable () -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val chipConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val horizontalGap = ENTITY_TAG_HORIZONTAL_GAP.roundToPx()
        val verticalGap = ENTITY_TAG_VERTICAL_GAP.roundToPx()
        val tags = subcompose(0, chips).map { it.measure(chipConstraints) }
        val overflow = mutableMapOf<Int, Placeable>()
        val visible = visibleEntityTagCount(tags.map { it.width }, width, horizontalGap) { remaining ->
            overflow.getOrPut(remaining) {
                subcompose(remaining) {
                    Text(
                        text = pluralStringResource(R.plurals.bookmark_remaining_entity_tags, remaining, remaining),
                        modifier = Modifier.heightIn(min = 26.dp).padding(horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }.single().measure(chipConstraints)
            }.width
        }
        val children = tags.take(visible) + listOfNotNull(overflow[tags.size - visible])
        val rows = entityTagRows(children.map { it.width }, width, horizontalGap)
        val rowHeights = rows.map { row -> row.maxOf { children[it].height } }
        val height = rowHeights.sum() + verticalGap * (rows.size - 1).coerceAtLeast(0)
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
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
