package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme

/**
 * Runs [action] on a Detail screen whose bookmark ([EntityTagConflictFixture.id]) and a private
 * bookmark ([EntityTagConflictFixture.other]) share the library spelling `canonical`.
 */
internal fun runEntityConflict(
    rule: ComposeContentTestRule,
    currentHasEntity: Boolean = true,
    classified: Boolean = false,
    action: suspend EntityTagConflictFixture.() -> Unit
): Unit = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val caches = OfflineVisualCaches()
    val library = withContext(Dispatchers.IO) { InMemoryLibrary(context).also { it.awaitReady() } }
    try {
        val fixture = EntityTagConflictFixture(rule, context, library)
        withContext(Dispatchers.IO) {
            library.db.bookmarkDao().insertAll(listOf(
                BookmarkEntity(fixture.id, "Editor", "https://editor.invalid", "Summary",
                    category = if (classified) "Tool" else null, subcategory = if (classified) "Tech" else null,
                    entities = if (currentHasEntity) listOf(fixture.canonical) else emptyList(), createdAt = 1, updatedAt = 1),
                BookmarkEntity(fixture.other, "Hidden", "https://hidden.invalid", "Summary",
                    entities = listOf(fixture.canonical), isPrivate = true, createdAt = 2, updatedAt = 2)
            ))
        }
        val model = library.viewModel()
        fixture.restoration.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                BookmarkDetailScreen(fixture.id, model, { fixture.navigations++ })
            }
        }
        fixture.action()
    } finally {
        caches.close()
        library.close()
    }
}

/** Drives the Detail tag editor through Compose semantics and reads the in-memory Library. */
internal class EntityTagConflictFixture(
    private val rule: ComposeContentTestRule,
    private val context: Context,
    val library: InMemoryLibrary
) {
    val id = "edit-conflict"
    val other = "hidden-conflict"
    val canonical = "ConflictTag"
    val variant = canonical.uppercase()
    /** Times the Detail screen asked to navigate back. */
    var navigations = 0
    internal val restoration = StateRestorationTester(rule)
    val dao get() = library.db.bookmarkDao()

    fun string(id: Int) = context.getString(id)
    val inTagDialog get() = inWindowShowing(string(R.string.detail_tag_dialog_title))
    val inPicker get() = inWindowShowing(string(R.string.detail_entity_picker_title))

    fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    fun await(matcher: SemanticsMatcher): SemanticsNode = rule.awaitNode(matcher)

    /**
     * Clicks the enabled control labelled [label] (text or description), optionally within [scope].
     * When several windows show the label, the most recently opened one wins.
     */
    fun click(label: String, scope: SemanticsMatcher? = null) {
        val matcher = (hasText(label) or hasContentDescription(label)).let { if (scope == null) it else it and scope }
        var click: (() -> Boolean)? = null
        rule.waitUntil(8_000) {
            click = nodes(matcher).asReversed().firstNotNullOfOrNull { node ->
                generateSequence(node) { it.parent }.firstOrNull { SemanticsActions.OnClick in it.config }
                    ?.takeIf { SemanticsProperties.Disabled !in it.config }
                    ?.config?.get(SemanticsActions.OnClick)?.action
            }
            click != null
        }
        rule.runOnUiThread { check(requireNotNull(click)()) }
        rule.waitForIdle()
    }

    fun click(label: Int, scope: SemanticsMatcher? = null) = click(string(label), scope)

    /** Types [value] into the Detail note field. */
    fun note(value: String) {
        await(hasText("Editor"))
        // SetText without focus, so no IME opens over the Detail screen.
        val field = await(hasSetTextAction())
        rule.runOnUiThread { check(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true) }
        rule.waitForIdle()
    }

    /** Opens the tag editor and adds [variant], which raises the spelling conflict. */
    fun conflict() {
        click(R.string.detail_edit_tags)
        addEntity(variant)
        await(hasText(string(R.string.entity_conflict_replace)))
    }

    fun addEntity(value: String) {
        click(R.string.detail_tag_add_entity)
        val inAddDialog = inWindowShowing(string(R.string.detail_add_entity_title))
        val field = await(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText) and inAddDialog)
        rule.runOnUiThread { check(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true) }
        await(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)) and inAddDialog)
        click(R.string.common_ok, inAddDialog)
    }

    /** Parent tag-dialog OK is the final persistence step; there is no Detail-level Save. */
    fun accept() = click(R.string.common_ok, inTagDialog)

    fun pressSystemBack() {
        check(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        rule.waitForIdle()
    }

    /** Emulates saving and restoring the Detail screen's instance state. */
    fun restore() = restoration.emulateSavedInstanceStateRestore()

    suspend fun row(bookmark: String = id): BookmarkEntity = withContext(Dispatchers.IO) { requireNotNull(dao.getBookmarkById(bookmark)) }
    suspend fun spelling(bookmark: String = other): String = row(bookmark).entities.single()
    suspend fun storedNote(): String? = row().note

    /** Polls the database off the main thread every 100ms until [condition] holds. */
    suspend fun awaitStored(condition: suspend () -> Boolean) = withTimeout(8_000) {
        while (!condition()) delay(100)
    }
}
