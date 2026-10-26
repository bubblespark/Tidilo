package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A stable toolbar row; title-specific typography and transitions belong to its slots. */
@Composable
fun TidiloFrostedHeader(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    FrostedHeaderContainer(modifier) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding()
                .height(HeaderDefaults.rowHeight())
                .padding(horizontal = HeaderDefaults.HorizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navigationIcon?.let { navigation ->
                Box(Modifier.padding(end = 4.dp), contentAlignment = Alignment.Center) { navigation() }
            }
            Column(Modifier.weight(1f)) {
                title()
                subtitle?.invoke()
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
