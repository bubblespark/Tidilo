package me.bubblespark.tidilo.data

import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.TidiloApp
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.CollectionEntity
import me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED
import me.bubblespark.tidilo.data.notifications.NOTIFICATION_PREFS_NAME
import me.bubblespark.tidilo.parser.WebPageParser
import me.bubblespark.tidilo.ui.screens.importscreen.ImportViewModel
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real OS `POST_NOTIFICATIONS` denial: a cold `MainActivity` launch and a manual URL save from the empty
 * Library never request the notification permission.
 */
class NotificationPermissionColdLaunchTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    /** Dialogs swap window focus; for a moment no Compose root is reachable. */
    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        runCatching { composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes() }.getOrDefault(emptyList())

    private fun await(matcher: SemanticsMatcher): SemanticsNode {
        composeRule.waitUntil(8_000) { nodes(matcher).isNotEmpty() }
        return nodes(matcher).first()
    }

    private fun clickableNode(node: SemanticsNode): SemanticsNode {
        var target = node
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
        return target
    }

    private fun click(node: SemanticsNode) {
        val target = clickableNode(node)
        composeRule.runOnUiThread { check(target.config[SemanticsActions.OnClick].action?.invoke() == true) }
        composeRule.waitForIdle()
    }

    @Test fun coldLaunchAndManualSaveNeverRequestNotificationPermission(): Unit = runBlocking {
        val notificationPrefs = context.getSharedPreferences(NOTIFICATION_PREFS_NAME, Context.MODE_PRIVATE)
        val hadRequestedKey = notificationPrefs.contains(KEY_NOTIFICATION_PERMISSION_REQUESTED)
        val previousRequested = notificationPrefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
        val oldBlur = AppConfig.overlayBlurEnabled.value
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) { InMemoryLibrary(context).also { it.awaitReady() } }
        val importJob = SupervisorJob()
        val testImportRepo = ImportRepository(
            taskDao = library.db.importTaskDao(),
            bookmarkDao = library.db.bookmarkDao(),
            notificationDao = library.db.importNotificationDao(),
            database = library.db,
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
        val collectionId = "empty-capture-denied"
        var scenario: ActivityScenario<MainActivity>? = null
        fun runShellCommand(command: String): String {
            val pfd = instrumentation.uiAutomation.executeShellCommand(command)
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }
        fun runCheckedPmCommand(command: String) {
            val output = runShellCommand(command)
            check(output.isBlank()) { "Unexpected pm output for '$command': ${output.trim()}" }
        }
        val myUserHandle = android.os.Process.myUserHandle()
        val myUserId = android.os.UserHandle::class.java.getMethod("myUserId").invoke(null) as Int
        fun isPostNotificationsGranted(): Boolean = context.packageManager.checkPermission(
            android.Manifest.permission.POST_NOTIFICATIONS,
            context.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        fun revokePostNotificationsWithoutKill() {
            instrumentation.uiAutomation.adoptShellPermissionIdentity()
            try {
                val permManager = checkNotNull(context.getSystemService("permission"))
                permManager.javaClass.getMethod(
                    "revokePostNotificationPermissionWithoutKillForTest",
                    String::class.java,
                    Int::class.javaPrimitiveType
                ).invoke(permManager, context.packageName, myUserId)
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
        }
        fun readPostNotifDumpLineOrNull(): String? {
            val dump = runShellCommand("dumpsys package ${context.packageName}")
            var seenTargetUserBlock = false
            var inTargetUserBlock = false
            var targetUserIndent = Int.MAX_VALUE
            var inRuntimePermissions = false
            var runtimePermissionsIndent = Int.MAX_VALUE
            for (rawLine in dump.lineSequence()) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) {
                    if (inTargetUserBlock) {
                        inTargetUserBlock = false
                        inRuntimePermissions = false
                    }
                    continue
                }
                val indent = rawLine.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) rawLine.length else it }
                if (!seenTargetUserBlock &&
                    trimmed.startsWith("User $myUserId:") &&
                    (trimmed.contains("installed=") || trimmed.contains("ceDataInode="))
                ) {
                    seenTargetUserBlock = true
                    inTargetUserBlock = true
                    targetUserIndent = indent
                    inRuntimePermissions = false
                    continue
                }
                if (inTargetUserBlock) {
                    if (indent <= targetUserIndent) {
                        inTargetUserBlock = false
                        inRuntimePermissions = false
                        continue
                    }
                    if (trimmed == "runtime permissions:") {
                        inRuntimePermissions = true
                        runtimePermissionsIndent = indent
                        continue
                    }
                    if (inRuntimePermissions) {
                        if (indent <= runtimePermissionsIndent) {
                            inRuntimePermissions = false
                        } else if (trimmed.startsWith("android.permission.POST_NOTIFICATIONS:") &&
                            trimmed.contains("granted=")
                        ) {
                            return trimmed
                        }
                    }
                }
            }
            check(seenTargetUserBlock) {
                "Target user $myUserId section missing in dumpsys package ${context.packageName}"
            }
            return null
        }
        val managedFlagMap = listOf(
            "USER_SET" to "user-set",
            "USER_FIXED" to "user-fixed",
            "REVIEW_REQUIRED" to "review-required",
            "REVOKED_COMPAT" to "revoked-compat",
            "REVOKE_WHEN_REQUESTED" to "revoke-when-requested"
        )
        fun extractManagedFlags(dumpLine: String?): List<String> =
            if (dumpLine == null) {
                emptyList()
            } else {
                managedFlagMap
                    .filter { (dumpName, _) -> Regex("\\b$dumpName\\b").containsMatchIn(dumpLine) }
                    .map { it.second }
            }
        val allManagedFlagArgs = managedFlagMap.joinToString(" ") { it.second }
        val initialDumpLine = readPostNotifDumpLineOrNull()
        val wasPermissionGranted = isPostNotificationsGranted()
        if (initialDumpLine == null) {
            check(!wasPermissionGranted) {
                "POST_NOTIFICATIONS runtime permission line missing while granted for user $myUserId"
            }
        }
        val initialManagedFlags = extractManagedFlags(initialDumpLine)
        val platformPermissionMonitor = instrumentation.addMonitor(
            android.content.IntentFilter("android.content.pm.action.REQUEST_PERMISSIONS"),
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null),
            true
        )
        val contractPermissionMonitor = instrumentation.addMonitor(
            android.content.IntentFilter(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS
            ),
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null),
            true
        )
        var primaryFailure: Throwable? = null
        fun recordFailure(failure: Throwable) {
            val existing = primaryFailure
            if (existing == null) {
                primaryFailure = failure
            } else {
                existing.addSuppressed(failure)
            }
        }
        suspend fun runCleanupStep(block: suspend () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                recordFailure(t)
            }
        }
        try {
            AppConfig.setOverlayBlurEnabled(false)
            assertTrue(notificationPrefs.edit().remove(KEY_NOTIFICATION_PERMISSION_REQUESTED).commit())
            if (wasPermissionGranted) {
                revokePostNotificationsWithoutKill()
            }
            runCheckedPmCommand(
                "pm clear-permission-flags --user $myUserId ${context.packageName} " +
                    "android.permission.POST_NOTIFICATIONS $allManagedFlagArgs"
            )
            val preLaunchDump = readPostNotifDumpLineOrNull()
            if (preLaunchDump != null) {
                assertTrue("Expected denied permission in dumpsys but was: $preLaunchDump", preLaunchDump.contains("granted=false"))
                assertFalse("POST_NOTIFICATIONS must not have USER_FIXED before launch: $preLaunchDump", preLaunchDump.contains("USER_FIXED"))
                assertFalse("POST_NOTIFICATIONS must not have USER_SET before launch: $preLaunchDump", preLaunchDump.contains("USER_SET"))
                assertFalse("POST_NOTIFICATIONS must not have POLICY_FIXED before launch: $preLaunchDump", preLaunchDump.contains("POLICY_FIXED"))
                assertFalse("POST_NOTIFICATIONS must not have SYSTEM_FIXED before launch: $preLaunchDump", preLaunchDump.contains("SYSTEM_FIXED"))
            }
            assertEquals(
                "Test fixture requires real denied OS POST_NOTIFICATIONS permission",
                android.content.pm.PackageManager.PERMISSION_DENIED,
                context.packageManager.checkPermission(android.Manifest.permission.POST_NOTIFICATIONS, context.packageName)
            )
            assertFalse(
                "POST_NOTIFICATIONS must be requestable rather than revoked by policy",
                context.packageManager.isPermissionRevokedByPolicy(android.Manifest.permission.POST_NOTIFICATIONS, context.packageName)
            )
            // Use an explicit ACTION_MAIN intent so IntentFilter monitors targeting permission actions do not match null-action launches.
            val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setClass(context, MainActivity::class.java)
            val launched = ActivityScenario.launch<MainActivity>(launchIntent).also { scenario = it }
            await(hasContentDescription(context.getString(R.string.navigation_open)))
            assertEquals(
                android.content.pm.PackageManager.PERMISSION_DENIED,
                context.packageManager.checkPermission(android.Manifest.permission.POST_NOTIFICATIONS, context.packageName)
            )
            launched.onActivity { activity ->
                assertEquals(
                    android.content.pm.PackageManager.PERMISSION_DENIED,
                    activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                )
                assertFalse(me.bubblespark.tidilo.data.notifications.hasPostNotificationsPermission(activity))
                assertFalse(
                    "POST_NOTIFICATIONS must be in initial requestable state before launch",
                    activity.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
                )
            }
            suspend fun awaitActiveWindowPackage(): String = withTimeout(5000) {
                while (true) {
                    val pkg = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                    if (pkg != null) return@withTimeout pkg
                    delay(100)
                }
                error("Unreachable")
            }
            assertEquals("MainActivity startup must not dispatch platform permission UI", 0, platformPermissionMonitor.hits)
            assertEquals("MainActivity startup must not dispatch permission contract", 0, contractPermissionMonitor.hits)
            assertEquals(
                "MainActivity startup must stay in Tidilo without system permission dialog",
                context.packageName,
                awaitActiveWindowPackage()
            )
            assertFalse(
                "Cold launch must not mark notification permission as requested",
                notificationPrefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
            )
            // The save flow runs on the launched Activity, behind a registry that denies every permission request.
            val model = library.viewModel()
            launched.onActivity { activity ->
                model.setFilterMode(LibraryFilterMode.COLLECTION)
                activity.setContent {
                    TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                        CompositionLocalProvider(LocalActivityResultRegistryOwner provides deniedRegistryOwner) {
                            TidiloApp(importViewModel = testImportViewModel, libraryViewModel = model)
                        }
                    }
                }
            }
            // The app starts on Discover; the toolbar opens the Library layer.
            click(await(hasTestTag("floating_nav_library")))
            withContext(Dispatchers.IO) {
                library.db.collectionDao().insert(CollectionEntity(collectionId, "Empty capture fixture", "", "folder", null, 0, 1L, 1L))
            }
            // Collection rows are collected only while the Library UI observes them.
            composeRule.waitUntil(8_000) { model.collectionState.value.rows.any { it.collection.id == collectionId } }
            composeRule.runOnIdle { model.selectCollectionFilter(collectionId) }
            await(hasText(context.getString(R.string.library_empty_title)))
            await(hasText(context.getString(R.string.library_empty_hint)))
            val saveButton = clickableNode(await(hasText(context.getString(R.string.library_empty_save_url))))
            val toolbar = await(hasTestTag("floating_nav"))
            val density = context.resources.displayMetrics.density
            assertTrue("Save URL button must meet 48dp touch height", saveButton.boundsInRoot.height >= 47.5f * density)
            assertFalse("Save URL button must not overlap the floating toolbar",
                saveButton.boundsInRoot.overlaps(toolbar.boundsInRoot))
            click(saveButton)
            await(hasText(context.getString(R.string.import_dialog_title)))
            val input = await(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
            val testUrl = "https://empty-capture-denied.invalid/article"
            composeRule.runOnUiThread { input.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(testUrl)) }
            val confirmLabel = context.getString(R.string.import_dialog_confirm)
            fun enabledConfirm() = nodes(hasText(confirmLabel)).firstOrNull { clickableNode(it).config.getOrNull(SemanticsProperties.Disabled) == null }
            composeRule.waitUntil(8_000) { enabledConfirm() != null }
            click(checkNotNull(enabledConfirm()))
            withTimeout(5000) {
                while (withContext(Dispatchers.IO) { library.db.bookmarkDao().getBookmarkByUrl(testUrl) } == null) delay(100)
            }
            assertEquals("Manual URL saving must not dispatch platform permission UI", 0, platformPermissionMonitor.hits)
            assertEquals("Manual URL saving must not dispatch permission contract", 0, contractPermissionMonitor.hits)
            assertTrue(
                "Cold launch and manual URL saving must not launch any permission request",
                launchedPermissionRequests.isEmpty()
            )
            assertEquals(
                "Manual URL saving must remain in Tidilo without system permission dialog",
                context.packageName,
                awaitActiveWindowPackage()
            )
            assertFalse("Manual URL saving must not request or mark notification permission",
                notificationPrefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false))
            // Prove Activity.requestPermissions is not short-circuited and dispatches REQUEST_PERMISSIONS when invoked.
            launched.onActivity { it.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 999) }
            withTimeout(3000) { while (platformPermissionMonitor.hits == 0) delay(100) }
            assertEquals(
                "Platform REQUEST_PERMISSIONS monitor must observe direct requestPermissions dispatch",
                1,
                platformPermissionMonitor.hits
            )
            await(hasText(context.getString(R.string.library_empty_save_url)))
        } catch (t: Throwable) {
            recordFailure(t)
        } finally {
            withContext(NonCancellable) {
                runCleanupStep { instrumentation.removeMonitor(platformPermissionMonitor) }
                runCleanupStep { instrumentation.removeMonitor(contractPermissionMonitor) }
                runCleanupStep { scenario?.close() }
                runCleanupStep { importJob.cancelAndJoin() }
                runCleanupStep { library.close() }
                runCleanupStep {
                    AppConfig.setOverlayBlurEnabled(oldBlur)
                    caches.close()
                }
                runCleanupStep {
                    val notifEdit = notificationPrefs.edit()
                    if (hadRequestedKey) notifEdit.putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, previousRequested)
                    else notifEdit.remove(KEY_NOTIFICATION_PERMISSION_REQUESTED)
                    check(notifEdit.commit()) { "Failed to restore $KEY_NOTIFICATION_PERMISSION_REQUESTED preference" }
                }
                runCleanupStep {
                    if (wasPermissionGranted) {
                        instrumentation.uiAutomation.grantRuntimePermissionAsUser(
                            context.packageName,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                            myUserHandle
                        )
                    } else if (isPostNotificationsGranted()) {
                        revokePostNotificationsWithoutKill()
                    }
                    runCheckedPmCommand(
                        "pm clear-permission-flags --user $myUserId ${context.packageName} " +
                            "android.permission.POST_NOTIFICATIONS $allManagedFlagArgs"
                    )
                    if (initialManagedFlags.isNotEmpty()) {
                        runCheckedPmCommand(
                            "pm set-permission-flags --user $myUserId ${context.packageName} " +
                                "android.permission.POST_NOTIFICATIONS ${initialManagedFlags.joinToString(" ")}"
                        )
                    }
                    val restoredGranted = isPostNotificationsGranted()
                    check(restoredGranted == wasPermissionGranted) {
                        "Failed to restore POST_NOTIFICATIONS grant state: expected=$wasPermissionGranted actual=$restoredGranted"
                    }
                    val restoredDumpLine = readPostNotifDumpLineOrNull()
                    if (wasPermissionGranted) {
                        check(restoredDumpLine != null && restoredDumpLine.contains("granted=true")) {
                            "Restored POST_NOTIFICATIONS dump did not reflect granted=true: $restoredDumpLine"
                        }
                    } else if (restoredDumpLine != null) {
                        check(restoredDumpLine.contains("granted=false")) {
                            "Restored POST_NOTIFICATIONS dump did not reflect granted=false: $restoredDumpLine"
                        }
                    }
                    val restoredManagedFlags = extractManagedFlags(restoredDumpLine).toSet()
                    check(restoredManagedFlags == initialManagedFlags.toSet()) {
                        "Failed to restore POST_NOTIFICATIONS managed flags: expected=${initialManagedFlags.toSet()} actual=$restoredManagedFlags"
                    }
                }
            }
            primaryFailure?.let { throw it }
        }
    }
}
