package me.bubblespark.tidilo.data.debug

import android.content.Context
import android.os.Build
import me.bubblespark.tidilo.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class CrashLogEntry(
    val id: String,
    val timestamp: Long,
    val exceptionClass: String,
    val message: String?,
    val stackTrace: String,
    val threadName: String,
    val appVersion: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    val osVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"
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
            threadName = thread.name,
            appVersion = resolveAppVersion(context),
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
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

    private fun resolveAppVersion(context: Context): String {
        return runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.longVersionCode})"
        }.getOrElse {
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
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
                        threadName = obj.optString("threadName", "unknown"),
                        appVersion = obj.optString("appVersion", "Unknown"),
                        osVersion = obj.optString("osVersion", "Unknown"),
                        deviceModel = obj.optString("deviceModel", "Unknown")
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
            obj.put("appVersion", entry.appVersion)
            obj.put("osVersion", entry.osVersion)
            obj.put("deviceModel", entry.deviceModel)
            jsonArray.put(obj)
        }

        val jsonBytes = jsonArray.toString(2).toByteArray(Charsets.UTF_8)
        val tempFile = File(file.parentFile, "${file.name}.tmp")

        try {
            // Write to temporary file with forced disk sync (fsync)
            FileOutputStream(tempFile).use { fos ->
                fos.write(jsonBytes)
                fos.flush()
                fos.fd.sync()
            }
            if (!tempFile.renameTo(file)) {
                // If atomic rename fails, write directly and sync
                FileOutputStream(file).use { fos ->
                    fos.write(jsonBytes)
                    fos.flush()
                    fos.fd.sync()
                }
                tempFile.delete()
            }
        } catch (_: Exception) {
            // Last resort: attempt direct write to ensure logs are never lost on crash
            runCatching {
                FileOutputStream(file).use { fos ->
                    fos.write(jsonBytes)
                    fos.flush()
                    fos.fd.sync()
                }
            }
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
