package me.bubblespark.tidilo.ui.screens.library

import android.os.Bundle
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver

/** Saves the accepted screen draft and the unaccepted nested editor as one unit. */
@Stable
internal class BookmarkDetailEditState(val originalNote: String, val originalTag: Tag, val originalNsfw: Boolean) {
    val noteText = mutableStateOf(originalNote)
    val editedTag = mutableStateOf(originalTag)
    val editedIsNsfw = mutableStateOf(originalNsfw)
    val pendingReplacements = mutableStateOf<Map<String, String>>(emptyMap())
    val showTagEditDialog = mutableStateOf(false)
    val showAddEntityDialog = mutableStateOf(false)
    val showEntityBottomSheet = mutableStateOf(false)
    val draftCategory = mutableStateOf<String?>(null)
    val draftSubcategory = mutableStateOf<String?>(null)
    val draftEntities = mutableStateOf<List<String>>(emptyList())
    val draftIsNsfw = mutableStateOf(false)
    val draftReplacements = mutableStateOf<Map<String, String>>(emptyMap())
    val entityInput = mutableStateOf("")
    val conflictInput = mutableStateOf<String?>(null)
    val conflictExisting = mutableStateOf<String?>(null)

    companion object {
        val Saver = Saver<BookmarkDetailEditState, Bundle>(
            save = { state -> Bundle().apply {
                putString("originalNote", state.originalNote)
                putTag("originalTag", state.originalTag)
                putBoolean("originalNsfw", state.originalNsfw)
                putString("note", state.noteText.value)
                putTag("tag", state.editedTag.value)
                putBoolean("nsfw", state.editedIsNsfw.value)
                putMap("pending", state.pendingReplacements.value)
                putBoolean("editor", state.showTagEditDialog.value)
                putBoolean("add", state.showAddEntityDialog.value)
                putBoolean("picker", state.showEntityBottomSheet.value)
                putTag("draft", Tag(state.draftCategory.value, state.draftSubcategory.value, state.draftEntities.value))
                putBoolean("draftNsfw", state.draftIsNsfw.value)
                putMap("draftPending", state.draftReplacements.value)
                putString("input", state.entityInput.value)
                putString("conflictInput", state.conflictInput.value)
                putString("conflictExisting", state.conflictExisting.value)
            } },
            restore = { saved -> BookmarkDetailEditState(saved.getString("originalNote").orEmpty(), saved.tag("originalTag"), saved.getBoolean("originalNsfw")).apply {
                noteText.value = saved.getString("note").orEmpty()
                editedTag.value = saved.tag("tag")
                editedIsNsfw.value = saved.getBoolean("nsfw")
                pendingReplacements.value = saved.map("pending")
                showTagEditDialog.value = saved.getBoolean("editor")
                showAddEntityDialog.value = saved.getBoolean("add")
                showEntityBottomSheet.value = saved.getBoolean("picker")
                val draft = saved.tag("draft")
                draftCategory.value = draft.category
                draftSubcategory.value = draft.subcategory
                draftEntities.value = draft.entities
                draftIsNsfw.value = saved.getBoolean("draftNsfw")
                draftReplacements.value = saved.map("draftPending")
                entityInput.value = saved.getString("input").orEmpty()
                conflictInput.value = saved.getString("conflictInput")
                conflictExisting.value = saved.getString("conflictExisting")
            } }
        )
        private fun Bundle.putTag(key: String, tag: Tag) {
            putString("$key.category", tag.category)
            putString("$key.subcategory", tag.subcategory)
            putStringArrayList("$key.entities", ArrayList(tag.entities))
        }
        private fun Bundle.tag(key: String) = Tag(getString("$key.category"), getString("$key.subcategory"), getStringArrayList("$key.entities").orEmpty())
        private fun Bundle.putMap(key: String, map: Map<String, String>) {
            putStringArrayList("$key.keys", ArrayList(map.keys))
            putStringArrayList("$key.values", ArrayList(map.values))
        }
        private fun Bundle.map(key: String) = getStringArrayList("$key.keys").orEmpty().zip(getStringArrayList("$key.values").orEmpty()).toMap()
    }
}
