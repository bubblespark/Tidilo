package me.bubblespark.tidilo.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.TidiloPrimaryActionSurface

@Composable
fun LibrarySearchAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TidiloPrimaryActionSurface(expanded = false, modifier = modifier.size(56.dp), blurBackground = true) {
        Box(Modifier.size(56.dp).testTag("library_search_action").clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Search, stringResource(R.string.nav_search), Modifier.size(28.dp))
        }
    }
}
