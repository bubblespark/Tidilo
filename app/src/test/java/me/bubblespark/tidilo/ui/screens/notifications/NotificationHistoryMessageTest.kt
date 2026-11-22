package me.bubblespark.tidilo.ui.screens.notifications

import me.bubblespark.tidilo.data.ImportNotificationMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationHistoryMessageTest {
    @Test fun knownLiteralsMapToKinds() {
        assertEquals(HistoryKind.DUPLICATE_IN_LIBRARY, historyKind("Duplicate URL (Already in Library)"))
        assertEquals(HistoryKind.DUPLICATE_IN_QUEUE, historyKind("Duplicate URL (Already in Queue)"))
        assertEquals(HistoryKind.PROMOTED_TO_LIBRARY, historyKind("Promoted from RSS to Library"))
    }

    @Test fun constantsKeepStoredValues() {
        assertEquals("Duplicate URL (Already in Library)", ImportNotificationMessages.DUPLICATE_IN_LIBRARY)
        assertEquals("Duplicate URL (Already in Queue)", ImportNotificationMessages.DUPLICATE_IN_QUEUE)
        assertEquals("Promoted from RSS to Library", ImportNotificationMessages.PROMOTED_TO_LIBRARY)
    }

    @Test fun unknownTextIsUnknownWithoutActionOrMessage() {
        listOf("", "Saved", "duplicate url (already in library)").forEach {
            assertEquals(it, HistoryKind.UNKNOWN, historyKind(it))
        }
        assertNull(HistoryKind.UNKNOWN.messageRes)
    }

    @Test fun onlyLibraryDuplicatesAndPromotionsOpenSavedPage() {
        assertEquals(setOf(HistoryKind.DUPLICATE_IN_LIBRARY, HistoryKind.PROMOTED_TO_LIBRARY),
            HistoryKind.entries.filter { it.opensSavedPage }.toSet())
    }
}
