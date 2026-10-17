package me.bubblespark.tidilo.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.Subcategory
import me.bubblespark.tidilo.ui.components.SortIcon

data class TagItem(
    val id: String,
    val displayName: String,
    val count: Int
)

enum class EntitySortMode {
    COUNT,
    NAME
}

private fun compareEntityNames(a: String, b: String): Int {
    val aFirst = a.firstOrNull()
    val bFirst = b.firstOrNull()
    val aIsAscii = aFirst != null && aFirst.code < 128
    val bIsAscii = bFirst != null && bFirst.code < 128

    return when {
        aIsAscii && !bIsAscii -> -1 // Alphabet first
        !aIsAscii && bIsAscii -> 1  // Japanese behind alphabet
        else -> a.compareTo(b, ignoreCase = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagSelectionScreen(
    viewModel: LibraryViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val localConfig = LocalConfiguration.current

    // Normalize initial tags selected in search bar into canonical IDs (English ID or Entity name)
    val initialSelectedTags = remember(viewModel) {
        viewModel.getSelectedTagsFromCurrentQuery().map { tag ->
            Category.fromId(tag)?.id ?: Subcategory.fromId(tag)?.id ?: tag
        }.toSet()
    }
    var selectedTags by remember { mutableStateOf(initialSelectedTags) }

    // Query for tag search input
    var tagSearchQuery by remember { mutableStateOf("") }

    // Calculate total tag occurrence counts across all bookmarks in Library
    val totalCategoryCounts = remember(bookmarks) {
        bookmarks.groupingBy { it.tag.category }.eachCount()
    }
    val totalSubcategoryCounts = remember(bookmarks) {
        bookmarks.groupingBy { it.tag.subcategory }.eachCount()
    }
    val totalEntityCounts = remember(bookmarks) {
        bookmarks.flatMap { it.tag.entities }.groupingBy { it }.eachCount()
    }

    // AND search logic:
    // Identify bookmarks matching all currently selected tags
    val activeBookmarks = remember(bookmarks, selectedTags) {
        if (selectedTags.isEmpty()) {
            bookmarks
        } else {
            bookmarks.filter { item ->
                selectedTags.all { selected ->
                    item.tag.category.equals(selected, ignoreCase = true) ||
                            item.tag.subcategory.equals(selected, ignoreCase = true) ||
                            item.tag.entities.any { it.equals(selected, ignoreCase = true) }
                }
            }
        }
    }

    // Always-visible Category / Subcategory:
    // Candidate list of all defined categories and subcategories (plus any legacy bookmark tags)
    val allCategoryIds = remember(bookmarks) {
        (Category.entries.map { it.id } + bookmarks.mapNotNull { it.tag.category }).distinct()
    }
    val allSubcategoryIds = remember(bookmarks) {
        (Subcategory.entries.map { it.id } + bookmarks.mapNotNull { it.tag.subcategory }).distinct()
    }

    // Occurrence count of each tag within currently active bookmarks
    val activeCategoryCounts = remember(activeBookmarks) {
        activeBookmarks.mapNotNull { it.tag.category }.groupingBy { it }.eachCount()
    }
    val activeSubcategoryCounts = remember(activeBookmarks) {
        activeBookmarks.mapNotNull { it.tag.subcategory }.groupingBy { it }.eachCount()
    }
    val activeEntityCounts = remember(activeBookmarks) {
        activeBookmarks.flatMap { it.tag.entities }.groupingBy { it }.eachCount()
    }

    // Only show entities that have non-zero occurrences in active bookmarks (or are already selected)
    val validEntityNames = remember(activeBookmarks, selectedTags) {
        val inActive = activeBookmarks.flatMap { it.tag.entities }.toSet()
        inActive + selectedTags.filter { totalEntityCounts.containsKey(it) }
    }

    // Filter by tag search input (partial match without '#')
    val cleanSearch = remember(tagSearchQuery) {
        tagSearchQuery.removePrefix("#").trim()
    }

    // 1. Category list (localized display, always visible even if 0 count; sorted by count desc, then name asc)
    val categoryTags = remember(allCategoryIds, activeCategoryCounts, cleanSearch, context) {
        allCategoryIds
            .map { id ->
                val localized = Category.toLocalized(id, context)
                TagItem(id = id, displayName = localized, count = activeCategoryCounts[id] ?: 0)
            }
            .filter { item ->
                if (cleanSearch.isBlank()) true
                else item.id.contains(cleanSearch, ignoreCase = true) || item.displayName.contains(cleanSearch, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<TagItem> { it.count }
                    .thenBy { it.displayName.lowercase() }
            )
    }

    // 2. Subcategory list (localized display, always visible even if 0 count; sorted by count desc, then name asc)
    val subcategoryTags = remember(allSubcategoryIds, activeSubcategoryCounts, cleanSearch, context) {
        allSubcategoryIds
            .map { id ->
                val localized = Subcategory.toLocalized(id, context)
                TagItem(id = id, displayName = localized, count = activeSubcategoryCounts[id] ?: 0)
            }
            .filter { item ->
                if (cleanSearch.isBlank()) true
                else item.id.contains(cleanSearch, ignoreCase = true) || item.displayName.contains(cleanSearch, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<TagItem> { it.count }
                    .thenBy { it.displayName.lowercase() }
            )
    }

    // 3. Entity category list (sorted via M3 Sort Menu)
    var entitySortMode by rememberSaveable { mutableStateOf(EntitySortMode.COUNT) }
    var isSortMenuExpanded by remember { mutableStateOf(false) }

    val entityTags = remember(validEntityNames, activeEntityCounts, cleanSearch, entitySortMode) {
        validEntityNames
            .map { name ->
                TagItem(id = name, displayName = name, count = activeEntityCounts[name] ?: 0)
            }
            .filter { item ->
                if (cleanSearch.isBlank()) true
                else item.displayName.contains(cleanSearch, ignoreCase = true)
            }
            .sortedWith { a, b ->
                when (entitySortMode) {
                    EntitySortMode.COUNT -> {
                        val countComp = b.count.compareTo(a.count)
                        if (countComp != 0) countComp else compareEntityNames(a.displayName, b.displayName)
                    }
                    EntitySortMode.NAME -> {
                        val nameComp = compareEntityNames(a.displayName, b.displayName)
                        if (nameComp != 0) nameComp else b.count.compareTo(a.count)
                    }
                }
            }
    }

    // Apply selected tags to search bar and navigate back
    val applyAndNavigateBack = {
        val tagsToApply = selectedTags.map { id ->
            Category.fromId(id)?.getLabel(context)
                ?: Subcategory.fromId(id)?.getLabel(context)
                ?: id
        }.toSet()
        viewModel.updateQueryWithSelectedTags(tagsToApply)
        onNavigateBack()
    }

    BackHandler {
        applyAndNavigateBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Top Menu: Back (←) button, title, and Clear button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = applyAndNavigateBack,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.tag_selection_back_and_apply),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text(
                        text = stringResource(R.string.tag_selection_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.tag_selection_subtitle, selectedTags.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Top-right Clear button (text button without background)
            TextButton(
                onClick = {
                    selectedTags = emptySet()
                },
                enabled = selectedTags.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            ) {
                Text(
                    text = stringResource(R.string.common_clear),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Top Search: Tag search component (matches regardless of '#')
        OutlinedTextField(
            value = tagSearchQuery,
            onValueChange = { tagSearchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.tag_selection_search_placeholder),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.tag_selection_search_content_desc),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (tagSearchQuery.isNotEmpty()) {
                    IconButton(onClick = { tagSearchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.common_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Group 1: Category (always visible even when 0 items)
        TagGroupSection(
            title = stringResource(R.string.tag_group_category),
            tags = categoryTags,
            selectedTags = selectedTags,
            onTagToggled = { tagId ->
                selectedTags = if (selectedTags.contains(tagId)) {
                    selectedTags - tagId
                } else {
                    selectedTags + tagId
                }
            },
            activeColor = MaterialTheme.colorScheme.primary,
            activeContentColor = MaterialTheme.colorScheme.onPrimary
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Group 2: Subcategory (always visible even when 0 items)
        TagGroupSection(
            title = stringResource(R.string.tag_group_subcategory),
            tags = subcategoryTags,
            selectedTags = selectedTags,
            onTagToggled = { tagId ->
                selectedTags = if (selectedTags.contains(tagId)) {
                    selectedTags - tagId
                } else {
                    selectedTags + tagId
                }
            },
            activeColor = MaterialTheme.colorScheme.secondary,
            activeContentColor = MaterialTheme.colorScheme.onSecondary
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Group 3: Entity category (specific category - includes M3 Sort Menu)
        TagGroupSection(
            title = stringResource(R.string.tag_group_entity),
            tags = entityTags,
            selectedTags = selectedTags,
            onTagToggled = { tagId ->
                selectedTags = if (selectedTags.contains(tagId)) {
                    selectedTags - tagId
                } else {
                    selectedTags + tagId
                }
            },
            activeColor = MaterialTheme.colorScheme.tertiary,
            activeContentColor = MaterialTheme.colorScheme.onTertiary,
            headerTrailing = {
                Box {
                    IconButton(
                        onClick = { isSortMenuExpanded = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = SortIcon,
                            contentDescription = stringResource(R.string.tag_sort_content_desc),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = isSortMenuExpanded,
                        onDismissRequest = { isSortMenuExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp
                    ) {
                        CompositionLocalProvider(
                            LocalContext provides context,
                            LocalConfiguration provides localConfig
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.tag_sort_count),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (entitySortMode == EntitySortMode.COUNT) FontWeight.Bold else FontWeight.Normal,
                                        color = if (entitySortMode == EntitySortMode.COUNT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    entitySortMode = EntitySortMode.COUNT
                                    isSortMenuExpanded = false
                                },
                                trailingIcon = if (entitySortMode == EntitySortMode.COUNT) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                } else null
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.tag_sort_name),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (entitySortMode == EntitySortMode.NAME) FontWeight.Bold else FontWeight.Normal,
                                        color = if (entitySortMode == EntitySortMode.NAME) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    entitySortMode = EntitySortMode.NAME
                                    isSortMenuExpanded = false
                                },
                                trailingIcon = if (entitySortMode == EntitySortMode.NAME) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * Tag group section (title, optional trailing header slot, and FlowRow chips layout).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagGroupSection(
    title: String,
    tags: List<TagItem>,
    selectedTags: Set<String>,
    onTagToggled: (String) -> Unit,
    activeColor: Color,
    activeContentColor: Color,
    headerTrailing: @Composable (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$title (${tags.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (headerTrailing != null) {
                headerTrailing()
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (tags.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.tag_empty_group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                tags.forEach { tagItem ->
                    val isSelected = selectedTags.contains(tagItem.id)
                    val isSelectable = isSelected || tagItem.count > 0

                    val backgroundColor by animateColorAsState(
                        targetValue = when {
                            isSelected -> activeColor
                            !isSelectable -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        animationSpec = tween(180),
                        label = "chip_bg"
                    )

                    val contentColor by animateColorAsState(
                        targetValue = when {
                            isSelected -> activeContentColor
                            !isSelectable -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(180),
                        label = "chip_content"
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp), // M3 Expressive Pill/Chip shape
                        color = backgroundColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .then(
                                if (isSelectable) {
                                    Modifier.clickable { onTagToggled(tagItem.id) }
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        Text(
                            text = "${tagItem.displayName} (${tagItem.count})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = contentColor,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                        )
                    }
                }
            }
        }
    }
}
