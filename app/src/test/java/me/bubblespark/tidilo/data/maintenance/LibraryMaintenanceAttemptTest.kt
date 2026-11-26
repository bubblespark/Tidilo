package me.bubblespark.tidilo.data.maintenance

import androidx.work.ListenableWorker
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LibraryMaintenanceAttemptTest {
    @Test fun failuresRetryWithoutRealignmentAndCancellationPropagates() = runTest {
        var aligned = false
        assertEquals(ListenableWorker.Result.retry(), runLibraryMaintenanceAttempt(
            clean = { throw IOException("fixture") }, realign = { aligned = true }))
        assertFalse(aligned)
        try {
            runLibraryMaintenanceAttempt(clean = { throw CancellationException("fixture") }, realign = { aligned = true })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(aligned)
        assertEquals(ListenableWorker.Result.success(), runLibraryMaintenanceAttempt(clean = {}, realign = { aligned = true }))
        assertTrue(aligned)
    }
}
