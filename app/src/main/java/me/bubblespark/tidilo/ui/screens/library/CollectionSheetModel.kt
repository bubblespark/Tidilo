package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

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
