package me.bubblespark.tidilo.data.discover

import me.bubblespark.tidilo.data.db.DiscoverSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoverReasonDecodeTest {
    private fun row(code: Int?, arg: String?) = DiscoverSnapshotEntity(1, 1, 0, "id", code, arg, null)

    @Test fun decodesEveryReason() {
        assertEquals(DiscoverReason.DormantNeverOpened(94), decodeReason(row(1, "94")))
        assertEquals(DiscoverReason.AffinityEntity("Kotlin"), decodeReason(row(2, "Kotlin")))
        assertEquals(DiscoverReason.OnThisDay(OnThisDayPeriod.SIX_MONTHS), decodeReason(row(3, "SIX_MONTHS")))
        assertEquals(DiscoverReason.RssExpiring(2), decodeReason(row(4, "2")))
        assertEquals(DiscoverReason.Connection("Rust"), decodeReason(row(5, "Rust")))
        assertEquals(DiscoverReason.Rising(4), decodeReason(row(6, "4")))
    }

    @Test fun unknownOrMalformedReasonsYieldNoLabel() {
        assertNull(decodeReason(row(null, "x")))
        assertNull(decodeReason(row(99, "x")))
        assertNull(decodeReason(row(1, "many")))
        assertNull(decodeReason(row(3, "TWO_YEARS")))
        assertNull(decodeReason(row(2, null)))
    }
}
