package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable

/** Safe chrome geometry without IME, leaving backgrounds and artwork full bleed. */
@Composable
fun screenSystemInsets(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)