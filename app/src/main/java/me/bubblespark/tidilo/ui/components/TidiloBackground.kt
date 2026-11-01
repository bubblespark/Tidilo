package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.bubblespark.tidilo.config.AppTheme

/**
 * Root-level centralized background layer for Tidilo.
 *
 * Renders the active theme's background layer while remaining stationary during
 * screen-to-screen slide transitions.
 */
@Composable
fun TidiloBackground(
    currentTheme: AppTheme,
    modifier: Modifier = Modifier
) {
    when (currentTheme) {
        AppTheme.DEFAULT -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        }
    }
}
