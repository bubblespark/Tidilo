package me.bubblespark.tidilo.ui.screens.settings

import me.bubblespark.tidilo.data.debug.CrashLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class SettingsDebugScreenTest {

    @Test
    fun testFormatTimestamp() {
        val fixedTime = 1700000000000L // 2023-11-14 22:13:20 UTC
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val formatted = CrashLogUiFormatter.formatTimestamp(fixedTime, Locale.US)
            assertEquals("2023-11-14 22:13:20", formatted)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun testFormatForClipboardWithAllFields() {
        val entry = CrashLogEntry(
            id = "test-id-1",
            timestamp = 1700000000000L,
            exceptionClass = "java.lang.NullPointerException",
            message = "Test null pointer",
            stackTrace = "java.lang.NullPointerException: Test null pointer\n\tat me.bubblespark.tidilo.Test.run(Test.kt:10)",
            threadName = "main"
        )

        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val formatted = CrashLogUiFormatter.formatForClipboard(entry, Locale.US)

            assertTrue(formatted.contains("Timestamp: 2023-11-14 22:13:20"))
            assertTrue(formatted.contains("App Version: ${entry.appVersion}"))
            assertTrue(formatted.contains("Device: ${entry.deviceModel}"))
            assertTrue(formatted.contains("OS: ${entry.osVersion}"))
            assertTrue(formatted.contains("Thread: main"))
            assertTrue(formatted.contains("Exception: java.lang.NullPointerException"))
            assertTrue(formatted.contains("Message: Test null pointer"))
            assertTrue(formatted.contains("Stack Trace:"))
            assertTrue(formatted.contains("at me.bubblespark.tidilo.Test.run(Test.kt:10)"))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun testFormatForClipboardWithNullMessage() {
        val entry = CrashLogEntry(
            id = "test-id-2",
            timestamp = 1700000000000L,
            exceptionClass = "java.lang.IllegalStateException",
            message = null,
            stackTrace = "java.lang.IllegalStateException\n\tat me.bubblespark.tidilo.Test.run(Test.kt:20)",
            threadName = "bg-worker"
        )

        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val formatted = CrashLogUiFormatter.formatForClipboard(entry, Locale.US)

            assertTrue(formatted.contains("Timestamp: 2023-11-14 22:13:20"))
            assertTrue(formatted.contains("App Version: ${entry.appVersion}"))
            assertTrue(formatted.contains("Device: ${entry.deviceModel}"))
            assertTrue(formatted.contains("OS: ${entry.osVersion}"))
            assertTrue(formatted.contains("Thread: bg-worker"))
            assertTrue(formatted.contains("Exception: java.lang.IllegalStateException"))
            assertTrue(formatted.contains("Message: None"))
            assertTrue(formatted.contains("Stack Trace:"))
            assertTrue(formatted.contains("at me.bubblespark.tidilo.Test.run(Test.kt:20)"))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }
}
