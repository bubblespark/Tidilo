package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Scopes observers to an open sheet while saving primitive drafts across recreation. */
@Composable
internal inline fun <reified T : ViewModel> collectionSheetModel(
    key: String, crossinline create: (SavedStateHandle) -> T
): T {
    val saved = rememberSaveable(key, saver = mapSaver(
        save = { handle: SavedStateHandle -> handle.keys().associateWith { handle.get<Any?>(it) } },
        restore = { SavedStateHandle(it) }
    )) { SavedStateHandle() }
    val store = remember(key) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    return remember(store) {
        ViewModelProvider(store, viewModelFactory { initializer { create(saved) } })[T::class.java]
    }
}

internal data class ClosingSheetController @OptIn(ExperimentalMaterial3Api::class) constructor(
    val sheetState: SheetState,
    val onDismissRequest: () -> Unit
)

/**
 * Coordinates two-step modal bottom sheet dismissal so `sheetState.hide()` always finishes before
 * [onClosed] unmounts or transitions the sheet, even if a touch gesture interrupts `AnchoredDraggableState`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberClosingSheetController(
    closing: Boolean,
    blockDismiss: Boolean = false,
    onRequestClose: () -> Unit,
    onClosed: (() -> Unit)?,
    onCancelWhileClosing: () -> Unit = {}
): ClosingSheetController {
    val isClosing by rememberUpdatedState(closing)
    val isBlocked by rememberUpdatedState(blockDismiss)
    val requestClose by rememberUpdatedState(onRequestClose)
    val currentOnClosed by rememberUpdatedState(onClosed)
    val currentOnCancelWhileClosing by rememberUpdatedState(onCancelWhileClosing)
    var closeNotified by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            when {
                isClosing -> target == SheetValue.Hidden
                target != SheetValue.Hidden -> true
                isBlocked -> false
                currentOnClosed != null -> {
                    requestClose()
                    false
                }
                else -> true
            }
        }
    )
    fun notifyClosed() {
        val callback = currentOnClosed
        if (callback != null && !sheetState.isVisible && !closeNotified) {
            closeNotified = true
            callback()
        }
    }
    LaunchedEffect(closing, sheetState) {
        if (closing && currentOnClosed != null) {
            do {
                try {
                    sheetState.hide()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
            } while (sheetState.isVisible || sheetState.targetValue != SheetValue.Hidden)
            notifyClosed()
        }
    }
    val onDismissRequest: () -> Unit = {
        if (!isBlocked) {
            if (currentOnClosed != null && isClosing) {
                currentOnCancelWhileClosing()
            } else {
                requestClose()
            }
        }
    }
    return ClosingSheetController(sheetState = sheetState, onDismissRequest = onDismissRequest)
}
