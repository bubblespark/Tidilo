package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.data.maintenance.ogImageExpired
import me.bubblespark.tidilo.data.maintenance.rssBookmarkExpired
import org.junit.Assert.*
import org.junit.Test

class RetentionPolicyTest {
    private val day = 86_400_000L

    @Test fun unreadExpiresExactlyTenDaysAfterIngestion() {
        assertFalse(rssBookmarkExpired(day, null, 11 * day - 1))
        assertTrue(rssBookmarkExpired(day, null, 11 * day))
    }

    @Test fun renewedInterestRestartsThirtyDayInterval() {
        assertFalse(rssBookmarkExpired(day, 29 * day, 59 * day - 1))
        assertTrue(rssBookmarkExpired(day, 29 * day, 59 * day))
        assertFalse(rssBookmarkExpired(day, 58 * day, 59 * day))
    }

    @Test fun futureTimesAreProtected() {
        assertFalse(rssBookmarkExpired(20 * day, null, day))
        assertFalse(rssBookmarkExpired(day, 20 * day, day))
        assertFalse(ogImageExpired(20 * day, null, day))
    }

    @Test fun imagesRequireBothSevenDayConditions() {
        assertTrue(ogImageExpired(day, day, 8 * day))
        assertFalse(ogImageExpired(day + 1, day, 8 * day))
        assertFalse(ogImageExpired(day, day + 1, 8 * day))
        assertTrue(ogImageExpired(day, null, 8 * day))
        assertFalse(ogImageExpired(0, null, 8 * day))
    }
}
