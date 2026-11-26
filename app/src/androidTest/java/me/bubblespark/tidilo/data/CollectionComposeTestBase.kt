package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.ui.screens.library.*
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Rule

/**
 * Shared host and semantics helpers for the Collection UI suites.
 *
 * The collection sheets read the app database directly, so tests that mount them seed rows with
 * unique IDs and delete them through [cleanUp].
 */
abstract class CollectionComposeTestBase {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context = instrumentation.targetContext
    protected val db by lazy { AppDatabase.getInstance(context) }

    @Composable
    protected fun Themed(dark: Boolean = false, content: @Composable () -> Unit) =
        TidiloTheme(darkTheme = dark, dynamicColor = false, blurEnabled = false, content = content)

    protected fun hasText(node: SemanticsNode, value: String) =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
    protected fun containsText(node: SemanticsNode, value: String): Boolean =
        hasText(node, value) || node.children.any { containsText(it, value) }
    protected fun text(value: String) = SemanticsMatcher("text \"$value\"") { hasText(it, value) }
    protected fun description(value: String) = SemanticsMatcher("description \"$value\"") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.contains(value) } == true
    }
    protected fun editable(value: String) = SemanticsMatcher("editable \"$value\"") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == value && it.config.getOrNull(SemanticsActions.SetText) != null
    }
    protected val dismissable = SemanticsMatcher("dismissable") { it.config.getOrNull(SemanticsActions.Dismiss) != null }
    protected fun checkboxRow(title: String) = SemanticsMatcher("checkbox row \"$title\"") { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox && node.children.any { hasText(it, title) }
    }
    protected fun enabledButton(label: String) = SemanticsMatcher("enabled button \"$label\"") { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            !node.config.contains(SemanticsProperties.Disabled) && containsText(node, label)
    }

    protected fun await(matcher: SemanticsMatcher) = composeRule.awaitNode(matcher)
    protected fun nodes(matcher: SemanticsMatcher) =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
    protected fun none(matcher: SemanticsMatcher) = nodes(matcher).isEmpty()
    protected fun clickable(node: SemanticsNode) = generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
    protected fun click(matcher: SemanticsMatcher) = composeRule.clickNode(await(matcher))
    protected fun longClick(node: SemanticsNode) {
        val target = generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnLongClick) != null }
        composeRule.runOnUiThread { check(target.config[SemanticsActions.OnLongClick].action?.invoke() == true) }
        composeRule.waitForIdle()
    }
    protected fun setText(node: SemanticsNode, value: String) {
        composeRule.runOnUiThread { check(node.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true) }
        composeRule.waitForIdle()
    }
    protected fun dismissSheet() {
        val node = await(dismissable)
        composeRule.runOnUiThread { check(node.config[SemanticsActions.Dismiss].action?.invoke() == true) }
        composeRule.waitForIdle()
    }
    protected fun liveRegionIds(): Set<Int> = nodes(SemanticsMatcher("polite") {
        it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
    }).mapTo(mutableSetOf()) { it.id }
    protected fun politeRegionFor(node: SemanticsNode): SemanticsNode =
        generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite }

    /** Polls a database condition at 100ms, letting the rule drive the UI between checks. */
    protected suspend fun awaitDb(condition: suspend () -> Boolean) = withTimeout(5_000) {
        while (true) {
            composeRule.waitForIdle()
            if (condition()) break
            delay(100)
        }
    }

    /** Deletes test rows from the app database even when the test was cancelled. */
    protected suspend fun cleanUp(collectionTitles: List<String> = emptyList(), collectionIds: List<String> = emptyList(),
        bookmarkIds: List<String> = emptyList(), triggers: List<String> = emptyList()) = withContext(NonCancellable + Dispatchers.IO) {
        triggers.forEach { db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $it") }
        val byTitle = db.collectionDao().observeCollections(true).first()
            .filter { it.collection.title in collectionTitles }.map { it.collection.id }
        db.withTransaction {
            (collectionIds + byTitle).forEach { db.collectionDao().delete(it) }
            bookmarkIds.forEach { db.bookmarkDao().deleteById(it) }
        }
    }
}
