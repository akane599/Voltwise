package com.akane.voltwise.battery.measurement

import org.junit.Assert.*
import org.junit.Test

class BatteryAlertsTest {
    private fun reading(time: Long = 0, level: Int? = 50, status: Int = 3, plugged: Int? = 0,
        current: Long? = -100_000, temperature: Int? = 250) = AlertReading(time, level, status, plugged, current, temperature, 30_000)
    private val defaults = BatteryAlertSettings()

    @Test fun unavailableReadingsNeverBecomeZeroPercentAlerts() {
        val alerts = BatteryAlerts()
        assertTrue(alerts.accept(reading(level = null, current = Long.MIN_VALUE, temperature = Int.MIN_VALUE), defaults).isEmpty())
        assertEquals(setOf(BatteryAlert.LOW), alerts.accept(reading(30_000, level = 0, current = 0), defaults))
    }
    @Test fun lowThresholdUsesHysteresisAndRearmsOnCharging() {
        val alerts = BatteryAlerts()
        assertEquals(setOf(BatteryAlert.LOW), alerts.accept(reading(level = 20), defaults))
        assertTrue(alerts.accept(reading(30_000, 19), defaults).isEmpty())
        assertTrue(alerts.accept(reading(60_000, 22), defaults).isEmpty())
        assertTrue(alerts.accept(reading(90_000, 23), defaults).isEmpty())
        assertEquals(setOf(BatteryAlert.LOW), alerts.accept(reading(120_000, 20), defaults))
        assertTrue(alerts.accept(reading(150_000, 20, status = 2, plugged = 1), defaults).isEmpty())
        assertEquals(setOf(BatteryAlert.LOW), alerts.accept(reading(180_000, 20), defaults))
    }
    @Test fun episodeStateSurvivesServiceRestartAndMissingData() {
        val first = BatteryAlerts()
        first.accept(reading(level = 10), defaults)
        val restored = BatteryAlerts(first.latches, first.lastAcceptedElapsedMs)
        assertTrue(restored.accept(reading(30_000, 10), defaults).isEmpty())
        assertTrue(restored.accept(reading(60_000, null, status = 1, plugged = null), defaults).isEmpty())
        assertTrue(restored.accept(reading(90_000, 10), defaults).isEmpty())
        restored.accept(reading(120_000, 25), defaults)
        assertEquals(setOf(BatteryAlert.LOW), restored.accept(reading(150_000, 10), defaults))
    }
    @Test fun restoredLowLatchExpiresAfterAnUnmonitoredGap() {
        val restored = BatteryAlerts(setOf(BatteryAlert.LOW), initialElapsedMs = 600_000)
        assertEquals("A new discharge episode must alert after the 100 s gap limit",
            setOf(BatteryAlert.LOW), restored.accept(reading(700_001, 15), defaults))
    }
    @Test fun restoredLowLatchExpiresWhenElapsedRealtimeMovesBackwards() {
        val restored = BatteryAlerts(setOf(BatteryAlert.LOW), initialElapsedMs = 600_000)
        assertEquals("A reading from a new boot must not retain the old LOW episode",
            setOf(BatteryAlert.LOW), restored.accept(reading(30_000, 15), defaults))
    }
    @Test fun restoredLowLatchSuppressesAQuickRestartIncludingTheGapBoundary() {
        for (time in listOf(630_000L, 700_000L)) {
            val restored = BatteryAlerts(setOf(BatteryAlert.LOW), initialElapsedMs = 600_000)
            assertTrue("A restart within the gap must retain storm protection",
                restored.accept(reading(time, 15), defaults).isEmpty())
        }
    }
    @Test fun fullUsesReportedStatusAndCoalescesTheHighThreshold() {
        val alerts = BatteryAlerts()
        assertTrue(alerts.accept(reading(level = 100, status = 2, plugged = 1), defaults).isEmpty())
        assertEquals(setOf(BatteryAlert.FULL), alerts.accept(reading(30_000, 100, status = 5, plugged = 1), defaults.copy(high = true)))
        assertTrue(alerts.accept(reading(60_000, 100, status = 5, plugged = 1), defaults.copy(high = true)).isEmpty())
        assertEquals(setOf(BatteryAlert.FULL, BatteryAlert.HIGH), alerts.latches)
    }
    @Test fun highChargeRearmsOnUnpluggingAndTemperatureHasIndependentHysteresis() {
        val alerts = BatteryAlerts()
        val settings = defaults.copy(high = true)
        assertEquals(setOf(BatteryAlert.HIGH, BatteryAlert.TEMPERATURE), alerts.accept(reading(level = 80, status = 2, plugged = 1, temperature = 450), settings))
        assertTrue(alerts.accept(reading(30_000, 80, temperature = 440), settings).isEmpty())
        assertEquals(setOf(BatteryAlert.HIGH), alerts.accept(reading(60_000, 80, status = 2, plugged = 1, temperature = 430), settings))
        assertEquals(setOf(BatteryAlert.TEMPERATURE), alerts.accept(reading(90_000, 80, status = 2, plugged = 1, temperature = 450), settings))
    }
    @Test fun highDischargeRequiresThreeReadingsAndAtLeastOneMinute() {
        val alerts = BatteryAlerts()
        val settings = defaults.copy(discharge = true)
        assertTrue(alerts.accept(reading(current = -800_000), settings).isEmpty())
        assertTrue(alerts.accept(reading(30_000, current = -800_000), settings).isEmpty())
        assertEquals(setOf(BatteryAlert.DISCHARGE), alerts.accept(reading(60_000, current = -800_000), settings))
        assertTrue(alerts.accept(reading(90_000, current = -800_000), settings).isEmpty())
        alerts.accept(reading(120_000, current = -400_000), settings)
        assertFalse(BatteryAlert.DISCHARGE in alerts.latches)
    }
    @Test fun gapsMissingSamplesAndVendorSignContradictionsCannotQualifyHighDischarge() {
        val settings = defaults.copy(discharge = true)
        for (interruption in listOf(reading(30_000, current = null), reading(30_000, current = 800_000), reading(300_000, current = -800_000))) {
            val alerts = BatteryAlerts()
            alerts.accept(reading(current = -800_000), settings)
            assertTrue(alerts.accept(interruption, settings).isEmpty())
            assertTrue(alerts.accept(reading(interruption.elapsedMs + 30_000, current = -800_000), settings).isEmpty())
        }
    }
    @Test fun duplicateSamplesUnknownPowerAndDisabledConditionsDoNotAlert() {
        val alerts = BatteryAlerts()
        assertTrue(alerts.accept(reading(level = 10, status = 0), defaults).isEmpty())
        assertTrue(alerts.accept(reading(30_000, 10, status = 6), defaults).isEmpty())
        assertTrue(alerts.accept(reading(60_000, 10, plugged = -1), defaults).isEmpty())
        assertEquals(setOf(BatteryAlert.LOW), alerts.accept(reading(90_000, 10), defaults))
        assertTrue(alerts.accept(reading(90_000, 10), defaults).isEmpty())
        assertTrue(alerts.accept(reading(120_000, 10), defaults.copy(low = false)).isEmpty())
        assertFalse(BatteryAlert.LOW in alerts.latches)
    }
    @Test fun reportedDischargeIsPreservedEvenWithAConnectedPowerSupply() {
        assertEquals(PowerState.DISCHARGING, BatteryReading.powerState(3, 1))
        val alerts = BatteryAlerts()
        assertEquals(setOf(BatteryAlert.LOW), alerts.accept(reading(level = 10, plugged = 1), defaults))
        assertEquals(PowerState.PLUGGED, BatteryReading.powerState(4, 1))
        assertEquals(PowerState.UNKNOWN, BatteryReading.powerState(2, 0))
    }
    @Test fun failedDeliveryCanRestoreThePreviousLatchStateForRetry() {
        val alerts = BatteryAlerts()
        assertEquals(setOf(BatteryAlert.LOW), alerts.accept(reading(level = 10), defaults))
        alerts.retryDelivery()
        assertEquals(setOf(BatteryAlert.LOW), alerts.accept(reading(30_000, 10), defaults))
    }
    @Test fun expiredLatchesCannotReturnWhenDeliveryFails() {
        val alerts = BatteryAlerts(setOf(BatteryAlert.LOW), initialElapsedMs = 600_000)
        assertEquals(setOf(BatteryAlert.LOW), alerts.accept(reading(700_001, 15), defaults))
        alerts.retryDelivery()
        assertTrue(alerts.latches.isEmpty())
        assertEquals(setOf(BatteryAlert.LOW), alerts.accept(reading(730_001, 15), defaults))
    }
    @Test fun bootIdentityExpiresLatchesEvenWhenUptimeHasCaughtUp() {
        for (time in listOf(600_000L, 630_000L)) {
            val alerts = BatteryAlerts(setOf(BatteryAlert.LOW), initialElapsedMs = 600_000, initialBootCount = 3)
            assertEquals(setOf(BatteryAlert.LOW), alerts.accept(reading(time, 15).copy(bootCount = 4), defaults))
        }
        val sameBoot = BatteryAlerts(setOf(BatteryAlert.LOW), initialElapsedMs = 600_000, initialBootCount = 4)
        assertTrue(sameBoot.accept(reading(630_000, 15).copy(bootCount = 4), defaults).isEmpty())
    }
    @Test fun legacyLatchesWithoutATimestampExpireOnTheFirstReading() {
        val alerts = BatteryAlerts(setOf(BatteryAlert.LOW))
        assertEquals(setOf(BatteryAlert.LOW), alerts.accept(reading(30_000, 15), defaults))
    }
    @Test fun fullAndTemperatureLatchesExpireAfterAnUnmonitoredGap() {
        val alerts = BatteryAlerts(setOf(BatteryAlert.FULL, BatteryAlert.TEMPERATURE), initialElapsedMs = 600_000)
        assertEquals(setOf(BatteryAlert.FULL, BatteryAlert.TEMPERATURE),
            alerts.accept(reading(700_001, 100, status = 5, plugged = 1, temperature = 460), defaults))
    }
    @Test fun recentRestartUsesTheLastAcceptedReadingNotTheAlertTime() {
        val first = BatteryAlerts()
        first.accept(reading(600_000, 15), defaults)
        for (time in 630_000L..900_000L step 30_000) first.accept(reading(time, 15), defaults)
        assertEquals(900_000L, first.lastAcceptedElapsedMs)
        val restored = BatteryAlerts(first.latches, first.lastAcceptedElapsedMs)
        assertTrue(restored.accept(reading(930_000, 15), defaults).isEmpty())
    }
    @Test fun refreshedLatchSurvivesRestartAfterRefreshIntervalAndOneFastCapture() {
        val savedElapsedMs = 600_000L
        val restored = BatteryAlerts(setOf(BatteryAlert.LOW), initialElapsedMs = savedElapsedMs)
        assertTrue("A throttled timestamp must still suppress the LOW alert after a quick restart",
            restored.accept(reading(savedElapsedMs + ALERT_REFRESH_MS + 2_000, 15)
                .copy(samplingIntervalMs = 2_000), defaults).isEmpty())
        assertEquals(setOf(BatteryAlert.LOW), restored.latches)
    }
    @Test fun restoredEpisodesUseTheSameClampedSamplingGapRule() {
        for (interval in listOf(1_000L, 5_000L, 300_000L, 600_000L)) {
            val gapLimit = interval.coerceIn(5_000, 300_000) * 3 + 10_000
            val atBoundary = BatteryAlerts(setOf(BatteryAlert.LOW), initialElapsedMs = 600_000)
            assertTrue(atBoundary.accept(reading(600_000 + gapLimit, 15).copy(samplingIntervalMs = interval), defaults).isEmpty())
            val afterBoundary = BatteryAlerts(setOf(BatteryAlert.LOW), initialElapsedMs = 600_000)
            assertEquals(setOf(BatteryAlert.LOW), afterBoundary.accept(
                reading(600_001 + gapLimit, 15).copy(samplingIntervalMs = interval), defaults))
        }
    }
}
