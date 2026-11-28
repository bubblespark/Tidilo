package me.bubblespark.tidilo.ui.screens.discover

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.discover.DiscoverPick
import me.bubblespark.tidilo.data.discover.DiscoverReason
import me.bubblespark.tidilo.data.discover.DiscoverShelf
import me.bubblespark.tidilo.data.discover.MapBubble
import me.bubblespark.tidilo.data.discover.RisingGroup
import me.bubblespark.tidilo.ui.components.LocalCacheVisualSource
import me.bubblespark.tidilo.ui.screens.library.rememberDetailHeroPalette

internal val ShelfMargin = 16.dp
private val HeroBottomShape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
private val FeatureShape = RoundedCornerShape(20.dp)
private const val PLATE_CARD = "plate_card"

/** Shelf title (`titleLarge`, accessibility heading) with the optional first-appearance caption (§4.6). */
@Composable
internal fun ShelfHeader(shelf: DiscoverShelf, showNewCaption: Boolean, modifier: Modifier = Modifier) {
    val title = stringResource(shelfTitleRes(shelf))
    Column(modifier.padding(horizontal = ShelfMargin).padding(bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() })
        if (showNewCaption) {
            Text(stringResource(R.string.discover_new_shelf, title), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/**
 * Today's pick as a matted cover (§4.2a): [masthead] on the top mat, the full-bleed plate dissolving into
 * mats made from its own edge colors, then the caption on the bottom mat. Nothing is written on the art.
 */
@Composable
internal fun HeroShelf(
    pick: DiscoverPick,
    actions: DiscoverActions,
    masthead: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    showNewCaption: Boolean = false,
    startInset: Dp = 0.dp,
    endInset: Dp = 0.dp,
    parallax: () -> Float = { 0f }
) {
    val item = pick.item
    val source = LocalCacheVisualSource.current
    val domain = remember(item.domain, item.url, source) { item.domain ?: source.extractDomain(item.url) }
    // One observer pair feeds both the mats' palette and the plate.
    val visuals = rememberPlateVisuals(item)
    val ogState = visuals.og
    val palette by rememberDetailHeroPalette(item.id, domain, item.ogImageFilename, ogState.bitmap, ogState.shouldShowFallback, visuals.domain)
    val fallback = MaterialTheme.colorScheme.surfaceContainer
    val topMat = palette.topEdgeColor.takeUnless { it == Color.Transparent } ?: fallback
    val bottomMat = palette.bottomEdgeColor.takeUnless { it == Color.Transparent } ?: fallback
    val reasonLabel = reasonText(pick.reason)
    Column(modifier.fillMaxWidth().testTag("discover_shelf_${DiscoverShelf.HERO.name}")) {
        Box(Modifier.fillMaxWidth().background(topMat)) { masthead() }
        Column(Modifier.fillMaxWidth().clip(HeroBottomShape).background(bottomMat).discoverCard(item, DiscoverShelf.HERO, reasonLabel, actions)) {
            // Parallax (§7.4): the plate trails the scroll and slides under the caption mat below it.
            Box(Modifier.fillMaxWidth().graphicsLayer { translationY = parallax() }) {
                DiscoverPlateContent(item, ogState, visuals.domain, RectangleShape,
                    Modifier.fillMaxWidth().discoverPlate(item, DiscoverShelf.HERO, actions), matColor = topMat)
                // The plate's edges dissolve into the mats (13% top, 6% bottom).
                Box(Modifier.fillMaxWidth().height(PlateSize.XL.height * 0.13f).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(topMat, Color.Transparent))))
                Box(Modifier.fillMaxWidth().height(PlateSize.XL.height * 0.06f).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, bottomMat))))
            }
            Column(Modifier.zIndex(1f).fillMaxWidth().background(bottomMat)
                .padding(start = 20.dp + startInset, end = 20.dp + endInset, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showNewCaption) {
                    Text(stringResource(R.string.discover_new_shelf, stringResource(R.string.discover_shelf_hero)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                reasonLabel?.let { ReasonChip(it) }
                Byline(item)
                Text(item.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (item.description.isNotBlank()) {
                    Text(item.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Uncontained, fixed-width carousel that never masks the 1.91:1 plates (§4.2a). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun <T> PlateCarousel(
    items: List<T>,
    key: (T) -> String,
    contentType: (T) -> Any? = { PLATE_CARD },
    content: @Composable (T) -> Unit
) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(horizontal = ShelfMargin),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items, key = key, contentType = contentType) { content(it) }
    }
}

@Composable
internal fun OnThisDayShelf(picks: List<DiscoverPick>, actions: DiscoverActions) {
    PlateCarousel(picks, key = { it.item.id }) { pick ->
        val period = (pick.reason as? DiscoverReason.OnThisDay)?.period
        PlateCard(pick.item, pick.reason, DiscoverShelf.ON_THIS_DAY, PlateSize.M.width, actions,
            overline = period?.let { stringResource(periodLabelRes(it)) })
    }
}

@Composable
internal fun JustSavedShelf(entries: List<JustSavedEntry>, actions: DiscoverActions) {
    PlateCarousel(entries, key = { it.key }, contentType = { it::class }) { entry ->
        when (entry) {
            is JustSavedEntry.InProgress -> SavingCard(entry.task, PlateSize.S.width)
            is JustSavedEntry.Failed -> FailedSaveCard(entry.task, PlateSize.S.width, actions)
            is JustSavedEntry.Saved -> PlateCard(entry.item, null, DiscoverShelf.JUST_SAVED, PlateSize.S.width, actions)
        }
    }
}

/**
 * Shuffle deck (§4.2a, §7.5): the top postcard over two cards tilted ±3°. A horizontal fling or a drag past
 * 40% of the width flicks it to the next card; anything else springs back. Next and Like buttons and
 * accessibility actions mean dragging is never the only way (WCAG 2.5.7). Double-tap likes.
 */
@Composable
internal fun DeckShelf(deck: DeckState, actions: DiscoverActions, reducedMotion: Boolean) {
    val current = deck.current
    Column(Modifier.fillMaxWidth().padding(horizontal = ShelfMargin).testTag("discover_deck"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (current == null) {
            if (deck.exhausted) {
                FilledTonalButton(onClick = actions.onDealAnother, modifier = Modifier.testTag("discover_deck_deal")) {
                    Text(stringResource(R.string.discover_deal_another))
                }
            }
            return@Column
        }
        val position = stringResource(R.string.discover_deck_position, deck.index + 1, deck.cards.size)
        val liked = deck.isLiked(current)
        val nextLabel = stringResource(R.string.discover_deck_next)
        val likeLabel = stringResource(if (liked) R.string.discover_deck_unlike else R.string.discover_deck_like)
        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val springBack = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
        // A new top card starts centered; the flicked one leaves with its own Animatable.
        val drag = remember(current.id) { Animatable(0f) }
        val burst = remember(current.id) { Animatable(0f) }
        val flickVelocity = with(LocalDensity.current) { FlickVelocity.toPx() }
        val currentReducedMotion by rememberUpdatedState(reducedMotion)
        val cardId = current.id
        fun like() {
            if (!liked && !reducedMotion) scope.launch { burst.snapTo(1f); burst.animateTo(0f, tween(500)) }
            actions.onToggleDeckLike(current)
        }
        Box(Modifier.widthIn(max = PostcardWidth).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (deck.index + 2 < deck.cards.size) PostcardBack(Modifier.matchParentSize().rotate(3f))
            if (deck.index + 1 < deck.cards.size) PostcardBack(Modifier.matchParentSize().rotate(-3f))
            // Pointer input sits before the layer so positions stay in the unmoved parent space;
            // otherwise the card follows the finger and the tracker measures no velocity.
            Postcard(current, actions, onDoubleClick = ::like, modifier = Modifier
                .pointerInput(cardId) {
                    val tracker = VelocityTracker()
                    fun release() {
                        val velocity = tracker.calculateVelocity().x
                        val flick = kotlin.math.abs(velocity) > flickVelocity || kotlin.math.abs(drag.value) > size.width * 0.4f
                        scope.launch {
                            if (!flick) {
                                drag.animateTo(0f, springBack)
                                return@launch
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            val direction = if (kotlin.math.abs(velocity) > flickVelocity) kotlin.math.sign(velocity) else kotlin.math.sign(drag.value)
                            // Reduced motion: the flick still works, without inertia.
                            if (!currentReducedMotion) drag.animateTo(direction * size.width * 1.4f, tween(160))
                            actions.onNextCard(cardId)
                        }
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = ::release,
                        onDragCancel = { scope.launch { drag.animateTo(0f, springBack) } },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            scope.launch { drag.snapTo(drag.value + amount) }
                        }
                    )
                }
                .graphicsLayer {
                    translationX = drag.value
                    rotationZ = drag.value * DragRotationPerPx
                }
                .semantics {
                    stateDescription = position
                    customActions = listOf(
                        CustomAccessibilityAction(nextLabel) { actions.onNextCard(cardId); true },
                        CustomAccessibilityAction(likeLabel) { actions.onToggleDeckLike(current); true }
                    )
                })
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp).graphicsLayer {
                    alpha = burst.value
                    val scale = 1.4f - 0.4f * burst.value
                    scaleX = scale
                    scaleY = scale
                })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { actions.onNextCard(cardId) }, modifier = Modifier.testTag("discover_deck_next")) {
                Text(nextLabel)
            }
            IconToggleButton(
                checked = liked,
                onCheckedChange = { actions.onToggleDeckLike(current) },
                modifier = Modifier.size(48.dp).testTag("discover_deck_like")
            ) {
                Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = likeLabel,
                    tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val PostcardWidth = 340.dp
private val FlickVelocity = 1_000.dp // per second
private const val DragRotationPerPx = 0.06f
private val postmarkFormat = DateTimeFormatter.ofPattern("yyyy.MM")

@Composable
private fun postcardColor() =
    if (MaterialTheme.colorScheme.surface.luminanceIsLight()) MaterialTheme.colorScheme.surfaceContainerLowest
    else MaterialTheme.colorScheme.surfaceContainerHigh

private fun Color.luminanceIsLight() = (0.2126f * red + 0.7152f * green + 0.0722f * blue) > 0.5f

@Composable
private fun PostcardBack(modifier: Modifier) {
    Surface(color = postcardColor(), shape = MaterialTheme.shapes.large, shadowElevation = 2.dp,
        modifier = modifier.clearAndSetSemantics {}) {}
}

@Composable
private fun Postcard(
    item: me.bubblespark.tidilo.ui.screens.library.BookmarkItem,
    actions: DiscoverActions,
    onDoubleClick: () -> Unit,
    modifier: Modifier
) {
    val postmark = remember(item.createdAt) {
        Instant.ofEpochMilli(item.createdAt).atZone(ZoneId.systemDefault()).format(postmarkFormat)
    }
    Surface(color = postcardColor(), shape = MaterialTheme.shapes.large, shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth().discoverCard(item, DiscoverShelf.DECK, null, actions, onDoubleClick)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DiscoverPlate(item, MaterialTheme.shapes.medium, Modifier.fillMaxWidth().discoverPlate(item, DiscoverShelf.DECK, actions))
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp))
            // The postmark sits on the paper, beside the byline, never on the art.
            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Byline(item, Modifier.weight(1f))
                Text(postmark, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

/** Editorial asymmetry (§4.2a): one feature (reason overline, L plate, title, pull quote) and up to four rows. */
@Composable
internal fun SleepingGemsShelf(picks: List<DiscoverPick>, actions: DiscoverActions) {
    val feature = picks.firstOrNull() ?: return
    Column(Modifier.padding(horizontal = ShelfMargin), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val reason = reasonText(feature.reason)
        Column(Modifier.fillMaxWidth().discoverCard(feature.item, DiscoverShelf.SLEEPING_GEMS, reason, actions),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            reason?.let { ReasonOverline(it) }
            DiscoverPlate(feature.item, FeatureShape, Modifier.fillMaxWidth().discoverPlate(feature.item, DiscoverShelf.SLEEPING_GEMS, actions))
            Text(feature.item.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (feature.item.description.isNotBlank()) {
                Text(feature.item.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        picks.drop(1).take(4).forEach { PlateRow(it.item, it.reason, DiscoverShelf.SLEEPING_GEMS, actions) }
    }
}

/** A tonal topic card per rising entity, then its carousel (§4.2a). */
@Composable
internal fun RisingShelf(groups: List<RisingGroup>, actions: DiscoverActions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        groups.forEach { group ->
            val count = (group.picks.firstOrNull()?.reason as? DiscoverReason.Rising)?.count
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.padding(horizontal = ShelfMargin).fillMaxWidth().heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .clickable(role = Role.Button) { actions.onEntity(group.entity) }
                    .testTag("discover_rising_${group.skeletonKey}")
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(group.entity, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    count?.let { Text(resolve(reasonTextSpec(DiscoverReason.Rising(it))), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer) }
                }
            }
            PlateCarousel(group.picks, key = { "${group.skeletonKey}_${it.item.id}" }) { pick ->
                PlateCard(pick.item, pick.reason, DiscoverShelf.RISING, PlateSize.S.width, actions)
            }
        }
    }
}

@Composable
internal fun FreshRssShelf(picks: List<DiscoverPick>, actions: DiscoverActions) {
    Column(Modifier.padding(horizontal = ShelfMargin)) {
        picks.forEach { PlateRow(it.item, it.reason, DiscoverShelf.FRESH_RSS, actions, reasonAsChip = true) }
    }
}

/** "Family tree" (§4.2a): the shared entity pill, two dashed connectors, and the pair two-up. */
@Composable
internal fun ConnectionsShelf(pair: ConnectionPair, actions: DiscoverActions) {
    val connector = MaterialTheme.colorScheme.outline
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = ShelfMargin)) {
        val cardWidth: Dp = minOf(PlateSize.S_PAIR.width, (maxWidth - 8.dp) / 2)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = CircleShape,
                modifier = Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button) { actions.onEntity(pair.entity) }
            ) {
                Text(pair.entity, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
            }
            Spacer(Modifier.fillMaxWidth().height(28.dp).drawWithCache {
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                val start = Offset(size.width / 2, 0f)
                val left = Offset(cardWidth.toPx() / 2, size.height)
                val right = Offset(size.width - cardWidth.toPx() / 2, size.height)
                onDrawBehind {
                    drawLine(connector, start, left, strokeWidth = 2f, pathEffect = dash)
                    drawLine(connector, start, right, strokeWidth = 2f, pathEffect = dash)
                }
            })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PlateCard(pair.first.item, pair.first.reason, DiscoverShelf.CONNECTIONS, cardWidth, actions)
                PlateCard(pair.second.item, pair.second.reason, DiscoverShelf.CONNECTIONS, cardWidth, actions)
            }
        }
    }
}

/** "Your map" (§4.2a): entity bubbles sized by affinity; a tap searches the entity, a long-press mutes it (§6). */
@Composable
internal fun MapShelf(bubbles: List<MapBubble>, actions: DiscoverActions) {
    val haptic = LocalHapticFeedback.current
    val muteLabel = stringResource(R.string.discover_mute_topic)
    FlowRow(
        modifier = Modifier.padding(horizontal = ShelfMargin).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        bubbles.forEach { bubble ->
            val weight = bubble.weight.coerceIn(0f, 1f)
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f + 0.55f * weight),
                shape = CircleShape,
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(CircleShape)
                    .combinedClickable(
                        role = Role.Button,
                        onLongClickLabel = muteLabel,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.onMute(bubble)
                        },
                        onClick = { actions.onEntity(bubble.entity) }
                    )
                    .testTag("discover_map_${bubble.skeletonKey}")
            ) {
                Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).padding(horizontal = (12 + 8 * weight).dp), contentAlignment = Alignment.Center) {
                    Text(bubble.entity, color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = (13 + 7 * weight).sp, fontWeight = if (weight > 0.6f) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Cold-start helper (§4.6, 1–49 items). */
@Composable
internal fun HowToSaveCard(actions: DiscoverActions, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.padding(horizontal = ShelfMargin).fillMaxWidth().testTag("discover_how_to_save")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.discover_how_to_save_title), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() })
            }
            Text(stringResource(R.string.discover_how_to_save_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = actions.onSaveUrl) { Text(stringResource(R.string.discover_save_url)) }
        }
    }
}

/** 0 items (§4.6): an empty wall under a spotlight with the explanation and Save URL. */
@Composable
internal fun EmptyWall(actions: DiscoverActions, modifier: Modifier = Modifier) {
    val spot = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    Box(modifier.fillMaxWidth().heightIn(min = 420.dp).testTag("discover_empty")
        .drawWithCache {
            // A soft spotlight that fades out well inside the wall, so no edge shows.
            val radius = size.minDimension * 0.48f
            val middle = Offset(size.width / 2, size.height / 2)
            val brush = Brush.radialGradient(listOf(spot, Color.Transparent), center = middle, radius = radius)
            onDrawBehind { drawCircle(brush, radius = radius, center = middle) }
        }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp).width(320.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.discover_empty_title), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.discover_empty_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = actions.onSaveUrl) { Text(stringResource(R.string.discover_save_url)) }
        }
    }
}
