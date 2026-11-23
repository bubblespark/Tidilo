package me.bubblespark.tidilo.ui.screens.discover

import android.text.format.DateFormat
import android.os.SystemClock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.ui.components.AmbientLightingEffect
import me.bubblespark.tidilo.ui.screens.library.CollectionEditorBottomSheet
import me.bubblespark.tidilo.ui.screens.library.CollectionMembershipBottomSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.discover.DiscoverShelf
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.ScreenBackdropScope
import me.bubblespark.tidilo.ui.components.StatusBarScrim
import me.bubblespark.tidilo.ui.components.screenBackdropSource
import me.bubblespark.tidilo.ui.components.screenSystemInsets

internal fun millisUntilNextLocalDay(now: ZonedDateTime): Long {
    val nextDayStart = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
    return Duration.between(now, nextDayStart).toMillis().coerceAtLeast(1_000L)
}

internal fun createDiscoverDateFormatter(pattern: String, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(pattern, locale)

/**
 * Discover home (§4): the matted Today's pick, then the shelves in fixed order. Empty shelves take no
 * space. Card taps, entity taps, saves, and likes go through the callbacks so the nav graph owns navigation.
 * Pull down re-deals the daily shelves; a long-press opens the Peek sheet (§6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onOpenNavigation: () -> Unit,
    onOpenBookmark: (id: String, shelf: DiscoverShelf) -> Unit,
    onOpenEntity: (value: String) -> Unit,
    onSaveUrl: () -> Unit,
    onRetryImport: (taskId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var peek by remember { mutableStateOf<PeekTarget?>(null) }
    var focusId by remember { mutableStateOf<String?>(null) }
    var collectionFor by remember { mutableStateOf<String?>(null) }
    var creatorFor by remember { mutableStateOf<String?>(null) }
    val actions = remember(viewModel, onOpenBookmark, onOpenEntity, onSaveUrl, onRetryImport) {
        DiscoverActions(
            onOpen = onOpenBookmark,
            onImpression = viewModel::onImpression,
            onEntity = onOpenEntity,
            onSaveUrl = onSaveUrl,
            onRetryImport = onRetryImport,
            onToggleDeckLike = viewModel::toggleDeckLike,
            onNextCard = viewModel::nextCard,
            onDealAnother = viewModel::dealAnother,
            onPeek = { item, reason ->
                peek = PeekTarget(item, reason)
                focusId = item.id
                viewModel.onPeek(item.id)
            },
            onMute = viewModel::muteTopic
        )
    }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val systemPadding = screenSystemInsets().asPaddingValues()
    val startInset = systemPadding.calculateStartPadding(layoutDirection)
    val endInset = systemPadding.calculateEndPadding(layoutDirection)
    val scrimThresholdPx = with(density) { 8.dp.roundToPx() }
    val scrolled by remember(listState, scrimThresholdPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > scrimThresholdPx
        }
    }
    val reducedMotion = rememberReducedMotion()
    val dateText = rememberDiscoverDateText()
    val greeting = when (state.greeting) {
        Greeting.PICK -> stringResource(R.string.discover_greeting_pick)
        Greeting.GROWING -> stringResource(R.string.discover_greeting_growing)
        Greeting.NONE -> ""
    }
    val hero = state.hero
    val header: @Composable (Modifier) -> Unit = { headerModifier ->
        DiscoverHeader(
            dateText = dateText,
            greeting = greeting,
            onOpenNavigation = onOpenNavigation,
            modifier = headerModifier.padding(start = startInset + 8.dp, end = endInset + 16.dp)
        )
    }

    // Ambient bleed (§7.1): the shelf nearest the viewport center lights the background. The background
    // layer crossfades and reads the color in its draw phase; reduced motion turns the bleed off.
    val ambient = remember { DiscoverAmbient() }
    val ambientColor = remember(listState, ambient) {
        derivedStateOf {
            val info = listState.layoutInfo
            val key = centerItemKey(info.viewportStartOffset, info.viewportEndOffset,
                info.visibleItemsInfo.map { VisibleItem(it.key, it.offset, it.size) })
            key?.let { ambient.colors[it] } ?: Color.Transparent
        }
    }
    if (!reducedMotion) AmbientLightingEffect(ambientColor, ownerId = "discover")

    // Re-deal flip (§7.3): snapshot cards turn face-down while the re-deal runs, then deal in.
    val dealFlip = remember { DealFlipState() }
    LaunchedEffect(state.reDealing) {
        if (dealFlip.reDealing && !state.reDealing) dealFlip.dealtAt = SystemClock.uptimeMillis()
        dealFlip.reDealing = state.reDealing
    }
    val playEntrance = state.playEntrance && !reducedMotion
    LaunchedEffect(state.playEntrance) { if (state.playEntrance) viewModel.entranceShown() }
    val parallaxCap = with(density) { 200.dp.toPx() }
    val parallax: () -> Float = remember(listState, reducedMotion, parallaxCap) {
        if (reducedMotion) {
            { 0f }
        } else {
            { if (listState.firstVisibleItemIndex == 0) minOf(listState.firstVisibleItemScrollOffset * 0.32f, parallaxCap) else parallaxCap }
        }
    }

    // One snackbar at a time; it outlives the notice, which the ViewModel clears as soon as it is shown.
    val snackbar = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val notice = state.notice
    val noticeText = notice?.let {
        when (it.kind) {
            DiscoverNotice.Kind.SNOOZED -> stringResource(R.string.discover_notice_snoozed)
            DiscoverNotice.Kind.HIDDEN -> stringResource(R.string.discover_notice_hidden)
            DiscoverNotice.Kind.MUTED -> stringResource(R.string.discover_notice_muted, it.label)
        }
    }
    val undoLabel = stringResource(R.string.discover_undo)
    LaunchedEffect(notice) {
        if (notice == null || noticeText == null) return@LaunchedEffect
        viewModel.noticeShown()
        snackbarScope.launch {
            if (snackbar.showSnackbar(noticeText, undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                viewModel.undo(notice)
            }
        }
    }

    // Focus goes back to the peeked card once every sheet it opened has closed (§10).
    val peekRequester = remember { FocusRequester() }
    val peekFocus = remember(focusId) { focusId?.let { PeekFocus(it, peekRequester) } }
    val sheetOpen = peek != null || collectionFor != null || creatorFor != null
    LaunchedEffect(sheetOpen) {
        if (!sheetOpen && focusId != null) runCatching { peekRequester.requestFocus() }
    }

    val haptic = LocalHapticFeedback.current
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState) {
        snapshotFlow { pullState.distanceFraction >= 1f }.drop(1).collect { crossed ->
            if (crossed) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    val bottomClearance = HeaderDefaults.FloatingNavigationClearance + systemPadding.calculateBottomPadding()

    CompositionLocalProvider(LocalPeekFocus provides peekFocus) {
    ScreenBackdropScope { backdrop ->
        Box(modifier = modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.reDealing,
                onRefresh = viewModel::reDeal,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.reDealing,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = systemPadding.calculateTopPadding())
                    )
                }
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .screenBackdropSource(backdrop)
                    .testTag("discover_list"),
                contentPadding = PaddingValues(
                    top = if (hero != null) 0.dp else systemPadding.calculateTopPadding() + 8.dp,
                    bottom = bottomClearance + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(ShelfSpacing)
            ) {
                if (hero != null) {
                    item(key = HERO_KEY, contentType = "hero") {
                        ShelfMotion(ambient, HERO_KEY, if (reducedMotion) null else dealFlip, 0) {
                            HeroShelf(
                                hero, actions,
                                masthead = {
                                    header(Modifier.padding(top = systemPadding.calculateTopPadding() + 8.dp, bottom = 12.dp))
                                },
                                modifier = Modifier.entrance(playEntrance, 0),
                                showNewCaption = state.newShelfCaption == DiscoverShelf.HERO,
                                startInset = startInset,
                                endInset = endInset,
                                parallax = parallax
                            )
                        }
                    }
                } else {
                    item(key = "discover_header", contentType = "header") { header(Modifier) }
                }
                if (!state.loading && state.libraryCount == 0 && state.justSaved.isEmpty()) {
                    item(key = "discover_empty", contentType = "empty") {
                        EmptyWall(actions, Modifier.padding(start = startInset, end = endInset))
                    }
                }
                for ((order, shelf) in state.presentShelves.withIndex()) {
                    if (shelf == DiscoverShelf.HERO) continue
                    val key = "discover_shelf_${shelf.name}"
                    item(key = key, contentType = shelf.name) {
                        ShelfMotion(ambient, key, dealFlip.takeIf { !reducedMotion && shelf in FLIPPING_SHELVES }, order) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .entrance(playEntrance, order)
                                    .padding(start = startInset, end = endInset)
                                    .testTag(key)
                            ) {
                                ShelfHeader(shelf, showNewCaption = state.newShelfCaption == shelf)
                                ShelfContent(shelf, state, actions, reducedMotion)
                            }
                        }
                    }
                    if (shelf == DiscoverShelf.DECK && state.showHowToSave) {
                        item(key = "discover_how_to_save", contentType = "how_to_save") {
                            HowToSaveCard(actions, Modifier.padding(start = startInset, end = endInset))
                        }
                    }
                }
                if (state.showHowToSave && DiscoverShelf.DECK !in state.presentShelves) {
                    item(key = "discover_how_to_save", contentType = "how_to_save") {
                            HowToSaveCard(actions, Modifier.padding(start = startInset, end = endInset))
                        }
                }
            }
            }

            StatusBarScrim(
                visible = scrolled || hero == null,
                modifier = Modifier.align(Alignment.TopCenter)
            )
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(start = startInset, end = endInset, bottom = bottomClearance)
            )
        }
    }
    }

    peek?.let { target ->
        DiscoverPeekSheet(
            target = target,
            liked = state.deck.isLiked(target.item),
            onToggleLike = { viewModel.togglePeekLike(target.item) },
            onSnooze = { viewModel.snooze(target.item) },
            onNeverAgain = { viewModel.neverAgain(target.item) },
            onAddToCollection = { collectionFor = target.item.id },
            onDismiss = { peek = null }
        )
    }
    collectionFor?.let { id ->
        CollectionMembershipBottomSheet(
            bookmarkId = id,
            onDismiss = { collectionFor = null },
            onNewCollection = {
                collectionFor = null
                creatorFor = id
            }
        )
    }
    creatorFor?.let { id ->
        CollectionEditorBottomSheet(collectionId = null, assignToBookmarkId = id, onDismiss = { creatorFor = null })
    }
}

private val ShelfSpacing = 40.dp
private const val HERO_KEY = "discover_hero"

/** Shelves whose picks come from the daily snapshot, so a re-deal flips them (§7.3). */
private val FLIPPING_SHELVES = setOf(
    DiscoverShelf.HERO, DiscoverShelf.ON_THIS_DAY, DiscoverShelf.SLEEPING_GEMS, DiscoverShelf.RISING, DiscoverShelf.CONNECTIONS
)

/** Gives a shelf's cards its ambient slot and, on snapshot shelves, the re-deal flip. */
@Composable
private fun ShelfMotion(ambient: DiscoverAmbient, key: Any, flip: DealFlipState?, order: Int, content: @Composable () -> Unit) {
    val shelfAmbient = remember(ambient, key) { ShelfAmbient(ambient, key) }
    val dealFlip = remember(flip, order) { flip?.let { DealFlip(it, order) } }
    CompositionLocalProvider(LocalShelfAmbient provides shelfAmbient, LocalDealFlip provides dealFlip, content = content)
}

@Composable
private fun ShelfContent(shelf: DiscoverShelf, state: DiscoverUiState, actions: DiscoverActions, reducedMotion: Boolean) {
    when (shelf) {
        DiscoverShelf.HERO -> Unit
        DiscoverShelf.ON_THIS_DAY -> OnThisDayShelf(state.onThisDay, actions)
        DiscoverShelf.JUST_SAVED -> JustSavedShelf(state.justSaved, actions)
        DiscoverShelf.DECK -> DeckShelf(state.deck, actions, reducedMotion)
        DiscoverShelf.SLEEPING_GEMS -> SleepingGemsShelf(state.sleepingGems, actions)
        DiscoverShelf.RISING -> RisingShelf(state.rising, actions)
        DiscoverShelf.FRESH_RSS -> FreshRssShelf(state.freshRss, actions)
        DiscoverShelf.CONNECTIONS -> state.connection?.let { ConnectionsShelf(it, actions) }
        DiscoverShelf.MAP -> MapShelf(state.map, actions)
    }
}

/** "Sunday, November 23" in the user's locale; refreshed at local midnight and on resume. */
@Composable
private fun rememberDiscoverDateText(): String {
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    }
    val dateFormatter = remember(locale) {
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
        createDiscoverDateFormatter(pattern, locale)
    }
    var currentDate by remember { mutableStateOf(LocalDate.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentDate = LocalDate.now()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val now = ZonedDateTime.now()
            currentDate = now.toLocalDate()
            delay(millisUntilNextLocalDay(now))
        }
    }
    return currentDate.format(dateFormatter)
}

@Composable
fun DiscoverHeader(
    dateText: String,
    greeting: String,
    onOpenNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("discover_header")
            .semantics { heading() },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(
            onClick = onOpenNavigation,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = stringResource(R.string.navigation_open),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = dateText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (greeting.isNotEmpty()) {
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
