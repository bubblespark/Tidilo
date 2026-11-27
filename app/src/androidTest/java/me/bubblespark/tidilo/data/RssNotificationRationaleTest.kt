package me.bubblespark.tidilo.data

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED
import me.bubblespark.tidilo.data.notifications.NOTIFICATION_PREFS_NAME
import me.bubblespark.tidilo.data.rss.RssPreferences
import me.bubblespark.tidilo.data.rss.RssRepository
import me.bubblespark.tidilo.data.rss.RssScheduler
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.ui.screens.settings.RssNotificationPermissionDialog
import me.bubblespark.tidilo.ui.screens.settings.RssSettingsScreen
import me.bubblespark.tidilo.ui.screens.settings.RssSettingsViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The one-time notification rationale offered by Daily fetch and feed registration. */
class RssNotificationRationaleTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val activity get() = composeRule.activity
    private val context get() = composeRule.activity.applicationContext
    private val notificationPrefs get() = context.getSharedPreferences(NOTIFICATION_PREFS_NAME, Context.MODE_PRIVATE)
    private val rssPreferences get() = RssPreferences.getInstance(context)
    private var hadRequestedKey = false
    private var previousRequestedValue = false
    private lateinit var previousRssSettings: RssSettings

    @Before fun saveDeviceState() = runBlocking {
        hadRequestedKey = notificationPrefs.contains(KEY_NOTIFICATION_PERMISSION_REQUESTED)
        previousRequestedValue = notificationPrefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
        rssPreferences.initialize()
        previousRssSettings = rssPreferences.state.value
        assertTrue(notificationPrefs.edit().remove(KEY_NOTIFICATION_PERMISSION_REQUESTED).commit())
        rssPreferences.save(previousRssSettings.copy(dailyEnabled = false))
    }

    @After fun restoreDeviceState() = runBlocking {
        withContext(NonCancellable + Dispatchers.IO) {
            rssPreferences.save(previousRssSettings)
            RssScheduler.reschedule(context)
            val restore = notificationPrefs.edit()
            if (hadRequestedKey) restore.putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, previousRequestedValue)
            else restore.remove(KEY_NOTIFICATION_PERMISSION_REQUESTED)
            assertTrue(restore.commit())
        }
    }

    private val requested get() = notificationPrefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
    private val rationaleTitle get() = activity.getString(R.string.rss_notification_permission_title)
    private fun showing(text: String) = composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun click(text: String) = composeRule.clickNode(composeRule.awaitNode(inRssDialog(hasText(text))))

    private fun toggleDailyFetch() {
        val label = activity.getString(R.string.rss_daily_fetch)
        composeRule.clickNode(composeRule.revealRssRow(hasText(label)))
    }

    /** A permission registry that records each launch and answers "denied". */
    private fun deniedRegistryOwner(onLaunch: () -> Unit) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                onLaunch()
                dispatchResult(requestCode, false)
            }
        }
    }

    @Test fun dailyFetchOffersRationaleOnceWithoutBlocking(): Unit = runBlocking {
        val model = RssSettingsViewModel(activity.application)
        var permissionLaunches = 0
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                RssSettingsScreen(
                    onNavigateBack = {},
                    model = model,
                    isNotificationPermissionGranted = { false },
                    onLaunchNotificationPermission = { permissionLaunches++ }
                )
            }
        }
        toggleDailyFetch()
        composeRule.waitUntil(3_000) { model.settings.value.dailyEnabled }
        composeRule.awaitNode(hasText(rationaleTitle))
        click(activity.getString(R.string.rss_later))
        composeRule.waitUntil(3_000) { !showing(rationaleTitle) && requested }
        assertEquals(0, permissionLaunches)
        assertTrue("Dismissing notification rationale must not block Daily fetch", model.settings.value.dailyEnabled)

        toggleDailyFetch()
        composeRule.waitUntil(3_000) { !model.settings.value.dailyEnabled }
        toggleDailyFetch()
        composeRule.waitUntil(3_000) { model.settings.value.dailyEnabled }
        composeRule.waitForIdle()
        delay(300) // Negative window: a reopened rationale would appear within a few frames.
        assertFalse("Notification rationale must appear only once", showing(rationaleTitle))
    }

    @Test fun registrationRationalePersistsRequestBeforeLaunch() {
        val model = RssSettingsViewModel(activity.application, SavedStateHandle(mapOf("initialFeed" to "initial-test-feed")))
        var permissionLaunches = 0
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                RssSettingsScreen(
                    onNavigateBack = {},
                    model = model,
                    isNotificationPermissionGranted = { false },
                    onLaunchNotificationPermission = {
                        assertTrue(
                            "KEY_NOTIFICATION_PERMISSION_REQUESTED must already be persisted before launching OS prompt",
                            requested
                        )
                        assertFalse(
                            "Rationale state must already be cleared before launching OS prompt",
                            model.notificationRationale.value
                        )
                        permissionLaunches++
                    }
                )
            }
        }
        composeRule.awaitNode(hasText(activity.getString(R.string.rss_initial_title)))
        click(activity.getString(R.string.rss_later))
        composeRule.awaitNode(hasText(rationaleTitle))
        click(activity.getString(R.string.rss_notification_permission_allow))
        composeRule.waitUntil(3_000) { permissionLaunches > 0 && !showing(rationaleTitle) && requested }
        assertEquals(1, permissionLaunches)
    }

    @Test fun deniedPermissionKeepsInitialFetchAndStaysDismissed(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("fetch-now-test", "https://xml.invalid/feed", "Fetch Now Feed")
            db.rssDao().putFeed(feed)
            val enqueuedInitial = CopyOnWriteArrayList<Triple<String, String, Boolean>>()
            val repo = RssRepository(
                context = context,
                db = db,
                scheduleChanged = {},
                enqueueInitial = { id, op, mobile -> enqueuedInitial.add(Triple(id, op, mobile)) }
            )
            val model = RssSettingsViewModel(
                activity.application,
                SavedStateHandle(mapOf("initialFeed" to feed.id)),
                repo,
                networkStateOverride = { true to true }
            )
            var deniedDispatches = 0
            val owner = deniedRegistryOwner {
                assertTrue(
                    "KEY_NOTIFICATION_PERMISSION_REQUESTED must already be persisted before launching OS prompt",
                    requested
                )
                assertFalse("Rationale state must already be cleared before launching OS prompt", model.notificationRationale.value)
                deniedDispatches++
            }
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        RssSettingsScreen(onNavigateBack = {}, model = model, isNotificationPermissionGranted = { false })
                    }
                }
            }
            composeRule.awaitNode(hasText(activity.getString(R.string.rss_initial_title)))
            click(activity.getString(R.string.rss_fetch_now))
            composeRule.awaitNode(hasText(rationaleTitle))
            assertEquals("Initial fetch must be enqueued before rationale is answered", 1, enqueuedInitial.size)
            assertEquals(feed.id, enqueuedInitial.single().first)
            assertNotNull(db.rssDao().feed(feed.id)?.acquisitionId)
            click(activity.getString(R.string.rss_notification_permission_allow))
            composeRule.waitUntil(3_000) { deniedDispatches > 0 && !showing(rationaleTitle) }
            assertEquals(1, deniedDispatches)
            assertNotNull(
                "Denying notification permission must not unschedule or block initial fetch",
                db.rssDao().feed(feed.id)?.acquisitionId
            )

            // Daily fetch starts off (set in @Before), so one toggle is the subsequent enable.
            toggleDailyFetch()
            composeRule.waitUntil(3_000) { model.settings.value.dailyEnabled && !model.busy.value }
            composeRule.waitForIdle()
            delay(300) // Negative window: a reopened rationale would appear within a few frames.
            assertFalse(model.notificationRationale.value)
            assertFalse(
                "Denied permission must not re-open notification rationale on subsequent Daily fetch enable",
                showing(rationaleTitle)
            )

            val staleSavedState = SavedStateHandle(mapOf("notificationRationale" to true))
            val restoredStaleModel = RssSettingsViewModel(
                activity.application,
                staleSavedState,
                repo,
                permissionGrantedProvider = { false }
            )
            assertFalse(
                "Restored rationale must stay hidden before recheck when already requested",
                restoredStaleModel.notificationRationale.value
            )
            composeRule.waitUntil(3_000) { staleSavedState.get<Boolean>("notificationRationale") == false }
            assertFalse(restoredStaleModel.notificationRationale.value)
        } finally {
            db.close()
        }
    }

    @Test fun restoredRationaleLaunchesOnlyFromTheResumedScreen(): Unit = runBlocking {
        val persistStarted = CompletableDeferred<Unit>()
        val allowPersistFinish = CompletableDeferred<Unit>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val model = RssSettingsViewModel(
                activity.application,
                SavedStateHandle(mapOf("notificationRationale" to true)),
                RssRepository(context = context, db = db, scheduleChanged = {}, enqueueInitial = { _, _, _ -> }),
                permissionGrantedProvider = { false },
                persistPermissionRequested = {
                    persistStarted.complete(Unit)
                    allowPersistFinish.await()
                    notificationPrefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).commit()
                }
            )
            val screenGeneration = mutableStateOf(1)
            val firstDisposed = CompletableDeferred<Unit>()
            val secondApplied = CompletableDeferred<Unit>()
            var firstOwnerLaunches = 0
            var secondOwnerLaunches = 0
            val firstOwner = deniedRegistryOwner { firstOwnerLaunches++ }
            val secondOwner = deniedRegistryOwner {
                assertTrue(
                    "KEY_NOTIFICATION_PERMISSION_REQUESTED must already be persisted before second screen launches OS prompt",
                    requested
                )
                secondOwnerLaunches++
            }
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    if (screenGeneration.value == 1) {
                        DisposableEffect(Unit) { onDispose { firstDisposed.complete(Unit) } }
                        CompositionLocalProvider(LocalActivityResultRegistryOwner provides firstOwner) {
                            RssSettingsScreen(onNavigateBack = {}, model = model, isNotificationPermissionGranted = { false })
                        }
                    } else {
                        CompositionLocalProvider(LocalActivityResultRegistryOwner provides secondOwner) {
                            RssSettingsScreen(onNavigateBack = {}, model = model, isNotificationPermissionGranted = { false })
                        }
                        SideEffect { secondApplied.complete(Unit) }
                    }
                }
            }
            composeRule.awaitNode(hasText(rationaleTitle))
            click(activity.getString(R.string.rss_notification_permission_allow))
            withTimeout(3_000) { persistStarted.await() }
            composeRule.runOnUiThread { screenGeneration.value = 2 }
            composeRule.waitForIdle()
            withTimeout(3_000) {
                firstDisposed.await()
                secondApplied.await()
            }
            allowPersistFinish.complete(Unit)
            composeRule.waitUntil(3_000) { secondOwnerLaunches > 0 }
            assertEquals("Disposed composition launcher must not be called", 0, firstOwnerLaunches)
            assertEquals("Resumed recreated screen launcher must be invoked once", 1, secondOwnerLaunches)
        } finally {
            allowPersistFinish.complete(Unit)
            db.close()
        }
    }

    @Test fun rationaleDialogMeetsTouchTargetsInJapaneseAtLargeFont() {
        val jaConfig = Configuration(activity.resources.configuration).apply {
            setLocale(Locale.JAPANESE)
            setLayoutDirection(Locale.JAPANESE)
            fontScale = 2f
            screenWidthDp = 360
            smallestScreenWidthDp = 360
        }
        val jaContext = activity.createConfigurationContext(jaConfig)
        val jaTitle = jaContext.getString(R.string.rss_notification_permission_title)
        val jaMessage = jaContext.getString(R.string.rss_notification_permission_message)
        val jaAllow = jaContext.getString(R.string.rss_notification_permission_allow)
        val jaLater = jaContext.getString(R.string.rss_later)
        val density = activity.resources.displayMetrics.density
        var dialogAllows = 0
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides jaContext,
                LocalResources provides jaContext.resources,
                LocalConfiguration provides jaConfig,
                LocalDensity provides Density(density, 2f)
            ) {
                TidiloTheme(darkTheme = true, dynamicColor = false, theme = AppTheme.MODERN, blurEnabled = false) {
                    RssNotificationPermissionDialog(
                        onAllow = { dialogAllows++ },
                        onDismiss = {},
                        modifier = Modifier.widthIn(max = 360.dp)
                    )
                }
            }
        }
        composeRule.awaitNode(hasText(jaTitle))
        composeRule.awaitNode(hasText(jaMessage))
        fun clickable(text: String): SemanticsNode = generateSequence(composeRule.awaitNode(hasText(text))) { it.parent }
            .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue("Dialog dismiss button must meet 48dp touch height", clickable(jaLater).size.height >= 47.5f * density)
        assertTrue("Dialog confirm button must meet 48dp touch height", clickable(jaAllow).size.height >= 47.5f * density)
        click(jaAllow)
        composeRule.waitUntil(3_000) { dialogAllows > 0 }
    }
}
