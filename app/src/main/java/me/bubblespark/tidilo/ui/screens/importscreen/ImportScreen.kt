package me.bubblespark.tidilo.ui.screens.importscreen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.data.ImportTask
import me.bubblespark.tidilo.data.ImportingTask
import me.bubblespark.tidilo.data.TaskStatus
import me.bubblespark.tidilo.ui.components.DomainWatermark
import me.bubblespark.tidilo.ui.components.FrostedHeaderContainer
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    initialSharedUrl: String? = null,
    modifier: Modifier = Modifier
) {
    val allReadyTasks by viewModel.readyTasks.collectAsStateWithLifecycle()
    val readyTasks = allReadyTasks.filter { it.rssFeedId == null }
    val rssReadyTasks = allReadyTasks.filter { it.rssFeedId != null }
    var isRssReadyExpanded by remember { mutableStateOf(true) }
    var showAllRssReady by remember { mutableStateOf(false) }
    val importingTasks by viewModel.importingTasks.collectAsStateWithLifecycle()
    val errorTasks by viewModel.errorTasks.collectAsStateWithLifecycle()

    // Section expand/collapse states (Requirements: Ready and Error default to open)
    var isReadyExpanded by remember { mutableStateOf(true) }
    var isErrorExpanded by remember { mutableStateOf(true) }

    // Show up to 5 items / More Items expanded state
    var showAllReady by remember { mutableStateOf(false) }
    var showAllError by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var snackbarJob by remember { mutableStateOf<Job?>(null) }

    // Delete & Undo handler (immediately dismisses prior snackbar and displays the newest)
    fun triggerDeleteReady(task: ImportTask) {
        val deleted = viewModel.deleteReadyTask(task.id) ?: return
        snackbarJob?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()
        val deletedTitle = deleted.first.title.ifBlank { deleted.first.url }
        snackbarJob = coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.import_item_deleted, deletedTitle),
                actionLabel = context.getString(R.string.common_undo),
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreReadyTask(deleted.first, deleted.second)
            }
        }
    }

    fun triggerDeleteError(task: ImportTask) {
        val deleted = viewModel.deleteErrorTask(task.id) ?: return
        snackbarJob?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()
        val deletedTitle = deleted.first.title.ifBlank { deleted.first.url }
        snackbarJob = coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.import_item_deleted, deletedTitle),
                actionLabel = context.getString(R.string.common_undo),
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreErrorTask(deleted.first, deleted.second)
            }
        }
    }

    // Battery Quota Exceeded dialog
    val quotaDialogVisible by viewModel.quotaExceededDialogVisible.collectAsStateWithLifecycle()
    if (quotaDialogVisible) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissQuotaExceededDialog() },
            title = {
                Text(
                    text = stringResource(R.string.dialog_aicore_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_aicore_quota_exceeded_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissQuotaExceededDialog() }) {
                    Text(stringResource(R.string.common_ok))
                }
            }
        )
    }

    // Horizontal bounce affordance on screen transition (if items exist in Ready or Error)
    val bounceOffset = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(300)
        if (allReadyTasks.isNotEmpty() || errorTasks.isNotEmpty()) {
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

    // Import from share intent
    LaunchedEffect(initialSharedUrl) {
        if (!initialSharedUrl.isNullOrBlank()) {
            viewModel.importUrl(initialSharedUrl)
        }
    }

    val isBarsVisible = LocalBarsVisible.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerHeight = statusBarTop + 80.dp

    Box(modifier = modifier.fillMaxSize()) {
        // 2 sections (Ready, Error)
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
            // ==================== 1. Ready Group (defaults to open) ====================
            item {
                CollapsibleGroupHeader(
                    title = stringResource(R.string.import_group_ready),
                    count = readyTasks.size + importingTasks.size,
                    color = MaterialTheme.colorScheme.secondary,
                    isExpanded = isReadyExpanded,
                    isLoading = importingTasks.isNotEmpty(),
                    onToggle = { isReadyExpanded = !isReadyExpanded }
                )
            }

            if (isReadyExpanded) {
                if (readyTasks.isEmpty() && importingTasks.isEmpty()) {
                    item {
                        EmptyGroupCard(
                            message = stringResource(R.string.import_empty_ready_title),
                            hint = stringResource(R.string.import_empty_ready_hint)
                        )
                    }
                } else {
                    if (importingTasks.isNotEmpty()) {
                        items(
                            items = importingTasks,
                            key = { "importing_${it.id}" },
                            contentType = { "importing_task" }
                        ) { item ->
                            ImportingTaskCard(
                                item = item,
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                            )
                        }
                    }
                    val visibleReady = if (showAllReady) readyTasks else readyTasks.take(5)
                    items(
                        items = visibleReady,
                        key = { it.id },
                        contentType = { "import_task" }
                    ) { item ->
                        SwipeableTaskCard(
                            item = item,
                            bounceOffsetDp = bounceOffset.value,
                            onSwipeRight = { viewModel.promoteToProcessing(item.id) },
                            onSwipeLeft = { triggerDeleteReady(item) },
                            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                        )
                    }
                    if (readyTasks.size > 5) {
                        item {
                            MoreItemsButton(
                                hiddenCount = readyTasks.size - 5,
                                isExpanded = showAllReady,
                                onClick = { showAllReady = !showAllReady }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            item(key = "rss_ready_header", contentType = "section_header") {
                CollapsibleGroupHeader(
                    title = stringResource(R.string.rss_ready), count = rssReadyTasks.size,
                    color = MaterialTheme.colorScheme.secondary, isExpanded = isRssReadyExpanded,
                    onToggle = { isRssReadyExpanded = !isRssReadyExpanded }
                )
            }
            if (isRssReadyExpanded) {
                if (rssReadyTasks.isEmpty()) {
                    item(key = "rss_ready_empty", contentType = "empty_section") {
                        EmptyGroupCard(stringResource(R.string.rss_ready_empty), stringResource(R.string.rss_ready_hint))
                    }
                } else {
                    items(items = if (showAllRssReady) rssReadyTasks else rssReadyTasks.take(5),
                        key = { it.id }, contentType = { "import_task" }) { item ->
                        SwipeableTaskCard(item = item, bounceOffsetDp = bounceOffset.value,
                            onSwipeRight = { viewModel.promoteToProcessing(item.id) },
                            onSwipeLeft = { triggerDeleteReady(item) },
                            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                    }
                    if (rssReadyTasks.size > 5) item(key = "rss_ready_more", contentType = "more_items") {
                        MoreItemsButton(hiddenCount = rssReadyTasks.size - 5, isExpanded = showAllRssReady,
                            onClick = { showAllRssReady = !showAllRssReady })
                    }
                }
            }

            // ==================== 3. Error Group (defaults to open) ====================
            item {
                CollapsibleGroupHeader(
                    title = stringResource(R.string.import_group_error),
                    count = errorTasks.size,
                    color = MaterialTheme.colorScheme.error,
                    isExpanded = isErrorExpanded,
                    onToggle = { isErrorExpanded = !isErrorExpanded }
                )
            }

            if (isErrorExpanded) {
                if (errorTasks.isEmpty()) {
                    item {
                        EmptyGroupCard(
                            message = stringResource(R.string.import_empty_error_title),
                            hint = stringResource(R.string.import_empty_error_hint)
                        )
                    }
                } else {
                    val visibleError = if (showAllError) errorTasks else errorTasks.take(5)
                    items(
                        items = visibleError,
                        key = { it.id },
                        contentType = { "import_task" }
                    ) { item ->
                        SwipeableTaskCard(
                            item = item,
                            bounceOffsetDp = bounceOffset.value,
                            onSwipeRight = { viewModel.retryError(item.id) },
                            onSwipeLeft = { triggerDeleteError(item) },
                            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                        )
                    }
                    if (errorTasks.size > 5) {
                        item {
                            MoreItemsButton(
                                hiddenCount = errorTasks.size - 5,
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
                            text = stringResource(R.string.import_header_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.import_header_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    // Right action: Import (+) button
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { showImportDialog = true },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.import_button_content_desc),
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
        if (showImportDialog) {
            ImportUrlDialog(
                onDismiss = { showImportDialog = false },
                onConfirm = { url ->
                    viewModel.importUrl(url)
                    showImportDialog = false
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
    isLoading: Boolean = false,
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
                if (isLoading) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = color
                    )
                }
            }

            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) {
                        stringResource(R.string.import_collapse_group, title)
                    } else {
                        stringResource(R.string.import_expand_group, title)
                    },
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
            text = if (isExpanded) {
                stringResource(R.string.import_show_less)
            } else {
                stringResource(R.string.import_more_items, hiddenCount)
            },
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

private enum class TaskSwipeDirection {
    Settled,
    StartToEnd, // Right swipe -> AI Analysis (Green)
    EndToStart  // Left swipe -> Delete (Red)
}

/**
 * Real-time continuous swipeable card with animated slide-out and spring-back.
 */
@Composable
private fun SwipeableTaskCard(
    item: ImportTask,
    bounceOffsetDp: Float = 0f,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offsetX = remember { Animatable(0f) }
    var itemWidthPx by remember { mutableFloatStateOf(1000f) }

    LaunchedEffect(item.id, item.status, item.statusText) {
        if (offsetX.value != 0f) {
            offsetX.snapTo(0f)
        }
    }

    val currentOffset = offsetX.value
    val swipeDirection = when {
        currentOffset > 0f -> TaskSwipeDirection.StartToEnd
        currentOffset < 0f -> TaskSwipeDirection.EndToStart
        else -> TaskSwipeDirection.Settled
    }

    val swipeFraction = (abs(currentOffset) / itemWidthPx).coerceIn(0f, 1f)
    val threshold = 0.40f
    val isPastThreshold = swipeFraction >= threshold

    val iconScale by animateFloatAsState(
        targetValue = if (isPastThreshold) 1.25f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "task_swipe_icon_scale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
    ) {
        if (swipeDirection != TaskSwipeDirection.Settled) {
            SwipeBackground(
                direction = swipeDirection,
                swipeFraction = swipeFraction,
                iconScale = iconScale,
                modifier = Modifier.matchParentSize()
            )
        }

        val bouncePx = with(density) { bounceOffsetDp.dp.toPx() }
        val displayOffset = if (offsetX.value == 0f) bouncePx else offsetX.value

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(x = displayOffset.roundToInt(), y = 0) }
                .draggable(
                    state = rememberDraggableState { delta ->
                        coroutineScope.launch {
                            offsetX.snapTo(offsetX.value + delta)
                        }
                    },
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val currentFraction = abs(offsetX.value) / itemWidthPx
                        if (currentFraction >= threshold) {
                            val targetX = if (offsetX.value > 0f) itemWidthPx * 1.2f else -itemWidthPx * 1.2f
                            coroutineScope.launch {
                                offsetX.animateTo(
                                    targetValue = targetX,
                                    animationSpec = tween(
                                        durationMillis = 200,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialVelocity = velocity
                                )
                                if (targetX > 0f) {
                                    onSwipeRight()
                                } else {
                                    onSwipeLeft()
                                }
                                offsetX.snapTo(0f)
                            }
                        } else {
                            coroutineScope.launch {
                                offsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    ),
                                    initialVelocity = velocity
                                )
                            }
                        }
                    }
                )
                .graphicsLayer {
                    itemWidthPx = size.width
                }
        ) {
            TaskCard(item = item)
        }
    }
}

@Composable
private fun SwipeBackground(
    direction: TaskSwipeDirection,
    swipeFraction: Float,
    iconScale: Float,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (direction) {
        TaskSwipeDirection.StartToEnd -> Color(0xFF2E7D32) // Green (AI Analysis)
        TaskSwipeDirection.EndToStart -> Color(0xFFC62828) // Red (Delete)
        TaskSwipeDirection.Settled -> Color.Transparent
    }
    val icon = when (direction) {
        TaskSwipeDirection.StartToEnd -> Icons.Default.AutoAwesome
        TaskSwipeDirection.EndToStart -> Icons.Default.Delete
        TaskSwipeDirection.Settled -> null
    }
    val alignment = if (direction == TaskSwipeDirection.StartToEnd) {
        Alignment.CenterStart
    } else {
        Alignment.CenterEnd
    }

    Box(
        modifier = modifier
            .background(backgroundColor.copy(alpha = (swipeFraction * 2.2f).coerceIn(0.2f, 1f))),
        contentAlignment = alignment
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .size(28.dp)
                    .graphicsLayer(scaleX = iconScale, scaleY = iconScale)
            )
        }
    }
}

/**
 * Task card (supports Error highlight and Completed tag badges).
 */
@Composable
private fun TaskCard(
    item: ImportTask,
    modifier: Modifier = Modifier
) {
    val isError = item.status == TaskStatus.ERROR
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            val domain = remember(item.url) { DomainIconManager.extractDomain(item.url) }

            if (!domain.isNullOrBlank()) {
                DomainWatermark(
                    domain = domain,
                    pageUrl = item.url,
                    size = 80.dp,
                    alpha = 0.22f,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                val resolvedDisplayName = remember(domain, item.siteName) {
                    val displayName = domain?.let { DomainPolicyManager.getDisplayName(it) }
                    when {
                        !displayName.isNullOrBlank() -> displayName
                        item.siteName.isNotBlank() -> item.siteName
                        !domain.isNullOrBlank() -> domain
                        else -> null
                    }
                }

                if (!resolvedDisplayName.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = resolvedDisplayName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (item.rssFeedId != null) Text(
                    stringResource(R.string.rss_source, item.siteName),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = item.title.ifBlank { item.url },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = item.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (item.status == TaskStatus.COMPLETED && item.assignedTags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item.assignedTags.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "#$tag",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                if (isError && !item.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = item.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val statusDisplay = resolveTaskStatusText(item)
                    if (item.status == TaskStatus.PROCESSING_AI) {
                        PulsingDot()
                    } else {
                        StatusBadgeIcon(status = item.status)
                    }
                    Text(
                        text = statusDisplay,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = when (item.status) {
                            TaskStatus.WAITING_AI -> MaterialTheme.colorScheme.tertiary
                            TaskStatus.READY -> MaterialTheme.colorScheme.secondary
                            TaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                            TaskStatus.PROCESSING_AI -> MaterialTheme.colorScheme.onSurfaceVariant
                            TaskStatus.ERROR -> MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        }
    }
}

/**
 * Card representing an active URL import in progress.
 * Displays a rotating CircularProgressIndicator matching the Ready theme.
 */
@Composable
private fun ImportingTaskCard(
    item: ImportingTask,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            val domain = remember(item.url) { DomainIconManager.extractDomain(item.url) }

            if (!domain.isNullOrBlank()) {
                DomainWatermark(
                    domain = domain,
                    pageUrl = item.url,
                    size = 80.dp,
                    alpha = 0.22f,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                val resolvedDisplayName = remember(domain) {
                    domain?.let { DomainPolicyManager.getDisplayName(it) } ?: domain
                }

                if (!resolvedDisplayName.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = resolvedDisplayName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val displayTitle = item.title?.takeIf { it.isNotBlank() } ?: item.url
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!item.title.isNullOrBlank() && item.title != item.url) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = stringResource(R.string.import_status_importing),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

/**
 * Pulsing dot indicator for PROCESSING_AI status.
 */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

/**
 * Status icon for badges.
 */
@Composable
private fun StatusBadgeIcon(status: TaskStatus) {
    val (icon, tint) = when (status) {
        TaskStatus.PROCESSING_AI -> Icons.Default.AutoAwesome to MaterialTheme.colorScheme.primary
        TaskStatus.WAITING_AI -> Icons.Default.HourglassBottom to MaterialTheme.colorScheme.tertiary
        TaskStatus.READY -> Icons.Default.AddLink to MaterialTheme.colorScheme.secondary
        TaskStatus.COMPLETED -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        TaskStatus.ERROR -> Icons.Default.Error to MaterialTheme.colorScheme.error
    }
    if (status == TaskStatus.WAITING_AI) {
        val transition = rememberInfiniteTransition(label = "rotate_hourglass")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 180f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "hourglass_rotation"
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer(rotationZ = rotation)
        )
    } else {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * Dialog for manual URL importing.
 */
@Composable
private fun ImportUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    val isValid = remember(urlText) { ImportViewModel.isValidHttpUrl(urlText) }
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
                    text = stringResource(R.string.import_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.import_dialog_description),
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
                                text = stringResource(R.string.import_dialog_error_invalid),
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(text = stringResource(R.string.import_dialog_supporting_text))
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
                    text = stringResource(R.string.import_dialog_confirm),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/**
 * Resolves localized status text for an import task while preserving backwards compatibility.
 */
@Composable
private fun resolveTaskStatusText(item: ImportTask): String {
    val text = item.statusText

    val retryMatch = Regex("""AICore busy • Retrying \((\d+)/(\d+)\)\.\.\.""").find(text)
    if (retryMatch != null) {
        val (current, max) = retryMatch.destructured
        return stringResource(R.string.import_status_aicore_busy, current.toInt(), max.toInt())
    }

    val blockedMatch = Regex("""AICore background blocked • Retrying \((\d+)/(\d+)\)\.\.\.""").find(text)
    if (blockedMatch != null) {
        val (current, max) = blockedMatch.destructured
        return stringResource(R.string.import_status_aicore_blocked, current.toInt(), max.toInt())
    }

    return when {
        text == "Waiting for AICore slot..." -> stringResource(R.string.import_status_waiting_aicore)
        text == "Gemini Nano generating semantic tags..." || text == "Gemini Nano analyzing content..." -> stringResource(R.string.import_status_analyzing_content)
        text == "Paused • Retrying when app is active" -> stringResource(R.string.import_status_paused_retry_active)
        text == "Queued • Quota exceeded" || text == "Ready • Quota exceeded" -> stringResource(R.string.import_status_ready_quota_exceeded)
        text == "Failed" -> stringResource(R.string.import_status_failed)
        text == "Completed" -> stringResource(R.string.import_status_completed)
        text == "Queued" || text == "Ready" || text == "Ready for AI Analysis" || text == "Ready • Added without metadata" -> stringResource(R.string.import_status_ready)
        else -> text
    }
}
