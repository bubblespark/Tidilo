package me.bubblespark.tidilo.ui.screens.library

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.ui.components.DomainWatermark
import me.bubblespark.tidilo.ui.components.MenuOpenIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import android.content.ClipData
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.Subcategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookmarkDetailScreen(
    bookmarkId: String,
    viewModel: LibraryViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAiReview: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()

    val bookmark = remember(bookmarks, bookmarkId) {
        bookmarks.find { it.id == bookmarkId }
    }

    if (bookmark == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.detail_item_not_found),
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        return
    }

    var noteText by remember(bookmark.id, bookmark.note) {
        mutableStateOf(bookmark.note)
    }
    var editedTag by remember(bookmark.id, bookmark.tag) {
        mutableStateOf(bookmark.tag)
    }
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }
    var isFabMenuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    var showTagEditDialog by remember { mutableStateOf(false) }
    var showAddEntityDialog by remember { mutableStateOf(false) }
    var showEntityBottomSheet by remember { mutableStateOf(false) }

    var draftCategory by remember { mutableStateOf<String?>(null) }
    var draftSubcategory by remember { mutableStateOf<String?>(null) }
    var draftEntities by remember { mutableStateOf<List<String>>(emptyList()) }

    val hasChanges = remember(noteText, bookmark.note, editedTag, bookmark.tag) {
        (noteText != bookmark.note) || (editedTag != bookmark.tag)
    }

    // Intercept swipe-back (system gesture back)
    BackHandler(enabled = hasChanges) {
        showDiscardConfirmDialog = true
    }

    val localizedCategory = remember(editedTag.category, context) {
        editedTag.category?.let { Category.toLocalized(it, context) } ?: ""
    }
    val localizedSubcategory = remember(editedTag.subcategory, context) {
        editedTag.subcategory?.let { Subcategory.toLocalized(it, context) } ?: ""
    }

    val formattedCreatedAt = remember(bookmark.createdAt) {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        sdf.format(Date(bookmark.createdAt))
    }
    val formattedUpdatedAt = remember(bookmark.updatedAt) {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        sdf.format(Date(bookmark.updatedAt))
    }

    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    var isNoteFocused by remember { mutableStateOf(false) }
    val isImeVisible = WindowInsets.isImeVisible

    // Clear focus on system back gesture while Note field is focused
    BackHandler(enabled = isNoteFocused) {
        focusManager.clearFocus()
    }

    // Clear focus when soft keyboard is dismissed
    LaunchedEffect(isImeVisible) {
        if (!isImeVisible && isNoteFocused) {
            focusManager.clearFocus()
        }
    }


    val domain = remember(bookmark.url) { DomainIconManager.extractDomain(bookmark.url) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                    })
                }
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
        Spacer(modifier = Modifier.height(16.dp))

        // 1. Top Header: Back Button + Title + Save Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (hasChanges) {
                            showDiscardConfirmDialog = true
                        } else {
                            onNavigateBack()
                        }
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.detail_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Top-right "Save" button (filled pill button; visually disabled when no unsaved changes)
            Button(
                onClick = {
                    if (hasChanges) {
                        viewModel.updateBookmarkDetails(bookmark.id, noteText, editedTag)
                    }
                    onNavigateBack()
                },
                enabled = hasChanges,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.detail_save),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Content container directly beneath HEADER (places DomainWatermark clipped to upper right)
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Upper right domain watermark (directly below HEADER, fitted inside screen right edge)
            DomainWatermark(
                domain = domain,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 20.dp) // Cancels Column's 20dp horizontal padding to align with right screen edge
            )

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                // 2. Item Title
                Text(
                    text = bookmark.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    lineHeight = MaterialTheme.typography.titleLarge.lineHeight * 1.15f,
                    modifier = Modifier.fillMaxWidth()
                )

        Spacer(modifier = Modifier.height(12.dp))

        // 3. URL (tap to launch browser, long press to copy to clipboard)
        @OptIn(ExperimentalFoundationApi::class)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(bookmark.url)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }.onFailure {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.bookmark_could_not_open_browser),
                                    Toast.LENGTH_SHORT
                                )
                                .show()
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        coroutineScope.launch {
                            val clipData = ClipData.newPlainText("URL", bookmark.url)
                            clipboard.setClipEntry(clipData.toClipEntry())
                        }
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.detail_url_copied),
                                Toast.LENGTH_SHORT
                            )
                            .show()
                    }
                ),
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.detail_open_url),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = bookmark.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 4. Section: Tags & Classification
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tag,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.detail_section_tags),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = {
                    draftCategory = editedTag.category
                    draftSubcategory = editedTag.subcategory
                    draftEntities = editedTag.entities
                    showTagEditDialog = true
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.detail_edit_tags),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val isAiSkipped = bookmark.isAiSkipped && editedTag.category == null && editedTag.subcategory == null && editedTag.entities.isEmpty()

        // [category] > [subcategory] newline [entities] (uniform 8.dp line spacing)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Line 1: [AI Skipped] or [category] > [subcategory] (+ [NSFW])
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isAiSkipped) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.bookmark_ai_skipped), fontWeight = FontWeight.Bold) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                } else if (localizedCategory.isNotBlank() && localizedSubcategory.isNotBlank()) {
                    // Category
                    AssistChip(
                        onClick = {},
                        label = { Text(localizedCategory) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Text(
                        text = ">",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline
                    )

                    // Subcategory
                    AssistChip(
                        onClick = {},
                        label = { Text(localizedSubcategory) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                } else if (localizedCategory.isNotBlank()) {
                    AssistChip(
                        onClick = {},
                        label = { Text(localizedCategory) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                if (bookmark.isNsfw) {
                    AssistChip(
                        onClick = {},
                        label = { Text("NSFW", fontWeight = FontWeight.Bold) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            // Line 2: [entities] (disables LocalMinimumInteractiveComponentSize to compact multi-line height)
            if (!isAiSkipped && editedTag.entities.isNotEmpty()) {
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides Dp.Unspecified
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        editedTag.entities.forEach { entity ->
                            AssistChip(
                                onClick = {},
                                label = { Text(entity.removePrefix("#")) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Tag,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                                    labelColor = MaterialTheme.colorScheme.onSurface
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 5. Section: Summary
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.detail_section_summary),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Text(
                text = bookmark.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.3f,
                modifier = Modifier.padding(14.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 6. Section: Note (Note label + character count counter)
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.EditNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.detail_section_note),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = "${noteText.length}/200",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (noteText.length >= 200) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = noteText,
                onValueChange = { input ->
                    val limited = if (input.length > 200) input.take(200) else input
                    noteText = limited
                },
                placeholder = {
                    Text(
                        text = stringResource(R.string.detail_note_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 150.dp)
                    .onFocusChanged { focusState ->
                        isNoteFocused = focusState.isFocused
                    },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                )
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 7. Date watermark (Created / Updated)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = stringResource(R.string.detail_created_at, formattedCreatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.detail_updated_at, formattedUpdatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        }

        } // End of inner content Column beneath Header
    } // End of content Box beneath Header

        // Bottom spacer to prevent overlap with FAB menu
        Spacer(modifier = Modifier.height(96.dp))
    } // End of main scrolling Column

    // Scrim (prevents clicks behind expanded menu and collapses on tap)
    if (isFabMenuExpanded) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    isFabMenuExpanded = false
                }
        )
    }

    // M3 FAB Menu (Material 3 Expressive: matches expanded action button 86dp / 40dp icon / 16dp margin)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(bottom = 16.dp, end = 16.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AnimatedVisibility(
                visible = isFabMenuExpanded,
                enter = fadeIn(animationSpec = tween(180)) + slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                ),
                exit = fadeOut(animationSpec = tween(120)) + slideOutVertically(
                    targetOffsetY = { it / 2 },
                    animationSpec = tween(120)
                )
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Top: Delete button
                    FabMenuItem(
                        label = stringResource(R.string.detail_action_delete),
                        icon = Icons.Default.Delete,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = {
                            isFabMenuExpanded = false
                            showDeleteConfirmDialog = true
                        }
                    )

                    // Bottom: AI Summary button
                    FabMenuItem(
                        label = stringResource(R.string.detail_action_ai_summary),
                        icon = Icons.Default.AutoAwesome,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = {
                            isFabMenuExpanded = false
                            onNavigateToAiReview(bookmark.id)
                        }
                    )
                }
            }

            // Parent toggle FAB: matches expanded action button (86dp circular container, 40dp icon, 10dp elevation)
            Surface(
                onClick = { isFabMenuExpanded = !isFabMenuExpanded },
                modifier = Modifier.size(86.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 10.dp,
                tonalElevation = 6.dp
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFabMenuExpanded) Icons.Default.Close else MenuOpenIcon,
                        contentDescription = stringResource(if (isFabMenuExpanded) R.string.detail_fab_close else R.string.detail_fab_open),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }

    // Discard changes confirmation dialog
    if (showDiscardConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.detail_dialog_discard_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.detail_dialog_discard_message)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.common_ok),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDiscardConfirmDialog = false }
                ) {
                    Text(
                        text = stringResource(R.string.common_cancel)
                    )
                }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.detail_dialog_delete_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.detail_dialog_delete_message)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteBookmark(bookmark.id)
                        onNavigateBack()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Tag edit dialogs & bottom sheet
    if (showTagEditDialog) {
        BookmarkTagEditDialog(
            selectedCategory = draftCategory,
            selectedSubcategory = draftSubcategory,
            selectedEntities = draftEntities,
            onCategoryChanged = { draftCategory = it },
            onSubcategoryChanged = { draftSubcategory = it },
            onAddEntityClick = { showAddEntityDialog = true },
            onOpenEntityPickerClick = { showEntityBottomSheet = true },
            onConfirm = {
                editedTag = Tag.create(draftCategory, draftSubcategory, draftEntities)
                showTagEditDialog = false
            },
            onDismiss = { showTagEditDialog = false }
        )
    }

    if (showAddEntityDialog) {
        AddEntityDialog(
            onDismiss = { showAddEntityDialog = false },
            onConfirm = { newEntity ->
                if (!draftEntities.any { it.equals(newEntity, ignoreCase = true) }) {
                    draftEntities = draftEntities + newEntity
                }
            }
        )
    }

    if (showEntityBottomSheet) {
        val allLibraryEntities = remember(bookmarks) {
            bookmarks.flatMap { it.tag.entities }.distinct()
        }
        EntityTagPickerBottomSheet(
            selectedEntities = draftEntities,
            allEntities = (allLibraryEntities + draftEntities).distinct(),
            onToggleEntity = { entity ->
                draftEntities = if (draftEntities.any { it.equals(entity, ignoreCase = true) }) {
                    draftEntities.filterNot { it.equals(entity, ignoreCase = true) }
                } else {
                    draftEntities + entity
                }
            },
            onRemoveEntity = { entity ->
                draftEntities = draftEntities.filterNot { it.equals(entity, ignoreCase = true) }
            },
            onDismiss = { showEntityBottomSheet = false }
        )
    }
} // End of Box (fillMaxSize)
} // End of BookmarkDetailScreen

/**
 * FAB menu item adhering to Material 3 Expressive guidelines.
 * - Large pill shape (CircleShape) matching parent FAB expansion (86dp)
 * - Harmonious composition of icon (28dp) and text (titleMedium)
 * - Right-aligned to parent FAB end, extending left based on text length
 */
@Composable
private fun FabMenuItem(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
        }
    }
}
