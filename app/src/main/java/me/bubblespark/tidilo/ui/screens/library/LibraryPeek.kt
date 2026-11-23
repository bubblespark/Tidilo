package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.ui.screens.discover.DiscoverPeekSheet
import me.bubblespark.tidilo.ui.screens.discover.PeekTarget

/** Saveable Peek-sheet state of the Library layer: which bookmark is peeked, and its collection sheets. */
internal class LibraryPeekState(peekId: String? = null, collectionFor: String? = null, creatorFor: String? = null) {
    var peekId by mutableStateOf(peekId)
    var collectionFor by mutableStateOf(collectionFor)
    var creatorFor by mutableStateOf(creatorFor)
    val isOpen: Boolean get() = peekId != null || collectionFor != null || creatorFor != null

    companion object {
        val Saver = listSaver<LibraryPeekState, String?>(
            save = { listOf(it.peekId, it.collectionFor, it.creatorFor) },
            restore = { LibraryPeekState(it[0], it[1], it[2]) }
        )
    }
}

/** Survives rotation, so an open sheet comes back after recreation (#334 lesson). */
@Composable
internal fun rememberLibraryPeekState(): LibraryPeekState = rememberSaveable(saver = LibraryPeekState.Saver) { LibraryPeekState() }

/**
 * The Library layer's long-press sheet (P5-Q1): the Discover Peek sheet with Like and Add to collection
 * only. The bookmark is observed from Room, so Like shows the stored state and a deleted bookmark closes it.
 */
@Composable
internal fun LibraryPeekSheets(state: LibraryPeekState, viewModel: LibraryViewModel) {
    state.peekId?.let { id ->
        val details by remember(id) { viewModel.bookmarkDetails(id) }.collectAsStateWithLifecycle(BookmarkDetailState())
        val item = details.bookmark
        LaunchedEffect(details.loading, item) { if (!details.loading && item == null) state.peekId = null }
        if (item != null) {
            DiscoverPeekSheet(
                target = PeekTarget(item, reason = null),
                liked = item.isLiked,
                onToggleLike = { viewModel.toggleLike(item.id, EngagementSurface.LIBRARY) },
                onSnooze = null,
                onNeverAgain = null,
                onAddToCollection = { state.collectionFor = item.id },
                onDismiss = { state.peekId = null }
            )
        }
    }
    state.collectionFor?.let { id ->
        CollectionMembershipBottomSheet(
            bookmarkId = id,
            onDismiss = { state.collectionFor = null },
            onNewCollection = {
                state.collectionFor = null
                state.creatorFor = id
            }
        )
    }
    state.creatorFor?.let { id ->
        CollectionEditorBottomSheet(collectionId = null, assignToBookmarkId = id, onDismiss = { state.creatorFor = null })
    }
}
