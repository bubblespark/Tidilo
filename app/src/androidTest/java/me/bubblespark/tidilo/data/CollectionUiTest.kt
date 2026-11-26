package me.bubblespark.tidilo.data

import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.ui.screens.library.*
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Collection sheets, editor, cards, and the Detail collection flow.
 *
 * The collection sheets read the app database directly, so the tests that mount them seed rows with
 * unique IDs and delete them afterwards; the pure-content tests use no database.
 */
class CollectionUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db by lazy { AppDatabase.getInstance(context) }

    @Composable
    private fun Themed(dark: Boolean = false, content: @Composable () -> Unit) =
        TidiloTheme(darkTheme = dark, dynamicColor = false, blurEnabled = false, content = content)

    private fun hasText(node: SemanticsNode, value: String) =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
    private fun containsText(node: SemanticsNode, value: String): Boolean =
        hasText(node, value) || node.children.any { containsText(it, value) }
    private fun text(value: String) = SemanticsMatcher("text \"$value\"") { hasText(it, value) }
    private fun description(value: String) = SemanticsMatcher("description \"$value\"") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.contains(value) } == true
    }
    private fun editable(value: String) = SemanticsMatcher("editable \"$value\"") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == value && it.config.getOrNull(SemanticsActions.SetText) != null
    }
    private val dismissable = SemanticsMatcher("dismissable") { it.config.getOrNull(SemanticsActions.Dismiss) != null }
    private fun checkboxRow(title: String) = SemanticsMatcher("checkbox row \"$title\"") { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox && node.children.any { hasText(it, title) }
    }
    private fun enabledButton(label: String) = SemanticsMatcher("enabled button \"$label\"") { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            !node.config.contains(SemanticsProperties.Disabled) && containsText(node, label)
    }

    private fun await(matcher: SemanticsMatcher) = composeRule.awaitNode(matcher)
    private fun nodes(matcher: SemanticsMatcher) =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
    private fun none(matcher: SemanticsMatcher) = nodes(matcher).isEmpty()
    private fun clickable(node: SemanticsNode) = generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
    private fun click(matcher: SemanticsMatcher) = composeRule.clickNode(await(matcher))
    private fun longClick(node: SemanticsNode) {
        val target = generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnLongClick) != null }
        composeRule.runOnUiThread { check(target.config[SemanticsActions.OnLongClick].action?.invoke() == true) }
        composeRule.waitForIdle()
    }
    private fun setText(node: SemanticsNode, value: String) {
        composeRule.runOnUiThread { check(node.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true) }
        composeRule.waitForIdle()
    }
    private fun dismissSheet() {
        val node = await(dismissable)
        composeRule.runOnUiThread { check(node.config[SemanticsActions.Dismiss].action?.invoke() == true) }
        composeRule.waitForIdle()
    }
    private fun liveRegionIds(): Set<Int> = nodes(SemanticsMatcher("polite") {
        it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
    }).mapTo(mutableSetOf()) { it.id }
    private fun politeRegionFor(node: SemanticsNode): SemanticsNode =
        generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite }

    /** Polls a database condition at 100ms, letting the rule drive the UI between checks. */
    private suspend fun awaitDb(condition: suspend () -> Boolean) = withTimeout(5_000) {
        while (true) {
            composeRule.waitForIdle()
            if (condition()) break
            delay(100)
        }
    }

    /** Deletes test rows from the app database even when the test was cancelled. */
    private suspend fun cleanUp(collectionTitles: List<String> = emptyList(), collectionIds: List<String> = emptyList(),
        bookmarkIds: List<String> = emptyList(), triggers: List<String> = emptyList()) = withContext(NonCancellable + Dispatchers.IO) {
        triggers.forEach { db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $it") }
        val byTitle = db.collectionDao().observeCollections(true).first()
            .filter { it.collection.title in collectionTitles }.map { it.collection.id }
        db.withTransaction {
            (collectionIds + byTitle).forEach { db.collectionDao().delete(it) }
            bookmarkIds.forEach { db.bookmarkDao().deleteById(it) }
        }
    }

    @Test fun failedInitialMembershipLoadCanClose() = verifyFailedLoadDismissal(false)

    @Test fun closeDuringMembershipLoadExitsImmediately() = verifyFailedLoadDismissal(true)

    private fun verifyFailedLoadDismissal(initiallyLoading: Boolean) {
        val shown = mutableStateOf(true)
        val state = CollectionMembershipState(loading = initiallyLoading, error = if (initiallyLoading) null else "load")
        val closes = AtomicInteger()
        composeRule.setContent { Themed {
            if (shown.value) CollectionMembershipSheet("failed-load", state, {}, {}, { closes.incrementAndGet(); shown.value = false }, {})
        } }
        await(text("Choose collections"))
        composeRule.waitForIdle()
        val dismiss = await(dismissable)
        // Closing never writes memberships, so it need not wait for the initial read.
        composeRule.runOnUiThread { dismiss.config[SemanticsActions.Dismiss].action?.invoke() }
        composeRule.waitUntil(5_000) { !shown.value }
        assertEquals(1, closes.get())
    }

    @Test fun editorChoicesExposeExclusiveRolesAndSelection() {
        val state = mutableStateOf(CollectionEditorState(draft = CollectionDraft("Choices")))
        fun option(label: String) = clickable(nodes(description(label)).first())
        composeRule.setContent { Themed {
            CollectionEditorContent(state.value, false, { state.value = state.value.copy(draft = it) }, {}, {}, {}, {})
        } }
        await(description("Folder"))
        assertEquals(Role.RadioButton, option("Folder").config.getOrNull(SemanticsProperties.Role))
        assertEquals(true, option("Folder").config.getOrNull(SemanticsProperties.Selected))
        assertEquals(Role.RadioButton, option("Color 1").config.getOrNull(SemanticsProperties.Role))
        assertEquals(2, nodes(SemanticsMatcher("group") { it.config.getOrNull(SemanticsProperties.SelectableGroup) == Unit }).size)
        click(description("Work"))
        composeRule.waitUntil(5_000) { option("Work").config.getOrNull(SemanticsProperties.Selected) == true }
        click(description("Color 8"))
        await(SemanticsMatcher("Color 8 selected") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.contains("Color 8") } == true &&
                it.config.getOrNull(SemanticsProperties.Selected) == true
        })
        assertEquals(false, option("Folder").config.getOrNull(SemanticsProperties.Selected))
        assertEquals(false, option("Color 1").config.getOrNull(SemanticsProperties.Selected))
        state.value = state.value.copy(loading = true)
        await(SemanticsMatcher("disabled Color 1") {
            it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.contains("Color 1") } == true &&
                it.config.contains(SemanticsProperties.Disabled)
        })
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test fun collectionFilterExposesRoleAndActiveState() {
        val mode = mutableStateOf(LibraryFilterMode.NEWEST_FIRST)
        composeRule.setContent { Themed {
            val rail = androidx.compose.material3.rememberWideNavigationRailState()
            LaunchedEffect(Unit) { rail.expand() }
            me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail(rail, mode.value, { mode.value = it }, {}, {}, {}, {})
        } }
        val label = await(text("Collection"))
        val button = generateSequence(label) { it.parent }.first { it.config.getOrNull(SemanticsProperties.Selected) != null }
        assertEquals(false, button.config.getOrNull(SemanticsProperties.Selected))
        composeRule.clickNode(button)
        await(SemanticsMatcher("selected Collection") {
            it.config.getOrNull(SemanticsProperties.Selected) == true && it.children.any { child -> hasText(child, "Collection") }
        })
        assertEquals(LibraryFilterMode.COLLECTION, mode.value)
    }

    @Test fun collectionCountPillHandlesEnglishAndRetainsJapanese() {
        val count = mutableIntStateOf(1)
        val japanese = mutableStateOf(false)
        composeRule.setContent {
            val base = androidx.compose.ui.platform.LocalContext.current
            val localized = base.createConfigurationContext(android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply {
                setLocale(if (japanese.value) java.util.Locale.JAPANESE else java.util.Locale.ENGLISH)
            })
            CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides localized,
                androidx.compose.ui.platform.LocalResources provides localized.resources) {
                Themed { CollectionCard(CollectionWithCount(CollectionEntity("count", "Count", "", "folder", null, 0, 1, 1), count.intValue), {}) }
            }
        }
        await(text("1 item"))
        count.intValue = 2
        await(text("2 items"))
        japanese.value = true; count.intValue = 1
        await(text("1件"))
        count.intValue = 0
        await(text("0件"))
    }

    @Test fun loadFailureOffersRetryWithoutSaveAction() {
        val retries = AtomicInteger()
        composeRule.setContent { Themed {
            CollectionEditorContent(CollectionEditorState(draft = CollectionDraft("Draft"), error = "load"),
                true, {}, {}, {}, {}, {}, onRetry = { retries.incrementAndGet() })
        } }
        click(text("Retry"))
        assertTrue("Existing collections autosave; there is no Save action", none(text("Save")))
        assertEquals(1, retries.get())
    }

    @Test fun collectionCardsExposeButtonRoles() {
        composeRule.setContent { Themed { Column {
            CollectionCard(CollectionWithCount(CollectionEntity("role", "Role card", "", "folder", null, 0, 1, 1), 0), {})
            AddCollectionDummyCard({})
        } } }
        for (node in listOf(await(text("Role card")), await(description("New Collection")))) {
            assertEquals(Role.Button, clickable(node).config.getOrNull(SemanticsProperties.Role))
        }
    }

    @Test fun collectionSubtitleUsesSingularAndPluralForms() {
        val resources = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.ENGLISH)
        }).resources
        val id = R.plurals.collection_subtitle_count
        assertEquals("0 collections", resources.getQuantityString(id, 0, 0))
        assertEquals("1 collection", resources.getQuantityString(id, 1, 1))
        assertEquals("2 collections", resources.getQuantityString(id, 2, 2))
    }

    @Test fun membershipTogglesPersistBeforeTheSheetCloses(): Unit = runBlocking {
        val id = UUID.randomUUID().toString()
        val title = "Auto ${id.take(8)}"
        val shown = mutableStateOf(true)
        try {
            db.withTransaction {
                db.collectionDao().insert(CollectionEntity(id, title, "", "folder", null, 0, System.currentTimeMillis(), 1))
                db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Auto assignment", "https://example.com/$id", "", createdAt = 1, updatedAt = 1, isLiked = true))
            }
            composeRule.setContent { Themed { if (shown.value) CollectionMembershipBottomSheet(id, { shown.value = false }, {}) } }
            click(text(title))
            awaitDb { db.collectionDao().observeMembershipIds(id).first() == listOf(id) }
            assertTrue("The toggle alone persists; the sheet stays open", shown.value)
            dismissSheet()
            composeRule.waitUntil(5_000) { !shown.value }
            assertEquals(listOf(id), db.collectionDao().observeMembershipIds(id).first())
            shown.value = true
            click(text(title))
            awaitDb { db.collectionDao().observeMembershipIds(id).first().isEmpty() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            composeRule.waitUntil(5_000) { !shown.value }
            assertTrue(db.collectionDao().observeMembershipIds(id).first().isEmpty())
            assertTrue(requireNotNull(db.bookmarkDao().getBookmarkById(id)).isLiked)
        } finally { cleanUp(collectionIds = listOf(id), bookmarkIds = listOf(id)) }
    }

    @Test fun failedToggleOffersRetryWhileTheSheetStaysOpen(): Unit = runBlocking {
        val id = UUID.randomUUID().toString()
        val trigger = "fail_collection_close_${id.replace("-", "")}"
        val title = "Retry ${id.take(8)}"
        val shown = mutableStateOf(true)
        try {
            db.withTransaction {
                db.collectionDao().insert(CollectionEntity(id, title, "", "folder", null, 0, System.currentTimeMillis(), 1))
                db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Retry assignment", "https://example.com/$id", "", createdAt = 1, updatedAt = 1))
            }
            composeRule.setContent { Themed {
                if (shown.value) CollectionMembershipBottomSheet(
                    id, { shown.value = false }, {}, modifier = Modifier.testTag("caller-membership-sheet"), sheetInstanceId = 7)
            } }
            val sheet = await(SemanticsMatcher("sheet 7") { it.config.getOrNull(CollectionMembershipSheetInstanceId) == 7 })
            assertEquals("caller-membership-sheet", sheet.config.getOrNull(SemanticsProperties.TestTag))
            val existingRegions = liveRegionIds()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON bookmark_collection_cross_ref WHEN NEW.bookmarkId = '$id' BEGIN SELECT RAISE(ABORT,'injected close failure'); END")
            click(text(title))
            val error = await(text("Could not save your changes. Please try again."))
            assertTrue("The membership error must update an existing live region", politeRegionFor(error).id in existingRegions)
            assertTrue("A failed toggle keeps the sheet visible", shown.value)
            assertTrue(db.collectionDao().observeMembershipIds(id).first().isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")
            click(text("Retry"))
            awaitDb { db.collectionDao().observeMembershipIds(id).first() == listOf(id) }
            assertTrue(shown.value)
        } finally { cleanUp(collectionIds = listOf(id), bookmarkIds = listOf(id), triggers = listOf(trigger)) }
    }

    @Test fun saveFailureRetryUsesTheRetryPath() {
        val retries = AtomicInteger()
        val dismissals = AtomicInteger()
        composeRule.setContent { Themed {
            CollectionMembershipContent(CollectionMembershipState(loading = false, error = "save"), {},
                { retries.incrementAndGet() }, { dismissals.incrementAndGet() }, {}, onClosed = {})
        } }
        click(text("Retry"))
        assertEquals(1, retries.get())
        assertEquals(0, dismissals.get())
    }

    @Test fun blankCollectionNameErrorIsAPoliteLiveRegion() {
        val originalTitle = "Saved title"
        val originalDescription = "Saved description"
        val draft = mutableStateOf(CollectionDraft(originalTitle, description = originalDescription))
        val editing = mutableStateOf(false)
        val visibleEditable = { value: String -> SemanticsMatcher("visible editable \"$value\"") {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == value && it.boundsInWindow.height > 0f
        } }

        fun titleToDescriptionOffset(headerResource: Int): Float {
            await(text(context.getString(headerResource)))
            composeRule.waitForIdle()
            return await(visibleEditable(originalDescription)).boundsInWindow.top - await(visibleEditable(originalTitle)).boundsInWindow.top
        }

        composeRule.setContent { Themed {
            CollectionEditorContent(
                CollectionEditorState(draft = draft.value),
                editing = editing.value,
                onEdit = { draft.value = it },
                onSave = {},
                onConfirmDelete = {},
                onDelete = {},
                onDismiss = {},
                autosaveStatus = if (draft.value.title.isBlank()) AutosaveStatus.Invalid else AutosaveStatus.Idle
            )
        } }
        val noSupportingSlotOffset = titleToDescriptionOffset(R.string.collection_create_title)
        editing.value = true
        val validTitleOffset = titleToDescriptionOffset(R.string.collection_edit_title)
        assertEquals("A valid edit title must keep the no-supporting-text field spacing",
            noSupportingSlotOffset, validTitleOffset, 1f)
        val label = context.getString(R.string.collection_name_label)
        val message = context.getString(R.string.collection_title_required)
        val field = await(SemanticsMatcher("title field") { it.config.getOrNull(SemanticsProperties.EditableText)?.text == originalTitle })
        val existingRegions = nodes(SemanticsMatcher("visible polite") {
            it.boundsInWindow.height > 0f && it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
        }).mapTo(mutableSetOf()) { it.id }
        assertTrue(none(text(message)))
        setText(field, "")
        val announcement = await(SemanticsMatcher("announced error") {
            hasText(it, message) && it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
        })
        val supportingText = await(SemanticsMatcher("supporting error") {
            hasText(it, message) && it.config.getOrNull(SemanticsProperties.LiveRegion) == null
        })
        assertTrue("The name error must update an existing live region", announcement.id in existingRegions)
        val fieldWithError = generateSequence(supportingText) { it.parent }.firstOrNull { node ->
            containsText(node, label) && node.config.getOrNull(SemanticsProperties.Error) == message
        }
        assertNotNull("The supporting message must belong to the name field in its error state", fieldWithError)
    }

    @Test fun onlyCreateHasHeaderActionAndSheetHeadersStayFixed() {
        val variant = mutableIntStateOf(0)
        val scale = mutableFloatStateOf(1f)
        val dark = mutableStateOf(false)
        val rows = (1..20).map { CollectionWithCount(CollectionEntity("$it", "Collection $it", "", "folder", null, 0, 1, 1), 0) }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.floatValue)) {
                Themed(dark.value) {
                    key(variant.intValue) {
                        if (variant.intValue == 0) CollectionEditorContent(
                            CollectionEditorState(draft = CollectionDraft("Draft")), false, {}, {}, {}, {}, {})
                        else CollectionMembershipContent(CollectionMembershipState(rows = rows, loading = false), {}, {}, {}, {}, onClosed = {})
                    }
                }
            }
        }
        // (sheet, dark, font scale): each sheet once per theme and once per scale.
        for ((sheet, night, font) in listOf(Triple(0, false, 1f), Triple(0, true, 2f), Triple(1, false, 2f), Triple(1, true, 1f))) {
            variant.intValue = sheet; scale.floatValue = font; dark.value = night
            val title = await(text(if (sheet == 0) "New Collection" else "Choose collections"))
            val save = if (sheet == 0) await(text("Create")) else null
            composeRule.waitForIdle()
            val beside = if (save != null) save.boundsInWindow.left >= title.boundsInWindow.right &&
                kotlin.math.abs(save.boundsInWindow.center.y - title.boundsInWindow.center.y) < 3f
                else none(text("Save")) && none(text("Create"))
            assertTrue("Only the create editor has a title-right action ($sheet, $night, $font)", beside)
            assertTrue("Sheet must not contain Cancel", none(text("Cancel")))
            val before = (save ?: title).boundsInWindow.top
            val scroll = await(SemanticsMatcher("scrollable") { it.config.getOrNull(SemanticsActions.ScrollBy) != null })
            composeRule.runOnUiThread { scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 2000f) }
            composeRule.waitForIdle()
            assertEquals("Header action must remain reachable while scrolling", before, (save ?: title).boundsInWindow.top, 1f)
        }
    }

    @Test fun toolbarCollectionSelectionTracksStoredMembership(): Unit = runBlocking {
        val id = UUID.randomUUID().toString()
        val dark = mutableStateOf(false)
        val caches = OfflineVisualCaches()
        try {
            db.withTransaction {
                db.collectionDao().insert(CollectionEntity(id, "Membership", "", "folder", null, 0, 1, 1))
                db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Membership toolbar", "https://example.com/$id", "", createdAt = 1, updatedAt = 1))
            }
            val model = ViewModelProvider(composeRule.activity)[LibraryViewModel::class.java]
            composeRule.setContent { Themed(dark.value) { BookmarkDetailScreen(id, model, {}) } }
            fun selected(): Boolean = generateSequence(await(description("Choose collections"))) { it.parent }
                .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Selected) } ?: false
            assertFalse(selected())
            db.collectionDao().insertMembership(BookmarkCollectionCrossRef(id, id))
            composeRule.waitUntil(5_000) { selected() }
            dark.value = true
            composeRule.waitForIdle()
            assertTrue("Selection survives the theme change", selected())
            db.collectionDao().delete(id)
            composeRule.waitUntil(5_000) { !selected() }
        } finally {
            caches.close()
            cleanUp(collectionIds = listOf(id), bookmarkIds = listOf(id))
        }
    }

    @Test fun createCollectionFromDetailAssignsAndReopensSelectionChecked(): Unit = runBlocking {
        val bookmarkId = UUID.randomUUID().toString()
        val existingAId = UUID.randomUUID().toString()
        val existingDupId = UUID.randomUUID().toString()
        val existingATitle = "Existing ${existingAId.take(6)}"
        val duplicateTitle = "Shared ${existingDupId.take(6)}"
        val cancelledTitle = "Cancelled ${bookmarkId.take(6)}"
        val backs = AtomicInteger()
        val caches = OfflineVisualCaches()
        try {
            db.withTransaction {
                db.bookmarkDao().insertOrUpdate(BookmarkEntity(bookmarkId, "Detail collection flow", "https://example.com/$bookmarkId", "",
                    createdAt = 1, updatedAt = 1, note = "Initial note"))
                db.collectionDao().insert(CollectionEntity(existingAId, existingATitle, "", "folder", null, 0, 1, 1))
                db.collectionDao().insert(CollectionEntity(existingDupId, duplicateTitle, "", "folder", null, 1, 2, 2))
            }
            val model = ViewModelProvider(composeRule.activity)[LibraryViewModel::class.java]
            composeRule.setContent { Themed {
                BookmarkDetailScreen(bookmarkId = bookmarkId, viewModel = model, onNavigateBack = { backs.incrementAndGet() })
            } }
            // Edit the note before opening the collection sheet.
            setText(await(editable("Initial note")), "Unsaved note")

            click(description("Choose collections"))
            await(text("Choose collections"))
            await(text("New collection"))
            click(text(existingATitle))
            awaitDb { db.collectionDao().observeMembershipIds(bookmarkId).first() == listOf(existingAId) }

            // New collection switches sheets sequentially.
            click(text("New collection"))
            await(text("New Collection"))
            assertTrue("Selection sheet must close before creation sheet is shown", none(text("Choose collections")))

            // Cancelling creation returns to the selection sheet.
            setText(await(editable("") and inWindowShowing("New Collection")), cancelledTitle)
            dismissSheet()
            await(text("Choose collections"))
            assertTrue("Creation sheet must close before selection sheet reopens", none(text("New Collection")))
            assertEquals("Cancelling creation must keep existing memberships intact", listOf(existingAId),
                db.collectionDao().observeMembershipIds(bookmarkId).first())
            assertTrue("Cancelled draft must not be created",
                db.collectionDao().observeCollections(true).first().none { it.collection.title == cancelledTitle })

            // Create a collection whose title duplicates an existing one.
            click(text("New collection"))
            await(text("New Collection"))
            setText(await(editable("") and inWindowShowing("New Collection")), duplicateTitle)
            await(editable(duplicateTitle))
            click(text("Create"))

            // The reopened selection has the new collection checked and the existing duplicate unchecked.
            await(text("Choose collections"))
            var allMatching = emptyList<CollectionWithCount>()
            awaitDb {
                allMatching = db.collectionDao().observeCollections(true).first().filter { it.collection.title == duplicateTitle }
                allMatching.size == 2
            }
            val newlyCreatedId = allMatching.map { it.collection.id }.first { it != existingDupId }
            val memberships = db.collectionDao().observeMembershipIds(bookmarkId).first().toSet()
            assertEquals(setOf(existingAId, newlyCreatedId), memberships)
            assertFalse("Pre-existing collection with duplicate title must not be checked", existingDupId in memberships)
            composeRule.waitUntil(5_000) {
                nodes(checkboxRow(duplicateTitle)).count { it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On } == 1
            }
            awaitDb { requireNotNull(db.bookmarkDao().getBookmarkById(bookmarkId)).note == "Unsaved note" }
            assertEquals(0, backs.get())
        } finally {
            caches.close()
            cleanUp(collectionTitles = listOf(duplicateTitle, cancelledTitle), collectionIds = listOf(existingAId, existingDupId),
                bookmarkIds = listOf(bookmarkId))
        }
    }

    @Test fun createFailureInEditorShowsInlineRetryAndRecoversWithoutDuplicate(): Unit = runBlocking {
        val bookmarkId = UUID.randomUUID().toString()
        val title = "RetryCreate ${bookmarkId.take(6)}"
        val trigger = "fail_detail_create_${bookmarkId.replace("-", "")}"
        val createdIds = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(bookmarkId, "Retry create bookmark", "https://example.com/$bookmarkId", "", createdAt = 1, updatedAt = 1))
            composeRule.setContent { Themed {
                CollectionEditorBottomSheet(collectionId = null, assignToBookmarkId = bookmarkId, onDismiss = {}, onCreated = { createdIds += it })
            } }
            setText(await(editable("")), title)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER $trigger BEFORE INSERT ON bookmark_collection_cross_ref WHEN NEW.bookmarkId = '$bookmarkId' BEGIN SELECT RAISE(ABORT,'injected create assign failure'); END")
            click(text("Create"))
            // The error and inline retry are visible without a manual scroll.
            val error = await(SemanticsMatcher("visible error") {
                hasText(it, "Could not save your changes. Please try again.") && it.boundsInWindow.height > 0f
            })
            assertEquals(LiveRegionMode.Polite, error.config.getOrNull(SemanticsProperties.LiveRegion))
            assertTrue("Rollback must prevent orphan collection", db.collectionDao().observeCollections(true).first().none { it.collection.title == title })
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")
            click(SemanticsMatcher("visible Retry") { hasText(it, "Retry") && it.boundsInWindow.height > 0f })
            composeRule.waitUntil(5_000) { createdIds.isNotEmpty() }
            assertEquals(1, createdIds.size)
            assertEquals(listOf(createdIds.single()), db.collectionDao().observeMembershipIds(bookmarkId).first())
        } finally { cleanUp(collectionTitles = listOf(title), bookmarkIds = listOf(bookmarkId), triggers = listOf(trigger)) }
    }

    @Test fun membershipSheetShowsDisabledNewCollectionWhileLoadingAndLoadError() {
        val state = mutableStateOf(CollectionMembershipState(loading = true))
        val clicks = AtomicInteger()
        composeRule.setContent { Themed {
            CollectionMembershipSheet(saveKey = "disabled-new-collection", state = state.value, onToggle = {}, onRetry = {},
                onDismiss = {}, onNewCollection = { clicks.incrementAndGet() })
        } }
        assertTrue("Loading disables New collection", clickable(await(text("New collection"))).config.contains(SemanticsProperties.Disabled))
        state.value = CollectionMembershipState(loading = false, error = "load")
        await(text("Could not load this collection. Please try again."))
        assertTrue("A load error disables New collection", clickable(await(text("New collection"))).config.contains(SemanticsProperties.Disabled))
        assertEquals(0, clicks.get())
    }

    private enum class FlowStage { EMPTY, MISSING, DETAIL }

    @Test fun detailCollectionFlowScrollFocusBackCancelMissingBookmarkAndEmptyState(): Unit = runBlocking {
        val bookmarkId = UUID.randomUUID().toString()
        val missingTargetBookmarkId = UUID.randomUUID().toString()
        val baseTime = System.currentTimeMillis() - 100_000L
        val seededIds = (1..25).map { UUID.randomUUID().toString() }
        val lastSeeded = "ScrollItem 25 ${seededIds.last().take(4)}"
        val missingTitle = "MissingTarget ${missingTargetBookmarkId.take(6)}"
        val newTitle = "TopFocused ${bookmarkId.take(6)}"
        val stage = mutableStateOf(FlowStage.EMPTY)
        val emptyClicks = AtomicInteger()
        val caches = OfflineVisualCaches()
        val selectionList = SemanticsMatcher("scrollable selection list") {
            it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
        } and inWindowShowing("Choose collections")
        fun scrollOffset() = requireNotNull(await(selectionList).config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)).value()
        fun newCollectionDisabled() = nodes(text("New collection")).firstOrNull()
            ?.let { clickable(it).config.contains(SemanticsProperties.Disabled) }
        try {
            db.withTransaction {
                seededIds.forEachIndexed { i, id ->
                    val index = i + 1
                    db.collectionDao().insert(CollectionEntity(id, "ScrollItem $index ${id.take(4)}", "", "folder", null, index % 8, baseTime + index, baseTime + index))
                }
                db.bookmarkDao().insertOrUpdate(BookmarkEntity(bookmarkId, "Scroll focus flow", "https://example.com/$bookmarkId", "", createdAt = 1, updatedAt = 1))
            }
            instrumentation.setInTouchMode(true)
            val model = ViewModelProvider(composeRule.activity)[LibraryViewModel::class.java]
            composeRule.setContent { Themed {
                when (stage.value) {
                    FlowStage.EMPTY -> CollectionMembershipContent(state = CollectionMembershipState(rows = emptyList(), loading = false),
                        onToggle = {}, onRetry = {}, onDismiss = {}, onNewCollection = { emptyClicks.incrementAndGet() }, onClosed = {})
                    FlowStage.MISSING -> CollectionEditorBottomSheet(collectionId = null, assignToBookmarkId = missingTargetBookmarkId, onDismiss = {})
                    FlowStage.DETAIL -> BookmarkDetailScreen(bookmarkId = bookmarkId, viewModel = model, onNavigateBack = {})
                }
            } }

            // 1. Empty state explanation and New collection button.
            await(text("Create a collection to organize this bookmark."))
            click(text("New collection"))
            assertEquals(1, emptyClicks.get())

            // 2. A missing bookmark disables fields and Create, omits Retry, and creates no orphan collection.
            stage.value = FlowStage.MISSING
            setText(await(editable("")), missingTitle)
            click(text("Create"))
            await(text("This bookmark is no longer available."))
            assertTrue("Create must be disabled on missing_bookmark", clickable(await(text("Create"))).config.contains(SemanticsProperties.Disabled))
            val editableNodes = nodes(SemanticsMatcher("editable") { it.config.getOrNull(SemanticsProperties.EditableText) != null })
            assertTrue("All text fields must be disabled on missing_bookmark", editableNodes.isNotEmpty() && editableNodes.all {
                it.config.contains(SemanticsProperties.Disabled) && it.config.getOrNull(SemanticsActions.SetText) == null
            })
            val optionNodes = nodes(SemanticsMatcher("radio") { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton })
            assertTrue("Icon/color choices must be disabled on missing_bookmark",
                optionNodes.isNotEmpty() && optionNodes.all { it.config.contains(SemanticsProperties.Disabled) })
            assertTrue("Retry must not appear on missing_bookmark", none(text("Retry")))
            assertTrue(db.collectionDao().observeCollections(true).first().none { it.collection.title == missingTitle })

            // 3. Multi-item list: scroll preservation on cancel, Back during OPENING_CREATOR, and focus on create return.
            stage.value = FlowStage.DETAIL
            click(description("Choose collections"))
            await(text("Choose collections"))
            await(text(lastSeeded))
            composeRule.waitForIdle()
            val scrollContainer = await(selectionList)
            composeRule.runOnUiThread { check(scrollContainer.config[SemanticsActions.ScrollBy].action?.invoke(0f, 1200f) == true) }
            composeRule.waitForIdle()
            val scrolledOffset = scrollOffset()
            assertTrue("Selection list should have scrolled down: $scrolledOffset", scrolledOffset > 0f)

            // Back during OPENING_CREATOR cancels the transition back to a visible selection, frame by frame.
            composeRule.mainClock.autoAdvance = false
            try {
                val newCollection = clickable(await(enabledButton("New collection")))
                composeRule.runOnUiThread { check(newCollection.config[SemanticsActions.OnClick].action?.invoke() == true) }
                composeRule.waitUntil(5_000) {
                    assertTrue("New Collection must not compose before cancellation", none(text("New Collection")))
                    val closingStarted = newCollectionDisabled() == true
                    if (!closingStarted) composeRule.mainClock.advanceTimeByFrame()
                    closingStarted
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                composeRule.waitUntil(5_000) {
                    assertTrue("New Collection must never compose when OPENING_CREATOR is cancelled", none(text("New Collection")))
                    val headingVisible = nodes(text("Choose collections")).any { it.boundsInWindow.height > 0f }
                    val reopened = headingVisible && newCollectionDisabled() == false
                    if (!reopened) composeRule.mainClock.advanceTimeByFrame()
                    reopened
                }
            } finally { composeRule.mainClock.autoAdvance = true }

            // Opening the creator and cancelling it preserves the previous scroll offset.
            click(enabledButton("New collection"))
            await(text("New Collection"))
            dismissSheet()
            await(text("Choose collections"))
            await(text(lastSeeded))
            composeRule.waitForIdle()
            assertEquals("Scroll position must be preserved on cancel return", scrolledOffset, scrollOffset(), 4f)

            // Creating while scrolled down scrolls the new row into view and focuses it on return.
            click(enabledButton("New collection"))
            await(text("New Collection"))
            setText(await(editable("") and inWindowShowing("New Collection")), newTitle)
            // Keyboard input mode lets the toggleable FocusTargetNode accept the focus request.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            click(text("Create"))
            await(text("Choose collections"))
            await(checkboxRow(newTitle))
            composeRule.waitUntil(5_000) {
                nodes(checkboxRow(newTitle)).firstOrNull()?.let { row ->
                    row.boundsInWindow.height > 0f && row.boundsInWindow.top >= 0f &&
                        row.config.getOrNull(SemanticsProperties.Focused) == true
                } == true
            }
            // Space activates the focused toggleable directly.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
            composeRule.waitUntil(5_000) {
                nodes(checkboxRow(newTitle)).firstOrNull()?.config?.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off
            }
        } finally {
            instrumentation.setInTouchMode(true)
            caches.close()
            cleanUp(collectionTitles = listOf(newTitle, missingTitle), collectionIds = seededIds, bookmarkIds = listOf(bookmarkId))
        }
    }

    @Test fun pendingEditorCannotBeDismissed() {
        val dismissals = AtomicInteger()
        composeRule.setContent { Themed {
            CollectionEditorContent(CollectionEditorState(draft = CollectionDraft("Pending"), pending = true),
                false, {}, {}, {}, {}, { dismissals.incrementAndGet() })
        } }
        val dismiss = await(dismissable)
        composeRule.runOnUiThread { dismiss.config[SemanticsActions.Dismiss].action?.invoke() }
        // The rule finishes any hide animation, so an accepted dismissal would already be counted.
        composeRule.waitForIdle()
        assertEquals(0, dismissals.get())
    }

    @Test fun prototypeCardsAndEditorRemainUsableWithLargeText(): Unit = runBlocking {
        val id = UUID.randomUUID().toString()
        val title = "Collection UI ${id.take(8)}"
        val edited = "Edited ${id.take(8)}"
        val created = "Created ${id.take(8)}"
        val row = CollectionEntity(id, title, "Two lines of description with a long label for layout verification.", "palette", "🎨", 4, 1, 1)
        val show = mutableStateOf(false)
        val editing = mutableStateOf<String?>(null)
        val dark = mutableStateOf(false)
        val scale = mutableFloatStateOf(1f)
        val scrollable = SemanticsMatcher("scrollable") { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
        try {
            db.collectionDao().insert(row)
            composeRule.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.floatValue)) {
                    Themed(dark.value) {
                        Column(Modifier.padding(20.dp)) {
                            CollectionCard(CollectionWithCount(row, 12), onClick = {}, onLongClick = { editing.value = id; show.value = true })
                            AddCollectionDummyCard(onClick = { editing.value = null; show.value = true })
                        }
                        if (show.value) CollectionEditorBottomSheet(editing.value, onDismiss = { show.value = false })
                    }
                }
            }
            for (night in listOf(false, true)) for (font in listOf(1f, 2f)) {
                dark.value = night; scale.floatValue = font
                composeRule.waitForIdle()
                assertTrue("Card must lay out at dark=$night font=$font", await(text(title)).boundsInRoot.height > 0)
            }
            longClick(await(text(title)))
            await(text("Edit Collection"))
            val field = await(editable(title))
            composeRule.clickNode(field)
            setText(field, edited)
            val scroll = await(scrollable and inWindowShowing("Edit Collection"))
            composeRule.runOnUiThread { check(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 5000f) == true) }
            // Existing collections autosave; there is no Save action to reach.
            awaitDb { db.collectionDao().getById(id)?.title == edited }
            assertTrue(none(text("Save")))
            dismissSheet()
            composeRule.waitUntil(5_000) { !show.value }
            click(description("New Collection"))
            await(text("New Collection"))
            setText(await(editable("") and inWindowShowing("New Collection")), created)
            val createScroll = await(scrollable and inWindowShowing("New Collection"))
            composeRule.runOnUiThread { check(createScroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 5000f) == true) }
            click(text("Create"))
            awaitDb { db.collectionDao().observeCollections(true).first().any { it.collection.title == created } }
        } finally { cleanUp(collectionTitles = listOf(created), collectionIds = listOf(id)) }
    }

    @Test fun membershipRowSingleFocusStopDisabledSkipsFocusAndSpaceEnterToggles() {
        val toggled = java.util.Collections.synchronizedList(mutableListOf<String>())
        val state = CollectionMembershipState(
            loading = false,
            rows = listOf(
                CollectionWithCount(CollectionEntity("a", "Row Alpha", "", "folder", null, 0, 1, 1), 0),
                CollectionWithCount(CollectionEntity("b", "Row Beta Disabled", "", "folder", null, 1, 2, 2), 0),
                CollectionWithCount(CollectionEntity("c", "Row Gamma", "", "folder", null, 2, 3, 3), 0)
            ),
            selectedIds = setOf("a"),
            pendingIds = setOf("b")
        )
        fun focusedTitle(): String? = nodes(SemanticsMatcher("focused checkbox") {
            it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox && it.config.getOrNull(SemanticsProperties.Focused) == true
        }).firstOrNull()?.let { node -> listOf("Row Alpha", "Row Beta Disabled", "Row Gamma").firstOrNull { label -> containsText(node, label) } }
        try {
            composeRule.setContent { Themed {
                CollectionMembershipContent(state = state, onToggle = { toggled += it }, onRetry = {}, onDismiss = {},
                    onNewCollection = {}, onClosed = {})
            } }
            await(SemanticsMatcher("visible Row Gamma") { hasText(it, "Row Gamma") && it.boundsInWindow.height > 0f })
            // Enter keyboard mode and move initial focus onto Row Alpha's single toggleable FocusTargetNode.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            val alpha = generateSequence(await(checkboxRow("Row Alpha"))) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.RequestFocus) != null }
            composeRule.runOnUiThread { check(alpha.config[SemanticsActions.RequestFocus].action?.invoke() == true) }
            composeRule.waitUntil(5_000) { focusedTitle() == "Row Alpha" }
            // Space toggles Row Alpha directly (proving focusRequester/Tab landed on .toggleable()).
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
            composeRule.waitUntil(5_000) { "a" in toggled }
            // One Tab skips any extra stop on Row Alpha and the disabled Row Beta, landing on Row Gamma.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            composeRule.waitUntil(5_000) {
                val title = focusedTitle()
                assertNotEquals("Disabled row must not receive focus", "Row Beta Disabled", title)
                title == "Row Gamma"
            }
            // Enter toggles Row Gamma directly.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            composeRule.waitUntil(5_000) { "c" in toggled }
            assertEquals(listOf("a", "c"), toggled.toList())
        } finally { instrumentation.setInTouchMode(true) }
    }

    @Test fun detailSequentialCreateSurvivesLifecycleRestartToCreatedAndBackToResumed(): Unit = runBlocking {
        val bookmarkId = UUID.randomUUID().toString()
        val draftTitle = "LifecycleDraft ${bookmarkId.take(6)}"
        val now = System.currentTimeMillis()
        val caches = OfflineVisualCaches()
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(bookmarkId, "Lifecycle Bookmark", "https://example.com/$bookmarkId", "", createdAt = now, updatedAt = now))
            val model = ViewModelProvider(composeRule.activity)[LibraryViewModel::class.java]
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = registry
            }
            composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            composeRule.setContent { Themed {
                CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                    BookmarkDetailScreen(bookmarkId = bookmarkId, viewModel = model, onNavigateBack = {})
                }
            } }
            click(description("Choose collections"))
            await(text("Choose collections"))
            click(text("New collection"))
            await(text("New Collection"))
            setText(await(editable("") and inWindowShowing("New Collection")), draftTitle)

            // Dropping to CREATED cancels the bookmarkDetails flow; RESUMED re-emits a loading state via onStart.
            composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
            composeRule.waitForIdle()
            composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            composeRule.waitForIdle()

            await(text("New Collection"))
            await(editable(draftTitle))
            click(text("Create"))
            await(text("Choose collections"))
            await(checkboxRow(draftTitle) and SemanticsMatcher("checked") {
                it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
            })

            // A bookmark deleted while stopped still resumes cleanly to detail_item_not_found.
            composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
            db.bookmarkDao().deleteById(bookmarkId)
            composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            await(text("Item not found"))
        } finally {
            caches.close()
            cleanUp(collectionTitles = listOf(draftTitle), bookmarkIds = listOf(bookmarkId))
        }
    }

    /*
     * The restoration test below drives its own Recomposer and frame clock. It never calls rule
     * finders or waits: the rule's idle check counts every running Recomposer and would stall on it.
     */
    private fun windowNodes(): List<SemanticsNode> {
        fun roots(view: View): List<ViewRootForTest> = when (view) {
            is ViewRootForTest -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
            else -> emptyList()
        }
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        return WindowInspector.getGlobalWindowViews().flatMap(::roots).flatMap { visit(it.semanticsOwner.unmergedRootSemanticsNode) }
    }

    /** Suspends until the next display frame, posting the callback on the UI thread. */
    private suspend fun awaitUiFrame() = suspendCancellableCoroutine<Unit> { cont ->
        instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { if (cont.isActive) cont.resumeWith(Result.success(Unit)) } }
    }

    @Test fun detailSheetTransitionalRestorationsMountTargetSheetFirst(): Unit = runBlocking {
        val bookmarkId = UUID.randomUUID().toString()
        val existingCollectionId = UUID.randomUUID().toString()
        val existingTitle = "RestoredTarget ${bookmarkId.take(6)}"
        val now = System.currentTimeMillis()

        suspend fun verifyFirstFrameCase(
            label: String,
            savedPhase: DetailCollectionSheetPhase,
            savedCancelTransition: Boolean,
            savedInstanceId: Int,
            savedFocusedId: String?,
            expectedSheetTitle: String,
            expectedInstanceIdAfterSettle: Int
        ) {
            val scenario = ActivityScenario.launch(ComponentActivity::class.java)
            val frameClock = BroadcastFrameClock()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
            val recomposer = Recomposer(scope.coroutineContext)
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            var frameTime = 0L

            suspend fun advanceUntil(condition: () -> Boolean) = withTimeout(5_000) {
                var reached = false
                while (!reached) {
                    instrumentation.runOnMainSync {
                        reached = condition()
                        if (!reached && frameClock.hasAwaiters) {
                            frameTime += 16_000_000
                            frameClock.sendFrame(frameTime)
                        }
                    }
                    if (!reached) awaitUiFrame()
                }
            }

            suspend fun awaitWindow(predicate: (SemanticsNode) -> Boolean) = withTimeout(5_000) {
                var found = false
                while (!found) {
                    instrumentation.runOnMainSync { found = windowNodes().any(predicate) }
                    if (!found) awaitUiFrame()
                }
            }

            try {
                // Seed and restore through ONE positional rememberSaveable site toggled off/on via showContent
                // so compositeKeyHash matches identically without custom key overrides.
                val sourceRegistry = androidx.compose.runtime.saveable.SaveableStateRegistry(null) { true }
                val restoredRegistryState = mutableStateOf<androidx.compose.runtime.saveable.SaveableStateRegistry?>(null)
                val showContent = mutableStateOf(true)
                var contentPresent = false
                var sourceComposed = false
                lateinit var restoredState: DetailCollectionSheetState
                var restoredHostComposed = false
                var settledHostComposed = false
                var phaseRestoredBySaver: DetailCollectionSheetPhase? = null
                val creatorTitle = "New Collection"
                val selectorTitle = "Choose collections"

                fun mountedSheetTitles() = windowNodes().mapNotNull { node ->
                    when {
                        hasText(node, creatorTitle) -> creatorTitle
                        hasText(node, selectorTitle) -> selectorTitle
                        else -> null
                    }
                }

                fun mountedSelectorInstanceIds() = windowNodes().mapNotNull { it.config.getOrNull(CollectionMembershipSheetInstanceId) }

                scenario.onActivity { activity ->
                    activity.setContent(parent = recomposer) {
                        Themed {
                            if (showContent.value) {
                                val restored = restoredRegistryState.value
                                val activeRegistry = restored ?: sourceRegistry
                                DisposableEffect(Unit) {
                                    contentPresent = true
                                    onDispose { contentPresent = false }
                                }
                                CompositionLocalProvider(androidx.compose.runtime.saveable.LocalSaveableStateRegistry provides activeRegistry) {
                                    val sheetState = androidx.compose.runtime.saveable.rememberSaveable(
                                        bookmarkId,
                                        saver = DetailCollectionSheetState.Saver
                                    ) {
                                        if (restored == null) {
                                            DetailCollectionSheetState(
                                                phase = savedPhase,
                                                membershipSheetInstanceId = savedInstanceId,
                                                focusedCollectionId = savedFocusedId,
                                                cancelCreatorTransition = savedCancelTransition
                                            )
                                        } else {
                                            DetailCollectionSheetState()
                                        }
                                    }
                                    if (restored == null) {
                                        SideEffect { sourceComposed = true }
                                    } else {
                                        val settled = sheetState.restoredPhaseSettled
                                        val phase = sheetState.phase
                                        DetailCollectionSheetHost(bookmarkId = bookmarkId, sheetState = sheetState)
                                        SideEffect {
                                            if (!restoredHostComposed) phaseRestoredBySaver = phase
                                            restoredState = sheetState
                                            restoredHostComposed = true
                                            settledHostComposed = settled
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                advanceUntil { sourceComposed }
                lateinit var savedBundle: Map<String, List<Any?>>
                instrumentation.runOnMainSync {
                    savedBundle = sourceRegistry.performSave()
                    showContent.value = false
                }
                advanceUntil { !contentPresent }

                val restoredRegistry = androidx.compose.runtime.saveable.SaveableStateRegistry(savedBundle) { true }
                instrumentation.runOnMainSync {
                    restoredRegistryState.value = restoredRegistry
                    showContent.value = true
                }
                advanceUntil { restoredHostComposed }
                // Let the dialog window attach without advancing the host to its settled composition.
                awaitWindow { hasText(it, creatorTitle) || hasText(it, selectorTitle) }
                instrumentation.runOnMainSync {
                    assertFalse("$label: settlement recomposed before the first sheet check", settledHostComposed)
                    assertEquals("$label: the Saver must restore the saved phase", savedPhase, phaseRestoredBySaver)
                    assertEquals("$label: only the target sheet must mount in the first restored composition",
                        listOf(expectedSheetTitle), mountedSheetTitles())
                    if (expectedSheetTitle == selectorTitle) {
                        assertEquals("$label: the first selector must use the settled instance key",
                            listOf(expectedInstanceIdAfterSettle), mountedSelectorInstanceIds())
                    }
                }

                advanceUntil { settledHostComposed }
                awaitWindow { hasText(it, expectedSheetTitle) }
                instrumentation.runOnMainSync {
                    assertEquals("$label: only the target sheet remains after settlement", listOf(expectedSheetTitle), mountedSheetTitles())
                    assertEquals("$label: instanceId must not churn after settle", expectedInstanceIdAfterSettle, restoredState.membershipSheetInstanceId)
                    assertFalse("$label: cancelCreatorTransition must clear after settle", restoredState.cancelCreatorTransition)
                }
            } finally {
                scenario.close()
                recomposer.cancel()
                scope.cancel()
            }
        }

        try {
            db.withTransaction {
                db.bookmarkDao().insertOrUpdate(BookmarkEntity(bookmarkId, "First Frame Bookmark", "https://example.com/$bookmarkId", "", createdAt = now, updatedAt = now))
                db.collectionDao().insert(CollectionEntity(existingCollectionId, existingTitle, "", "folder", null, 0, now, now))
            }
            verifyFirstFrameCase(
                label = "OPENING_CREATOR(cancel=false)",
                savedPhase = DetailCollectionSheetPhase.OPENING_CREATOR,
                savedCancelTransition = false,
                savedInstanceId = 2,
                savedFocusedId = null,
                expectedSheetTitle = "New Collection",
                expectedInstanceIdAfterSettle = 2
            )
            verifyFirstFrameCase(
                label = "OPENING_CREATOR(cancel=true)",
                savedPhase = DetailCollectionSheetPhase.OPENING_CREATOR,
                savedCancelTransition = true,
                savedInstanceId = 3,
                savedFocusedId = null,
                expectedSheetTitle = "Choose collections",
                expectedInstanceIdAfterSettle = 4
            )
            verifyFirstFrameCase(
                label = "RETURNING_TO_SELECTION",
                savedPhase = DetailCollectionSheetPhase.RETURNING_TO_SELECTION,
                savedCancelTransition = false,
                savedInstanceId = 5,
                savedFocusedId = existingCollectionId,
                expectedSheetTitle = "Choose collections",
                expectedInstanceIdAfterSettle = 5
            )
        } finally { cleanUp(collectionIds = listOf(existingCollectionId), bookmarkIds = listOf(bookmarkId)) }
    }
}
