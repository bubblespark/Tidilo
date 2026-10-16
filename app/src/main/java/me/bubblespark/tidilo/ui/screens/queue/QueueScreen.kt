package me.bubblespark.tidilo.ui.screens.queue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.ui.components.FrostedHeaderContainer
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QueueScreen(
    viewModel: QueueViewModel,
    initialSharedUrl: String? = null,
    modifier: Modifier = Modifier
) {
    val runningItems by viewModel.runningItems.collectAsStateWithLifecycle()
    val queuingItems by viewModel.queuingItems.collectAsStateWithLifecycle()
    val errorItems by viewModel.errorItems.collectAsStateWithLifecycle()

    // Section expand/collapse states (Requirements: Queuing and Error default to open, others to closed)
    var isRunningExpanded by remember { mutableStateOf(false) }
    var isQueuingExpanded by remember { mutableStateOf(true) }
    var isErrorExpanded by remember { mutableStateOf(true) }

    // Show up to 5 items / More Items expanded state
    var showAllRunning by remember { mutableStateOf(false) }
    var showAllQueuing by remember { mutableStateOf(false) }
    var showAllError by remember { mutableStateOf(false) }

    var showEnqueueDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var snackbarJob by remember { mutableStateOf<Job?>(null) }

    // Delete & Undo handler (immediately dismisses prior snackbar and displays the newest)
    fun triggerDeleteQueuing(item: QueueItem) {
        val deleted = viewModel.deleteQueuingItem(item.id) ?: return
        snackbarJob?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarJob = coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "\"${deleted.first.title.ifBlank { deleted.first.url }}\" deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreQueuingItem(deleted.first, deleted.second)
            }
        }
    }

    fun triggerDeleteError(item: QueueItem) {
        val deleted = viewModel.deleteErrorItem(item.id) ?: return
        snackbarJob?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarJob = coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "\"${deleted.first.title.ifBlank { deleted.first.url }}\" deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreErrorItem(deleted.first, deleted.second)
            }
        }
    }

    // Horizontal bounce affordance on screen transition (if items exist in Queuing or Error)
    val bounceOffset = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(300)
        if (queuingItems.isNotEmpty() || errorItems.isNotEmpty()) {
            bounceOffset.animateTo(
                targetValue = 24f,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
            )
            bounceOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }

    // Enqueue from share intent
    LaunchedEffect(initialSharedUrl) {
        if (!initialSharedUrl.isNullOrBlank()) {
            viewModel.enqueueUrl(initialSharedUrl)
        }
    }

    val isBarsVisible = LocalBarsVisible.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerHeight = statusBarTop + 80.dp

    Box(modifier = modifier.fillMaxSize()) {
        // 3 sections (Running, Queuing, Error)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = headerHeight,
                bottom = 140.dp,
                start = 20.dp,
                end = 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ==================== 1. Running Group (defaults to closed) ====================
            item {
                CollapsibleGroupHeader(
                    title = "Running",
                    count = runningItems.size,
                    color = MaterialTheme.colorScheme.primary,
                    isExpanded = isRunningExpanded,
                    onToggle = { isRunningExpanded = !isRunningExpanded }
                )
            }

            if (isRunningExpanded) {
                if (runningItems.isEmpty()) {
                    item {
                        EmptyGroupCard(
                            message = "No tasks currently running",
                            hint = "Trigger 'AI Tagging All' from the menu or promote an item"
                        )
                    }
                } else {
                    val visibleRunning = if (showAllRunning) runningItems else runningItems.take(5)
                    items(
                        items = visibleRunning,
                        key = { it.id },
                        contentType = { "queue_item" }
                    ) { item ->
                        QueueItemCard(
                            item = item,
                            onAiPromoteClick = if (item.status == QueueItemStatus.WAITING_AI) {
                                { viewModel.retryRunning(it.id) }
                            } else null,
                            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                        )
                    }
                    if (runningItems.size > 5) {
                        item {
                            MoreItemsButton(
                                hiddenCount = runningItems.size - 5,
                                isExpanded = showAllRunning,
                                onClick = { showAllRunning = !showAllRunning }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            // ==================== 2. Queuing Group (defaults to open) ====================
            item {
                CollapsibleGroupHeader(
                    title = "Queuing",
                    count = queuingItems.size,
                    color = MaterialTheme.colorScheme.secondary,
                    isExpanded = isQueuingExpanded,
                    onToggle = { isQueuingExpanded = !isQueuingExpanded }
                )
            }

            if (isQueuingExpanded) {
                if (queuingItems.isEmpty()) {
                    item {
                        EmptyGroupCard(
                            message = "No URLs waiting in queue",
                            hint = "Add URLs via the '+' Enqueue button or device Share menu"
                        )
                    }
                } else {
                    val visibleQueuing = if (showAllQueuing) queuingItems else queuingItems.take(5)
                    items(
                        items = visibleQueuing,
                        key = { it.id },
                        contentType = { "queue_item" }
                    ) { item ->
                        SwipeableQueueItemCard(
                            item = item,
                            bounceOffsetDp = bounceOffset.value,
                            onAiPromoteClick = { viewModel.promoteToRunning(it.id) },
                            onSwipeRight = { viewModel.promoteToRunning(item.id) },
                            onSwipeLeft = { triggerDeleteQueuing(item) },
                            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                        )
                    }
                    if (queuingItems.size > 5) {
                        item {
                            MoreItemsButton(
                                hiddenCount = queuingItems.size - 5,
                                isExpanded = showAllQueuing,
                                onClick = { showAllQueuing = !showAllQueuing }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            // ==================== 3. Error Group (defaults to open) ====================
            item {
                CollapsibleGroupHeader(
                    title = "Error",
                    count = errorItems.size,
                    color = MaterialTheme.colorScheme.error,
                    isExpanded = isErrorExpanded,
                    onToggle = { isErrorExpanded = !isErrorExpanded }
                )
            }

            if (isErrorExpanded) {
                if (errorItems.isEmpty()) {
                    item {
                        EmptyGroupCard(
                            message = "No failed tasks",
                            hint = "Rate limits or non-200 HTTP responses will appear here"
                        )
                    }
                } else {
                    val visibleError = if (showAllError) errorItems else errorItems.take(5)
                    items(
                        items = visibleError,
                        key = { it.id },
                        contentType = { "queue_item" }
                    ) { item ->
                        SwipeableQueueItemCard(
                            item = item,
                            bounceOffsetDp = bounceOffset.value,
                            onRetryClick = { viewModel.retryError(it.id) },
                            onSwipeRight = { viewModel.retryError(item.id) },
                            onSwipeLeft = { triggerDeleteError(item) },
                            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                        )
                    }
                    if (errorItems.size > 5) {
                        item {
                            MoreItemsButton(
                                hiddenCount = errorItems.size - 5,
                                isExpanded = showAllError,
                                onClick = { showAllError = !showAllError }
                            )
                        }
                    }
                }
            }
        }

        // Expressive Header (slides out/in from top screen boundary linked with scroll)
        AnimatedVisibility(
            visible = isBarsVisible,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeOut(animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            FrostedHeaderContainer {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Queue",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Semantic AI Tagging Queue",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    // Right action: Enqueue (+) button (AI Tagging All moved to bottom navigation right action button)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showEnqueueDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Enqueue URL",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Undo SnackbarHost (placed above the floating navigation bar)
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp)
        )

        // URL input dialog
        if (showEnqueueDialog) {
            EnqueueUrlDialog(
                onDismiss = { showEnqueueDialog = false },
                onConfirm = { url ->
                    viewModel.enqueueUrl(url)
                    showEnqueueDialog = false
                }
            )
        }
    }
}

/**
 * Collapsible group header.
 */
@Composable
private fun CollapsibleGroupHeader(
    title: String,
    count: Int,
    color: Color,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onToggle() },
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = color.copy(alpha = 0.25f),
                    modifier = Modifier.size(10.dp)
                ) {}
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "$title ($count)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * "More Items" / "Show Less" toggle button when there are more than 5 items.
 */
@Composable
private fun MoreItemsButton(
    hiddenCount: Int,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (isExpanded) "Show Less" else "More Items ($hiddenCount more)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Empty state card.
 */
@Composable
private fun EmptyGroupCard(
    message: String,
    hint: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/**
 * Swipe-action enabled card (SwipeToDismissBox):
 * - Right swipe: AI Tagging (green background + Icons.Default.AutoAwesome)
 * - Left swipe: Delete (red background + Icons.Default.Delete)
 * - positionalThreshold: Prevents unintended triggers and vertical scroll interference (75% movement to confirm)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableQueueItemCard(
    item: QueueItem,
    bounceOffsetDp: Float,
    onAiPromoteClick: ((QueueItem) -> Unit)? = null,
    onRetryClick: ((QueueItem) -> Unit)? = null,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var isActionCompleted by remember(item.id) { mutableStateOf(false) }
    var confirmedDirection by remember(item.id) { mutableStateOf<SwipeToDismissBoxValue?>(null) }
    val backgroundAlpha = remember(item.id) { Animatable(1f) }

    // Cleanly instantiate with remember(item.id) instead of rememberSaveable to prevent
    // restoring past off-screen swipe states (EndToStart/StartToEnd) upon Undo restore.
    var dismissStateRef by remember(item.id) { mutableStateOf<SwipeToDismissBoxState?>(null) }
    val dismissState = remember(item.id) {
        @Suppress("DEPRECATION")
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            density = density,
            confirmValueChange = { dismissValue ->
                if (isActionCompleted) return@SwipeToDismissBoxState false
                if (dismissValue == SwipeToDismissBoxValue.Settled) {
                    confirmedDirection = null
                    return@SwipeToDismissBoxState true
                }
                // Reset if not swiped past ~75% (reliably prevents accidental triggers due to velocity)
                val currentProgress = dismissStateRef?.progress ?: 0f
                android.util.Log.d("QueueSwipe", "confirmValueChange: $dismissValue, progress=$currentProgress")
                if (currentProgress < 0.75f) {
                    confirmedDirection = null
                    return@SwipeToDismissBoxState false
                }
                // Confirmed swipe at or above 75%. Lock direction during slide-out and fade
                confirmedDirection = dismissValue
                true
            },
            positionalThreshold = { totalDistance -> totalDistance * 0.75f }
        ).also { dismissStateRef = it }
    }

    // After the card completely leaves the screen (currentValue reaches boundary),
    // fade out the remaining background color and execute the action upon completion.
    LaunchedEffect(dismissState.currentValue) {
        val current = dismissState.currentValue
        if (current != SwipeToDismissBoxValue.Settled && !isActionCompleted) {
            isActionCompleted = true
            confirmedDirection = current
            // 1. Fade out the remaining action background color
            backgroundAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
            )
            // 2. Execute action after fade-out completes (ViewModel update -> item removal)
            if (current == SwipeToDismissBoxValue.StartToEnd) {
                onSwipeRight()
            } else if (current == SwipeToDismissBoxValue.EndToStart) {
                onSwipeLeft()
            }
        }
    }

    // Determine background direction to display:
    // After confirmation, use locked confirmedDirection.
    // Otherwise (idle, swiping, cancel return), use dismissState.dismissDirection directly.
    // Reliably return to Settled (transparent) when released back to center.
    val activeDirection = if (isActionCompleted || confirmedDirection != null) {
        confirmedDirection ?: dismissState.dismissDirection
    } else {
        dismissState.dismissDirection
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            SwipeDismissBackground(
                direction = activeDirection,
                alpha = backgroundAlpha.value
            )
        },
        modifier = modifier.offset(x = bounceOffsetDp.dp),
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        gesturesEnabled = !isActionCompleted
    ) {
        QueueItemCard(
            item = item,
            onAiPromoteClick = onAiPromoteClick,
            onRetryClick = onRetryClick
        )
    }
}

/**
 * Background displayed during swipe (Green + Sparkles / Red + Trash).
 * Supports alpha parameter for fading out background color after card frames out.
 */
@Composable
private fun SwipeDismissBackground(
    direction: SwipeToDismissBoxValue,
    alpha: Float = 1f
) {
    val backgroundColor = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Color(0xFF2E7D32) // Green (AI Tagging)
        SwipeToDismissBoxValue.EndToStart -> Color(0xFFC62828) // Red (Delete)
        SwipeToDismissBoxValue.Settled -> Color.Transparent
    }
    val icon = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Icons.Default.AutoAwesome
        SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
        SwipeToDismissBoxValue.Settled -> null
    }
    val alignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
        Alignment.CenterStart
    } else {
        Alignment.CenterEnd
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/**
 * Queue item card (supports Error highlight and Completed tag badges).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueItemCard(
    item: QueueItem,
    onAiPromoteClick: ((QueueItem) -> Unit)? = null,
    onRetryClick: ((QueueItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isError = item.status == QueueItemStatus.ERROR

    Card(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadgeIcon(status = item.status)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = item.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // AI tagging button for Queuing or Running (WAITING_AI) items
                if ((item.status == QueueItemStatus.QUEUED || item.status == QueueItemStatus.WAITING_AI) && onAiPromoteClick != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = { onAiPromoteClick(item) },
                        modifier = Modifier.size(38.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Run AI Tagging",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Retry button for Error items
                if (isError && onRetryClick != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = { onRetryClick(item) },
                        modifier = Modifier.size(38.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Highlight error message
            if (isError && !item.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Display tag badges for Completed items
            if (item.status == QueueItemStatus.COMPLETED && item.assignedTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item.assignedTags.forEach { tag ->
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Pulse animation only during AI processing
            if (item.status == QueueItemStatus.PROCESSING_AI) {
                PulsingStatusText(
                    text = item.statusText,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else if (!isError) {
                Text(
                    text = item.statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (item.status) {
                        QueueItemStatus.WAITING_AI -> MaterialTheme.colorScheme.tertiary
                        QueueItemStatus.QUEUED -> MaterialTheme.colorScheme.secondary
                        QueueItemStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        QueueItemStatus.PROCESSING_AI,
                        QueueItemStatus.ERROR -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/**
 * Lightweight pulsing status text specifically for AI processing state.
 */
@Composable
private fun PulsingStatusText(
    text: String,
    color: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.graphicsLayer { this.alpha = alphaAnim }
    )
}

@Composable
private fun StatusBadgeIcon(status: QueueItemStatus) {
    val (icon: ImageVector, color: Color) = when (status) {
        QueueItemStatus.PROCESSING_AI -> Icons.Default.AutoAwesome to MaterialTheme.colorScheme.primary
        QueueItemStatus.WAITING_AI -> Icons.Default.HourglassBottom to MaterialTheme.colorScheme.tertiary
        QueueItemStatus.QUEUED -> Icons.Default.AddLink to MaterialTheme.colorScheme.secondary
        QueueItemStatus.COMPLETED -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        QueueItemStatus.ERROR -> Icons.Default.Error to MaterialTheme.colorScheme.error
    }

    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Dialog for manual URL enqueuing.
 */
@Composable
private fun EnqueueUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    val isValid = remember(urlText) { QueueViewModel.isValidHttpUrl(urlText) }
    val isDirty = urlText.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AddLink,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Enqueue URL",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "Enter a valid HTTP or HTTPS web address to add to the queue for Gemini Nano tagging.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    placeholder = { Text("https://example.com/...") },
                    singleLine = true,
                    isError = isDirty && !isValid,
                    supportingText = {
                        if (isDirty && !isValid) {
                            Text(
                                text = "Must start with http:// or https:// and have a valid domain",
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(text = "http:// or https:// required")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(urlText) },
                enabled = isValid
            ) {
                Text(
                    text = "Enqueue",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
