package me.bubblespark.tidilo.data

import androidx.work.WorkInfo
import me.bubblespark.tidilo.ui.screens.settings.ManualFetchStatus
import me.bubblespark.tidilo.ui.screens.settings.manualFetchStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RssManualFetchStatusTest {
    @Test fun mapsUnfinishedWorkToWaitingOrRunningAndFinishedWorkToIdle() {
        assertEquals(ManualFetchStatus.Idle, manualFetchStatus(emptyList()))
        for (state in listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) {
            assertEquals(ManualFetchStatus.Idle, manualFetchStatus(listOf(state)))
        }
        for (state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)) {
            assertEquals(ManualFetchStatus.Waiting, manualFetchStatus(listOf(state)))
        }
        assertEquals(ManualFetchStatus.Running, manualFetchStatus(listOf(WorkInfo.State.RUNNING)))
        assertEquals(ManualFetchStatus.Running, manualFetchStatus(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)))
    }
}
