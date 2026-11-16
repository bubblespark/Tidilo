package me.bubblespark.tidilo.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.ResolvedTidiloSurface
import me.bubblespark.tidilo.ui.components.screenSystemInsets
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.ThemeSurfaceState
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces

private val shortcutOrder = LibraryShortcut.entries

private class MenuEnvironment {
    var signature: Any? = null
    var width = 0
    var height = 0
    var fits = false
}

private fun LibraryShortcut.filter(): LibraryFilterMode? = when (this) {
    LibraryShortcut.SEARCH -> null
    LibraryShortcut.COLLECTION -> LibraryFilterMode.COLLECTION
    LibraryShortcut.RSS -> LibraryFilterMode.RSS
    LibraryShortcut.DOMAIN -> LibraryFilterMode.DOMAIN
    LibraryShortcut.NEWEST_FIRST -> LibraryFilterMode.NEWEST_FIRST
}

private fun LibraryShortcut.label(): Int = when (this) {
    LibraryShortcut.SEARCH -> R.string.nav_search
    LibraryShortcut.COLLECTION -> R.string.collection_title
    LibraryShortcut.RSS -> R.string.rss_title
    LibraryShortcut.DOMAIN -> R.string.bottom_bar_domain
    LibraryShortcut.NEWEST_FIRST -> R.string.bottom_bar_newest_first
}

private class TriggerPressFeedback(private val source: MutableInteractionSource) {
    private var press: PressInteraction.Press? = null
    fun start(position: androidx.compose.ui.geometry.Offset) {
        cancel()
        PressInteraction.Press(position).also { press = it; source.tryEmit(it) }
    }
    fun release() { press?.let { source.tryEmit(PressInteraction.Release(it)) }; press = null }
    fun cancel() { press?.let { source.tryEmit(PressInteraction.Cancel(it)) }; press = null }
}

