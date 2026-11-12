package me.bubblespark.tidilo.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * Provides the current cache invalidation generation for domain icons and feed icons.
 * Defaults to 0L when no provider is present (such as in standalone previews or tests).
 */
val LocalDomainIconCacheGeneration = compositionLocalOf { 0L }

/**
 * Provides the current cache invalidation generation for OG preview images.
 * Defaults to 0L when no provider is present (such as in standalone previews or tests).
 */
val LocalOgImageCacheGeneration = compositionLocalOf { 0L }
