package me.bubblespark.tidilo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible

/** Edge-to-edge body, independent header, then optional foreground overlays. */
@Composable
fun TidiloScreenScaffold(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    hideHeaderOnScroll: Boolean = false,
    headerVisible: Boolean = true,
    bottomContentPadding: Dp = 0.dp,
    bodyModifier: Modifier = Modifier,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    avoidIme: Boolean = false,
    content: @Composable BoxScope.(PaddingValues) -> Unit
) {
    var consumedInsets by remember { mutableStateOf<WindowInsets>(WindowInsets(0, 0, 0, 0)) }
    val remaining = contentWindowInsets.exclude(consumedInsets)
    val bodyInsets = if (avoidIme) remaining.exclude(WindowInsets.ime) else remaining
    val safePadding = bodyInsets.asPaddingValues()
    val legacyTop = HeaderDefaults.bodyTopPadding()
    val headerClearance = HeaderDefaults.rowHeight() + HeaderDefaults.FadeHeight + HeaderDefaults.ClearanceGap
    // Keep inset reads in layout, and never consume app-specific header or action spacing.
    val padding = object : PaddingValues {
        override fun calculateLeftPadding(layoutDirection: LayoutDirection) =
            HeaderDefaults.HorizontalPadding + safePadding.calculateLeftPadding(layoutDirection)
        override fun calculateRightPadding(layoutDirection: LayoutDirection) =
            HeaderDefaults.HorizontalPadding + safePadding.calculateRightPadding(layoutDirection)
        override fun calculateTopPadding() = maxOf(legacyTop, headerClearance + safePadding.calculateTopPadding())
        override fun calculateBottomPadding() = bottomContentPadding + safePadding.calculateBottomPadding()
    }
    ScreenBackdropScope { backdrop ->
        Box(modifier.fillMaxSize().onConsumedWindowInsetsChanged { consumedInsets = it }) {
            Box(Modifier.fillMaxSize().screenBackdropSource(backdrop).then(bodyModifier)) {
                Box(Modifier.fillMaxSize()
                    .then(if (avoidIme) Modifier.imePadding() else Modifier)
                    .consumeWindowInsets(bodyInsets)) { content(padding) }
            }
            ScaffoldHeaderVisibility(
                header = header,
                hideHeaderOnScroll = hideHeaderOnScroll,
                headerVisible = headerVisible,
                modifier = Modifier.align(Alignment.TopCenter)
            )
            overlay?.invoke(this)
        }
    }
}

/** Observe scroll visibility only in the header's restart scope. */
@Composable
private fun ScaffoldHeaderVisibility(
    header: @Composable () -> Unit,
    hideHeaderOnScroll: Boolean,
    headerVisible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = headerVisible && (!hideHeaderOnScroll || LocalBarsVisible.current),
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        ) + fadeIn(tween(200)),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        ) + fadeOut(tween(150)),
        modifier = modifier
    ) { header() }
}
