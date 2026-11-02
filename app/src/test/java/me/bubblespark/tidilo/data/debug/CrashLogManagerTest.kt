package me.bubblespark.tidilo.data.debug

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrashLogManagerTest {

    private lateinit var tempDir: File
    private lateinit var fakeContext: Context
    private var originalDefaultHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("crash_log_test").toFile()
        fakeContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = File(tempDir, "files").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        originalDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalDefaultHandler)
        tempDir.deleteRecursively()
    }

    @Test
    fun testRecordCrashPersistsToJsonFile() {
        val exception = IllegalArgumentException("Test crash message")
        val thread = Thread.currentThread()

        CrashLogManager.recordCrash(fakeContext, thread, exception)

        val logs = CrashLogManager.getCrashLogs(fakeContext)
        assertEquals(1, logs.size)

        val entry = logs[0]
        assertNotNull(entry.id)
        assertTrue(entry.timestamp > 0)
        assertEquals(IllegalArgumentException::class.java.name, entry.exceptionClass)
        assertEquals("Test crash message", entry.message)
        assertTrue(entry.stackTrace.contains("IllegalArgumentException: Test crash message"))
        assertEquals(thread.name, entry.threadName)
        assertNotNull(entry.appVersion)
        assertNotNull(entry.osVersion)
        assertNotNull(entry.deviceModel)

        val logFile = File(fakeContext.filesDir, CrashLogManager.FILE_NAME)
        assertTrue(logFile.exists())
    }

    @Test
    fun testRotationPreservesTenMostRecentEntries() {
        val totalCrashes = 15
        for (i in 1..totalCrashes) {
            val exception = RuntimeException("Crash #$i")
            CrashLogManager.recordCrash(fakeContext, Thread.currentThread(), exception)
        }

        val logs = CrashLogManager.getCrashLogs(fakeContext)
        assertEquals(CrashLogManager.MAX_LOGS, logs.size)
        assertEquals(10, logs.size)

        // Newest entry should be Crash #15, oldest remaining should be Crash #6
        assertEquals("Crash #15", logs[0].message)
        assertEquals("Crash #6", logs[9].message)

        // Verify older entries (Crash #1 through #5) were dropped
        for (i in 1..5) {
            val found = logs.any { it.message == "Crash #$i" }
            assertTrue("Expected Crash #$i to have been purged by FIFO rotation", !found)
        }
    }

    @Test
    fun testCorruptFileRecovery() {
        val logFile = File(fakeContext.filesDir, CrashLogManager.FILE_NAME)
        logFile.parentFile?.mkdirs()
        logFile.writeText("Corrupted { invalid JSON content [")

        // Reading corrupt file should not crash and should return empty list
        val logsBefore = CrashLogManager.getCrashLogs(fakeContext)
        assertTrue(logsBefore.isEmpty())

        // Recording a new crash should recover the file gracefully
        val exception = IllegalStateException("Recovered crash")
        CrashLogManager.recordCrash(fakeContext, Thread.currentThread(), exception)

        val logsAfter = CrashLogManager.getCrashLogs(fakeContext)
        assertEquals(1, logsAfter.size)
        assertEquals("Recovered crash", logsAfter[0].message)
    }

    @Test
    fun testClearLogsRemovesStoredEntries() {
        CrashLogManager.recordCrash(
            fakeContext,
            Thread.currentThread(),
            RuntimeException("Crash before clear")
        )
        assertEquals(1, CrashLogManager.getCrashLogs(fakeContext).size)

        CrashLogManager.clearLogs(fakeContext)

        val logs = CrashLogManager.getCrashLogs(fakeContext)
        assertTrue(logs.isEmpty())
        val logFile = File(fakeContext.filesDir, CrashLogManager.FILE_NAME)
        assertTrue(!logFile.exists())
    }

    @Test
    fun testUncaughtExceptionHandlerChaining() {
        var previousHandlerInvoked = false
        val previousHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
            previousHandlerInvoked = true
        }
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)

        CrashLogManager.initialize(fakeContext)

        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(currentHandler)

        val testException = RuntimeException("Uncaught test crash")
        currentHandler?.uncaughtException(Thread.currentThread(), testException)

        // Verify crash was recorded
        val logs = CrashLogManager.getCrashLogs(fakeContext)
        assertEquals(1, logs.size)
        assertEquals("Uncaught test crash", logs[0].message)

        // Verify previous handler was invoked
        assertTrue(previousHandlerInvoked)
    }

    @Test
    fun testLegacyJsonCompatibilityHandlesMissingMetadataFieldsGracefully() {
        val legacyJson = """
            [
              {
                "id": "legacy-id-1",
                "timestamp": 1700000000000,
                "exceptionClass": "java.lang.NullPointerException",
                "message": "Legacy crash without device info",
                "stackTrace": "java.lang.NullPointerException\n\tat me.test.run(Test.kt:1)",
                "threadName": "main"
              }
            ]
        """.trimIndent()

        val logFile = File(fakeContext.filesDir, CrashLogManager.FILE_NAME)
        logFile.parentFile?.mkdirs()
        logFile.writeText(legacyJson)

        val logs = CrashLogManager.getCrashLogs(fakeContext)
        assertEquals(1, logs.size)
        val entry = logs[0]
        assertEquals("legacy-id-1", entry.id)
        assertEquals("Legacy crash without device info", entry.message)
        assertEquals("Unknown", entry.appVersion)
        assertEquals("Unknown", entry.osVersion)
        assertEquals("Unknown", entry.deviceModel)
    }
}