/** A square layout corner belongs to outside dismissal, never to a shortcut. */
private suspend fun AwaitPointerEventScope.consumeOutsideGesture() {
    do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

/** One persistent trigger owns physical input; every item also has ordinary button semantics. */
@Composable
fun LibraryRadialMenu(
    state: LibraryRadialMenuState,
    selectedFilter: LibraryFilterMode,
    onSelectFilter: (LibraryFilterMode) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val geometry = remember(density.density) {
        LibraryRadialMenuGeometry(density.density, with(density) { HeaderDefaults.LibraryActionSize.roundToPx() })
    }
    val insets = screenSystemInsets()
    val left = insets.getLeft(density, LayoutDirection.Ltr)
    val top = insets.getTop(density)
    val right = insets.getRight(density, LayoutDirection.Ltr)
    val bottom = insets.getBottom(density)
    val margin = with(density) { 24.dp.roundToPx() }
    val window = LocalWindowInfo.current
    val configuration = LocalConfiguration.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptic = LocalHapticFeedback.current
    val focuses = remember { List(6) { FocusRequester() } }
    val source = remember { MutableInteractionSource() }
    val pressFeedback = remember(source) { TriggerPressFeedback(source) }
    val environment = remember { MenuEnvironment() }
    // Updated metadata is read again at input dispatch, before asynchronous observers can run.
    val metadata by rememberUpdatedState(listOf(configuration.orientation, density.density, density.fontScale, left, top, right, bottom))
    fun signature(): Any = listOf(window.containerSize, window.isWindowFocused, metadata,
        environment.width, environment.height, environment.fits,
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    val currentSignature by rememberUpdatedState(::signature)
    val dispatch by rememberUpdatedState<(LibraryRadialMenuEvent) -> Unit>({ event ->
        if (!environment.fits || environment.signature != currentSignature() || !window.isWindowFocused ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pressFeedback.cancel()
            state.cancel()
            return@rememberUpdatedState
        }
        if (event.opened) {
            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            focuses[0].requestFocus()
        } else if (event.closed) {
            focuses[0].requestFocus()
        }
        if (event.entered != null) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        event.action?.let { action ->
            val mode = action.filter()
            if (mode == null) onSearch() else { onSelectFilter(mode); focuses[0].requestFocus() }
        }
    })
    val cancel by rememberUpdatedState<() -> Unit>({
        state.cancel()
    })
    DisposableEffect(lifecycle, state, pressFeedback) {
        state.onCancellation = {
            pressFeedback.cancel()
            if (window.isWindowFocused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) focuses[0].requestFocus()
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cancel() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.onCancellation = null
            pressFeedback.cancel()
            state.cancel()
        }
    }
    LaunchedEffect(window, state) {
        var previous = currentSignature()
        snapshotFlow { currentSignature() }.collect { next ->
            if (next != previous && environment.signature != next) cancel()
            previous = next
        }
    }
    val open = state.isOpen
    val alpha = animateFloatAsState(if (open) 1f else 0f, tween(140), label = "shortcutOpacity")
    val dim = animateFloatAsState(if (open) .65f else 0f, tween(180), label = "shortcutDim")
    val glyphRotation = animateFloatAsState(if (open) 90f else 0f, tween(200), label = "shortcutRotation")
    LaunchedEffect(state, pressFeedback) {
        snapshotFlow { state.isOpen }.collect { if (!it) pressFeedback.cancel() }
    }
    val pane = stringResource(R.string.library_shortcuts_title)
    val triggerLabel = stringResource(if (open) R.string.common_close else R.string.library_shortcuts_open)
    val slop = LocalViewConfiguration.current.touchSlop
    val triggerInput = Modifier.pointerInput(state, geometry, source) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val center = size.width / 2f
            val origin = down.position
            down.consume()
            if (!geometry.inTrigger(origin.x - center, origin.y - center)) {
                try { consumeOutsideGesture() } finally { cancel() }
                return@awaitEachGesture
            }
            val stamp = currentSignature()
            environment.signature = stamp
            state.beginPress(down.id.value, stamp)
            pressFeedback.start(origin)
            var crossedSlop = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    // Compose represents Android ACTION_CANCEL as consumed synthetic ups.
                    if (event.changes.any { it.isConsumed }) cancel()
                    event.changes.forEach { it.consume() }
                    if (event.changes.any { it.id != down.id && it.pressed } || change == null) cancel()
                    if (change == null) break
                    if (currentSignature() != stamp) cancel()
                    val relative = change.position - androidx.compose.ui.geometry.Offset(center, center)
                    if (!crossedSlop && (change.position - origin).getDistance() > slop) {
                        crossedSlop = true
                        pressFeedback.cancel()
                    }
                    if (change.pressed) {
                        dispatch(state.movePress(down.id.value, crossedSlop,
                            geometry.targetAt(relative.x, relative.y), currentSignature()))
                    } else {
                        val inside = geometry.inTrigger(relative.x, relative.y)
                        if (!crossedSlop && inside && stamp == currentSignature()) pressFeedback.release() else pressFeedback.cancel()
                        dispatch(state.endPress(down.id.value, geometry.targetAt(relative.x, relative.y), inside, currentSignature()))
                        break
                    }
                }
            } finally {
                pressFeedback.cancel()
                state.cancelPendingPress(down.id.value)
            }
        }
    }
    val retainedTarget = remember { arrayOfNulls<LibraryShortcut>(1) }
    Layout(modifier = modifier.fillMaxSize().focusProperties {
        onExit = { if (state.isOpen) cancelFocusChange() }
    }.focusGroup().then(if (open) Modifier.semantics {
        paneTitle = pane
        isTraversalGroup = true
    } else Modifier).onPreviewKeyEvent { event ->
        if (!state.isOpen || event.type != KeyEventType.KeyDown) false
        else if (event.key == Key.Escape) { cancel(); true } else false
    }, content = {
        Box(Modifier.fillMaxSize().testTag("library_radial_scrim")
            .drawBehind { drawRect(Color.Black.copy(alpha = dim.value)) }
            .then(if (open) Modifier.pointerInput(state) {
                detectTapGestures { cancel() }
            } else Modifier))
        RadialButton(null, triggerLabel, true, false, false, source,
            Modifier.zIndex(1f).then(triggerInput)
                .focusRequester(focuses[0]).focusProperties {
                    canFocus = true
                    if (open) { next = focuses[1]; previous = focuses[5] }
                }.onPreviewKeyEvent { event ->
                    if (open && event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                        focuses[if (event.isShiftPressed) 5 else 1].requestFocus(); true
                    } else false
                }, onClick = {
                    environment.signature = currentSignature()
                    dispatch(state.activateTrigger())
                }) {
            Crossfade(open, modifier = Modifier.graphicsLayer { rotationZ = glyphRotation.value }, animationSpec = tween(160), label = "shortcutGlyph") { expanded ->
                if (expanded) Icon(Icons.Default.Close, null, Modifier.size(28.dp))
                else Icon(painterResource(R.drawable.ic_radial_menu), null, Modifier.size(28.dp))
            }
        }
        shortcutOrder.forEachIndexed { index, action ->
            key(action) {
                val interaction = remember { MutableInteractionSource() }
                val name = stringResource(action.label())
                val selected = action.filter() == selectedFilter
                RadialButton(action, name, open, selected, state.feedbackTarget == action, interaction,
                    Modifier.zIndex(1f).graphicsLayer { this.alpha = alpha.value }
                        .focusRequester(focuses[index + 1]).focusProperties {
                            canFocus = open
                            next = focuses[if (index == 4) 0 else index + 2]
                            previous = focuses[index]
                        }.onPreviewKeyEvent { event ->
                            if (open && event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                                focuses[if (event.isShiftPressed) index else if (index == 4) 0 else index + 2].requestFocus(); true
                            } else false
                        }, onClick = { dispatch(state.activateItem(action)) },
                    onFeedback = { dispatch(state.feedback(it)) }, onPointerCancellation = state::cancel) {
                    Icon(when (action) {
                        LibraryShortcut.SEARCH -> Icons.Default.Search
                        LibraryShortcut.COLLECTION -> Icons.Default.CollectionsBookmark
                        LibraryShortcut.RSS -> Icons.Default.RssFeed
                        LibraryShortcut.DOMAIN -> Icons.Default.Language
                        LibraryShortcut.NEWEST_FIRST -> Icons.Default.Schedule
                    }, null, Modifier.size(28.dp))
                }
            }
        }
        val target = state.feedbackTarget
        SideEffect { if (target != null) retainedTarget[0] = target }
        val popupAlpha = animateFloatAsState(if (open && target != null) 1f else 0f, tween(100), label = "shortcutName")
        val popupMovement = animateFloatAsState(if (open && target != null) 0f else 1f, tween(140), label = "shortcutNameMovement")
        Text((target ?: retainedTarget[0])?.let { stringResource(it.label()) }.orEmpty(),
            Modifier.zIndex(2f).testTag("library_radial_name").clearAndSetSemantics {}
                .graphicsLayer { this.alpha = popupAlpha.value; translationY = popupMovement.value * -6.dp.toPx() }
                .background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.small).padding(12.dp, 8.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge)
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val control = Constraints.fixed(geometry.diameter, geometry.diameter)
        val scrim = measurables[0].measure(Constraints.fixed(width, height))
        val buttons = measurables.subList(1, 7).map { it.measure(control) }
        val popup = measurables[7].measure(Constraints(maxWidth = (width - left - right - margin * 2).coerceAtLeast(1)))
        val anchor = geometry.anchor(width, height, right, bottom, margin)
        // Plain measured metadata is shared with input; layout never writes snapshot state.
        environment.width = width
        environment.height = height
        environment.fits = geometry.fits(anchor, left, top, width - right, height - bottom)
        val target = state.feedbackTarget ?: retainedTarget[0] ?: LibraryShortcut.SEARCH
        val label = geometry.popup(anchor, target, popup.width, popup.height, left + margin, top + margin,
            width - right - margin, height - bottom - margin, (8 * density.density).toInt())
        layout(width, height) {
            scrim.place(0, 0)
            buttons[0].place(anchor.x - geometry.diameter / 2, anchor.y - geometry.diameter / 2)
            shortcutOrder.forEachIndexed { index, action ->
                val point = geometry.centers.getValue(action)
                buttons[index + 1].place(anchor.x + point.x - geometry.diameter / 2, anchor.y + point.y - geometry.diameter / 2)
            }
            popup.place(label.x, label.y)
        }
    }
}

