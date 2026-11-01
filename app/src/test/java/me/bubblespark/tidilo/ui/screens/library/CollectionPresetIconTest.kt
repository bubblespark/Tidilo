package me.bubblespark.tidilo.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionPresetIconTest {
    @Test fun resolvesTrimmedCaseInsensitiveIdsAndFallsBackForUnknownIds() {
        assertEquals(CollectionPresetIcon.WORK, CollectionPresetIcon.fromId(" WORK "))
        assertEquals(CollectionPresetIcon.FAVORITE, CollectionPresetIcon.fromId("FaVoRiTe"))
        assertEquals(CollectionPresetIcon.FOLDER, CollectionPresetIcon.fromId(null))
        assertEquals(CollectionPresetIcon.FOLDER, CollectionPresetIcon.fromId("unknown"))
    }
}
