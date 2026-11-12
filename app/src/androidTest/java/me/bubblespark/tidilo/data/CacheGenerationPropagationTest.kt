package me.bubblespark.tidilo.data

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.ui.components.LocalDomainIconCacheGeneration
import me.bubblespark.tidilo.ui.components.LocalOgImageCacheGeneration
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class CacheGenerationPropagationTest {

    @Test
    fun scaffoldPropagatesCacheGenerationsAndUpdatesOnClear(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as ComponentActivity

        val observedDomainGen = AtomicLong(-1L)
        val observedOgGen = AtomicLong(-1L)

        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    TidiloScreenScaffold(
                        header = { Text("Header") }
                    ) {
                        val domainGen = LocalDomainIconCacheGeneration.current
                        val ogGen = LocalOgImageCacheGeneration.current
                        SideEffect {
                            observedDomainGen.set(domainGen)
                            observedOgGen.set(ogGen)
                        }
                    }
                }
            }

            // Wait for initial composition to complete and values to be populated
            withTimeout(5000) {
                while (observedDomainGen.get() < 0L || observedOgGen.get() < 0L) {
                    delay(20)
                }
            }

            val initialDomainGen = observedDomainGen.get()
            val initialOgGen = observedOgGen.get()

            // Trigger cache clears
            DomainIconManager.clearCache(context)
            OgImageManager.clearImageCache(context)

            // Verify that the scaffold recomposes and propagates the incremented generations
            withTimeout(5000) {
                while (observedDomainGen.get() <= initialDomainGen || observedOgGen.get() <= initialOgGen) {
                    delay(20)
                }
            }

            assertTrue(observedDomainGen.get() > initialDomainGen)
            assertTrue(observedOgGen.get() > initialOgGen)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
