package me.bubblespark.tidilo.data

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class EntityTagConflictTest {
    private val host = HeaderTestHost()
    private val addEntityDialog = AddEntityDialogTestControls(host)
    private var stage = "mount"
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return node
            repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child)?.let { return it } } }
            return null
        }
        host.instrumentation.uiAutomation.clearCache()
        return host.instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private suspend fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo = withTimeout(8000) {
        var found = find(predicate)
        while (found == null) { delay(40); found = find(predicate) }
        found
    }
    private suspend fun click(text: String) = withTimeout(8000) {
        stage = "click: $text"
        while (true) {
            var target = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
            while (target != null && !target.isClickable) target = target.parent
            if (target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
            delay(40)
        }
        delay(200)
    }
    private fun matchesInAnyWindow(label: String): Boolean {
        fun visit(node: AccessibilityNodeInfo): Boolean =
            (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) ||
                (0 until node.childCount).any { index -> node.getChild(index)?.let(::visit) == true }
        host.instrumentation.uiAutomation.clearCache()
        return host.instrumentation.uiAutomation.windows.any { window -> window.root?.let(::visit) == true }
    }
    /** Clicks the last matching node in the topmost window that has one, e.g. a sheet above a dialog. */
    private suspend fun clickInAnyWindow(label: String) = withTimeout(8000) {
        stage = "click in any window: $label"
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var match: AccessibilityNodeInfo? = null
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) match = node
            repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child)?.let { match = it } } }
            return match
        }
        val automation = host.instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        while (true) {
            automation.clearCache()
            var target = automation.windows.sortedByDescending { it.layer }
                .firstNotNullOfOrNull { window -> window.root?.let(::visit) }
            while (target != null && !target.isClickable) target = target.parent
            if (target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
            delay(40)
        }
        delay(200)
    }
    private suspend fun enter(value: String) {
        stage = "enter text"
        val target = node { it.isEditable }
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }
    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(8000) { while (!predicate()) delay(40) }

    private fun verify(currentHasEntity: Boolean = true, classified: Boolean = false, action: suspend Fixture.() -> Unit): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val token = UUID.randomUUID().toString()
        val id = "edit-$token"
        val other = "hidden-$token"
        val canonical = "Conflict$token"
        val variant = canonical.uppercase()
        var launchedActivity: ComponentActivity? = null
        var originalFailure: Throwable? = null
        val mounted = mutableStateOf(true)
        val registry = mutableStateOf(SaveableStateRegistry(null) { true })
        var navigations = 0
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Editor", "https://$id.invalid", "Summary",
                category = if (classified) "Tool" else null, subcategory = if (classified) "Tech" else null,
                entities = if (currentHasEntity) listOf(canonical) else emptyList(), createdAt = 1, updatedAt = 1))
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(other, "Hidden", "https://$other.invalid", "Summary",
                entities = listOf(canonical), isPrivate = true, createdAt = 2, updatedAt = 2))
            val activity = host.launch().also { launchedActivity = it }
            host.main {
                val vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent { MaterialTheme {
                    if (mounted.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry.value) {
                        BookmarkDetailScreen(id, vm, { navigations++ })
                    }
                } }
            }
            val fixture = Fixture(db, id, other, canonical, variant, { navigations }, { amount -> host.scroll(activity, amount) }, {
                var saved: Map<String, List<Any?>> = emptyMap()
                host.main { saved = registry.value.performSave(); mounted.value = false }
                host.instrumentation.waitForIdleSync()
                delay(150)
                host.main { registry.value = SaveableStateRegistry(saved) { true }; mounted.value = true }
            })
            fixture.action()
        } catch (failure: Throwable) {
            val reported = AssertionError("Editor stage: $stage", failure)
            originalFailure = reported
            throw reported
        } finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val primary = originalFailure ?: cleanupFailure
                    if (primary != null) primary.addSuppressed(failure) else cleanupFailure = failure
                }
            }
            cleanup { db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS entity_conflict_failure") }
            cleanup { launchedActivity?.let { activity -> host.main { activity.finish() } } }
            cleanup { db.bookmarkDao().deleteById(id) }
            cleanup { db.bookmarkDao().deleteById(other) }
            if (originalFailure == null) cleanupFailure?.let { throw it }
        }
    }
    private inner class Fixture(val db: AppDatabase, val id: String, val other: String,
        val canonical: String, val variant: String, val navigations: () -> Int, val scroll: (Float) -> Unit, val restore: suspend () -> Unit) {
        suspend fun note(value: String) {
            node { it.text?.toString() == "Editor" }
            repeat(5) {
                if (find { it.isEditable } == null) { scroll(600f); delay(150) }
            }
            enter(value)
            delay(250)
            scroll(-10000f)
            delay(250)
        }
        suspend fun conflict() {
            click(host.context.getString(R.string.detail_edit_tags))
            addEntity(variant)
            node { it.text?.toString() == "Replace across all bookmarks" }
        }
        suspend fun addEntity(value: String) {
            click(host.context.getString(R.string.detail_tag_add_entity))
            stage = "enter text in Add Entity"
            addEntityDialog.enter(value)
            stage = "confirm Add Entity"
            addEntityDialog.confirm()
        }
        suspend fun spelling(bookmark: String = other) = requireNotNull(db.bookmarkDao().getBookmarkById(bookmark)).entities.single()
        /** Parent tag-dialog OK is the final persistence step; there is no Detail-level Save. */
        suspend fun accept() = click("OK")
        suspend fun storedNote() = requireNotNull(db.bookmarkDao().getBookmarkById(id)).note
    }

    @Test fun useExistingKeepsCanonicalSpelling() = verify {
        conflict(); click("Use existing tag"); accept()
        await { canonical in requireNotNull(db.bookmarkDao().getBookmarkById(id)).entities }
        assertEquals(canonical, spelling())
        assertEquals(canonical, spelling(id))
    }
    @Test fun dismissAndParentCancelDoNotPersistReplacement() = verify {
        conflict()
        host.instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        delay(150)
        assertEquals(canonical, spelling())
        click("Cancel")
        click(host.context.getString(R.string.common_back))
        await { navigations() == 1 }
        assertEquals(canonical, spelling(id))
    }
    @Test fun parentCancelDiscardsNestedReplacement() = verify {
        conflict(); click("Replace across all bookmarks"); click("Cancel")
        delay(300)
        assertEquals(canonical, spelling())
        assertEquals(canonical, spelling(id))
        click(host.context.getString(R.string.common_back))
        await { navigations() == 1 }
    }
    @Test fun pickerOkThenParentCancelLeavesStoredTagsUnchanged() = verify {
        click(host.context.getString(R.string.detail_edit_tags))
        click(host.context.getString(R.string.detail_tag_select_entities))
        // The picker is a separate sheet window above the dialog; toggle the entity off in its library list.
        clickInAnyWindow(canonical)
        await { matchesInAnyWindow("Selected (0/10)") }
        clickInAnyWindow(host.context.getString(R.string.common_ok))
        click("Cancel")
        delay(300)
        assertEquals(listOf(canonical), requireNotNull(db.bookmarkDao().getBookmarkById(id)).entities)
        assertEquals(0, navigations())
    }
    @Test fun parentOkPersistsReplacementAndUpdatesHiddenRows() = verify {
        conflict(); click("Replace across all bookmarks")
        assertEquals("Replacement stays a draft until parent OK", canonical, spelling())
        accept()
        await { spelling() == variant }
        assertEquals(variant, spelling(id))
        assertEquals("Committing tags never navigates away", 0, navigations())
        assertNull("The tag dialog closes after a successful commit", find { it.text?.toString() == "Cancel" })
    }
    @Test fun backAfterCommitLeavesWithoutDiscardPrompt() = verify {
        conflict(); click("Replace across all bookmarks"); accept()
        await { spelling() == variant }
        click(host.context.getString(R.string.common_back))
        await { navigations() == 1 }
        assertEquals(variant, spelling())
    }
    @Test fun tagSaveFailureKeepsDialogDraftForRetry() = verify {
        conflict(); click("Replace across all bookmarks")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER entity_conflict_failure BEFORE UPDATE ON bookmarks BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        accept()
        node { it.text?.toString() == host.context.getString(R.string.common_save_failed) }
        assertEquals(0, navigations())
        assertEquals("No partial cross-bookmark replacement", canonical, spelling())
        assertEquals(canonical, spelling(id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER entity_conflict_failure")
        accept()
        await { spelling() == variant }
        assertEquals(variant, spelling(id))
    }
    @Test fun restorationAndRoomEmissionKeepNestedChoice() = verify {
        conflict(); restore(); click("Replace across all bookmarks")
        val row = requireNotNull(db.bookmarkDao().getBookmarkById(id))
        db.bookmarkDao().insertOrUpdate(row.copy(note = "Concurrent note", updatedAt = 3))
        delay(250)
        restore(); accept()
        await { spelling() == variant }
        assertEquals("Tag commit must not revert an independently saved note", "Concurrent note", storedNote())
    }
    @Test fun replacementOnlyEditCommitsOnParentOk() = verify(classified = true) {
        conflict(); click("Replace across all bookmarks"); accept()
        await { spelling() == variant }
        click(host.context.getString(R.string.detail_edit_tags))
        addEntity(canonical)
        click("Replace across all bookmarks"); accept()
        await { spelling(id) == canonical }
        assertEquals(canonical, spelling())
        val row = requireNotNull(db.bookmarkDao().getBookmarkById(id))
        assertEquals("Tool", row.category); assertEquals("Tech", row.subcategory)
    }
    @Test fun removedTargetCannotBulkRenameOtherBookmarks() = verify {
        conflict(); click("Replace across all bookmarks")
        db.bookmarkDao().deleteById(id)
        node { it.text?.toString() == host.context.getString(R.string.detail_item_not_found) }
        delay(300)
        assertEquals(canonical, spelling())
        assertEquals(0, navigations())
    }
    @Test fun rapidOkTapsCommitOnce() = verify {
        conflict(); click("Replace across all bookmarks")
        val ok = node { it.text?.toString() == "OK" }
        var button: AccessibilityNodeInfo? = ok
        while (button != null && !button.isClickable) button = button.parent
        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        await { spelling() == variant }; delay(300)
        assertEquals(0, navigations()); assertEquals(variant, spelling(id))
    }
    @Test fun completeDraftSurvivesRestorationAndKeepsAutosavedNote() = verify {
        note("My autosaved note")
        await { storedNote() == "My autosaved note" }
        conflict(); click("Replace across all bookmarks")
        click("Select Category"); click("Tool")
        click("Select Subcategory"); click("Tech")
        val privateSwitch = node { it.isCheckable }
        assertTrue(privateSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        restore(); accept()
        await { requireNotNull(db.bookmarkDao().getBookmarkById(id)).isPrivate }
        val saved = requireNotNull(db.bookmarkDao().getBookmarkById(id))
        assertEquals("My autosaved note", saved.note)
        assertEquals("Tool", saved.category); assertEquals("Tech", saved.subcategory)
        assertEquals(variant, spelling(id))
    }
    @Test fun noteAutosaveKeepsLatestExternalSpelling() = verify {
        note("My note")
        BookmarkRepository(db, autoInitialize = false).updateBookmarkTags(other, Tag.fromDisplayValues(null, null, listOf(canonical)), true, mapOf(entitySkeletonKey(canonical) to variant), 9)
        await { storedNote() == "My note" }
        assertEquals(variant, spelling(id))
    }

    @Test fun hiddenOnlyLibrarySpellingRequiresAChoice() = verify(false) {
        conflict(); click("Use existing tag"); accept()
        await { requireNotNull(db.bookmarkDao().getBookmarkById(id)).entities.isNotEmpty() }
        assertEquals(canonical, spelling(id))
    }
    @Test fun pickerShowsStagedSpellingOncePerKey() = verify {
        conflict(); click("Replace across all bookmarks")
        click(host.context.getString(R.string.detail_tag_select_entities))
        node { it.text?.toString() == variant }
        assertNull("Previous library spelling must be overlaid by the pending choice", find { it.text?.toString() == canonical })
        assertEquals(canonical, spelling())
    }

    @Test fun restoredNoteDraftAutosavesWithoutClassification() = verify {
        note("Restored note"); restore()
        await { storedNote() == "Restored note" }
        val row = requireNotNull(db.bookmarkDao().getBookmarkById(id))
        assertNull(row.category); assertNull(row.subcategory)
        assertEquals(0, navigations())
    }

}
