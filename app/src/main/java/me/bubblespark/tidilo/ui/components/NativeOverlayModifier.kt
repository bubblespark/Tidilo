package me.bubblespark.tidilo.ui.components

import androidx.compose.ui.Modifier

/**
 * Native surfaces append anchors, predictive-back layers, and clipping to the caller modifier.
 * Keep that native chain outside the background so both background and foreground receive the
 * same transforms. Do not use this for content-slot modifiers that append internal padding.
 */
internal fun Modifier.withNativeOverlayBackground(background: Modifier): Modifier =
    NativeOverlayModifier(this, background)

private data class NativeOverlayModifier(val outer: Modifier, val background: Modifier) : Modifier {
    private val resolved: Modifier get() = outer.then(background)

    override fun <R> foldIn(initial: R, operation: (R, Modifier.Element) -> R): R =
        resolved.foldIn(initial, operation)

    override fun <R> foldOut(initial: R, operation: (Modifier.Element, R) -> R): R =
        resolved.foldOut(initial, operation)

    override fun any(predicate: (Modifier.Element) -> Boolean): Boolean = resolved.any(predicate)
    override fun all(predicate: (Modifier.Element) -> Boolean): Boolean = resolved.all(predicate)

    override infix fun then(other: Modifier): Modifier =
        if (other === Modifier) this else NativeOverlayModifier(outer.then(other), background)
}
