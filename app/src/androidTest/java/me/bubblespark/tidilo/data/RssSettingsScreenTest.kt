package me.bubblespark.tidilo.data

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.rss.RssPreferences
import me.bubblespark.tidilo.data.rss.RssRepository
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.ui.screens.settings.ManualFetchStatus
import me.bubblespark.tidilo.ui.screens.settings.RssFeedEditor
import me.bubblespark.tidilo.ui.screens.settings.RssInitialFetchDialog
import me.bubblespark.tidilo.ui.screens.settings.RssManualFetchDialog
import me.bubblespark.tidilo.ui.screens.settings.RssRegistrationDialog
import me.bubblespark.tidilo.ui.screens.settings.RssSettingsContent
import me.bubblespark.tidilo.ui.screens.settings.RssSettingsScreen
import me.bubblespark.tidilo.ui.screens.settings.RssSettingsViewModel
import me.bubblespark.tidilo.ui.screens.settings.StorageLoad
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Scrolls the RSS settings list until [matcher] is composed, then returns the first unmerged match. */
internal fun ComposeTestRule.revealRssRow(matcher: SemanticsMatcher): SemanticsNode {
    waitForIdle()
    onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(matcher)
    return awaitNode(matcher)
}

/** Matches [matcher] only inside a dialog window. */
internal fun inRssDialog(matcher: SemanticsMatcher) = matcher and hasAnyAncestor(isDialog())

/** The nearest node, from [this] upwards, that carries a click action. */
internal fun SemanticsNode.clickTarget(): SemanticsNode =
    generateSequence(this) { it.parent }.first { SemanticsActions.OnClick in it.config }

