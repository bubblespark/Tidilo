package me.bubblespark.tidilo.data

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import me.bubblespark.tidilo.R
import org.junit.Assert.assertEquals
import org.junit.Test

class RssCounterResourcesTest {
    @Test fun englishFeedAndBookmarkCountersUseSingularOnlyForOne() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val resources = context.createConfigurationContext(configuration).resources
        for ((id, noun) in listOf(R.plurals.rss_subtitle_feeds to "feed", R.plurals.rss_subtitle_bookmarks to "bookmark")) {
            for (count in listOf(0, 1, 2)) {
                assertEquals("$count $noun${if (count == 1) "" else "s"}", resources.getQuantityString(id, count, count))
            }
        }
    }
}
