package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared geometry is independent of the current title and its animation state. */
object HeaderDefaults {
    val ContentHeight = 80.dp
    val FadeHeight = 18.dp
    val DecayOverlap = 6.dp
    val ClearanceGap = 6.dp
    val HorizontalPadding = 20.dp
    val BottomNavigationPadding = 140.dp

    @Composable
    fun rowHeight(): Dp {
        val measurer = rememberTextMeasurer()
        val title = measurer.measure("Agあ", style = MaterialTheme.typography.headlineMedium, maxLines = 1)
        val subtitle = measurer.measure("Agあ", style = MaterialTheme.typography.labelMedium, maxLines = 1)
        return with(LocalDensity.current) {
            maxOf(ContentHeight, (title.size.height + subtitle.size.height).toDp() + 8.dp)
        }
    }

    @Composable
    fun bodyTopPadding(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        rowHeight() + FadeHeight + ClearanceGap
}