/** RSS settings content, dialogs, and their ViewModel wiring on an isolated Compose host. */
class RssSettingsScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val activity get() = composeRule.activity
    private val context get() = composeRule.activity.applicationContext
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun exists(matcher: SemanticsMatcher) =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun absent(matcher: SemanticsMatcher): Boolean {
        composeRule.waitForIdle()
        return !exists(matcher)
    }

    private fun enabled(node: SemanticsNode) = SemanticsProperties.Disabled !in node.clickTarget().config

    private fun click(matcher: SemanticsMatcher) = composeRule.clickNode(composeRule.awaitNode(matcher))
    private fun clickRow(matcher: SemanticsMatcher) = composeRule.clickNode(composeRule.revealRssRow(matcher))
    private fun clickDialog(label: String) = click(inRssDialog(hasText(label)))
    private fun description(resource: Int, vararg args: Any) = hasContentDescription(activity.getString(resource, *args))
    private fun pressBack() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

    @Composable
    private fun Themed(content: @Composable () -> Unit) =
        TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false, content = content)

    @Composable
    private fun Content(
        feeds: List<RssFeedEntity> = emptyList(),
        settings: RssSettings = RssSettings(),
        wifi: Boolean? = true,
        onBack: () -> Unit = {},
        onSettings: (RssSettings) -> Unit = {},
        onFeed: (RssFeedEntity?, String) -> Unit = { _, _ -> },
        onDelete: (String) -> Unit = {},
        onFetch: () -> Unit = {},
        onEnabled: (String, Boolean) -> Unit = { _, _ -> },
        manualFetch: ManualFetchStatus = ManualFetchStatus.Idle
    ) = Themed {
        RssSettingsContent(
            StorageLoad.Ready(feeds), settings, false, null, wifi,
            onBack = onBack, onRetry = {}, onSettings = onSettings, onFeed = onFeed, onDelete = onDelete,
            onFetch = onFetch, onDismissError = {}, onEnabled = onEnabled, manualFetch = manualFetch
        )
    }

    @Test fun settingsInitializationFailureBlocksWritesAndCanRetry(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val preferences = RssPreferences.getInstance(context)
        preferences.initialize()
        val original = preferences.state.value
        val saved = RssSettings(listOf(LocalTime.of(12, 34)), wifiOnly = false, dailyEnabled = false)
        val attempts = AtomicInteger()
        try {
            preferences.save(saved)
            db.rssDao().putFeed(RssFeedEntity("settings-load", "https://example.invalid/feed", "Example Feed"))
            lateinit var model: RssSettingsViewModel
            composeRule.runOnUiThread {
                model = RssSettingsViewModel(
                    activity.application,
                    SavedStateHandle(),
                    RssRepository(context, db),
                    networkStateOverride = { true to true },
                    initializeSettings = {
                        if (attempts.incrementAndGet() == 1) throw java.io.IOException("Fixture settings failure")
                        preferences.initialize()
                    }
                )
            }
            composeRule.setContent { Themed { RssSettingsScreen({}, model) } }
            val failure = activity.getString(R.string.rss_settings_load_failed)
            val retry = activity.getString(R.string.summary_retry)
            val daily = activity.getString(R.string.rss_daily_fetch)
            val wifi = activity.getString(R.string.rss_wifi_only)
            val fetch = activity.getString(R.string.rss_fetch_now)
            composeRule.awaitNode(hasText(failure))
            composeRule.runOnUiThread { model.saveSettings(RssSettings(dailyEnabled = true), permissionGranted = true) }
            withTimeout(3_000) { model.busy.first { !it } }
            assertEquals(saved, RssPreferences(context).let {
                it.initialize()
                it.state.value
            })
            fun assertEnabled(label: String, expected: Boolean) =
                assertEquals(label, expected, enabled(composeRule.revealRssRow(hasText(label))))
            for (label in listOf(daily, wifi, fetch)) assertEnabled(label, false)
            val triggerLabels = listOf(
                activity.getString(R.string.rss_add_trigger_time),
                activity.getString(R.string.rss_edit_trigger_time, "12:34"),
                activity.getString(R.string.rss_remove_trigger_time, "12:34")
            )
            for (label in triggerLabels) {
                assertFalse(label, enabled(composeRule.revealRssRow(hasContentDescription(label))))
            }
            clickRow(hasText(retry))
            composeRule.waitUntil(3_000) { attempts.get() >= 2 && !model.busy.value }
            assertTrue(absent(hasText(failure)))
            assertEnabled(daily, true)
            for (label in listOf(wifi, fetch)) assertEnabled(label, true)
            assertEquals(saved, preferences.state.value)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { preferences.save(original) }
            db.close()
        }
    }

    @Test fun fetchNowQueuesOnlyTheExplicitManualChoice(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val preferences = RssPreferences.getInstance(context)
        preferences.initialize()
        val original = preferences.state.value
        val requests = CopyOnWriteArrayList<Boolean>()
        var connection = true to false
        val state = SavedStateHandle()
        val repo = RssRepository(context, db)
        fun model() = RssSettingsViewModel(
            activity.application, state, repo,
            networkStateOverride = { connection },
            enqueueManualFetch = { requests.add(it) }
        )
        val current = mutableStateOf<RssSettingsViewModel?>(null)
        try {
            preferences.save(original.copy(wifiOnly = true, dailyEnabled = false))
            db.rssDao().putFeed(RssFeedEntity("manual-choice", "https://example.invalid/feed", "Example Feed"))
            composeRule.runOnUiThread { current.value = model() }
            composeRule.setContent { Themed { current.value?.let { RssSettingsScreen({}, it) } } }
            val fetch = hasText(activity.getString(R.string.rss_fetch_now))
            val wait = activity.getString(R.string.rss_manual_wait_wifi)
            val mobile = activity.getString(R.string.rss_manual_use_mobile)
            fun busy() = current.value?.busy?.value == true
            clickRow(fetch)
            composeRule.awaitNode(inRssDialog(hasText(mobile)))
            assertTrue(requests.isEmpty())
            composeRule.runOnUiThread { current.value = model() }
            composeRule.awaitNode(inRssDialog(hasText(wait)))
            assertTrue(requests.isEmpty())
            pressBack()
            composeRule.waitUntil(3_000) { !exists(inRssDialog(hasText(wait))) }
            assertTrue(requests.isEmpty())

            clickRow(fetch)
            clickDialog(wait)
            composeRule.waitUntil(3_000) { requests.size >= 1 && !busy() }
            clickRow(fetch)
            clickDialog(mobile)
            composeRule.waitUntil(3_000) { requests.size >= 2 && !busy() }
            assertEquals(listOf(false, true), requests.toList())
            assertTrue(preferences.state.value.wifiOnly)
            assertFalse(preferences.state.value.dailyEnabled)

            composeRule.runOnUiThread { connection = true to true }
            clickRow(fetch)
            composeRule.waitUntil(3_000) { requests.size >= 3 }
            assertEquals(listOf(false, true, false), requests.toList())
            assertTrue(absent(inRssDialog(hasText(mobile))))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { preferences.save(original) }
            db.close()
        }
    }

    @Test fun rssSettingsAndConsentRemainReadableInEnglishLight() = readabilityCase(Locale.ENGLISH, dark = false, scale = 1f)

    @Test fun rssSettingsAndConsentRemainReadableInJapaneseDarkAtLargeFont() =
        readabilityCase(Locale.JAPANESE, dark = true, scale = 2f)

    private fun readabilityCase(locale: Locale, dark: Boolean, scale: Float) {
        val config = Configuration(activity.resources.configuration).apply {
            setLocale(locale)
            fontScale = scale
        }
        val localized = activity.createConfigurationContext(config)
        val stage = mutableStateOf(0)
        var choice: Boolean? = null
        var dismissed = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides config,
                LocalResources provides localized.resources,
                LocalDensity provides Density(activity.resources.displayMetrics.density, scale)
            ) {
                TidiloTheme(darkTheme = dark, dynamicColor = false, blurEnabled = false) {
                    val feeds = if (stage.value == 0) emptyList() else listOf(
                        RssFeedEntity("visual", "https://example.invalid/feed", "Example Feed", error = "XML_FETCH_FAILED")
                    )
                    RssSettingsContent(
                        StorageLoad.Ready(feeds), RssSettings(), false, null, false,
                        onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> },
                        onDelete = {}, onFetch = {}, onDismissError = {}
                    )
                    if (stage.value == 2) {
                        RssManualFetchDialog(
                            busy = false,
                            onChoice = { choice = it; stage.value = 1 },
                            onDismiss = { dismissed = true; stage.value = 1 }
                        )
                    }
                }
            }
        }
        fun visible(matcher: SemanticsMatcher) {
            composeRule.awaitNode(matcher)
            composeRule.onAllNodes(matcher, useUnmergedTree = true).onFirst().assertIsDisplayed()
        }
        val add = localized.getString(R.string.rss_add_feed)
        composeRule.awaitNode(hasText(localized.getString(R.string.rss_first_feed_title)))
        visible(hasText(add))
        click(hasText(add))
        visible(hasText(localized.getString(R.string.rss_retention_permanent)))
        click(hasText(localized.getString(R.string.rss_retention_details)))
        composeRule.awaitNode(hasText(localized.getString(R.string.rss_retention_interactions)))
        val save = hasText(localized.getString(R.string.rss_save))
        fun fullyVisible(node: SemanticsNode) = node.boundsInRoot.height >= node.size.height - 1 && node.boundsInRoot.height > 0
        val saveLabel = composeRule.awaitNode(save)
        if (!fullyVisible(saveLabel)) {
            val sheet = generateSequence(saveLabel) { it.parent }.first { SemanticsActions.ScrollBy in it.config }
            composeRule.runOnUiThread { assertTrue(sheet.config[SemanticsActions.ScrollBy].action?.invoke(0f, 4000f) == true) }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(save, useUnmergedTree = true).fetchSemanticsNodes().any(::fullyVisible)
        }
        pressBack()
        composeRule.waitUntil(3_000) { !exists(save) }
        composeRule.runOnUiThread { stage.value = 1 }
        visible(hasText(localized.getString(R.string.rss_xml_fetch_failed)))
        visible(hasContentDescription(localized.getString(R.string.rss_enabled)))
        composeRule.runOnUiThread { stage.value = 2 }
        val mobile = localized.getString(R.string.rss_manual_use_mobile)
        val wait = localized.getString(R.string.rss_manual_wait_wifi)
        visible(hasText(mobile))
        visible(hasText(wait))
        click(hasText(wait))
        assertEquals(false, choice)
        composeRule.runOnUiThread { stage.value = 2 }
        click(hasText(mobile))
        assertEquals(true, choice)
        composeRule.runOnUiThread { stage.value = 2; choice = null }
        composeRule.awaitNode(hasText(wait))
        pressBack()
        composeRule.waitUntil(3_000) { dismissed }
        assertNull(choice)
    }

    @Test fun emptyTriggerSectionIsSeparateAndEditableWhileOff() {
        val settings = mutableStateOf(RssSettings())
        composeRule.setContent { Content(settings = settings.value, onSettings = { settings.value = it }) }
        val firstTop = composeRule.awaitNode(hasText("Add your first feed")).boundsInRoot.top
        composeRule.awaitNode(hasText("https://example.com/feed.xml", substring = true))
        composeRule.awaitNode(hasText("Add feed"))
        val daily = composeRule.revealRssRow(hasText("Daily fetch time")).boundsInRoot
        val trigger = composeRule.revealRssRow(hasText("Trigger time")).boundsInRoot
        assertTrue(firstTop < daily.top)
        assertTrue(trigger.top > daily.bottom)
        composeRule.revealRssRow(hasText("0 / 3"))
        composeRule.revealRssRow(hasText("Not set"))
        assertTrue(absent(hasText("Up to 3 times per day")))
        val add = description(R.string.rss_add_trigger_time)
        clickRow(add)
        clickDialog("Cancel")
        assertTrue(settings.value.triggerTimes.isEmpty())
        clickRow(add)
        clickDialog("Add")
        composeRule.waitUntil(3_000) { settings.value.triggerTimes.isNotEmpty() }
        assertEquals(listOf(LocalTime.of(8, 0)), settings.value.triggerTimes)
        assertFalse(settings.value.dailyEnabled)
        assertTrue(settings.value.wifiOnly)
        val remove = description(R.string.rss_remove_trigger_time, "08:00")
        val confirmation = hasText("Delete the trigger time 08:00?")
        clickRow(remove)
        composeRule.awaitNode(confirmation)
        assertEquals(listOf(LocalTime.of(8, 0)), settings.value.triggerTimes)
        clickDialog("Cancel")
        assertEquals(listOf(LocalTime.of(8, 0)), settings.value.triggerTimes)
        clickRow(remove)
        clickDialog("Delete")
        composeRule.revealRssRow(hasText("Not set"))
        assertTrue(settings.value.triggerTimes.isEmpty())
        composeRule.runOnUiThread { settings.value = settings.value.copy(triggerTimes = listOf(LocalTime.of(8, 0))) }
        clickRow(remove)
        composeRule.awaitNode(confirmation)
        composeRule.runOnUiThread { settings.value = settings.value.copy(triggerTimes = listOf(LocalTime.NOON), wifiOnly = false) }
        composeRule.waitUntil(3_000) { !exists(inRssDialog(confirmation)) }
        assertEquals(listOf(LocalTime.NOON), settings.value.triggerTimes)
        assertFalse(settings.value.wifiOnly)
    }

    @Test fun duplicateAndLimitDoNotMutateSettings() {
        val settings = mutableStateOf(RssSettings(listOf(LocalTime.of(8, 0), LocalTime.of(18, 30))))
        composeRule.setContent { Content(settings = settings.value, onSettings = { settings.value = it }) }
        val add = description(R.string.rss_add_trigger_time)
        clickRow(add)
        composeRule.awaitNode(inRssDialog(hasText("This time is already set.")))
        assertFalse(enabled(composeRule.awaitNode(inRssDialog(hasText("Add")))))
        clickDialog("Cancel")
        composeRule.runOnUiThread {
            settings.value = settings.value.copy(triggerTimes = listOf(LocalTime.of(8, 0), LocalTime.NOON, LocalTime.of(18, 30)))
        }
        composeRule.revealRssRow(hasText("3 / 3"))
        assertFalse(enabled(composeRule.revealRssRow(add)))
        clickRow(description(R.string.rss_remove_trigger_time, "12:00"))
        clickDialog("Delete")
        composeRule.revealRssRow(hasText("2 / 3"))
        assertTrue(enabled(composeRule.revealRssRow(add)))
        clickRow(description(R.string.rss_edit_trigger_time, "18:30"))
        clickDialog("Save")
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 30)), settings.value.triggerTimes)
    }

    @Test fun manualFetchWaitsWithoutProgressAndReenablesAfterCompletion() {
        val status = mutableStateOf(ManualFetchStatus.Waiting)
        var fetches = 0
        composeRule.setContent {
            Content(
                feeds = listOf(RssFeedEntity("waiting-test", "https://example.invalid/feed", "Example")),
                wifi = false, onFetch = { fetches++ }, manualFetch = status.value
            )
        }
        assertFalse(enabled(composeRule.revealRssRow(hasText("Waiting to fetch"))))
        assertTrue(absent(progress))
        composeRule.runOnUiThread { status.value = ManualFetchStatus.Running }
        composeRule.awaitNode(progress)
        composeRule.runOnUiThread { status.value = ManualFetchStatus.Idle }
        clickRow(hasText("Fetch now"))
        composeRule.waitUntil(3_000) { fetches > 0 }
        assertEquals(1, fetches)
    }

    @Test fun feedListScrollsBehindTheFixedRssHeader() {
        val feeds = (1..12).map { RssFeedEntity("header-$it", "https://header-$it.invalid/feed", "Feed $it") }
        composeRule.setContent { Content(feeds = feeds) }
        composeRule.awaitNode(hasText("Feed 1"))
        val listMatcher = SemanticsMatcher("RSS list") {
            SemanticsActions.ScrollBy in it.config && SemanticsProperties.CollectionInfo in it.config
        }
        val header = hasText("RSS")
        val headerBounds = composeRule.awaitNode(header).boundsInRoot
        val list = composeRule.awaitNode(listMatcher)
        assertTrue("The scrolling viewport must extend behind the header", list.boundsInRoot.top < headerBounds.top)
        composeRule.runOnUiThread { assertTrue(list.config[SemanticsActions.ScrollBy].action?.invoke(0f, 500f) == true) }
        composeRule.waitForIdle()
        fun offset() = composeRule.awaitNode(listMatcher).config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = offset()
        val start = headerBounds.center
        composeRule.onRoot().performTouchInput { swipe(start, start + Offset(0f, 192f), durationMillis = 200) }
        composeRule.waitForIdle()
        val after = offset()
        assertTrue("A drag starting on the header must scroll the list: $before -> $after", after < before)
        composeRule.awaitNode(header)
    }

    @Test fun longPressRequestsConfirmationBeforeDeletingFeed() {
        var deleted: String? = null
        composeRule.setContent {
            Content(feeds = listOf(RssFeedEntity("delete-test", "https://example.com/feed", "Example")), onDelete = { deleted = it })
        }
        val card = generateSequence(composeRule.revealRssRow(hasText("Example"))) { it.parent }
            .first { SemanticsActions.OnLongClick in it.config }
        composeRule.runOnUiThread { assertTrue(card.config[SemanticsActions.OnLongClick].action?.invoke() == true) }
        composeRule.awaitNode(hasText("Stop fetching", substring = true))
        assertNull(deleted)
        click(hasText("Cancel"))
        assertNull(deleted)
    }

    @Test fun manualFetchShowsProgressAndPreventsDuplicateRequests() {
        composeRule.setContent {
            Content(
                feeds = listOf(RssFeedEntity("progress-test", "https://example.com/feed", "Example")),
                manualFetch = ManualFetchStatus.Running
            )
        }
        assertFalse(enabled(composeRule.revealRssRow(hasText("Fetch now"))))
        composeRule.awaitNode(progress)
    }

    @Test fun dailyToggleUpdatesSettingWithoutDisablingManualFetch() {
        val settings = mutableStateOf(RssSettings(dailyEnabled = true))
        var fetched = false
        composeRule.setContent {
            Content(
                feeds = listOf(RssFeedEntity("daily-test", "https://example.com/feed", "Example")),
                settings = settings.value, onSettings = { settings.value = it }, onFetch = { fetched = true }
            )
        }
        clickRow(hasText("Daily fetch"))
        composeRule.waitUntil(3_000) { !settings.value.dailyEnabled }
        clickRow(hasText("Fetch now"))
        composeRule.waitUntil(3_000) { fetched }
    }

    @Test fun legacyRssFeedEditorHasNoAiModePicker() {
        val original = RssFeedEntity("feed", "https://fixture.example/feed", "Feed", mode = "AI")
        composeRule.setContent { Themed { RssFeedEditor(original, onDismiss = {}) { _ -> } } }
        composeRule.awaitNode(hasText("Save"))
        assertTrue(absent(hasText("AI Analyze")))
        assertTrue(absent(hasText("Direct")))
    }

    @Test fun initialFetchDialogSeparatesTimingAndShowsMobileNotice() {
        var fetched = false
        var later = false
        composeRule.setContent {
            Themed { RssInitialFetchDialog(true, true, false, onFetch = { fetched = true }, onLater = { later = true }) }
        }
        composeRule.awaitNode(hasText("This fetch will use mobile data", substring = true))
        assertTrue(absent(hasText("permanently deleted", substring = true)))
        click(hasText("Later"))
        assertTrue(later)
        assertFalse(fetched)
    }

    @Test fun automaticallyDisabledFeedCardExplainsXmlFailure() {
        val feed = RssFeedEntity("failed", "https://fixture.example/feed", "Failed feed", enabled = false, error = "XML_FETCH_FAILED")
        composeRule.setContent { Content(feeds = listOf(feed)) }
        composeRule.revealRssRow(hasText(activity.getString(R.string.rss_xml_fetch_failed)))
        assertTrue(absent(hasText("Enable this feed to try again", substring = true)))
        val toggle = generateSequence(composeRule.awaitNode(hasContentDescription("Enabled"))) { it.parent }
            .first { SemanticsProperties.ToggleableState in it.config }
        assertEquals(ToggleableState.Off, toggle.config[SemanticsProperties.ToggleableState])
    }

    @Test fun resubscriptionDialogExplainsCycleDeletionAndRequiresExplicitChoice() {
        var accepted = false
        var dismissed = false
        composeRule.setContent {
            Themed { RssRegistrationDialog(100, false, onConfirm = { accepted = true }, onDismiss = { dismissed = true }) }
        }
        composeRule.awaitNode(hasText("100 saved articles", substring = true))
        composeRule.awaitNode(hasText("daily maintenance", substring = true))
        assertFalse(accepted)
        click(hasText("Cancel"))
        assertTrue(dismissed)
        assertFalse(accepted)
        composeRule.awaitNode(hasText("Collection", substring = true))
        click(hasText("Register"))
        assertTrue(accepted)
    }

    @Test fun feedEditorShowsAgePolicyWithoutCountControls() {
        composeRule.setContent { Themed { RssFeedEditor(null, onDismiss = {}) { _ -> } } }
        composeRule.awaitNode(hasText("10 days", substring = true))
        val warning = hasText(activity.getString(R.string.rss_retention_permanent))
        val details = hasText(activity.getString(R.string.rss_retention_interactions))
        composeRule.awaitNode(warning)
        assertTrue(absent(details))
        click(hasText(activity.getString(R.string.rss_retention_details)))
        composeRule.awaitNode(details)
        composeRule.awaitNode(warning)
        assertTrue(absent(hasText("Unlimited")))
        assertTrue(absent(hasText("10–500", substring = true)))
        assertTrue(absent(SemanticsMatcher("editable 30") { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "30" }))
    }

    @Test fun feedCardShowsCompactMetadataAndTopRightSwitch() {
        val feed = mutableStateOf(RssFeedEntity("feed", "https://example.com/feed", "Example",
            retentionLimit = null, error = "HTTP 500", lastAttemptAt = 100, lastSuccessAt = 50))
        composeRule.setContent {
            Themed {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)) {
                    RssSettingsContent(StorageLoad.Ready(listOf(feed.value)), RssSettings(), false, null, true,
                        onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> }, onDelete = {},
                        onFetch = {}, onDismissError = {}, onEnabled = { id, value ->
                            assertEquals("feed", id)
                            feed.value = feed.value.copy(enabled = value)
                        })
                }
            }
        }
        fun descendants(node: SemanticsNode): List<SemanticsNode> {
            val result = mutableListOf<SemanticsNode>()
            val pending = ArrayDeque(node.children)
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                result.add(next)
                pending.addAll(next.children)
            }
            return result
        }
        fun texts(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        fun feedCards(): List<SemanticsNode> = composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick), useUnmergedTree = true
        ).fetchSemanticsNodes().filter { card -> descendants(card).any { "Example" in texts(it) } }
        val enabledLabel = activity.getString(R.string.rss_enabled)
        assertTrue(absent(hasText("Maximum AI queue:", substring = true)))
        composeRule.revealRssRow(hasText("Example"))
        val card = feedCards().single()
        val content = descendants(card)
        val title = content.single { "Example" in texts(it) }
        val error = content.single { "HTTP 500" in texts(it) }
        val toggle = content.single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(enabledLabel) == true }
        val minTouchSize = 48 * activity.resources.displayMetrics.density
        assertTrue(toggle.boundsInRoot.width >= minTouchSize - 1)
        assertTrue(toggle.boundsInRoot.height >= minTouchSize - 1)
        assertTrue(toggle.boundsInRoot.left >= title.boundsInRoot.right)
        assertTrue(toggle.boundsInRoot.bottom <= error.boundsInRoot.top)
        assertFalse(content.flatMap(::texts).any {
            it == "Direct" || it.startsWith("Unread 10d") || it.startsWith("Last attempt:") || it.startsWith("Last success:")
        })
        assertEquals(Role.Switch, toggle.config[SemanticsProperties.Role])
        assertEquals(ToggleableState.On, toggle.config[SemanticsProperties.ToggleableState])
        composeRule.runOnUiThread { assertTrue(toggle.config[SemanticsActions.OnClick].action?.invoke() == true) }
        composeRule.waitUntil(3_000) { !feed.value.enabled }
        assertTrue(absent(hasText(activity.getString(R.string.rss_edit))))
        composeRule.runOnUiThread { feed.value = feed.value.copy(error = null, retentionLimit = 30) }
        composeRule.waitUntil(3_000) { feedCards().any { card -> descendants(card).none { "HTTP 500" in texts(it) } } }
        val updated = feedCards().single()
        composeRule.runOnUiThread { assertTrue(updated.config[SemanticsActions.OnClick].action?.invoke() == true) }
        composeRule.awaitNode(hasText(activity.getString(R.string.rss_delete)))
    }

    @Test fun scheduleWifiAndDirectFeedEditorExposeIndependentControls() {
        val settings = mutableStateOf(RssSettings(listOf(LocalTime.MIDNIGHT)))
        val wifi = mutableStateOf<Boolean?>(null)
        var savedUrl: String? = null
        var backed = false
        composeRule.setContent {
            Content(
                settings = settings.value, wifi = wifi.value, onBack = { backed = true },
                onSettings = { settings.value = it }, onFeed = { _, url -> savedUrl = url }
            )
        }
        val warning = hasText("Fetches that require Wi-Fi wait for a Wi-Fi connection.")
        val wifiPolicy = hasText("Fetch only over Wi-Fi")
        composeRule.revealRssRow(hasText("00:00"))
        assertTrue(absent(warning))
        composeRule.runOnUiThread { wifi.value = false }
        val blocked = composeRule.revealRssRow(warning).boundsInRoot
        val wifiLabel = composeRule.awaitNode(wifiPolicy)
        assertTrue("Warning remains inside the policy card, above Wi-Fi", blocked.bottom <= wifiLabel.boundsInRoot.top)
        assertTrue(generateSequence(wifiLabel) { it.parent }.any { SemanticsProperties.ToggleableState in it.config })
        composeRule.clickNode(wifiLabel)
        composeRule.waitUntil(3_000) { !settings.value.wifiOnly }
        assertFalse(settings.value.dailyEnabled)
        composeRule.waitUntil(3_000) { !exists(warning) }
        clickRow(description(R.string.rss_edit_trigger_time, "00:00"))
        clickDialog("Cancel")
        assertEquals(listOf(LocalTime.MIDNIGHT), settings.value.triggerTimes)
        clickRow(hasText("Add feed"))
        composeRule.awaitNode(hasText("10 days", substring = true))
        assertTrue(absent(hasText("Import Mode")))
        val urlField = SemanticsMatcher("feed URL field") {
            SemanticsActions.SetText in it.config && it.config.getOrNull(SemanticsProperties.EditableText)?.text != "30"
        }
        composeRule.awaitNode(urlField)
        composeRule.onAllNodes(urlField).onFirst().performTextReplacement("https://example.com/feed.xml")
        assertTrue(absent(hasText("Unlimited")))
        click(hasText("Save"))
        composeRule.waitUntil(3_000) { savedUrl != null }
        assertEquals("https://example.com/feed.xml", savedUrl)
        click(hasContentDescription(activity.getString(R.string.common_back)))
        assertTrue(backed)
    }
}
