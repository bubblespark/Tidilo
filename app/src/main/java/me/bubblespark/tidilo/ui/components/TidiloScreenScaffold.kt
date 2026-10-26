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
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.(PaddingValues) -> Unit
) {
    val padding = PaddingValues(
        start = HeaderDefaults.HorizontalPadding,
        end = HeaderDefaults.HorizontalPadding,
        top = HeaderDefaults.bodyTopPadding(),
        bottom = bottomContentPadding
    )
    Box(modifier.fillMaxSize()) {
        content(padding)
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
            modifier = Modifier.align(Alignment.TopCenter)
        ) { header() }
        overlay?.invoke(this)
    }
}
