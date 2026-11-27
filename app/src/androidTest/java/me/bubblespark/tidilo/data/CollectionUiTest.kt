package me.bubblespark.tidilo.data

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.ui.screens.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Collection sheets, editor content, cards, and the Library rail, mostly without a database. */
class CollectionUiTest : CollectionComposeTestBase() {
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
}
