package me.bubblespark.tidilo.data

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import java.util.concurrent.CopyOnWriteArrayList
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.TidiloApp
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.ImportRepository
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.CollectionEntity
import me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED
import me.bubblespark.tidilo.data.notifications.NOTIFICATION_PREFS_NAME
import me.bubblespark.tidilo.parser.WebPageParser
import me.bubblespark.tidilo.ui.screens.importscreen.ImportViewModel
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.UUID

class LibraryNavigationInteractionTest {
    private fun clickableNode(node: SemanticsNode): SemanticsNode {
        var target = node
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
        return target
    }

    private fun click(node: SemanticsNode) {
        check(clickableNode(node).config[SemanticsActions.OnClick].action?.invoke() == true)
    }

    @Test fun searchStaysCompactWhileScrollingAndBackRestoresLibrary(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        val prefix = "navigation-${UUID.randomUUID()}"
        val ids = (0..15).map { "$prefix-$it" }
        lateinit var model: LibraryViewModel
        var initialized = false
        var contentReady = false
        var stage = "creating fixtures"
        var previous = LibraryFilterMode.NEWEST_FIRST
        try {
            ids.forEachIndexed { index, id -> dao.insertOrUpdate(BookmarkEntity(id, "Navigation fixture $index",
                "https://navigation.invalid/$id", "Summary", createdAt = System.currentTimeMillis() + index, updatedAt = 1)) }
            host.main {
                model = ViewModelProvider(activity)[LibraryViewModel::class.java]
                initialized = true
                previous = model.filterMode.value
                model.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                activity.setContent { MaterialTheme {
                    TidiloApp(libraryViewModel = model)
                    SideEffect { contentReady = true }
                } }
            }
            withTimeout(3000) { while (!contentReady) delay(20) }
            stage = "waiting for the Library Search action"
            val before = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger" }.boundsInRoot
            stage = "waiting for Library fixtures"
            host.await(activity) { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Navigation fixture") } == true }
            val libraryTitle = host.await(activity) { host.text(it, host.context.getString(R.string.library_header_title)) }
            host.main { click(libraryTitle) }
            // Paging can retain its visible key across fixture invalidations; this test checks Search return position.
            host.await(activity) { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Navigation fixture") } == true }
            host.scroll(activity, 500f)
            delay(400)
            val action = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger" }
            var firstVisibleTitle = ""
            var firstVisibleTop = 0f
            host.main {
                val first = host.nodes(activity).filter { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Navigation fixture") } == true &&
                        node.boundsInRoot.top > 0f && node.boundsInRoot.bottom < activity.window.decorView.height
                }.minBy { it.boundsInRoot.top }
                firstVisibleTitle = first.config[SemanticsProperties.Text].single().text
                firstVisibleTop = first.boundsInRoot.top
                val after = action.boundsInRoot
                val density = activity.resources.displayMetrics.density
                assertEquals(56f * density, after.width, 1f)
                assertEquals(before.top, after.top, 1f)
                assertEquals(before.width, after.width, 1f)
                click(action)
            }
            val innerSearch = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_search" && it.config.getOrNull(SemanticsActions.OnClick) != null }
            host.main { click(innerSearch) }
            stage = "opening Search"
            host.await(activity) { host.text(it, host.context.getString(R.string.search_header_title)) }
            withTimeout(3000) {
                var searchOnly = false
                while (!searchOnly) {
                    host.main { searchOnly = host.nodes(activity).none { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger" } }
                    if (!searchOnly) delay(20)
                }
            }
            host.main {
                assertFalse(host.nodes(activity).any { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger" })
            }
            stage = "returning from Search"
            val back = host.await(activity) { host.description(it, host.context.getString(R.string.common_back)) }
            host.main { click(back) }
            host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger" }
            assertEquals(LibraryFilterMode.NEWEST_FIRST, model.filterMode.value)
            delay(500)
            stage = "restoring the visible bookmark"
            val restored = host.await(activity) { host.text(it, firstVisibleTitle) }
            assertEquals("Back must restore the same bookmark position", firstVisibleTop, restored.boundsInRoot.top, 2f)
        } catch (error: Exception) {
            host.capture("scroll-failure")
            throw AssertionError("Navigation test failed while $stage; filter=${model.filterMode.value}; fixture=${dao.getBookmarkById(ids.last())?.title}; initialized=${model.isInitialized.value}", error)
        }
        finally {
            host.main { if (initialized) model.setFilterMode(previous); activity.finish() }
            ids.forEach { dao.deleteById(it) }
        }
    }

    @Test fun drawerOpensNotificationsAndManualImportWithoutRootTabs(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            var menu = host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
            host.main { click(menu) }
            val notifications = host.await(activity) { host.description(it, host.context.getString(R.string.settings_notification_title)) }
            delay(700)
            host.capture("rail-open")
            host.main { click(notifications) }
            host.await(activity) { host.text(it, host.context.getString(R.string.settings_notification_title)) }
            delay(600)
            val back = host.await(activity) { host.description(it, host.context.getString(R.string.common_back)) }
            host.main { click(back) }
            menu = host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
            delay(600)
            host.capture("rail-closed")
            host.main { click(menu) }
            val manual = host.await(activity) { host.text(it, host.context.getString(R.string.navigation_manual_import)) }
            host.main { click(manual) }
            host.await(activity) { host.text(it, host.context.getString(R.string.import_dialog_title)) }
            val cancel = host.await(activity) { host.text(it, host.context.getString(R.string.common_cancel)) }
            host.main { click(cancel) }
            host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
        } finally { host.main { activity.finish() } }
    }

    @Test fun emptyLibrarySaveUrlOpensImportDialogAcrossThemesAndFontScales(): Unit = runBlocking {
        val host = HeaderTestHost()
        val db = AppDatabase.getInstance(host.context)
        val collectionDao = db.collectionDao()
        val prefs = host.context.getSharedPreferences("tidilo_library_prefs", android.content.Context.MODE_PRIVATE)
        val previousModePref = prefs.getString("library_filter_mode", null)
        val notificationPrefs = host.context.getSharedPreferences(NOTIFICATION_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val hadRequestedKey = notificationPrefs.contains(KEY_NOTIFICATION_PERMISSION_REQUESTED)
        val previousRequested = notificationPrefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
        assertTrue(notificationPrefs.edit().remove(KEY_NOTIFICATION_PERMISSION_REQUESTED).commit())
        val inMemoryDb = Room.inMemoryDatabaseBuilder(host.context, AppDatabase::class.java).build()
        val importJob = SupervisorJob()
        val testImportRepo = ImportRepository(
            taskDao = inMemoryDb.importTaskDao(),
            bookmarkDao = inMemoryDb.bookmarkDao(),
            notificationDao = inMemoryDb.importNotificationDao(),
            database = inMemoryDb,
            repositoryScope = CoroutineScope(Dispatchers.IO + importJob),
            articleAcquirer = { url ->
                WebPageParser.parseHtml(
                    url,
                    "<html><head><title>Denied Save Fixture</title></head><body><p>Saved without notification permission.</p></body></html>",
                    url,
                    200,
                    contentType = "text/html"
                )
            },
            reportImportError = {}
        )
        val testImportViewModel = ImportViewModel(testImportRepo)
        val launchedPermissionRequests = CopyOnWriteArrayList<Any?>()
        val deniedRegistryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?
                ) {
                    launchedPermissionRequests.add(input)
                    dispatchResult(requestCode, false)
                }
            }
        }
        val collectionId = "empty-capture-${UUID.randomUUID()}"
        lateinit var model: LibraryViewModel
        var initialized = false
        var previousMode = LibraryFilterMode.NEWEST_FIRST
        var previousCollection: String? = null
        var activity: ComponentActivity? = null
        fun runShellCommand(command: String): String {
            val pfd = host.instrumentation.uiAutomation.executeShellCommand(command)
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }
        val wasPermissionGranted = host.context.packageManager.checkPermission(
            android.Manifest.permission.POST_NOTIFICATIONS,
            host.context.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (wasPermissionGranted) {
            host.instrumentation.uiAutomation.adoptShellPermissionIdentity()
            try {
                val permManager = checkNotNull(host.context.getSystemService("permission"))
                val myUserId = android.os.UserHandle::class.java.getMethod("myUserId").invoke(null) as Int
                permManager.javaClass.getMethod(
                    "revokePostNotificationPermissionWithoutKillForTest",
                    String::class.java,
                    Int::class.javaPrimitiveType
                ).invoke(permManager, host.context.packageName, myUserId)
            } finally {
                host.instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
        }
        runShellCommand(
            "pm clear-permission-flags ${host.context.packageName} android.permission.POST_NOTIFICATIONS " +
                "user-set user-fixed review-required revoked-compat revoke-when-requested"
        )
        val platformPermissionMonitor = host.instrumentation.addMonitor(
            android.content.IntentFilter("android.content.pm.action.REQUEST_PERMISSIONS"),
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null),
            true
        )
        val contractPermissionMonitor = host.instrumentation.addMonitor(
            android.content.IntentFilter(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS
            ),
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null),
            true
        )
        try {
            val preLaunchDump = runShellCommand("dumpsys package ${host.context.packageName}")
                .lineSequence()
                .firstOrNull { line ->
                    val trimmed = line.trim()
                    trimmed.startsWith("android.permission.POST_NOTIFICATIONS:") && trimmed.contains("granted=")
                }
            assertNotNull("POST_NOTIFICATIONS runtime permission line must exist in dumpsys", preLaunchDump)
            assertTrue("Expected denied permission in dumpsys but was: $preLaunchDump", preLaunchDump!!.contains("granted=false"))
            assertFalse("POST_NOTIFICATIONS must not have USER_FIXED before launch: $preLaunchDump", preLaunchDump.contains("USER_FIXED"))
            assertFalse("POST_NOTIFICATIONS must not have USER_SET before launch: $preLaunchDump", preLaunchDump.contains("USER_SET"))
            assertFalse("POST_NOTIFICATIONS must not have POLICY_FIXED before launch: $preLaunchDump", preLaunchDump.contains("POLICY_FIXED"))
            assertFalse("POST_NOTIFICATIONS must not have SYSTEM_FIXED before launch: $preLaunchDump", preLaunchDump.contains("SYSTEM_FIXED"))
            assertEquals(
                "Test fixture requires real denied OS POST_NOTIFICATIONS permission",
                android.content.pm.PackageManager.PERMISSION_DENIED,
                host.context.packageManager.checkPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    host.context.packageName
                )
            )
            assertFalse(
                "POST_NOTIFICATIONS must be requestable rather than revoked by policy",
                host.context.packageManager.isPermissionRevokedByPolicy(
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    host.context.packageName
                )
            )
            // Use an explicit ACTION_MAIN intent so IntentFilter monitors targeting permission actions do not match null-action launches.
            val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setClass(host.context, me.bubblespark.tidilo.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val hostActivity = (host.instrumentation.startActivitySync(launchIntent) as ComponentActivity)
                .also { activity = it }
            host.await(hostActivity) { host.description(it, host.context.getString(R.string.navigation_open)) }
            assertEquals(
                android.content.pm.PackageManager.PERMISSION_DENIED,
                host.context.packageManager.checkPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    host.context.packageName
                )
            )
            assertEquals(
                android.content.pm.PackageManager.PERMISSION_DENIED,
                hostActivity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            )
            assertFalse(me.bubblespark.tidilo.data.notifications.hasPostNotificationsPermission(hostActivity))
            suspend fun awaitActiveWindowPackage(): String = withTimeout(5000) {
                while (true) {
                    val pkg = host.instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                    if (pkg != null) return@withTimeout pkg
                    delay(50)
                }
                error("Unreachable")
            }
            assertFalse(
                "POST_NOTIFICATIONS must be in initial requestable state before launch",
                hostActivity.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
            )
            assertEquals("MainActivity startup must not dispatch platform permission UI", 0, platformPermissionMonitor.hits)
            assertEquals("MainActivity startup must not dispatch permission contract", 0, contractPermissionMonitor.hits)
            assertEquals(
                "MainActivity startup must stay in Tidilo without system permission dialog",
                host.context.packageName,
                awaitActiveWindowPackage()
            )
            assertFalse(
                "Cold launch must not mark notification permission as requested",
                notificationPrefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
            )
            collectionDao.insert(CollectionEntity(collectionId, "Empty capture fixture", "", "folder", null, 0, 1L, 1L))
            host.main {
                model = ViewModelProvider(hostActivity)[LibraryViewModel::class.java]
                initialized = true
                previousMode = model.filterMode.value
                previousCollection = model.selectedCollectionId.value
                model.setFilterMode(LibraryFilterMode.COLLECTION)
            }
            withTimeout(8000) {
                while (model.collectionState.value.rows.none { it.collection.id == collectionId }) delay(20)
            }
            host.main {
                model.selectCollectionFilter(collectionId)
                hostActivity.setContent {
                    TidiloTheme {
                        CompositionLocalProvider(LocalActivityResultRegistryOwner provides deniedRegistryOwner) {
                            TidiloApp(importViewModel = testImportViewModel, libraryViewModel = model)
                        }
                    }
                }
            }
            host.await(hostActivity) { host.text(it, host.context.getString(R.string.library_empty_title)) }
            host.await(hostActivity) { host.text(it, host.context.getString(R.string.library_empty_hint)) }
            val saveButton = host.await(hostActivity) { host.text(it, host.context.getString(R.string.library_empty_save_url)) }
            val radial = host.await(hostActivity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger" }
            val density = hostActivity.resources.displayMetrics.density
            val clickableSave = clickableNode(saveButton)
            assertTrue("Save URL button must meet 48dp touch height", clickableSave.boundsInRoot.height >= 47.5f * density)
            assertFalse("Save URL button must not overlap the radial shortcut trigger",
                clickableSave.boundsInRoot.overlaps(radial.boundsInRoot))
            host.main { click(clickableSave) }
            host.await(hostActivity) { host.text(it, host.context.getString(R.string.import_dialog_title)) }

            val input = host.await(hostActivity) { it.config.getOrNull(SemanticsActions.SetText) != null }
            val testUrl = "https://empty-capture-denied.invalid/article"
            host.main { input.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(testUrl)) }
            val confirm = host.await(hostActivity) { node ->
                host.text(node, host.context.getString(R.string.import_dialog_confirm)) &&
                    clickableNode(node).config.getOrNull(SemanticsProperties.Disabled) == null
            }
            host.main { click(confirm) }
            withTimeout(5000) {
                while (inMemoryDb.bookmarkDao().getBookmarkByUrl(testUrl) == null) delay(30)
            }
            assertEquals("Manual URL saving must not dispatch platform permission UI", 0, platformPermissionMonitor.hits)
            assertEquals("Manual URL saving must not dispatch permission contract", 0, contractPermissionMonitor.hits)
            assertTrue(
                "Cold launch and manual URL saving must not launch any permission request",
                launchedPermissionRequests.isEmpty()
            )
            assertEquals(
                "Manual URL saving must remain in Tidilo without system permission dialog",
                host.context.packageName,
                awaitActiveWindowPackage()
            )
            assertFalse("Manual URL saving must not request or mark notification permission",
                notificationPrefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false))
            // Prove Activity.requestPermissions is not short-circuited and dispatches REQUEST_PERMISSIONS when invoked.
            host.main { hostActivity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 999) }
            withTimeout(3000) { while (platformPermissionMonitor.hits == 0) delay(20) }
            assertEquals(
                "Platform REQUEST_PERMISSIONS monitor must observe direct requestPermissions dispatch",
                1,
                platformPermissionMonitor.hits
            )
            host.await(hostActivity) { host.text(it, host.context.getString(R.string.library_empty_save_url)) }

            val cases = listOf(
                Triple(AppTheme.DEFAULT, false, 1f to Locale.ENGLISH),
                Triple(AppTheme.DEFAULT, true, 2f to Locale.ENGLISH),
                Triple(AppTheme.MODERN, false, 2f to Locale.JAPANESE),
                Triple(AppTheme.MODERN, true, 1f to Locale.JAPANESE)
            )
            for ((theme, dark, scaleAndLocale) in cases) {
                val (fontScale, locale) = scaleAndLocale
                val config = Configuration(host.context.resources.configuration).apply { setLocale(locale) }
                val localized = host.context.createConfigurationContext(config)
                val emptyTitle = localized.getString(R.string.library_empty_title)
                val emptyHint = localized.getString(R.string.library_empty_hint)
                val saveLabel = localized.getString(R.string.library_empty_save_url)
                var saveClicks = 0
                var ready = false
                host.main {
                    hostActivity.setContent {
                        key(theme, dark, fontScale, locale) {
                            TidiloTheme(darkTheme = dark, dynamicColor = false, theme = theme) {
                                CompositionLocalProvider(
                                    LocalConfiguration provides config,
                                    LocalContext provides localized,
                                    LocalDensity provides Density(LocalDensity.current.density, fontScale)
                                ) {
                                    Box(Modifier.size(360.dp, 640.dp).background(MaterialTheme.colorScheme.background)) {
                                        LibraryScreen(
                                            viewModel = model,
                                            onOpenManualImport = { saveClicks++ }
                                        )
                                    }
                                    SideEffect { ready = true }
                                }
                            }
                        }
                    }
                }
                withTimeout(3000) { while (!ready) delay(20) }
                host.await(hostActivity) { host.text(it, emptyTitle) }
                host.await(hostActivity) { host.text(it, emptyHint) }
                val cta = host.await(hostActivity) { host.text(it, saveLabel) }
                val ctaTarget = clickableNode(cta)
                assertTrue("$theme/$dark/$fontScale/$locale CTA must have >= 48dp height",
                    ctaTarget.boundsInRoot.height >= 47.5f * density)
                host.main { click(ctaTarget) }
                assertEquals(1, saveClicks)
            }
        } finally {
            host.instrumentation.removeMonitor(platformPermissionMonitor)
            host.instrumentation.removeMonitor(contractPermissionMonitor)
            activity?.let { current ->
                host.main {
                    if (initialized) {
                        model.selectCollectionFilter(previousCollection)
                        model.setFilterMode(previousMode)
                    }
                    current.finish()
                }
            }
            importJob.cancelAndJoin()
            inMemoryDb.close()
            val edit = prefs.edit()
            if (previousModePref == null) edit.remove("library_filter_mode") else edit.putString("library_filter_mode", previousModePref)
            edit.commit()
            val notifEdit = notificationPrefs.edit()
            if (hadRequestedKey) notifEdit.putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, previousRequested)
            else notifEdit.remove(KEY_NOTIFICATION_PERMISSION_REQUESTED)
            notifEdit.commit()
            collectionDao.delete(collectionId)
            runCatching {
                runShellCommand(
                    "pm clear-permission-flags ${host.context.packageName} android.permission.POST_NOTIFICATIONS " +
                        "user-set user-fixed review-required revoked-compat revoke-when-requested"
                )
            }
            if (wasPermissionGranted) {
                host.instrumentation.uiAutomation.grantRuntimePermission(
                    host.context.packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }
}
