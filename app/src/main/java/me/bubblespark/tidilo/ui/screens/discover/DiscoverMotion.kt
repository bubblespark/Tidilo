package me.bubblespark.tidilo.ui.screens.discover

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Reduced motion (§7): the system animator scale is 0, which "Remove animations" also sets. Read on
 * entering Discover; the user changes it in system settings, outside the app.
 */
@Composable
internal fun rememberReducedMotion(): Boolean = remember { !ValueAnimator.areAnimatorsEnabled() }

/** One visible list item for [centerItemKey]: its key and its main-axis offset and size in px. */
internal data class VisibleItem(val key: Any, val offset: Int, val size: Int)

/** The key of the visible item whose center is nearest the viewport center, or null when none is visible. */
internal fun centerItemKey(viewportStart: Int, viewportEnd: Int, items: List<VisibleItem>): Any? {
    val center = (viewportStart + viewportEnd) / 2
    return items.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - center) }?.key
}

/** Ambient colors reported by the plates of each shelf (§7.1), keyed by the shelf's list key. */
@Stable
internal class DiscoverAmbient {
    val colors = mutableStateMapOf<Any, Color>()
}

/** The ambient registry and the list key of the shelf being composed. Null outside Discover. */
internal class ShelfAmbient(val registry: DiscoverAmbient, val key: Any)

internal val LocalShelfAmbient = staticCompositionLocalOf<ShelfAmbient?> { null }

/** The first plate of a shelf with a known color sets the shelf's ambient color. */
@Composable
internal fun ReportAmbientColor(color: Color) {
    val shelf = LocalShelfAmbient.current ?: return
    if (color == Color.Unspecified || color == Color.Transparent) return
    SideEffect { if (shelf.key !in shelf.registry.colors) shelf.registry.colors[shelf.key] = color }
}

/** Re-deal flip state shared by the snapshot shelves (§7.3). Read in effects only, never in composition. */
@Stable
internal class DealFlipState {
    var reDealing by mutableStateOf(false)
    var dealtAt by mutableLongStateOf(Long.MIN_VALUE / 2)
    fun justDealt() = SystemClock.uptimeMillis() - dealtAt < DEAL_IN_WINDOW_MS
}

private const val DEAL_IN_WINDOW_MS = 600L

/** The flip state and the shelf's stagger [order]. */
internal class DealFlip(val state: DealFlipState, val order: Int)

/** Null on shelves that never flip (Just saved, deck, Fresh RSS, map) and under reduced motion. */
internal val LocalDealFlip = staticCompositionLocalOf<DealFlip?> { null }

/**
 * Flips a snapshot card face-down while a re-deal runs, then deals it in from −90°, shelves staggered
 * 30 ms apart. A card composed during or just after the re-deal starts edge-on so it deals in too.
 */
@Composable
internal fun Modifier.dealFlip(): Modifier {
    val slot = LocalDealFlip.current ?: return this
    val flip = slot.state
    val order = slot.order
    val dealIn = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val rotation = remember {
        Animatable(Snapshot.withoutReadObservation { if (flip.reDealing || flip.justDealt()) -90f else 0f })
    }
    val cameraDistance = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(flip) {
        snapshotFlow { flip.reDealing }.collectLatest { reDealing ->
            delay(order * 30L)
            if (reDealing) {
                rotation.animateTo(90f, tween(150))
            } else if (rotation.value != 0f) {
                if (rotation.value > 0f) rotation.snapTo(-90f)
                rotation.animateTo(0f, dealIn)
            }
        }
    }
    return graphicsLayer {
        rotationY = rotation.value
        this.cameraDistance = cameraDistance
    }
}

/**
 * First-open entrance (§7.6): rises 24dp and fades in, staggered 40 ms by [order]. [play] is read once,
 * when the item is first composed, so a running entrance never stops halfway.
 */
@Composable
internal fun Modifier.entrance(play: Boolean, order: Int): Modifier {
    if (!remember { play }) return this
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        delay(order * 40L)
        progress.animateTo(1f, spec)
    }
    val rise = with(LocalDensity.current) { 24.dp.toPx() }
    return graphicsLayer {
        val p = progress.value
        translationY = (1f - p) * rise
        alpha = p.coerceIn(0f, 1f)
    }
}
