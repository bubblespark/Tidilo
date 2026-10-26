package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import me.bubblespark.tidilo.R
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
                Box(contentAlignment = Alignment.Center) { navigation() }
            }
            Column(Modifier.weight(1f)) {
                title()
                subtitle?.invoke()
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Standard secondary-page header, including loading and unavailable-content states. */
@Composable
fun TidiloPageHeader(
    title: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TidiloFrostedHeader(
        title = {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
        subtitle = subtitle,
        navigationIcon = {
            IconButton(onNavigateBack, Modifier.padding(end = 4.dp).size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
        },
        actions = actions
    )
}
