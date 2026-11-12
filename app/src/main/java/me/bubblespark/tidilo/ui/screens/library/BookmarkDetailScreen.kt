package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.data.EntityCandidateExtractor

import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import me.bubblespark.tidilo.ui.theme.ThemeCorner
import me.bubblespark.tidilo.ui.theme.themeCorner
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import kotlinx.coroutines.ensureActive
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import me.bubblespark.tidilo.ui.theme.collectionMembershipColor
import me.bubblespark.tidilo.ui.components.TidiloClassificationContent
import me.bubblespark.tidilo.ui.components.TidiloSurface
import me.bubblespark.tidilo.ui.components.TidiloIconButton
import me.bubblespark.tidilo.ui.components.TidiloIconToggleButton
import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.SurfaceVariant
import me.bubblespark.tidilo.ui.theme.ThemeSurfaceState
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.ui.util.TimeFormatter
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.text.style.TextAlign

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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import me.bubblespark.tidilo.ui.theme.modernRaisedTextStyle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import me.bubblespark.tidilo.data.DomainIconManager
import androidx.compose.ui.layout.ContentScale
import me.bubblespark.tidilo.ui.components.DomainWatermark
import me.bubblespark.tidilo.ui.components.calculateOgAspectRatio
import me.bubblespark.tidilo.ui.components.rememberOgImage
import androidx.compose.ui.res.vectorResource
import me.bubblespark.tidilo.ui.components.TidiloAlertDialog
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
    modifier: Modifier = Modifier,
    onOpenCollections: () -> Unit = {}
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
            header = {
                DetailFloatingHeaderOverlay(
                    onBackClick = onNavigateBack,
                    backEnabled = true,
                    showSave = false
                )
            }
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

    val edit = rememberSaveable(bookmark.id, saver = BookmarkDetailEditState.Saver) {
        BookmarkDetailEditState(bookmark.note, bookmark.tag, bookmark.isNsfw)
    }
    LaunchedEffect(bookmark.id, bookmark.note, bookmark.tag, bookmark.isNsfw) {
        edit.syncWithBookmark(bookmark)
    }
    val hasCollection by remember(viewModel, bookmark.id) { viewModel.bookmarkHasCollections(bookmark.id) }
        .collectAsStateWithLifecycle(initialValue = false)
    var noteText by edit.noteText
    var editedTag by edit.editedTag
    var editedIsNsfw by edit.editedIsNsfw
    var pendingReplacements by edit.pendingReplacements
    var showTagEditDialog by edit.showTagEditDialog
    var showAddEntityDialog by edit.showAddEntityDialog
    var showEntityBottomSheet by edit.showEntityBottomSheet
    var draftCategory by edit.draftCategory
    var draftSubcategory by edit.draftSubcategory
    var draftEntities by edit.draftEntities
    var draftIsNsfw by edit.draftIsNsfw
    var draftReplacements by edit.draftReplacements
    var entityInput by edit.entityInput
    var conflictInput by edit.conflictInput
    var conflictExisting by edit.conflictExisting
    var showDiscardConfirmDialog by rememberSaveable(bookmark.id) { mutableStateOf(false) }
    var discardToCollections by rememberSaveable(bookmark.id) { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showCollectionMembership by rememberSaveable(bookmark.id) { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var entityLibraryRetry by remember { mutableStateOf(0) }
    val libraryFlow = remember(viewModel, entityLibraryRetry) { viewModel.entityLibrary() }
    val libraryState = rememberEntityLibraryState(showAddEntityDialog || showEntityBottomSheet, libraryFlow)

    val isNsfwContentVisible by AppConfig.isNsfwContentVisible.collectAsStateWithLifecycle()

    val hasChanges = noteText != edit.originalNote || editedTag != edit.originalTag ||
        editedIsNsfw != edit.originalNsfw || pendingReplacements.isNotEmpty()

    fun requestExit(toCollections: Boolean = false) {
        if (isSaving) return
        discardToCollections = toCollections
        if (hasChanges) showDiscardConfirmDialog = true
        else if (toCollections) onOpenCollections() else onNavigateBack()
    }
    if (showCollectionMembership) CollectionMembershipBottomSheet(bookmark.id,
        onDismiss = { showCollectionMembership = false },
        onOpenCollections = { showCollectionMembership = false; requestExit(toCollections = true) })

    // Intercept swipe-back (system gesture back)
    BackHandler(enabled = isSaving || hasChanges) {
        requestExit()
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
    val policies by DomainPolicyManager.policies.collectAsStateWithLifecycle()
    val domainDisplayName = remember(domain, policies) {
        domain?.let { DomainPolicyManager.getDisplayName(it) } ?: domain ?: ""
    }
    val relativeCreatedAt = remember(bookmark.createdAt, context) {
        TimeFormatter.formatRelativeTime(bookmark.createdAt, context)
    }
    val sourceAttributionText = remember(domainDisplayName, relativeCreatedAt) {
        if (domainDisplayName.isNotBlank()) "$domainDisplayName • $relativeCreatedAt" else relativeCreatedAt
    }
    val classificationBreadcrumb = remember(localizedCategory, localizedSubcategory, editedIsNsfw) {
        formatClassificationBreadcrumb(localizedCategory, localizedSubcategory, editedIsNsfw)
    }

    val ogFilename = bookmark.ogImageFilename
    val ogState by rememberOgImage(bookmark.url, domain, bookmark.ogImageUrl, ogFilename)
    val ogBitmap = ogState.bitmap
    val heroPaletteState = rememberDetailHeroPalette(bookmark.id, domain, ogFilename, ogBitmap, ogState.shouldShowFallback)
    val detailAccent = remember(heroPaletteState) { derivedStateOf { heroPaletteState.value.accentColor } }
    val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val surfaces = currentThemeSurfaces()
    val darkLighting = surfaces.isDark

    fun openTagEditorDialog() {
        draftCategory = editedTag.category
        draftSubcategory = editedTag.subcategory
        draftEntities = editedTag.entities
        draftIsNsfw = editedIsNsfw
        draftReplacements = pendingReplacements
        showTagEditDialog = true
    }

    TidiloScreenScaffold(
        modifier = modifier,
        bodyModifier = Modifier.detailAmbientLight(
            color = detailAccent,
            dark = darkLighting,
            showFallback = ogState.shouldShowFallback,
            scrollYProvider = { scrollState.value },
            bannerAspectRatio = ogBitmap?.let { calculateOgAspectRatio(it.width, it.height) },
            statusBarTopPadding = statusBarTopPadding
        ),
        hideHeaderOnScroll = false,
        header = {
            DetailFloatingHeaderOverlay(
                onBackClick = { requestExit() },
                backEnabled = !isSaving,
                showSave = true,
                saveEnabled = hasChanges && !isSaving,
                isSaving = isSaving,
                topScrimColorProvider = {
                    val topEdge = heroPaletteState.value.topEdgeColor
                    if (ogBitmap != null && topEdge != Color.Transparent) {
                        topEdge
                    } else {
                        surfaces.canvasColor
                    }
                },
                onSaveClick = {
                    if (hasChanges && !isSaving) {
                        isSaving = true
                        coroutineScope.launch {
                            try {
                                viewModel.updateBookmarkDetails(bookmark.id, noteText, editedTag, editedIsNsfw, pendingReplacements)
                                onNavigateBack()
                            } catch (e: Exception) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                Toast.makeText(context, context.getString(R.string.common_save_failed), Toast.LENGTH_LONG).show()
                            } finally {
                                isSaving = false
                            }
                        }
                    }
                }
            )
        },
        overlay = {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val toolbarOffset = if (maxWidth >= 368.dp) 32.dp else 0.dp
                Row(
                    modifier = Modifier.fillMaxSize().navigationBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, bottom = 31.dp)
                        .offset(x = toolbarOffset),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom
                ) {
                    TidiloSurface(role = SurfaceRole.TOOLBAR, blurBackground = true) {
                        Box {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TidiloIconButton(role = SurfaceRole.TOOLBAR_ACTION, onClick = { showDeleteConfirmDialog = true }, modifier = Modifier.size(54.dp)) {
                                    Icon(ImageVector.vectorResource(R.drawable.ic_delete),
                                        contentDescription = stringResource(R.string.detail_action_delete),
                                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                                }
                                TidiloIconButton(role = SurfaceRole.TOOLBAR_ACTION, onClick = { onNavigateToAiReview(bookmark.id) }, modifier = Modifier.size(54.dp)) {
                                    Icon(Icons.Default.AutoAwesome,
                                        contentDescription = stringResource(R.string.detail_action_ai_summary),
                                        modifier = Modifier.size(22.dp))
                                }
                                TidiloIconButton(role = SurfaceRole.TOOLBAR_ACTION, onClick = { showCollectionMembership = true },
                                    modifier = Modifier.size(54.dp).semantics { selected = hasCollection }) {
                                    Icon(if (hasCollection) Icons.Filled.Star else ImageVector.vectorResource(R.drawable.ic_star),
                                        contentDescription = stringResource(R.string.bookmark_collection_action),
                                        tint = if (hasCollection) collectionMembershipColor(MaterialTheme.colorScheme.surfaceContainerHighest)
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp))
                                }
                                TidiloIconToggleButton(role = SurfaceRole.LIKE_ACTION, checked = bookmark.isLiked, onCheckedChange = { viewModel.toggleLike(bookmark.id) }, modifier = Modifier.size(54.dp)) {
                                    Icon(
                                        imageVector = if (bookmark.isLiked) Icons.Filled.Favorite else ImageVector.vectorResource(R.drawable.ic_favorite),
                                        contentDescription = stringResource(if (bookmark.isLiked) R.string.bookmark_unlike else R.string.bookmark_like),
                                        tint = if (bookmark.isLiked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    TidiloSurface(
                        onClick = { openBookmarkInBrowser(context, bookmark.url) { viewModel.recordBrowserOpen(bookmark.id) } },
                        role = SurfaceRole.PRIMARY_ACTION,
                        blurBackground = true,
                        variant = SurfaceVariant.COMPACT,
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_open_in_browser),
                                    contentDescription = stringResource(R.string.bookmark_open_in_browser),
                                    tint = LocalContentColor.current,
                                    modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }

            // Discard changes confirmation dialog
            if (showDiscardConfirmDialog) {
                TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).shape,
                    onDismissRequest = { showDiscardConfirmDialog = false; discardToCollections = false },
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
                                val toCollections = discardToCollections
                                discardToCollections = false
                                if (toCollections) onOpenCollections() else onNavigateBack()
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
                            onClick = { showDiscardConfirmDialog = false; discardToCollections = false }
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
                TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
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
                    shape = themeCorner(ThemeCorner.PROMINENT_CARD)
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
                    onAddEntityClick = { entityInput = ""; showAddEntityDialog = true },
                    onOpenEntityPickerClick = { showEntityBottomSheet = true },
                    onConfirm = {
                        editedTag = Tag.fromDisplayValues(
                            draftCategory, draftSubcategory, draftEntities
                        )
                        editedIsNsfw = draftIsNsfw
                        pendingReplacements = draftReplacements
                        showTagEditDialog = false
                    },
                    onDismiss = { showTagEditDialog = false }
                )
            }

            if (showAddEntityDialog) {
                val suggestionCandidates = remember(bookmark.title, bookmark.description) {
                    EntityCandidateExtractor.default()
                        .extractSuggestionTokens(bookmark.title, bookmark.description)
                }
                AddEntityDialog(
                    onDismiss = { showAddEntityDialog = false },
                    text = entityInput,
                    onTextChange = { entityInput = it },
                    libraryState = libraryState,
                    onRetry = { entityLibraryRetry++ },
                    candidates = suggestionCandidates,
                    onConfirm = { rawInput ->
                        val draft = EntityTagDraft(draftEntities, draftReplacements)
                        val conflict = entityTagConflict(rawInput, draft, libraryState.spellings)
                        if (conflict != null) {
                            conflictInput = rawInput
                            conflictExisting = conflict.existing
                        } else {
                            val next = applyEntityTagChoice(draft, rawInput, null, false)
                            draftEntities = next.entities
                        }
                    }
                )
            }
            val rawConflictInput = conflictInput
            val existingConflict = conflictExisting
            if (rawConflictInput != null && existingConflict != null) {
                EntityTagConflictDialog(
                    entered = me.bubblespark.tidilo.data.EntityTagPolicy.clean(rawConflictInput).orEmpty(),
                    existing = existingConflict,
                    onDismiss = { conflictInput = null; conflictExisting = null },
                    onChoice = { replace ->
                        val next = applyEntityTagChoice(EntityTagDraft(draftEntities, draftReplacements), rawConflictInput, existingConflict, replace)
                        draftEntities = next.entities
                        draftReplacements = next.replacements
                        conflictInput = null
                        conflictExisting = null
                    }
                )
            }

            if (showEntityBottomSheet) {
                val allLibraryEntities = libraryState.spellings.map { (key, value) -> draftReplacements[key] ?: value }
                EntityTagPickerBottomSheet(
                    selectedEntities = draftEntities,
                    allEntities = (draftEntities + allLibraryEntities).distinctBy { me.bubblespark.tidilo.data.entitySkeletonKey(it) },
                    onToggleEntity = { entity ->
                        draftEntities = me.bubblespark.tidilo.data.EntityTagPolicy.toggleDisplayEntity(draftEntities, entity)
                    },
                    onRemoveEntity = { entity ->
                        val targetKey = me.bubblespark.tidilo.data.entitySkeletonKey(entity)
                        draftEntities = draftEntities.filterNot { me.bubblespark.tidilo.data.entitySkeletonKey(it) == targetKey }
                    },
                    onDismiss = { showEntityBottomSheet = false }
                )
            }
        }
    ) { _ ->
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
        ) {
            // Hero + Above-Title Metadata Container (full-bleed og:image or DomainWatermark fallback)
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Upper right domain watermark (rendered only when og:image is unavailable and resolution completed)
                if (ogState.shouldShowDomainWatermarkFallback) {
                    DomainWatermark(
                        domain = domain,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    val canvasColor = surfaces.canvasColor
                    if (ogBitmap != null) {
                        // Upward gradient extension above the image (status bar band, mirroring downward extension)
                        if (statusBarTopPadding > 0.dp) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(statusBarTopPadding)
                                    .drawWithCache {
                                        var cachedTopColor = Color.Unspecified
                                        var cachedBrush: Brush? = null
                                        onDrawBehind {
                                            val topEdge = heroPaletteState.value.topEdgeColor
                                            if (topEdge != Color.Transparent) {
                                                val existing = cachedBrush
                                                val brush = if (topEdge != cachedTopColor || existing == null) {
                                                    cachedTopColor = topEdge
                                                    Brush.verticalGradient(
                                                        colorStops = arrayOf(
                                                            0.0f to Color.Transparent,
                                                            0.45f to topEdge.copy(alpha = 0.50f),
                                                            1.0f to topEdge
                                                        )
                                                    ).also { cachedBrush = it }
                                                } else {
                                                    existing
                                                }
                                                drawRect(brush = brush)
                                            }
                                        }
                                    }
                            )
                        }
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val bannerHeight = minOf(
                                maxWidth / calculateOgAspectRatio(ogBitmap.width, ogBitmap.height),
                                DETAIL_HERO_MAX_BANNER_HEIGHT
                            )
                            Image(
                                bitmap = ogBitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(bannerHeight)
                                    .drawWithCache {
                                        // Minimal edge feathering: just enough to soften the 1px seam without covering the artwork
                                        val topFeatherPx = minOf(12.dp.toPx(), size.height * 0.08f)
                                        val bottomFeatherPx = minOf(14.dp.toPx(), size.height * 0.10f)
                                        val bottomStartY = size.height - bottomFeatherPx
                                        var cachedTopColor = Color.Unspecified
                                        var cachedTopBrush: Brush? = null
                                        var cachedBottomColor = Color.Unspecified
                                        var cachedBottomBrush: Brush? = null
                                        onDrawWithContent {
                                            drawContent()
                                            val palette = heroPaletteState.value
                                            val topEdge = if (palette.topEdgeColor != Color.Transparent) {
                                                palette.topEdgeColor
                                            } else {
                                                canvasColor
                                            }
                                            if (topFeatherPx > 0f && topEdge != Color.Transparent) {
                                                val existingTop = cachedTopBrush
                                                val topBrush = if (topEdge != cachedTopColor || existingTop == null) {
                                                    cachedTopColor = topEdge
                                                    Brush.verticalGradient(
                                                        colors = listOf(topEdge, Color.Transparent),
                                                        startY = 0f,
                                                        endY = topFeatherPx
                                                    ).also { cachedTopBrush = it }
                                                } else {
                                                    existingTop
                                                }
                                                drawRect(
                                                    brush = topBrush,
                                                    size = Size(size.width, topFeatherPx)
                                                )
                                            }
                                            val bottomEdge = palette.bottomEdgeColor
                                            if (bottomFeatherPx > 0f && bottomEdge != Color.Transparent) {
                                                val existingBottom = cachedBottomBrush
                                                val bottomBrush = if (bottomEdge != cachedBottomColor || existingBottom == null) {
                                                    cachedBottomColor = bottomEdge
                                                    Brush.verticalGradient(
                                                        colors = listOf(Color.Transparent, bottomEdge),
                                                        startY = bottomStartY,
                                                        endY = size.height
                                                    ).also { cachedBottomBrush = it }
                                                } else {
                                                    existingBottom
                                                }
                                                drawRect(
                                                    brush = bottomBrush,
                                                    topLeft = Offset(0f, bottomStartY),
                                                    size = Size(size.width, bottomFeatherPx)
                                                )
                                            }
                                        }
                                    }
                            )
                        }
                    } else {
                        // Fallback top clearance so floating Back & Save buttons never overlap text
                        Spacer(modifier = Modifier.height(statusBarTopPadding + 72.dp))
                    }

                    // Downward gradient extension (starts at bottomEdgeColor when og:image is present)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawWithCache {
                                var cachedBottomColor = Color.Unspecified
                                var cachedBrush: Brush? = null
                                onDrawBehind {
                                    val bottomEdge = heroPaletteState.value.bottomEdgeColor
                                    if (ogBitmap != null && bottomEdge != Color.Transparent) {
                                        val existing = cachedBrush
                                        val brush = if (bottomEdge != cachedBottomColor || existing == null) {
                                            cachedBottomColor = bottomEdge
                                            Brush.verticalGradient(
                                                colorStops = arrayOf(
                                                    0.0f to bottomEdge,
                                                    0.55f to bottomEdge.copy(alpha = 0.35f),
                                                    1.0f to Color.Transparent
                                                )
                                            ).also { cachedBrush = it }
                                        } else {
                                            existing
                                        }
                                        drawRect(brush = brush)
                                    }
                                }
                            }
                            .padding(
                                start = HeaderDefaults.HorizontalPadding,
                                end = HeaderDefaults.HorizontalPadding,
                                top = if (ogBitmap != null) 12.dp else 0.dp,
                                bottom = 14.dp
                            )
                    ) {
                        // Line 1: Source Attribution Row (Domain • Relative Time)
                        Text(
                            text = sourceAttributionText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Line 2: Plain-Text Classification Breadcrumb (omitted with 0dp gap when empty)
                        if (classificationBreadcrumb != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = classificationBreadcrumb,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Line 3: Bookmark Title
                        Text(
                            text = bookmark.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            lineHeight = MaterialTheme.typography.titleLarge.lineHeight * 1.15f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Below-Title Body Sections (padded by HorizontalPadding)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HeaderDefaults.HorizontalPadding)
            ) {
                // 4. Below-Title Entity-Only Tag Row + Edit Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        if (editedTag.entities.isNotEmpty()) {
                            TidiloClassificationContent(
                                decorated = {
                                    BookmarkEntityChipsFlow(entities = editedTag.entities)
                                },
                                material = {
                                    CompositionLocalProvider(
                                        LocalMinimumInteractiveComponentSize provides Dp.Unspecified
                                    ) {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            editedTag.entities.forEach { entity ->
                                                AssistChip(
                                                    onClick = {},
                                                    label = { Text(entity) },
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
                                                    shape = themeCorner(ThemeCorner.CONTROL)
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        } else {
                            Surface(
                                onClick = { openTagEditorDialog() },
                                shape = themeCorner(ThemeCorner.CONTROL),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                            ) {
                                Text(
                                    text = stringResource(R.string.detail_add_tags_placeholder),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    CompositionLocalProvider(
                        LocalMinimumInteractiveComponentSize provides Dp.Unspecified
                    ) {
                        IconButton(
                            onClick = { openTagEditorDialog() },
                            modifier = Modifier.size(28.dp)
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

                Spacer(modifier = Modifier.height(24.dp))

                // 5. Section: Summary (Note-like document icon + Editorial Summary Callout)
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_summarize),
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

                val defaultSummaryAccent = MaterialTheme.colorScheme.primary
                val summaryWashStart = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                val summaryWashEnd = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f)
                val summaryEndCorner = surfaces.preset.shapeScale.field
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(
                            RoundedCornerShape(
                                topStart = 4.dp,
                                bottomStart = 4.dp,
                                topEnd = summaryEndCorner,
                                bottomEnd = summaryEndCorner
                            )
                        )
                        .drawWithCache {
                            val washBrush = Brush.horizontalGradient(
                                colors = listOf(summaryWashStart, summaryWashEnd)
                            )
                            onDrawBehind {
                                drawRect(brush = washBrush)
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(3.dp)
                            .fillMaxHeight()
                            .drawBehind {
                                val accent = detailAccent.value
                                drawRect(color = if (accent != Color.Transparent) accent else defaultSummaryAccent)
                            }
                    )
                    Text(
                        text = bookmark.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.3f,
                        modifier = Modifier.padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)
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
                        shape = themeCorner(ThemeCorner.FIELD),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 7. Footer Metadata: Full URL + Date watermark (Created / Updated)
                val copyUrlToClipboard = {
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
                @OptIn(ExperimentalFoundationApi::class)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = bookmark.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .clip(themeCorner(ThemeCorner.SMALL))
                            .combinedClickable(
                                onClick = copyUrlToClipboard,
                                onLongClick = copyUrlToClipboard
                            )
                            .padding(vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
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
            } // End of below-title body Column

            // Bottom spacer to prevent overlap with the toolbar
            Spacer(modifier = Modifier.height(96.dp))
        }
    } // End of shared screen scaffold
} // End of BookmarkDetailScreen

/**
 * Persistent top overlay for [BookmarkDetailScreen] providing a status-bar protection gradient scrim
 * and fixed circular floating **Back** (top-left) and **Save** (top-right, floppy-disk icon) actions.
 */
@Composable
private fun DetailFloatingHeaderOverlay(
    onBackClick: () -> Unit,
    backEnabled: Boolean,
    showSave: Boolean,
    saveEnabled: Boolean = false,
    isSaving: Boolean = false,
    topScrimColorProvider: () -> Color = { Color.Unspecified },
    onSaveClick: () -> Unit = {}
) {
    val surfaces = currentThemeSurfaces()
    val fallbackScrimColor = surfaces.canvasColor
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier = Modifier.fillMaxWidth()) {
        // Permanent top status-bar protection gradient scrim (confined to the status bar + 8dp soft edge)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(statusBarHeight + 8.dp)
                .drawWithCache {
                    var cachedScrimBase = Color.Unspecified
                    var cachedBrush: Brush? = null
                    onDrawBehind {
                        val provided = topScrimColorProvider()
                        val scrimBase = if (provided != Color.Unspecified && provided != Color.Transparent) {
                            provided
                        } else {
                            fallbackScrimColor
                        }
                        val existing = cachedBrush
                        val brush = if (scrimBase != cachedScrimBase || existing == null) {
                            cachedScrimBase = scrimBase
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to scrimBase.copy(alpha = 0.68f),
                                    0.72f to scrimBase.copy(alpha = 0.24f),
                                    1.0f to Color.Transparent
                                )
                            ).also { cachedBrush = it }
                        } else {
                            existing
                        }
                        drawRect(brush = brush)
                    }
                }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(
                    start = HeaderDefaults.HorizontalPadding,
                    end = HeaderDefaults.HorizontalPadding,
                    top = 10.dp,
                    bottom = 8.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TidiloSurface(
                onClick = onBackClick,
                role = SurfaceRole.FLOATING_BAR,
                state = ThemeSurfaceState(enabled = backEnabled),
                blurBackground = true,
                modifier = Modifier.size(48.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        tint = if (!backEnabled) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (showSave) {
                TidiloSurface(
                    onClick = onSaveClick,
                    role = if (saveEnabled || isSaving) SurfaceRole.PRIMARY_ACTION else SurfaceRole.FLOATING_BAR,
                    state = ThemeSurfaceState(enabled = saveEnabled, selected = saveEnabled),
                    blurBackground = true,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current
                            )
                        } else {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_save),
                                contentDescription = stringResource(R.string.detail_save),
                                tint = if (saveEnabled) {
                                    LocalContentColor.current
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }
        }
    }
}
/** Disposes the lookup with its editors and resets the snapshot on reopen or retry. */
@Composable
internal fun rememberEntityLibraryState(
    needed: Boolean,
    source: kotlinx.coroutines.flow.Flow<EntityLibraryState>
): EntityLibraryState {
    if (!needed) return EntityLibraryState()
    return key(source) {
        val state by source.collectAsStateWithLifecycle(initialValue = EntityLibraryState())
        state
    }
}
