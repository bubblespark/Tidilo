package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import me.bubblespark.tidilo.R

/** Displays a user-facing retry action without exposing database implementation details. */
@Composable
internal fun PagingFeedback(state: LoadState, retry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (state) {
                is LoadState.Loading -> CircularProgressIndicator()
                is LoadState.Error -> {
                    Text(stringResource(R.string.library_load_failed))
                    TextButton(onClick = retry) { Text(stringResource(R.string.common_retry)) }
                }
                else -> Unit
            }
        }
    }
}

/** Both directions can load again after maxSize evicts pages; errors take priority. */
internal fun boundaryFeedback(prepend: LoadState, append: LoadState): LoadState? =
    listOf(prepend, append).firstOrNull { it is LoadState.Error }
        ?: listOf(prepend, append).firstOrNull { it is LoadState.Loading }
