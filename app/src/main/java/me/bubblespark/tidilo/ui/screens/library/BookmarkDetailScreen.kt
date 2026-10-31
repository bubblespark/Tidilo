package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.ui.components.TidiloFrostedHeader
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import kotlinx.coroutines.ensureActive

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.material.icons.outlined.Warning
import me.bubblespark.tidilo.ui.theme.WarningColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.vector.ImageVector
import me.bubblespark.tidilo.data.DomainIconManager
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.ContentScale
import me.bubblespark.tidilo.ui.components.DomainWatermark
import me.bubblespark.tidilo.ui.components.calculateOgAspectRatio
import me.bubblespark.tidilo.ui.components.rememberOgImage
import androidx.compose.ui.res.vectorResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Favorite
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DETAIL_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

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
    var detailRetry by remember(bookmarkId) { mutableStateOf(0) }
    val detailFlow = remember(viewModel, bookmarkId, detailRetry) { viewModel.bookmarkDetails(bookmarkId) }
    val detailState by detailFlow.collectAsStateWithLifecycle(initialValue = BookmarkDetailState())
    val bookmark = detailState.bookmark
    var detailRecorded by rememberSaveable(bookmarkId) { mutableStateOf(false) }
    LaunchedEffect(bookmarkId, bookmark?.id) {
        if (bookmark != null && !detailRecorded) {
            detailRecorded = true
            viewModel.recordDetailView(bookmarkId)
        }
    }
    if (detailState.loading || detailState.error != null || bookmark == null) {
        TidiloScreenScaffold(
            modifier = modifier,
            // Top Header: Back Button + Title + Save Button
        header = { TidiloPageHeader(stringResource(R.string.detail_title), onNavigateBack) }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                when {
                    detailState.loading -> androidx.compose.material3.CircularProgressIndicator()
                    detailState.error != null -> PagingFeedback(
                        androidx.paging.LoadState.Error(requireNotNull(detailState.error)), { detailRetry++ }
                    )
                    else -> Text(stringResource(R.string.detail_item_not_found), color = MaterialTheme.colorScheme.outline)
                }
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
    var editedIsNsfw by remember(bookmark.id, bookmark.isNsfw) {
        mutableStateOf(bookmark.isNsfw)
    }
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    var showTagEditDialog by remember { mutableStateOf(false) }
    var showAddEntityDialog by remember { mutableStateOf(false) }
    var showEntityBottomSheet by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var draftCategory by remember { mutableStateOf<String?>(null) }
    var draftSubcategory by remember { mutableStateOf<String?>(null) }
    var draftEntities by remember { mutableStateOf<List<String>>(emptyList()) }
    var draftIsNsfw by remember { mutableStateOf(false) }

    val isNsfwContentVisible by AppConfig.isNsfwContentVisible.collectAsStateWithLifecycle()

    val hasChanges = remember(noteText, bookmark.note, editedTag, bookmark.tag, editedIsNsfw, bookmark.isNsfw) {
        (noteText != bookmark.note) || (editedTag != bookmark.tag) || (editedIsNsfw != bookmark.isNsfw)
    }

    // Intercept swipe-back (system gesture back)
    BackHandler(enabled = isSaving || hasChanges) {
        if (!isSaving) {
            showDiscardConfirmDialog = true
        }
    }

    val localizedCategory = remember(editedTag.category, context) {
        editedTag.category?.let { Category.toLocalized(it, context) } ?: ""
    }
    val localizedSubcategory = remember(editedTag.subcategory, context) {
        editedTag.subcategory?.let { Subcategory.toLocalized(it, context) } ?: ""
    }

    val formattedCreatedAt = remember(bookmark.createdAt) {
        DETAIL_DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(bookmark.createdAt).atZone(ZoneId.systemDefault()))
    }
    val formattedUpdatedAt = remember(bookmark.updatedAt) {
        DETAIL_DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(bookmark.updatedAt).atZone(ZoneId.systemDefault()))
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

    val ogFilename = bookmark.ogImageFilename
    val ogState by rememberOgImage(bookmark.url, domain, bookmark.ogImageUrl, ogFilename)
    val ogBitmap = ogState.bitmap
    val detailAccent = rememberDetailAccent(bookmark.id, domain, ogFilename, ogBitmap, ogState.shouldShowFallback)
    val bannerBounds = remember(bookmark.id, ogBitmap) { mutableStateOf<Rect?>(null) }
    val lightingHostOrigin = remember { mutableStateOf(Offset.Zero) }
    val darkLighting = MaterialTheme.colorScheme.background.luminance() < 0.5f

    TidiloScreenScaffold(
        modifier = modifier
            .onGloballyPositioned { lightingHostOrigin.value = it.positionInRoot() }
            .detailAmbientLight(detailAccent, bannerBounds, lightingHostOrigin, darkLighting, ogState.shouldShowFallback),
        hideHeaderOnScroll = false,
        header = {
            TidiloFrostedHeader(
                scrollState = scrollState,
                title = {
                    Text(
                        text = stringResource(R.string.detail_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!isSaving) {
                                if (hasChanges) {
                                    showDiscardConfirmDialog = true
                                } else {
                                    onNavigateBack()
                                }
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = if (isSaving) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            if (hasChanges && !isSaving) {
                                isSaving = true
                                coroutineScope.launch {
                                    try {
                                        viewModel.updateBookmarkDetails(bookmark.id, noteText, editedTag, editedIsNsfw)
                                        onNavigateBack()
                                    } catch (e: Exception) {
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        Toast.makeText(context, context.getString(R.string.common_save_failed), Toast.LENGTH_LONG).show()
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                        },
                        enabled = hasChanges && !isSaving,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.detail_save),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            )
        },
        overlay = {
            Row(
                modifier = Modifier.fillMaxSize().navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 31.6.dp)
                    .offset(x = 30.8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom
            ) {
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    tonalElevation = 6.dp,
                    shadowElevation = 10.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showDeleteConfirmDialog = true }, modifier = Modifier.size(52.8.dp)) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.detail_action_delete),
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = { onNavigateToAiReview(bookmark.id) }, modifier = Modifier.size(52.8.dp)) {
                            Icon(Icons.Default.AutoAwesome,
                                contentDescription = stringResource(R.string.detail_action_ai_summary),
                                modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = {}, modifier = Modifier.size(52.8.dp)) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_star),
                                contentDescription = stringResource(R.string.bookmark_collection_placeholder),
                                modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = { viewModel.toggleLike(bookmark.id) }, modifier = Modifier.size(52.8.dp)) {
                            Icon(
                                imageVector = if (bookmark.isLiked) Icons.Filled.Favorite else ImageVector.vectorResource(R.drawable.ic_favorite),
                                contentDescription = stringResource(if (bookmark.isLiked) R.string.bookmark_unlike else R.string.bookmark_like),
                                tint = if (bookmark.isLiked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.8.dp))
                Surface(
                    onClick = { openBookmarkInBrowser(context, bookmark.url) { viewModel.recordBrowserOpen(bookmark.id) } },
                    modifier = Modifier.size(52.8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_open_in_browser),
                            contentDescription = stringResource(R.string.bookmark_open_in_browser),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp))
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
                                coroutineScope.launch {
                                    try {
                                        if (viewModel.deleteBookmark(bookmark.id)) onNavigateBack()
                                    } catch (e: Exception) {
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        Toast.makeText(context, context.getString(R.string.common_save_failed), Toast.LENGTH_LONG).show()
                                    }
                                }
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
                    isNsfw = draftIsNsfw,
                    isNsfwContentVisible = isNsfwContentVisible,
                    onCategoryChanged = { draftCategory = it },
                    onSubcategoryChanged = { draftSubcategory = it },
                    onNsfwChanged = { draftIsNsfw = it },
                    onAddEntityClick = { showAddEntityDialog = true },
                    onOpenEntityPickerClick = { showEntityBottomSheet = true },
                    onConfirm = {
                        editedTag = Tag.create(draftCategory, draftSubcategory, draftEntities)
                        editedIsNsfw = draftIsNsfw
                        showTagEditDialog = false
                    },
                    onDismiss = { showTagEditDialog = false }
                )
            }

            if (showAddEntityDialog) {
                AddEntityDialog(
                    onDismiss = { showAddEntityDialog = false },
                    onConfirm = { newEntity ->
                        draftEntities = Tag.addEntity(draftEntities, newEntity)
                    }
                )
            }

            if (showEntityBottomSheet) {
                val allLibraryEntities by viewModel.entityNames.collectAsStateWithLifecycle()
                EntityTagPickerBottomSheet(
                    selectedEntities = draftEntities,
                    allEntities = (allLibraryEntities + draftEntities).distinct(),
                    onToggleEntity = { entity ->
                        draftEntities = Tag.toggleEntity(draftEntities, entity)
                    },
                    onRemoveEntity = { entity ->
                        draftEntities = draftEntities.filterNot { it.equals(entity, ignoreCase = true) }
                    },
                    onDismiss = { showEntityBottomSheet = false }
                )
            }
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                    })
                }
                .verticalScroll(scrollState)
                .padding(contentPadding)
        ) {
                // Content container directly beneath HEADER (places DomainWatermark or og:image banner)
                Box(
                    modifier = Modifier.fillMaxWidth().onGloballyPositioned {
                        if (ogBitmap == null) {
                            bannerBounds.value = Rect(it.positionInRoot() + Offset(it.size.width.toFloat(), 0f), Size.Zero)
                        }
                    }
                ) {
                    // Upper right domain watermark (rendered only when og:image is unavailable and resolution completed)
                    if (ogState.shouldShowDomainWatermarkFallback) {
                        DomainWatermark(
                            domain = domain,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = HeaderDefaults.HorizontalPadding) // Cancels scaffold horizontal padding to align with right screen edge
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (ogBitmap != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val bannerHeight = minOf(
                                    maxWidth / calculateOgAspectRatio(ogBitmap.width, ogBitmap.height),
                                    300.dp
                                )
                                Image(
                                    bitmap = ogBitmap,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(bannerHeight)
                                        .onGloballyPositioned {
                                            bannerBounds.value = Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                                        }
                                        .detailBannerLight(detailAccent)
                                        .clip(RoundedCornerShape(16.dp))
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        } else {
                            Spacer(modifier = Modifier.height(20.dp))
                        }

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
                                openBookmarkInBrowser(context, bookmark.url) { viewModel.recordBrowserOpen(bookmark.id) }
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

                if (bookmark.isAiSkipped) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = WarningColor.textOrIcon,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.detail_ai_not_analyzed_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = WarningColor.textOrIcon
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                } else {
                    Spacer(modifier = Modifier.height(24.dp))
                }

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

                    CompositionLocalProvider(
                        LocalMinimumInteractiveComponentSize provides Dp.Unspecified
                    ) {
                        IconButton(
                            onClick = {
                                draftCategory = editedTag.category
                                draftSubcategory = editedTag.subcategory
                                draftEntities = editedTag.entities
                                draftIsNsfw = editedIsNsfw
                                showTagEditDialog = true
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.detail_edit_tags),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // [category] > [subcategory] newline [entities] (uniform 8.dp line spacing)
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides Dp.Unspecified
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Line 1: [category] > [subcategory] (+ [NSFW])
                        val hasCategoryLine = localizedCategory.isNotBlank() || localizedSubcategory.isNotBlank() || editedIsNsfw
                        if (hasCategoryLine) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                        if (localizedCategory.isNotBlank() && localizedSubcategory.isNotBlank()) {
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
                        } else if (localizedSubcategory.isNotBlank()) {
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
                        }


                        if (editedIsNsfw) {
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

                        }

                    // Line 2: [entities] (disables LocalMinimumInteractiveComponentSize to compact multi-line height)
                    if (editedTag.entities.isNotEmpty()) {
                        CompositionLocalProvider(
                            LocalMinimumInteractiveComponentSize provides Dp.Unspecified
                        ) {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth(),
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

                // Bottom spacer to prevent overlap with the toolbar
                Spacer(modifier = Modifier.height(96.dp))
        }
    } // End of shared screen scaffold
} // End of BookmarkDetailScreen
