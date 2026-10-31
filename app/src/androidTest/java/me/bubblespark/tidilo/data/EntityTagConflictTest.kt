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
import me.bubblespark.tidilo.data.db.updateBookmarkDetails
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class EntityTagConflictTest {
    private val host = HeaderTestHost()
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
                entities = if (currentHasEntity) listOf(canonical) else emptyList(), createdAt = 1, updatedAt = 1, isAiSkipped = true))
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(other, "Hidden", "https://$other.invalid", "Summary",
                entities = listOf(canonical), isNsfw = true, createdAt = 2, updatedAt = 2, isAiSkipped = true))
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
            click(host.context.getString(R.string.detail_tag_add_entity))
            enter(variant)
            click("OK")
            node { it.text?.toString() == "Replace across all bookmarks" }
        }
        suspend fun spelling(bookmark: String = other) = requireNotNull(db.bookmarkDao().getBookmarkById(bookmark)).entities.single()
        suspend fun accept() = click("OK")
        suspend fun save() = click("Save")
    }

    @Test fun useExistingKeepsCanonicalSpelling() = verify {
        conflict(); click("Use existing tag"); accept()
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
        assertEquals(canonical, spelling())
        click(host.context.getString(R.string.common_back))
        await { navigations() == 1 }
    }
    @Test fun acceptedReplacementIsDraftUntilSaveAndUpdatesHiddenRows() = verify {
        conflict(); click("Replace across all bookmarks"); accept()
        assertEquals(canonical, spelling())
        save(); await { navigations() == 1 }
        assertEquals(variant, spelling()); assertEquals(variant, spelling(id))
    }
    @Test fun screenDiscardDropsAcceptedReplacement() = verify {
        conflict(); click("Replace across all bookmarks"); accept()
        click(host.context.getString(R.string.common_back)); click("OK")
        await { navigations() == 1 }
        assertEquals(canonical, spelling())
    }
    @Test fun saveFailurePreservesDraftForRetry() = verify {
        conflict(); click("Replace across all bookmarks"); accept()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER entity_conflict_failure BEFORE UPDATE ON bookmarks BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        save(); delay(700)
        assertEquals(0, navigations()); assertEquals(canonical, spelling())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER entity_conflict_failure")
        save(); await { navigations() == 1 }; assertEquals(variant, spelling())
    }
    @Test fun restorationAndRoomEmissionKeepNestedChoice() = verify {
        conflict(); restore(); click("Replace across all bookmarks")
        val row = requireNotNull(db.bookmarkDao().getBookmarkById(id))
        db.bookmarkDao().insertOrUpdate(row.copy(note = "Concurrent note", updatedAt = 3))
        delay(250)
        restore(); accept(); restore(); save()
        await { navigations() == 1 }; assertEquals(variant, spelling())
    }
    @Test fun replacementOnlyDirtyStateStillEnablesSave() = verify(classified = true) {
        conflict(); click("Replace across all bookmarks"); accept()
        click(host.context.getString(R.string.detail_edit_tags))
        click(host.context.getString(R.string.detail_tag_add_entity)); enter(canonical); click("OK")
        click("Replace across all bookmarks"); accept()
        assertEquals(canonical, spelling(id))
        save(); await { navigations() == 1 }
        assertEquals(canonical, spelling())
    }
    @Test fun removedTargetCannotBulkRenameOtherBookmarks() = verify {
        conflict(); click("Replace across all bookmarks"); accept()
        db.bookmarkDao().deleteById(id)
        node { it.text?.toString() == host.context.getString(R.string.detail_item_not_found) }
        assertEquals(canonical, spelling())
        assertEquals(0, navigations())
    }
    @Test fun rapidSaveTapsCommitOnce() = verify {
        conflict(); click("Replace across all bookmarks"); accept()
        val save = node { it.text?.toString() == "Save" }
        var button: AccessibilityNodeInfo? = save
        while (button != null && !button.isClickable) button = button.parent
        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        await { navigations() == 1 }; delay(300)
        assertEquals(1, navigations()); assertEquals(variant, spelling())
    }
    @Test fun completeDraftSurvivesRestorationAndConcurrentEmission() = verify {
        note("My unsaved note")
        conflict(); click("Replace across all bookmarks")
        click("Select Category"); click("Tool")
        click("Select Subcategory"); click("Tech")
        val nsfw = node { it.isCheckable }
        assertTrue(nsfw.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        restore(); accept(); restore()
        val row = requireNotNull(db.bookmarkDao().getBookmarkById(id))
        db.bookmarkDao().insertOrUpdate(row.copy(note = "External note", updatedAt = 5))
        delay(250); save(); await { navigations() == 1 }
        val saved = requireNotNull(db.bookmarkDao().getBookmarkById(id))
        assertEquals("My unsaved note", saved.note)
        assertTrue(saved.isNsfw)
        assertEquals("Tool", saved.category); assertEquals("Tech", saved.subcategory)
        assertEquals(variant, spelling(id))
    }
    @Test fun noteOnlySaveResolvesLatestExternalSpelling() = verify {
        note("My note")
        db.updateBookmarkDetails(other, "", Tag.fromDisplayValues(null, null, listOf(canonical)), true, mapOf(entitySkeletonKey(canonical) to variant), 9)
        delay(250); save(); await { navigations() == 1 }
        assertEquals(variant, spelling(id))
        assertEquals("My note", requireNotNull(db.bookmarkDao().getBookmarkById(id)).note)
    }

    @Test fun hiddenOnlyLibrarySpellingRequiresAChoice() = verify(false) {
        conflict(); click("Use existing tag"); accept(); save()
        await { navigations() == 1 }; assertEquals(canonical, spelling(id))
    }
    @Test fun pickerShowsStagedSpellingOncePerKey() = verify {
        conflict(); click("Replace across all bookmarks")
        click(host.context.getString(R.string.detail_tag_select_entities))
        node { it.text?.toString() == variant }
        assertNull("Previous library spelling must be overlaid by the pending choice", find { it.text?.toString() == canonical })
        assertEquals(canonical, spelling())
    }

    @Test fun restoredNoteOnlySavePreservesAbsentClassification() = verify {
        note("Restored note"); restore(); save(); await { navigations() == 1 }
        val row = requireNotNull(db.bookmarkDao().getBookmarkById(id))
        assertNull(row.category); assertNull(row.subcategory)
        assertEquals("Restored note", row.note)
    }

}
