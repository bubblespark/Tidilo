package me.bubblespark.tidilo.data.debug

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CrashLogEntry(
    val id: String,
    val timestamp: Long,
    val exceptionClass: String,
    val message: String?,
    val stackTrace: String,
    val threadName: String
)

object CrashLogManager {
    const val MAX_LOGS = 10
    const val FILE_NAME = "crash_logs.json"

    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
            val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (currentHandler is CrashUncaughtExceptionHandler) return

            Thread.setDefaultUncaughtExceptionHandler(
                CrashUncaughtExceptionHandler(appContext, currentHandler)
            )
        }
    }

    fun getCrashLogs(context: Context): List<CrashLogEntry> {
        return synchronized(lock) {
            readLogsInternal(context)
        }
    }

    fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val entry = CrashLogEntry(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            exceptionClass = throwable.javaClass.name,
            message = throwable.message,
            stackTrace = throwable.stackTraceToString(),
            threadName = thread.name
        )
        recordCrash(context, entry)
    }

    fun recordCrash(context: Context, entry: CrashLogEntry) {
        synchronized(lock) {
            runCatching {
                val existing = readLogsInternal(context)
                val updated = (listOf(entry) + existing).take(MAX_LOGS)
                writeLogsInternal(context, updated)
            }
        }
    }

    fun clearLogs(context: Context) {
        synchronized(lock) {
            runCatching {
                val file = getCrashLogFile(context)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    private fun getCrashLogFile(context: Context): File {
        val dir = runCatching { (context.applicationContext ?: context).filesDir }
            .getOrElse { context.filesDir }
        return File(dir, FILE_NAME)
    }

    private fun readLogsInternal(context: Context): List<CrashLogEntry> {
        return runCatching {
            val file = getCrashLogFile(context)
            if (!file.exists()) return emptyList()
            val text = file.readText()
            if (text.isBlank()) return emptyList()

            val jsonArray = JSONArray(text)
            val logs = mutableListOf<CrashLogEntry>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                logs.add(
                    CrashLogEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        timestamp = obj.optLong("timestamp", 0L),
                        exceptionClass = obj.optString("exceptionClass", "UnknownException"),
                        message = if (obj.isNull("message")) null else obj.optString("message"),
                        stackTrace = obj.optString("stackTrace", ""),
                        threadName = obj.optString("threadName", "unknown")
                    )
                )
            }
            logs
        }.getOrElse { emptyList() }
    }

    private fun writeLogsInternal(context: Context, logs: List<CrashLogEntry>) {
        val file = getCrashLogFile(context)
        file.parentFile?.mkdirs()

        val jsonArray = JSONArray()
        for (entry in logs) {
            val obj = JSONObject()
            obj.put("id", entry.id)
            obj.put("timestamp", entry.timestamp)
            obj.put("exceptionClass", entry.exceptionClass)
            if (entry.message != null) {
                obj.put("message", entry.message)
            } else {
                obj.put("message", JSONObject.NULL)
            }
            obj.put("stackTrace", entry.stackTrace)
            obj.put("threadName", entry.threadName)
            jsonArray.put(obj)
        }

        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeText(jsonArray.toString(2))
        if (!tempFile.renameTo(file)) {
            file.writeText(jsonArray.toString(2))
            tempFile.delete()
        }
    }

    private class CrashUncaughtExceptionHandler(
        private val context: Context,
        private val originalHandler: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            runCatching {
                recordCrash(context, thread, throwable)
            }
            originalHandler?.uncaughtException(thread, throwable)
        }
    }
}
