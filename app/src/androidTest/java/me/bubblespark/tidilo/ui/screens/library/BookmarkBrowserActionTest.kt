package me.bubblespark.tidilo.ui.screens.library

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkBrowserActionTest {
    @Test fun callbackRunsOnlyAfterSuccessfulLaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var launches = 0
        var callbacks = 0
        var shouldFail = false
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun startActivity(intent: Intent) {
                launches++
                if (shouldFail) throw ActivityNotFoundException()
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals("https://example.com", intent.data.toString())
            }
        }
        instrumentation.runOnMainSync {
            openBookmarkInBrowser(context, "https://example.com") { callbacks++ }
            shouldFail = true
            openBookmarkInBrowser(context, "https://example.com") { callbacks++ }
        }
        assertEquals(2, launches)
        assertEquals(1, callbacks)
    }
}
