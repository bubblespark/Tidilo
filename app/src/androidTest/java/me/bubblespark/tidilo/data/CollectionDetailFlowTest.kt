package me.bubblespark.tidilo.data

import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.ui.screens.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The Detail screen collection flow on the app database: sheet switching, focus, lifecycle, and restoration. */
class CollectionDetailFlowTest : CollectionComposeTestBase() {
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
