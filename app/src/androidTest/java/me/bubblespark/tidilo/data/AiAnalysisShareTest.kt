package me.bubblespark.tidilo.data

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.ShareReceiverActivity
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Test

class AiAnalysisShareTest {
    @Test fun disabledShareSavesParsedDescriptionAndPostsBookmarkAdded(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalMode = AppConfig.aiAnalysisMode.value
        org.junit.Assume.assumeTrue("Grant notification permission before running this test",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val serving = AtomicBoolean(true)
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/article-${UUID.randomUUID()}"
        val worker = thread(isDaemon = true, name = "issue125-publisher") {
            while (serving.get()) {
                try {
                    server.accept().use { client ->
                        val input = client.getInputStream().bufferedReader()
                        val request = input.readLine().orEmpty()
                        while (!input.readLine().isNullOrEmpty()) { /* Consume HTTP headers. */ }
                        val article = request.contains("/article-")
                        val body = if (article) "<html><head><title>Shared fixture</title><meta name='description' content='Publisher Description'></head><body><article><p>Acquired article body for the direct share fixture.</p></article></body></html>" else ""
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val status = if (article) "200 OK" else "404 Not Found"
                        client.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } catch (_: java.io.IOException) { /* Closing the fixture server ends the loop. */ }
            }
        }
        val db = AppDatabase.getInstance(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        try {
            AppConfig.setAiAnalysisMode(AiAnalysisMode.DISABLE)
            context.startActivity(Intent(context, ShareReceiverActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            withTimeout(15000) {
                while (db.bookmarkDao().getBookmarkByUrl(url) == null) delay(30)
                while (notifications.activeNotifications.none {
                    it.notification.extras.getCharSequence("android.text")?.toString() == "Bookmark added"
                }) delay(30)
            }
            val bookmark = db.bookmarkDao().getBookmarkByUrl(url)!!
            assertEquals("Publisher Description", bookmark.description)
            assertEquals("Shared fixture", bookmark.title)
            assertTrue(bookmark.isAiSkipped)
            assertNull(bookmark.category)
            assertTrue(bookmark.entities.isEmpty())
            assertNull(db.importTaskDao().getTaskByUrl(url))
        } finally {
            db.bookmarkDao().getBookmarkByUrl(url)?.let { db.bookmarkDao().deleteById(it.id) }
            db.importTaskDao().getTaskByUrl(url)?.let { db.importTaskDao().deleteById(it.id) }
            notifications.cancel(1001)
            AppConfig.setAiAnalysisMode(originalMode)
            serving.set(false)
            server.close()
            worker.join(1000)
        }
    }
}
