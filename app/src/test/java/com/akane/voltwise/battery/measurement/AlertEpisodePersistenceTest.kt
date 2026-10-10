package com.akane.voltwise.battery.measurement

import org.junit.Assert.*
import org.junit.Test

class AlertEpisodePersistenceTest {
    @Test fun twoSecondCapturesWhileLatchedWriteAtMostOncePerRefreshInterval() {
        val alerts = BatteryAlerts()
        var savedElapsedMs: Long? = null
        val writes = mutableListOf<Long>()
        for (time in 100_000L..130_000L step 2_000) {
            val before = alerts.latches
            alerts.accept(AlertReading(time, 15, 3, 0, -100_000, 250, 2_000), BatteryAlertSettings())
            if (alertEpisodeWriteDue(alerts.latches != before, alerts.latches.isNotEmpty(), savedElapsedMs, time)) {
                writes += time
                savedElapsedMs = time
            }
        }
        assertTrue("Latched timestamp writes must be at least ALERT_REFRESH_MS apart",
            writes.zipWithNext().all { (before, after) -> after - before >= ALERT_REFRESH_MS })
        assertEquals(listOf(100_000L, 110_000L, 120_000L, 130_000L), writes)
    }

    @Test fun latchChangesWriteImmediatelyIncludingClearingTheLastLatch() {
        for (latched in listOf(true, false)) {
            assertTrue(alertEpisodeWriteDue(true, latched, 100_000, 102_000))
        }
    }

    @Test fun latchedEpisodeWithoutASavedTimestampWritesImmediately() {
        assertTrue(alertEpisodeWriteDue(false, true, null, 100_000))
    }

    @Test fun backwardsElapsedClockRefreshesTheLatchedEpisode() {
        assertTrue(alertEpisodeWriteDue(false, true, 100_000, 2_000))
    }

    @Test fun unchangedUnlatchedEpisodeNeverWritesATimestamp() {
        for (saved in listOf(null, 100_000L)) {
            for (sample in listOf(2_000L, 100_000L, 102_000L, 110_000L)) {
                assertFalse(alertEpisodeWriteDue(false, false, saved, sample))
            }
        }
    }

    @Test fun refreshIsDueOnlyAtOrAfterTheIntervalBoundary() {
        assertFalse(alertEpisodeWriteDue(false, true, 100_000, 100_000))
        assertFalse(alertEpisodeWriteDue(false, true, 100_000, 100_000 + ALERT_REFRESH_MS - 1))
        assertTrue(alertEpisodeWriteDue(false, true, 100_000, 100_000 + ALERT_REFRESH_MS))
        assertTrue(alertEpisodeWriteDue(false, true, 100_000, 100_000 + ALERT_REFRESH_MS + 1))
    }
}
