package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.ui.components.TidiloSurface
import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import me.bubblespark.tidilo.ui.theme.ThemeCorner
import me.bubblespark.tidilo.ui.theme.themeCorner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tag
import me.bubblespark.tidilo.ui.components.TidiloAlertDialog
import me.bubblespark.tidilo.ui.components.TidiloDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.Subcategory
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BookmarkTagEditDialog(
    selectedCategory: String?,
    selectedSubcategory: String?,
    selectedEntities: List<String>,
    isNsfw: Boolean = false,
    isNsfwContentVisible: Boolean = true,
    onCategoryChanged: (String) -> Unit,
    onSubcategoryChanged: (String) -> Unit,
    onNsfwChanged: (Boolean) -> Unit = {},
    onAddEntityClick: () -> Unit,
    onOpenEntityPickerClick: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isCategoryDropdownExpanded by remember { mutableStateOf(false) }
    var isSubcategoryDropdownExpanded by remember { mutableStateOf(false) }
    val categoryScrollState = rememberScrollState()
    val subcategoryScrollState = rememberScrollState()
    var showInlineLimitError by remember { mutableStateOf(false) }

    LaunchedEffect(selectedEntities.size) {
        if (selectedEntities.size < Tag.MAX_ENTITIES) {
            showInlineLimitError = false
        }
    }

    val localizedCategory = remember(selectedCategory, context) {
        selectedCategory?.let { Category.toLocalized(it, context) } ?: ""
    }
    val localizedSubcategory = remember(selectedSubcategory, context) {
        selectedSubcategory?.let { Subcategory.toLocalized(it, context) } ?: ""
    }

    TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.detail_tag_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Category Dropdown
                Column {
                    Text(
                        text = stringResource(R.string.detail_tag_category_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        TidiloSurface(
                            onClick = { isCategoryDropdownExpanded = true },
                            role = SurfaceRole.INPUT,
                            modifier = Modifier
                                .fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = localizedCategory.ifBlank { "Select Category" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (localizedCategory.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        TidiloDropdownMenu(
                            expanded = isCategoryDropdownExpanded,
                            onDismissRequest = { isCategoryDropdownExpanded = false },
                            scrollState = categoryScrollState,
                            modifier = Modifier
                                .width(maxWidth)
                                .heightIn(max = 250.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = themeCorner(ThemeCorner.IMAGE)
                                )
                                .verticalScrollbar(
                                    scrollState = categoryScrollState,
                                    color = MaterialTheme.colorScheme.primary
                                ),
                            shape = themeCorner(ThemeCorner.IMAGE),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Category.entries.forEach { category ->
                                val label = category.getLabel(context)
                                val isSelected = category.id.equals(selectedCategory, ignoreCase = true)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    leadingIcon = {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    onClick = {
                                        onCategoryChanged(category.id)
                                        isCategoryDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 2. Subcategory Dropdown
                Column {
                    Text(
                        text = stringResource(R.string.detail_tag_subcategory_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        TidiloSurface(
                            onClick = { isSubcategoryDropdownExpanded = true },
                            role = SurfaceRole.INPUT,
                            modifier = Modifier
                                .fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = localizedSubcategory.ifBlank { "Select Subcategory" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (localizedSubcategory.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        TidiloDropdownMenu(
                            expanded = isSubcategoryDropdownExpanded,
                            onDismissRequest = { isSubcategoryDropdownExpanded = false },
                            scrollState = subcategoryScrollState,
                            modifier = Modifier
                                .width(maxWidth)
                                .heightIn(max = 250.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = themeCorner(ThemeCorner.IMAGE)
                                )
                                .verticalScrollbar(
                                    scrollState = subcategoryScrollState,
                                    color = MaterialTheme.colorScheme.primary
                                ),
                            shape = themeCorner(ThemeCorner.IMAGE),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Subcategory.entries.forEach { subcategory ->
                                val label = subcategory.getLabel(context)
                                val isSelected = subcategory.id.equals(selectedSubcategory, ignoreCase = true)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    leadingIcon = {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    onClick = {
                                        onSubcategoryChanged(subcategory.id)
                                        isSubcategoryDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 3. NSFW Toggle Section
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.detail_tag_nsfw_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Switch(
                            checked = isNsfw,
                            onCheckedChange = onNsfwChanged
                        )
                    }

                    if (!isNsfwContentVisible && isNsfw) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = themeCorner(ThemeCorner.CONTROL),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.detail_tag_nsfw_hidden_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // 4. Entity Section (Entity label + action icons + chips without delete button)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${stringResource(R.string.detail_tag_entity_label)} (${selectedEntities.size}/${Tag.MAX_ENTITIES})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // "Add" icon button without background
                            IconButton(
                                onClick = {
                                    showInlineLimitError = selectedEntities.size >= Tag.MAX_ENTITIES
                                    onAddEntityClick()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.detail_tag_add_entity),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // "Select" icon button without background (List Alt icon)
                            IconButton(
                                onClick = onOpenEntityPickerClick,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_list_alt),
                                    contentDescription = stringResource(R.string.detail_tag_select_entities),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (showInlineLimitError && selectedEntities.size >= Tag.MAX_ENTITIES) {
                        Surface(
                            shape = themeCorner(ThemeCorner.CONTROL),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.detail_tag_entity_limit_inline),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    if (selectedEntities.isEmpty()) {
                        Surface(
                            shape = themeCorner(ThemeCorner.CONTROL),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.detail_entity_picker_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            selectedEntities.forEach { entity ->
                                Surface(
                                    shape = themeCorner(ThemeCorner.CONTROL),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tag,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = entity,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.common_ok),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.common_cancel))
            }
        },
        shape = themeCorner(ThemeCorner.PROMINENT_CARD)
    )

}

/**
 * Child dialog for manually entering a new entity tag name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddEntityDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    text: String,
    onTextChange: (String) -> Unit,
    libraryState: EntityLibraryState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    candidates: List<String> = emptyList()
) {
    TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = stringResource(R.string.detail_add_entity_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = {
                        Text(stringResource(R.string.detail_add_entity_placeholder))
                    },
                    trailingIcon = if (text.isNotEmpty()) {
                        {
                            IconButton(onClick = { onTextChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.common_clear)
                                )
                            }
                        }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = themeCorner(ThemeCorner.FIELD),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                if (candidates.isNotEmpty()) {
                    val suggestionsScrollState = rememberScrollState()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.detail_add_entity_suggestions_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScrollbar(
                                    scrollState = suggestionsScrollState,
                                    color = MaterialTheme.colorScheme.primary
                                )
                        ) {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(suggestionsScrollState)
                                    .padding(end = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val chipShape = themeCorner(ThemeCorner.CHIP)
                                candidates.forEach { candidate ->
                                    Surface(
                                        shape = chipShape,
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .clip(chipShape)
                                            .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                                onTextChange(appendEntitySuggestion(text, candidate))
                                            }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Tag,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = candidate,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                when {
                    libraryState.loading -> androidx.compose.material3.CircularProgressIndicator()
                    libraryState.error != null -> Column {
                        Text(stringResource(R.string.entity_library_failed), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(text)
                    onDismiss()
                },
                enabled = !libraryState.loading && libraryState.error == null &&
                    me.bubblespark.tidilo.data.EntityTagPolicy.clean(text) != null
            ) {
                Text(
                    text = stringResource(R.string.common_ok),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.common_cancel))
            }
        },
        shape = themeCorner(ThemeCorner.CARD)
    )
}

/**
 * Renders a subtle vertical scrollbar on the right side of a scrollable container.
 * Only draws the track and thumb when [scrollState.maxValue] > 0 (i.e. content exceeds container bounds).
 */
private fun Modifier.verticalScrollbar(
    scrollState: ScrollState,
    color: Color,
    width: Dp = 4.dp,
    paddingEnd: Dp = 6.dp,
    paddingVertical: Dp = 12.dp,
    minThumbHeight: Dp = 24.dp
): Modifier = this.drawWithContent {
    drawContent()

    val totalHeight = size.height
    val totalScrollable = scrollState.maxValue
    if (totalScrollable > 0 && totalHeight > 0) {
        val padVertPx = paddingVertical.toPx()
        val usableTrackHeight = totalHeight - 2 * padVertPx
        if (usableTrackHeight > 0) {
            val widthPx = width.toPx()
            val padEndPx = paddingEnd.toPx()
            val thumbOffsetX = size.width - padEndPx - widthPx

            // 1. Subtle track
            drawRoundRect(
                color = color.copy(alpha = 0.15f),
                topLeft = Offset(thumbOffsetX, padVertPx),
                size = Size(widthPx, usableTrackHeight),
                cornerRadius = CornerRadius(widthPx / 2, widthPx / 2)
            )

            // 2. Dynamic thumb
            val contentHeight = totalScrollable + totalHeight
            val viewRatio = totalHeight / contentHeight.coerceAtLeast(1f)
            val minThumbHeightPx = minThumbHeight.toPx()
            val thumbHeightPx = (usableTrackHeight * viewRatio).coerceIn(minThumbHeightPx, usableTrackHeight)

            val scrollFraction = (scrollState.value.toFloat() / totalScrollable.toFloat()).coerceIn(0f, 1f)
            val maxThumbOffset = usableTrackHeight - thumbHeightPx
            val thumbOffsetY = padVertPx + maxThumbOffset * scrollFraction

            drawRoundRect(
                color = color,
                topLeft = Offset(thumbOffsetX, thumbOffsetY),
                size = Size(widthPx, thumbHeightPx),
                cornerRadius = CornerRadius(widthPx / 2, widthPx / 2)
            )
        }
    }
}

/** Replacement choices are staged locally until the detail screen is saved. */
@Composable
fun EntityTagConflictDialog(
    entered: String,
    existing: String,
    onDismiss: () -> Unit,
    onChoice: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).shape,
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.entity_conflict_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.entity_conflict_spellings, entered, existing))
                Text(stringResource(R.string.entity_conflict_save_notice))
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onChoice(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.entity_conflict_use_existing))
                }
                TextButton(onClick = { onChoice(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.entity_conflict_replace))
                }
            }
        }
    )
}
