package me.bubblespark.tidilo.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.NavBackStackEntry
import me.bubblespark.tidilo.ui.screens.discover.rememberReducedMotion

/** The `SharedTransitionLayout` around `NavHost`; null with the Discover preview off or under reduced motion. */
internal val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The animated scope of the current `NavHost` destination. */
internal val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Shared-element key of a plate and the Detail hero it opens (§7.2). [surface] is the code Detail is opened
 * with, so the same bookmark on two Discover shelves never shares a key.
 */
internal data class PlateKey(val id: String, val surface: Int)

/** Card ↔ Detail hero morph for one destination. Resolve once per screen with [rememberPlateTransition]. */
@androidx.compose.runtime.Stable
internal class PlateTransition(
    private val scope: SharedTransitionScope,
    private val animatedScope: AnimatedVisibilityScope
) {
    @Composable
    fun Modifier.sharedPlate(key: PlateKey): Modifier = with(scope) {
        val motion = MaterialTheme.motionScheme
        sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedScope,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.defaultEffectsSpec()),
            boundsTransform = { _, _ -> motion.defaultSpatialSpec() }
        )
    }
}

/** Null when no shared scope is provided (flag off, reduced motion, or a host without `NavHost`). */
@Composable
internal fun rememberPlateTransition(): PlateTransition? {
    val scope = LocalSharedTransitionScope.current ?: return null
    val animatedScope = LocalNavAnimatedScope.current ?: return null
    return remember(scope, animatedScope) { PlateTransition(scope, animatedScope) }
}

/** Applies [PlateTransition.sharedPlate] when [transition] exists. */
@Composable
internal fun Modifier.sharedPlate(transition: PlateTransition?, key: PlateKey): Modifier =
    if (transition == null) this else with(transition) { this@sharedPlate.sharedPlate(key) }

/** Discover and Library show plates; Detail opened from them receives the shared hero. */
internal fun isPlateRoute(route: String?): Boolean = route == Screen.Discover.route || route == Screen.Library.route

/** A plate screen opening Detail. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.isPlateOpen(): Boolean =
    isPlateRoute(initialState.destination.route) && targetState.destination.route == Screen.BookmarkDetail.route

/** Detail returning to the plate screen it came from. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.isPlateReturn(): Boolean =
    initialState.destination.route == Screen.BookmarkDetail.route && isPlateRoute(targetState.destination.route)

/**
 * Hosts `NavHost` ([content]) in a `SharedTransitionLayout` when [enabled]. Under [reducedMotion] no scope is
 * provided, so plates attach nothing and Detail only crossfades (§7).
 */
@Composable
internal fun PlateTransitionLayout(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = rememberReducedMotion(),
    content: @Composable (Modifier) -> Unit
) {
    if (!enabled) return content(modifier)
    SharedTransitionLayout(modifier) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this.takeUnless { reducedMotion }) {
            content(Modifier.fillMaxSize())
        }
    }
}