@Composable
private fun RadialButton(
    action: LibraryShortcut?, label: String, enabled: Boolean, selected: Boolean, highlighted: Boolean,
    source: MutableInteractionSource, modifier: Modifier, onClick: () -> Unit,
    onFeedback: (LibraryShortcut?) -> Unit = {}, onPointerCancellation: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val style = currentThemeSurfaces().resolve(
        if (action == null || action == LibraryShortcut.SEARCH) SurfaceRole.PRIMARY_ACTION else SurfaceRole.FILTER_ACTION,
        state = ThemeSurfaceState(selected = selected)).copy(shape = CircleShape)
    val guard = if (action != null && enabled) Modifier.pointerInput(action) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            fun inside(position: androidx.compose.ui.geometry.Offset) =
                (position - androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)).getDistance() <= size.width / 2f
            var valid = inside(down.position)
            var completed = false
            if (!valid) {
                down.consume()
                try { consumeOutsideGesture() } finally { onPointerCancellation() }
                return@awaitEachGesture
            }
            onFeedback(action)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.isConsumed }) {
                        valid = false
                        onPointerCancellation()
                    }
                    if (event.changes.any { it.id != down.id && it.pressed }) {
                        valid = false
                        onPointerCancellation()
                    }
                    if (event.changes.any { !inside(it.position) }) valid = false
                    if (!valid) event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                completed = true
            } finally {
                onFeedback(null)
                if (!completed) onPointerCancellation()
            }
        }
    } else Modifier
    ResolvedTidiloSurface(style, modifier.size(HeaderDefaults.LibraryActionSize), blurBackground = enabled) {
        Box(Modifier.fillMaxSize().testTag(if (action == null) "library_radial_trigger" else "library_radial_${action.name.lowercase(java.util.Locale.ROOT)}").clip(CircleShape).then(guard)
            .then(if (enabled) Modifier.semantics {
                contentDescription = label
                traversalIndex = if (action == null) 0f else action.ordinal + 1f
                if (action != null && action != LibraryShortcut.SEARCH) this.selected = selected
            }.clickable(interactionSource = source, indication = ripple(), role = Role.Button, onClick = onClick)
            else Modifier.clearAndSetSemantics {})
            .drawBehind { if (highlighted) drawCircle(style.contentColor.copy(alpha = .16f)) },
            contentAlignment = Alignment.Center) {
            content()
            if (selected && action != LibraryShortcut.SEARCH && action != null) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(4.dp).background(style.contentColor, CircleShape))
            }
        }
    }
}
