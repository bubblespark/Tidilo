package me.bubblespark.tidilo.data

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.TrashedBookmarkEntity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TrashNavigationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var oldDiscoverer: ((String) -> DomainIconManager.HtmlDiscoveryResult)? = null
    private var oldDownloader: ((String) -> DomainIconManager.DownloadResult)? = null
    private var oldOgDownloader: (suspend (String) -> ByteArray?)? = null
    private var oldBlurEnabled: Boolean = true

    @Before
    fun setUp() {
        oldDiscoverer = DomainIconManager.htmlDiscoverer
        oldDownloader = DomainIconManager.httpDownloader
        oldOgDownloader = OgImageManager.imageDownloader
        oldBlurEnabled = AppConfig.overlayBlurEnabled.value
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
        OgImageManager.imageDownloader = { null }
        AppConfig.setOverlayBlurEnabled(false)
    }

    @After
    fun tearDown() {
        AppConfig.setOverlayBlurEnabled(oldBlurEnabled)
        DomainIconManager.htmlDiscoverer = oldDiscoverer
        DomainIconManager.httpDownloader = oldDownloader
        OgImageManager.imageDownloader = oldOgDownloader
    }

    @Test
    fun railOpensNonInteractiveTrashListAndBackReturnsToLibrary(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        val id = "trash-navigation-${UUID.randomUUID()}"
        val title = "Trash navigation fixture $id"
        db.trashDao().insert(
            TrashedBookmarkEntity(
                id, title, "https://example.com/$id", "",
                createdAt = 1, updatedAt = 1, note = "", isPrivate = false, trashedAt = System.currentTimeMillis()
            )
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                scenario.onActivity { activity = it }
                val navOpenDesc = activity.getString(R.string.navigation_open)
                val navSearchText = activity.getString(R.string.nav_search)
                val trashTitle = activity.getString(R.string.trash_title)
                val backDesc = activity.getString(R.string.common_back)

                fun contentDescMatcher(desc: String) = SemanticsMatcher("contentDesc=$desc") {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(desc) == true
                }

                composeRule.waitUntil(8000) {
                    composeRule.onAllNodes(contentDescMatcher(navOpenDesc), useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithContentDescription(navOpenDesc).performClick()

                composeRule.waitUntil(8000) {
                    composeRule.onAllNodes(hasText(navSearchText), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                        composeRule.onAllNodes(hasText(trashTitle), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                val search = composeRule.onNodeWithText(navSearchText, useUnmergedTree = true).fetchSemanticsNode()
                val trash = composeRule.onNodeWithText(trashTitle, useUnmergedTree = true).fetchSemanticsNode()
                assertTrue(trash.boundsInRoot.top > search.boundsInRoot.bottom)
                composeRule.onNodeWithText(trashTitle).performClick()

                composeRule.waitUntil(8000) {
                    composeRule.onAllNodes(hasText(title), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                val rowTitle = composeRule.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
                assertFalse(generateSequence(rowTitle) { it.parent }.any { it.config.getOrNull(SemanticsActions.OnClick) != null })

                composeRule.waitUntil(5000) {
                    composeRule.onAllNodes(contentDescMatcher(navOpenDesc), useUnmergedTree = true)
                        .fetchSemanticsNodes().isEmpty()
                }
                composeRule.onNodeWithContentDescription(backDesc).performClick()
                composeRule.waitUntil(8000) {
                    composeRule.onAllNodes(contentDescMatcher(navOpenDesc), useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                db.trashDao().deleteById(id)
            }
        }
    }
}
