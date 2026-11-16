package me.bubblespark.tidilo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

data class LibraryRadialMenuEvent(
    val action: LibraryShortcut? = null,
    val opened: Boolean = false,
    val closed: Boolean = false,
    val entered: LibraryShortcut? = null
)

/** Ephemeral presentation; cancelling a press invalidates its eventual release. */
class LibraryRadialMenuState {
    var isOpen by mutableStateOf(false)
        private set
    var feedbackTarget by mutableStateOf<LibraryShortcut?>(null)
        private set
    private var pointer: Long? = null
    private var signature: Any? = null
    private var valid = false
    private var swiping = false
    internal var onCancellation: (() -> Unit)? = null

    fun activateTrigger(): LibraryRadialMenuEvent {
        if (pointer != null) return LibraryRadialMenuEvent()
        val opening = !isOpen
        isOpen = opening
        feedbackTarget = null
        return LibraryRadialMenuEvent(opened = opening, closed = !opening)
    }

    fun beginPress(id: Long, geometrySignature: Any) {
        if (pointer != null) { cancel(); return }
        pointer = id
        signature = geometrySignature
        valid = true
        swiping = false
    }

    fun movePress(id: Long, beyondSlop: Boolean, target: LibraryShortcut?, geometrySignature: Any): LibraryRadialMenuEvent {
        if (!eligible(id, geometrySignature)) return LibraryRadialMenuEvent()
        if (!swiping && !beyondSlop) return LibraryRadialMenuEvent()
        swiping = true
        val opening = !isOpen
        isOpen = true
        val entered = if (feedbackTarget != target) target else null
        if (feedbackTarget != target) feedbackTarget = target
        return LibraryRadialMenuEvent(opened = opening, entered = entered)
    }

    fun endPress(id: Long, target: LibraryShortcut?, insideTrigger: Boolean, geometrySignature: Any): LibraryRadialMenuEvent {
        val allowed = eligible(id, geometrySignature)
        val wasSwiping = swiping
        pointer = null
        valid = false
        swiping = false
        if (!allowed) return LibraryRadialMenuEvent()
        if (wasSwiping) {
            close()
            return LibraryRadialMenuEvent(action = target, closed = target == null)
        }
        return if (insideTrigger) activateTrigger() else LibraryRadialMenuEvent()
    }

    fun activateItem(action: LibraryShortcut): LibraryRadialMenuEvent {
        if (!isOpen || pointer != null) return LibraryRadialMenuEvent()
        close()
        return LibraryRadialMenuEvent(action = action)
    }

    fun feedback(target: LibraryShortcut?): LibraryRadialMenuEvent {
        if (!isOpen || feedbackTarget == target) return LibraryRadialMenuEvent()
        feedbackTarget = target
        return LibraryRadialMenuEvent(entered = target)
    }

    fun cancel() {
        val wasActive = isOpen || valid
        close()
        if (wasActive) onCancellation?.invoke()
    }

    private fun close() {
        valid = false
        isOpen = false
        feedbackTarget = null
    }

    fun cancelPendingPress(id: Long) {
        if (pointer == id) {
            cancel()
            pointer = null
            swiping = false
        }
    }

    private fun eligible(id: Long, geometrySignature: Any): Boolean {
        if (pointer != id || !valid) return false
        if (signature != geometrySignature) { cancel(); return false }
        return true
    }
}

@Composable
fun rememberLibraryRadialMenuState(): LibraryRadialMenuState = remember { LibraryRadialMenuState() }
