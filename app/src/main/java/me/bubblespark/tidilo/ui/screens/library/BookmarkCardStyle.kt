package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Scoped accent input; shared theme resolution owns its visual treatment. */
internal val LocalCardAccentColor = staticCompositionLocalOf { Color.Unspecified }
