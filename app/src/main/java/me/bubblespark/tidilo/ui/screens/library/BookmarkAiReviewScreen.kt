package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.ui.components.TidiloFrostedHeader
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.Subcategory
import me.bubblespark.tidilo.service.GeminiTaggingService
import me.bubblespark.tidilo.service.TaggingResult

enum class ProposalSource {
    CURRENT,
    PROPOSAL
}

sealed interface AiReviewUiState {
    data object Loading : AiReviewUiState
    data class Success(val proposal: TaggingResult) : AiReviewUiState
    data class Error(val message: String) : AiReviewUiState
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookmarkAiReviewScreen(
    bookmarkId: String,
    viewModel: LibraryViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    generateProposal: suspend (String, me.bubblespark.tidilo.config.AppLanguage, String?) -> Result<TaggingResult> = GeminiTaggingService::generateProposal
) {
    val context = LocalContext.current
    val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()
    var isSaving by remember { mutableStateOf(false) }
    val persistenceScope = androidx.compose.runtime.rememberCoroutineScope()
    var detailRetry by remember(bookmarkId) { mutableStateOf(0) }
    val detailFlow = remember(viewModel, bookmarkId, detailRetry) { viewModel.bookmarkDetails(bookmarkId) }
    val detailState by detailFlow.collectAsStateWithLifecycle(initialValue = BookmarkDetailState())
    val bookmark = detailState.bookmark
    if (detailState.loading || detailState.error != null || bookmark == null) {
        TidiloScreenScaffold(
            modifier = modifier,
            header = { TidiloPageHeader(stringResource(R.string.ai_review_title), onNavigateBack) }
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

    var retryCount by remember { mutableIntStateOf(0) }
    var uiState by remember { mutableStateOf<AiReviewUiState>(AiReviewUiState.Loading) }
    var selectedSummarySource by rememberSaveable { mutableStateOf(ProposalSource.PROPOSAL) }
    var selectedTagSource by rememberSaveable { mutableStateOf(ProposalSource.PROPOSAL) }
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }

    // Intercept back gesture only when reviewing proposal with unsaved changes
    BackHandler(enabled = uiState is AiReviewUiState.Success) {
        showDiscardConfirmDialog = true
    }

    // Trigger AI inference on entry or retry
    LaunchedEffect(bookmarkId, retryCount) {
        uiState = AiReviewUiState.Loading
        val result = generateProposal(bookmark.url, currentLanguage, bookmark.title)
        uiState = result.fold(
            onSuccess = { proposal ->
                AiReviewUiState.Success(proposal)
            },
            onFailure = { error ->
                AiReviewUiState.Error(error.message ?: "Failed to generate AI proposal")
            }
        )
    }

    TidiloScreenScaffold(
        modifier = modifier,
        hideHeaderOnScroll = false,
        header = {
            TidiloFrostedHeader(
                title = {
                    Text(
                        text = stringResource(R.string.ai_review_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState is AiReviewUiState.Success) {
                                showDiscardConfirmDialog = true
                            } else {
                                onNavigateBack()
                            }
                        },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            )
        },
        overlay = {
            // Floating Checkmark FAB (matches Detail screen design and position: 86dp circular container, 40dp icon, 16dp margins)
            if (uiState is AiReviewUiState.Success) {
                val proposal = (uiState as AiReviewUiState.Success).proposal
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp, end = 16.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Surface(
                        onClick = {
                            if (isSaving) return@Surface
                            isSaving = true
                            val finalSummary = if (selectedSummarySource == ProposalSource.PROPOSAL) {
                                proposal.summary
                            } else {
                                bookmark.description
                            }

                            val finalTag = if (selectedTagSource == ProposalSource.PROPOSAL) {
                                me.bubblespark.tidilo.ui.screens.library.Tag.create(
                                    category = proposal.category,
                                    subcategory = proposal.subcategory,
                                    entities = proposal.entities
                                )
                            } else {
                                bookmark.tag
                            }

                            persistenceScope.launch {
                                try {
                                    viewModel.updateBookmarkFields(
                                        id = bookmark.id,
                                        description = finalSummary,
                                        tag = finalTag
                                    )

                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.ai_review_applied_toast),
                                        Toast.LENGTH_SHORT
                                    ).show()

                                    onNavigateBack()
                                } catch (e: Exception) {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    Toast.makeText(context, context.getString(R.string.common_save_failed), Toast.LENGTH_LONG).show()
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
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
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.ai_review_apply_changes),
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { contentPadding ->
        // Body according to state
        when (val state = uiState) {
            is AiReviewUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding).padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = stringResource(R.string.ai_review_loading_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.ai_review_loading_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            is AiReviewUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding).padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.ai_review_error_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { retryCount++ },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }

            is AiReviewUiState.Success -> {
                val proposal = state.proposal
                val scrollState = rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(contentPadding)
                ) {
                    // Target item overview header
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = bookmark.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = bookmark.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 1. Section: Summary
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
                            text = stringResource(R.string.ai_review_section_summary),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = stringResource(R.string.ai_review_select_summary),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )

                    // Option 1: AI Proposal
                    SummaryComparisonCard(
                        title = stringResource(R.string.ai_review_proposal_badge),
                        text = proposal.summary,
                        isSelected = selectedSummarySource == ProposalSource.PROPOSAL,
                        isAi = true,
                        onClick = { selectedSummarySource = ProposalSource.PROPOSAL }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Option 2: Current
                    SummaryComparisonCard(
                        title = stringResource(R.string.ai_review_current_badge),
                        text = bookmark.description.ifBlank {
                            stringResource(R.string.ai_review_no_summary)
                        },
                        isSelected = selectedSummarySource == ProposalSource.CURRENT,
                        isAi = false,
                        onClick = { selectedSummarySource = ProposalSource.CURRENT }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 2. Section: Category & Tags
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.ai_review_section_tags),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = stringResource(R.string.ai_review_select_tags),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )

                    // Option 1: AI Proposal
                    TagComparisonCard(
                        title = stringResource(R.string.ai_review_proposal_badge),
                        category = proposal.category,
                        subcategory = proposal.subcategory,
                        entities = proposal.entities,
                        isSelected = selectedTagSource == ProposalSource.PROPOSAL,
                        isAi = true,
                        onClick = { selectedTagSource = ProposalSource.PROPOSAL }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Option 2: Current
                    TagComparisonCard(
                        title = stringResource(R.string.ai_review_current_badge),
                        category = bookmark.tag.category,
                        subcategory = bookmark.tag.subcategory,
                        entities = bookmark.tag.entities,
                        isSelected = selectedTagSource == ProposalSource.CURRENT,
                        isAi = false,
                        onClick = { selectedTagSource = ProposalSource.CURRENT }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 3. Section: Note Info Notice
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.ai_review_note_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    // Space for floating check button
                    Spacer(modifier = Modifier.height(130.dp))
                }
            }
        }
    }

    // Discard confirmation dialog
    if (showDiscardConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.ai_review_dialog_discard_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.ai_review_dialog_discard_message)
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
                        text = stringResource(R.string.ai_review_dialog_discard_confirm),
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
}

@Composable
private fun SummaryComparisonCard(
    title: String,
    text: String,
    isSelected: Boolean,
    isAi: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isAi) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (isAi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                RadioButton(
                    selected = isSelected,
                    onClick = null,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.3f
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagComparisonCard(
    title: String,
    category: String?,
    subcategory: String?,
    entities: List<String>,
    isSelected: Boolean,
    isAi: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    }

    val localizedCategory = category?.let { Category.toLocalized(it, context) } ?: ""
    val localizedSubcategory = subcategory?.let { Subcategory.toLocalized(it, context) } ?: ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isAi) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (isAi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                RadioButton(
                    selected = isSelected,
                    onClick = null,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Category & Subcategory chip
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (localizedCategory.isNotBlank() && localizedSubcategory.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text(
                            text = "$localizedCategory › $localizedSubcategory",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = stringResource(R.string.ai_review_uncategorized),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Entities chips
            if (entities.isNotEmpty()) {
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides Dp.Unspecified
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        entities.forEach { entity ->
                            AssistChip(
                                onClick = {},
                                label = { Text(entity.removePrefix("#"), style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Tag,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                                    labelColor = MaterialTheme.colorScheme.onSurface
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
