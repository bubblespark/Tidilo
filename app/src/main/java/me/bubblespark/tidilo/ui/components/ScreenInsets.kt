package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable

/** Safe chrome geometry without IME, leaving backgrounds and artwork full bleed. */
@Composable
fun screenSystemInsets(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
/** Floating actions own their system insets independently of scrolling content. */
@Composable
fun Modifier.screenActionInsets(): Modifier =
    windowInsetsPadding(screenSystemInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
