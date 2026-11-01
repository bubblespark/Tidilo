package me.bubblespark.tidilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Represents a registered ambient lighting request from a specific screen or component owner.
 */
data class AmbientLightingRequest(
    val ownerId: String,
    val color: Color
)

/**
 * State holder managing the active ambient highlight / lighting color across screens.
 *
 * Employs an owner-keyed stack to cleanly decouple ambient lighting requests:
 * - When navigating deeper (e.g. Library DrillDown -> Bookmark Detail), the new screen's
 *   color is pushed to the top of the stack.
 * - Outgoing screen disposals only remove their own owner request, preventing disposal races
 *   from clobbering the active top-level screen's lighting.
 * - When returning from a detail screen, popping the detail owner automatically restores the
 *   underlying screen's ambient lighting without requiring re-triggering effects.
 * - Destination gating ([isDestinationAllowed]) allows immediate parallel fade-out when switching
 *   to destinations without ambient lighting (e.g. Search, Settings) at the start of navigation.
 */
class AmbientLightingState(initialColor: Color? = null) {
    private val _requests = mutableStateListOf<AmbientLightingRequest>()

    /**
     * Whether the active navigation destination supports ambient lighting.
     * When false (e.g. on Search tab), [effectiveActiveColor] resolves to null immediately.
     */
    var isDestinationAllowed: Boolean by mutableStateOf(true)

    init {
        if (initialColor != null && initialColor != Color.Transparent) {
            _requests.add(AmbientLightingRequest(DEFAULT_OWNER, initialColor))
        }
    }

    /**
     * The raw top-level ambient lighting color requested, or null if no active request exists.
     */
    val activeColor: Color?
        get() = _requests.lastOrNull()?.color

    /**
     * The effective ambient lighting color after applying destination gating.
     */
    val effectiveActiveColor: Color?
        get() = if (isDestinationAllowed) activeColor else null

    /**
     * Registers or updates an ambient lighting color for [ownerId].
     * Passing null or [Color.Transparent] removes the owner request.
     */
    fun setAmbientColor(ownerId: String = DEFAULT_OWNER, color: Color?) {
        if (color == null || color == Color.Transparent) {
            removeAmbientColor(ownerId)
            return
        }
        val existingIndex = _requests.indexOfFirst { it.ownerId == ownerId }
        if (existingIndex >= 0) {
            if (existingIndex == _requests.lastIndex) {
                _requests[existingIndex] = _requests[existingIndex].copy(color = color)
            } else {
                _requests.removeAt(existingIndex)
                _requests.add(AmbientLightingRequest(ownerId, color))
            }
        } else {
            _requests.add(AmbientLightingRequest(ownerId, color))
        }
    }

    /**
     * Overload for backward compatibility.
     */
    fun setAmbientColor(color: Color?) {
        setAmbientColor(DEFAULT_OWNER, color)
    }

    /**
     * Removes the ambient lighting request associated with [ownerId].
     */
    fun removeAmbientColor(ownerId: String = DEFAULT_OWNER) {
        val index = _requests.indexOfFirst { it.ownerId == ownerId }
        if (index >= 0) {
            _requests.removeAt(index)
        }
    }

    /**
     * Clears all ambient lighting requests.
     */
    fun clear() {
        _requests.clear()
    }

    companion object {
        const val DEFAULT_OWNER = "default"
    }
}

val LocalAmbientLighting = compositionLocalOf { AmbientLightingState() }

/**
 * Publishes an ambient lighting [color] for [ownerId] to [LocalAmbientLighting] while in composition,
 * and safely removes only this owner's request upon leaving composition.
 */
@Composable
fun RememberAmbientLighting(
    color: Color?,
    ownerId: String = AmbientLightingState.DEFAULT_OWNER
) {
    val lightingState = LocalAmbientLighting.current
    DisposableEffect(ownerId, color) {
        lightingState.setAmbientColor(ownerId, color)
        onDispose {
            lightingState.removeAmbientColor(ownerId)
        }
    }
}
