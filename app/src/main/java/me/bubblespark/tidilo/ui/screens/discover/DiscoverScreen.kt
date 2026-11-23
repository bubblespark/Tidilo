package me.bubblespark.tidilo.ui.screens.discover

import android.text.format.DateFormat
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
 */
@Composable
fun DiscoverScreen(
    onOpenNavigation: () -> Unit,
    onOpenBookmark: (id: String, shelf: DiscoverShelf) -> Unit,
    onOpenEntity: (value: String) -> Unit,
    onSaveUrl: () -> Unit,
    onRetryImport: (taskId: String) -> Unit,
    onToggleLike: (id: String, shelf: DiscoverShelf) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenBookmark, onOpenEntity, onSaveUrl, onRetryImport, onToggleLike) {
        DiscoverActions(
            onOpen = onOpenBookmark,
            onImpression = viewModel::onImpression,
            onEntity = onOpenEntity,
            onSaveUrl = onSaveUrl,
            onRetryImport = onRetryImport,
            onToggleLike = { id, shelf ->
                if (shelf == DiscoverShelf.DECK) viewModel.onDeckLikeToggled(id)
                onToggleLike(id, shelf)
            },
            onNextCard = viewModel::nextCard,
            onDealAnother = viewModel::dealAnother
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

    ScreenBackdropScope { backdrop ->
        Box(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .screenBackdropSource(backdrop)
                    .testTag("discover_list"),
                contentPadding = PaddingValues(
                    top = if (hero != null) 0.dp else systemPadding.calculateTopPadding() + 8.dp,
                    bottom = HeaderDefaults.FloatingNavigationClearance + systemPadding.calculateBottomPadding() + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(ShelfSpacing)
            ) {
                if (hero != null) {
                    item(key = "discover_hero", contentType = "hero") {
                        HeroShelf(hero, actions, masthead = {
                            header(Modifier.padding(top = systemPadding.calculateTopPadding() + 8.dp, bottom = 12.dp))
                        })
                    }
                } else {
                    item(key = "discover_header", contentType = "header") { header(Modifier) }
                }
                if (!state.loading && state.libraryCount == 0 && state.justSaved.isEmpty()) {
                    item(key = "discover_empty", contentType = "empty") { EmptyWall(actions) }
                }
                for (shelf in state.presentShelves) {
                    if (shelf == DiscoverShelf.HERO) continue
                    item(key = "discover_shelf_${shelf.name}", contentType = shelf.name) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = startInset, end = endInset)
                                .testTag("discover_shelf_${shelf.name}")
                        ) {
                            ShelfHeader(shelf, showNewCaption = state.newShelfCaption == shelf)
                            ShelfContent(shelf, state, actions)
                        }
                    }
                    if (shelf == DiscoverShelf.DECK && state.showHowToSave) {
                        item(key = "discover_how_to_save", contentType = "how_to_save") { HowToSaveCard(actions) }
                    }
                }
                if (state.showHowToSave && DiscoverShelf.DECK !in state.presentShelves) {
                    item(key = "discover_how_to_save", contentType = "how_to_save") { HowToSaveCard(actions) }
                }
            }

            StatusBarScrim(
                visible = scrolled || hero == null,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

private val ShelfSpacing = 40.dp

@Composable
private fun ShelfContent(shelf: DiscoverShelf, state: DiscoverUiState, actions: DiscoverActions) {
    when (shelf) {
        DiscoverShelf.HERO -> Unit
        DiscoverShelf.ON_THIS_DAY -> OnThisDayShelf(state.onThisDay, actions)
        DiscoverShelf.JUST_SAVED -> JustSavedShelf(state.justSaved, actions)
        DiscoverShelf.DECK -> DeckShelf(state.deck, actions)
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
