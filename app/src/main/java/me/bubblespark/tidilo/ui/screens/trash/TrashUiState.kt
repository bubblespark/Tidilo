package me.bubblespark.tidilo.ui.screens.trash

import kotlinx.coroutines.flow.Flow
import me.bubblespark.tidilo.data.db.TrashBookmarkRow
import me.bubblespark.tidilo.data.db.TrashMutationResult

enum class TrashAction { Restore, Delete }
data class TrashConfirmation(val id: String, val action: TrashAction)
data class TrashUiState(
    val loading: Boolean = true,
    val rows: List<TrashBookmarkRow> = emptyList(),
    val confirmation: TrashConfirmation? = null,
    val busy: Boolean = false,
    val error: Throwable? = null,
    val readFailed: Boolean = false,
    val actionResult: TrashMutationResult? = null
)

interface TrashStore {
    fun observe(showNsfw: Boolean): Flow<List<TrashBookmarkRow>>
    suspend fun restore(id: String): TrashMutationResult
    suspend fun delete(id: String): TrashMutationResult
    suspend fun retry()
}
