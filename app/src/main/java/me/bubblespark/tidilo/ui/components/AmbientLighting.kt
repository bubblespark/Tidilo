package me.bubblespark.tidilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * State holder managing the active ambient highlight / lighting color across screens.
 *
 * Decouples dynamic ambient lighting (such as RSS feed, domain, collection, or detail artwork)
 * from individual screen layouts so it renders stationary within [TidiloBackground] without
 * sliding across the screen during route navigation.
 */
class AmbientLightingState(initialColor: Color? = null) {
    val activeColor: MutableState<Color?> = mutableStateOf(initialColor)

    fun setAmbientColor(color: Color?) {
        activeColor.value = color
    }

    fun clear() {
        activeColor.value = null
    }
}

val LocalAmbientLighting = compositionLocalOf { AmbientLightingState() }

/**
 * Publishes an ambient lighting [color] to [LocalAmbientLighting] while this composable is in the composition,
 * and resets it when leaving the composition.
 */
@Composable
fun RememberAmbientLighting(color: Color?) {
    val lightingState = LocalAmbientLighting.current
    DisposableEffect(color) {
        lightingState.setAmbientColor(color)
        onDispose {
            lightingState.clear()
        }
    }
}
