package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.sampling.SessionExtremes
import com.akane.voltwise.battery.data.sampling.SessionReport
import com.akane.voltwise.battery.measurement.Observation
import com.akane.voltwise.battery.measurement.ObservationEngine
import com.akane.voltwise.battery.measurement.PowerState
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryPolicyTest {
    private fun sample(status: Int = 3, plugged: Int? = 0) = BatterySample(timestamp = 1000, levelPercent = 0, status = status, plugged = plugged,
        currentNowUa = 0, chargeCounterUah = 0, voltageMv = 4000, temperatureDeciC = 0,
        health = 2, screenOn = true, elapsedMs = 100, uptimeMs = 80, observationId = "observation", sessionId = "session", source = "BatteryManager")
    private fun session() = ChargeSession("session", SessionType.DISCHARGE, 1000, null, 60, 59, 1000, -1000, null,
        observationId = "observation", lastSampleTime = 2000, observedMs = 1000, counterCoveredMs = 1000, screenOnMs = 1000, screenOnUah = 1000)

    private val importClock = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC)
    private val importTimeMs = importClock.millis()
    private val dayMs = 24 * 60 * 60 * 1000L

    @Test fun sampleOneYearAfterImportTimeIsRejected() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HistoryPolicy.sample(sample().copy(timestamp = importTimeMs + 365 * dayMs), importClock)
        }
        assertEquals("Invalid timestamp; exceeds import time allowance", failure.message)
    }

    @Test fun sampleOneHourAfterImportTimeIsAccepted() {
        val timestamp = importTimeMs + 60 * 60 * 1000
        assertEquals(timestamp, HistoryPolicy.sample(sample().copy(timestamp = timestamp), importClock).timestamp)
    }

    @Test fun sampleImportClockAllowanceIsInclusiveAndUsesTheInjectedClock() {
        val boundary = sample().copy(timestamp = importTimeMs + dayMs)
        assertEquals(boundary.timestamp, HistoryPolicy.sample(boundary, importClock).timestamp)
        assertThrows(IllegalArgumentException::class.java) {
            HistoryPolicy.sample(boundary.copy(timestamp = boundary.timestamp + 1), importClock)
        }
        val tomorrow = Clock.offset(importClock, java.time.Duration.ofDays(1))
        assertEquals(boundary.timestamp + 1,
            HistoryPolicy.sample(boundary.copy(timestamp = boundary.timestamp + 1), tomorrow).timestamp)
    }

    @Test fun absoluteTimestampBoundsStillApplyWithAnInjectedClock() {
        for (timestamp in listOf(-1L, 253402300800000L, Long.MAX_VALUE)) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                HistoryPolicy.sample(sample().copy(timestamp = timestamp), importClock)
            }
            assertEquals("Invalid timestamp; expected Unix milliseconds", failure.message)
        }
    }

    @Test fun everySessionTimestampRejectsExcessiveImportClockSkew() {
        val boundary = importTimeMs + dayMs
        val future = boundary + 1
        val rows = listOf(
            session().copy(startTime = future, endTime = future + 1000, lastSampleTime = future + 1000),
            session().copy(startTime = importTimeMs, endTime = future, lastSampleTime = importTimeMs + 1000),
            // Only the last sample is outside the allowance, even though session normalization would clamp it.
            session().copy(startTime = boundary - 1000, endTime = boundary, lastSampleTime = future),
            session().copy(startTime = importTimeMs, endTime = null, lastSampleTime = future),
        )
        for (row in rows) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                HistoryPolicy.session(row, clock = importClock)
            }
            assertEquals("Invalid timestamp; exceeds import time allowance", failure.message)
        }
    }

    @Test fun closedAndActiveSessionsAcceptOneHourSkewAndTheOneDayBoundary() {
        for (end in listOf(importTimeMs + 60 * 60 * 1000, importTimeMs + dayMs)) {
            val row = session().copy(startTime = end - 1000, endTime = end, lastSampleTime = end)
            val closed = HistoryPolicy.session(row, clock = importClock)
            val active = HistoryPolicy.session(row.copy(endTime = null), clock = importClock)
            assertEquals(end - 1000, closed.startTime)
            assertEquals(end, closed.endTime)
            assertEquals(end, closed.lastSampleTime)
            assertEquals(end, active.endTime)
            assertEquals(end, active.lastSampleTime)
        }
    }

    @Test fun sampleIdentitySurvivesRepeatedImportsAndLocalRowIds() {
        val original = sample()
        val imported = HistoryPolicy.sample(original)
        assertTrue(imported.id < -1)
        assertEquals(imported, HistoryPolicy.sample(imported))
        assertTrue(HistoryPolicy.sameSample(original.copy(id = 123), imported))
        assertNotEquals(imported.id, HistoryPolicy.sample(original.copy(currentNowUa = 1)).id)
        assertEquals(0L, imported.currentNowUa); assertEquals(0L, imported.chargeCounterUah)
        assertEquals(0, imported.levelPercent); assertEquals(0, imported.temperatureDeciC)
    }
    @Test fun knownUnavailableLegacySentinelsStayMissing() {
        val imported = HistoryPolicy.sample(sample().copy(currentNowUa = Long.MIN_VALUE,
            chargeCounterUah = Int.MIN_VALUE.toLong(), voltageMv = 0, temperatureDeciC = Int.MIN_VALUE))
        assertNull(imported.currentNowUa); assertNull(imported.chargeCounterUah)
        assertNull(imported.voltageMv); assertNull(imported.temperatureDeciC)
    }
    @Test fun outOfRangeSampleHealthIsUnknownAndKnownCategoriesSurviveImport() {
        for (health in listOf(Int.MIN_VALUE, -1, 0, 8, 99, Int.MAX_VALUE)) {
            val result = runCatching { HistoryPolicy.sample(sample().copy(health = health)) }
            assertTrue("OEM health $health must not abort import: ${result.exceptionOrNull()?.message}", result.isSuccess)
            val imported = result.getOrThrow()
            assertNull(imported.health)
            assertEquals(HistoryPolicy.sample(sample().copy(health = null)), imported)
            assertEquals(imported, HistoryPolicy.sample(imported))
        }
        for (health in (1..7).toList() + null) {
            assertEquals(health, HistoryPolicy.sample(sample().copy(health = health)).health)
        }
    }
    @Test fun legacyZeroSampleStatusBecomesUnknown() {
        val result = runCatching { HistoryPolicy.sample(sample(status = 0)) }
        assertTrue("Legacy status 0 must not abort import: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(HistoryPolicy.sample(sample(status = 1)), result.getOrThrow())
        assertTrue(HistoryPolicy.sameSample(sample(status = 0), sample(status = 1)))
    }
    @Test fun outOfRangeSampleStatusBecomesUnknown() {
        val result = runCatching { HistoryPolicy.sample(sample(status = 6)) }
        assertTrue("Status 6 must not abort import: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(HistoryPolicy.sample(sample(status = 1)), result.getOrThrow())
    }
    @Test fun outOfRangeSamplePluggedBecomesMissing() {
        val result = runCatching { HistoryPolicy.sample(sample(plugged = 16)) }
        assertTrue("Plugged 16 must not abort import: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(HistoryPolicy.sample(sample(plugged = null)), result.getOrThrow())
        assertTrue(HistoryPolicy.sameSample(sample(plugged = 16), sample(plugged = null)))
    }
    @Test fun validSamplePowerStateSurvivesImport() {
        for (status in 1..5) {
            for (plugged in (0..15).toList() + null) {
                val imported = HistoryPolicy.sample(sample(status = status, plugged = plugged))
                assertEquals(status, imported.status)
                assertEquals(plugged, imported.plugged)
                assertEquals(imported, HistoryPolicy.sample(imported))
            }
        }
    }
    @Test fun invalidUnitsTimesAndTextAreRejected() {
        for (bad in listOf(sample().copy(levelPercent = 101), sample().copy(currentNowUa = 1_000_000_000),
            sample().copy(voltageMv = 4_000_000), sample().copy(uptimeMs = 101), sample().copy(timestamp = -1),
            sample().copy(etaBasis = "=1+1"), sample().copy(observationId = "line\nbreak"))) {
            assertThrows(IllegalArgumentException::class.java) { HistoryPolicy.sample(bad) }
        }
    }
    @Test fun activeSnapshotsCloseAtLastEvidenceAndNeverResume() {
        val imported = HistoryPolicy.session(session())
        assertEquals(2000L, imported.endTime); assertNull(imported.activeKey)
        assertEquals("import:session", imported.sessionId)
        assertEquals(imported, HistoryPolicy.session(imported))
        assertEquals(1000L, HistoryPolicy.session(session().copy(lastSampleTime = null, observedMs = 0,
            counterCoveredMs = 0, deltaUah = null, screenOnMs = 0, screenOnUah = null)).endTime)
    }
    @Test fun accumulatedBackwardClockCorrectionsClampCoverageToTheWallSpan() {
        val original = session().copy(endTime = 101_000, lastSampleTime = 101_000,
            observedMs = 108_000, counterCoveredMs = 108_000,
            screenOnMs = 60_000, screenOffMs = 48_000, screenOnUah = 600, screenOffUah = 400,
            cpuSuspendMs = 106_000, screenOffSuspendMs = 48_000)
        val result = runCatching { HistoryPolicy.session(original) }
        assertTrue("App-written span + 8 s must import: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val imported = result.getOrThrow()
        assertEquals(100_000L, imported.observedMs)
        assertEquals(100_000L, imported.counterCoveredMs)
        assertEquals(55_555L, imported.screenOnMs)
        assertEquals(44_444L, imported.screenOffMs)
        assertTrue(imported.screenOnMs + imported.screenOffMs <= imported.observedMs)
        assertEquals(98_148L, imported.cpuSuspendMs)
        assertEquals(44_444L, imported.screenOffSuspendMs)
        assertEquals(original.deltaUah, imported.deltaUah)
        assertEquals(555L, imported.screenOnUah)
        assertEquals(370L, imported.screenOffUah)
        assertEquals(imported, HistoryPolicy.session(imported))
    }
    @Test fun clockCorrectedImportsPreservePerScreenDrainRates() {
        val original = session().copy(endTime = 201_000, lastSampleTime = 201_000,
            observedMs = 216_000, counterCoveredMs = 216_000, deltaUah = 45_000,
            screenOnMs = 108_000, screenOffMs = 108_000,
            screenOnUah = 30_000, screenOffUah = 15_000)
        val imported = HistoryPolicy.session(original)
        val before = SessionDrain.of(original, fullUah = 4_000_000)
        val after = SessionDrain.of(imported, fullUah = 4_000_000)
        assertEquals(100_000L, after.screenOn.durationMs)
        assertEquals(100_000L, after.screenOff.durationMs)
        for ((expected, actual) in listOf(before.screenOn to after.screenOn, before.screenOff to after.screenOff)) {
            // Integer µAh rounding may lose less than one unit, but must not inflate the rate.
            val chargeRoundingMa = 3_600.0 / actual.durationMs
            assertEquals("Screen current must survive clock correction", checkNotNull(expected.currentMa),
                checkNotNull(actual.currentMa), chargeRoundingMa)
            assertEquals("Capacity-relative drain must survive clock correction", checkNotNull(expected.percentPerHour),
                checkNotNull(actual.percentPerHour), chargeRoundingMa * 100_000 / 4_000_000)
        }
        assertEquals(original.deltaUah, imported.deltaUah)
        assertEquals(imported, HistoryPolicy.session(imported))
    }
    @Test fun clockCorrectionScalesChargeByTheRoundedBucketDuration() {
        val original = session().copy(endTime = 1010, lastSampleTime = 1010,
            observedMs = 12, counterCoveredMs = 12, deltaUah = 120,
            screenOnMs = 1, screenOffMs = 11, screenOnUah = 10, screenOffUah = 110)
        val imported = HistoryPolicy.session(original)
        assertEquals(0L, imported.screenOnMs)
        assertNull(imported.screenOnUah)
        assertEquals(9L, imported.screenOffMs)
        assertEquals(90L, imported.screenOffUah)
        assertEquals(original.deltaUah, imported.deltaUah)
        assertEquals(imported, HistoryPolicy.session(imported))
        assertNull(HistoryPolicy.session(original.copy(screenOffUah = null)).screenOffUah)
    }
    @Test fun twoSmallWriterClockStepsRemainImportableWithoutAnObservationGap() {
        val engine = ObservationEngine()
        val points = (0..2).map { index ->
            Observation(wallMs = 1_000 + index * 27_000L, elapsedMs = index * 30_000L,
                uptimeMs = index * 30_000L, level = 60, chargeUah = 1_000_000 - index * 1_000L,
                currentUa = -100_000, voltageMv = 4000, power = PowerState.DISCHARGING,
                interactive = true, dozing = false, generation = "observation")
        }
        val firstSample = sample().copy(timestamp = points.first().wallMs)
        val open = SessionReport.open(points.first(), firstSample)
        points.forEach { engine.accept(it) }
        val written = SessionReport.report(open, firstSample.copy(timestamp = points.last().wallMs),
            engine.summary, SessionExtremes())
        assertEquals(0, engine.summary.gaps)
        assertEquals(60_000L, written.observedMs)
        assertEquals(54_000L, checkNotNull(written.lastSampleTime) - written.startTime)
        val imported = HistoryPolicy.session(written)
        assertEquals(54_000L, imported.observedMs)
        assertEquals(imported.observedMs, imported.counterCoveredMs)
        assertEquals(imported, HistoryPolicy.session(imported))
    }
    @Test fun clockCorrectedSampleWindowsAcceptSmallExcessButNotCorruption() {
        val original = session().copy(endTime = 101_000, lastSampleTime = 109_000)
        val imported = HistoryPolicy.session(original)
        assertEquals(101_000L, imported.lastSampleTime)
        assertTrue(HistoryPolicy.sampleInSessionWindow(109_000, imported))
        assertTrue(HistoryPolicy.sampleInSessionWindow(116_000, imported))
        assertFalse(HistoryPolicy.sampleInSessionWindow(116_001, imported))
        assertFalse(HistoryPolicy.sampleInSessionWindow(1_000_000, imported))
        assertFalse(HistoryPolicy.sampleInSessionWindow(999, imported))
        assertTrue(HistoryPolicy.sampleInSessionWindow(109_000, original.copy(endTime = null), 101_000))
        assertThrows(IllegalArgumentException::class.java) {
            HistoryPolicy.session(original.copy(lastSampleTime = 1_000_000))
        }
    }
    @Test fun zeroWallSpanNormalizationKeepsChargeCoverageInvariants() {
        val imported = HistoryPolicy.session(session().copy(endTime = 1000, lastSampleTime = 1000,
            cpuSuspendMs = 1000, screenOffSuspendMs = 1000))
        assertEquals(0L, imported.observedMs)
        assertEquals(0L, imported.counterCoveredMs)
        assertEquals(0L, imported.screenOnMs)
        assertEquals(0L, imported.cpuSuspendMs)
        assertEquals(0L, imported.screenOffSuspendMs)
        assertNull(imported.deltaUah)
        assertNull(imported.screenOnUah)
        assertEquals(imported, HistoryPolicy.session(imported))
    }
    @Test fun formerlyImportedClockSlackStillMergesAfterNormalization() {
        val stored = HistoryPolicy.session(session()).copy(observedMs = 4000,
            counterCoveredMs = 4000, screenOnMs = 4000)
        val incoming = HistoryPolicy.session(stored)
        val plan = HistoryPolicy.planSessionImport(HistoryPolicy.session(stored), incoming)
        assertEquals(ImportSessionDisposition.UNCHANGED, plan.disposition)
    }
    @Test fun counterGapExtensionWithClockCorrectionsUpdatesImportedSession() {
        val first = session().copy(endTime = 3_601_000, lastSampleTime = 3_601_000,
            observedMs = 3_600_000, counterCoveredMs = 3_600_000)
        val second = first.copy(endTime = 4_191_000, lastSampleTime = 4_191_000, observedMs = 4_200_000)
        val result = runCatching {
            HistoryPolicy.planSessionImport(HistoryPolicy.session(first), second)
        }
        assertTrue("Counter-gap extension must update, not abort: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val plan = result.getOrThrow()
        assertEquals(ImportSessionDisposition.UPDATED, plan.disposition)
        assertEquals(4_190_000L, plan.session.observedMs)
        assertEquals(3_591_428L, plan.session.counterCoveredMs)
        assertEquals(ImportSessionDisposition.UNCHANGED,
            HistoryPolicy.planSessionImport(plan.session, second).disposition)
        assertEquals(ImportSessionDisposition.STALE,
            HistoryPolicy.planSessionImport(plan.session, first).disposition)
    }
    @Test fun genuinelyDecreasingRawCoverageStillRejectsAnExtension() {
        val previous = session().copy(source = "import:legacy", endTime = 3_591_000,
            lastSampleTime = 3_591_000, observedMs = 3_600_000, counterCoveredMs = 3_600_000)
        val counterRegression = previous.copy(endTime = 4_201_000, lastSampleTime = 4_201_000,
            observedMs = 4_200_000, counterCoveredMs = 3_595_000)
        val observedRegression = previous.copy(endTime = 3_596_000, lastSampleTime = 3_596_000,
            observedMs = 3_595_000, counterCoveredMs = 3_595_000)
        for (incoming in listOf(counterRegression, observedRegression)) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                HistoryPolicy.planSessionImport(previous, incoming)
            }
            assertEquals("Incompatible imported session coverage", failure.message)
        }
    }
    @Test fun longSessionClockAllowanceStopsAtFifteenMinutesPlusFiveSeconds() {
        val span = 3 * 24 * 60 * 60 * 1000L
        val end = 1000 + span
        val original = session().copy(endTime = end, lastSampleTime = end, observedMs = span + 905_000)
        assertEquals(span, HistoryPolicy.session(original).observedMs)
        assertThrows(IllegalArgumentException::class.java) {
            HistoryPolicy.session(original.copy(observedMs = span + 905_001))
        }
        assertTrue(HistoryPolicy.sampleInSessionWindow(end + 905_000, original))
        assertFalse(HistoryPolicy.sampleInSessionWindow(end + 905_001, original))
    }
    @Test fun hourOfClockExcessOnThreeDaySessionIsRejected() {
        val span = 3 * 24 * 60 * 60 * 1000L
        val original = session().copy(endTime = 1000 + span, lastSampleTime = 1000 + span,
            observedMs = span + 60 * 60 * 1000L)
        assertThrows("An hour of clock excess must not be normalized away", IllegalArgumentException::class.java) {
            HistoryPolicy.session(original)
        }
    }
    @Test fun invalidCoverageIsNotHiddenByClockCorrectionNormalization() {
        val original = session().copy(endTime = 101_000, lastSampleTime = 101_000, observedMs = 108_000)
        for (bad in listOf(original.copy(counterCoveredMs = 108_001),
            original.copy(screenOnMs = 108_000, screenOffMs = 1),
            original.copy(cpuSuspendMs = 108_001), original.copy(screenOffSuspendMs = -1))) {
            assertThrows(IllegalArgumentException::class.java) { HistoryPolicy.session(bad) }
        }
    }
    @Test fun negativeAndWildlyInflatedObservedIntervalsAreRejected() {
        val original = session().copy(endTime = 101_000, lastSampleTime = 101_000)
        for (observed in listOf(-1L, 1_000_000L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                HistoryPolicy.session(original.copy(observedMs = observed))
            }
        }
    }
    @Test fun fictionalScreenOffAndIncompatibleCoverageAreRejected() {
        for (bad in listOf(session().copy(screenOffUah = 10), session().copy(screenOffMs = 1),
            session().copy(counterCoveredMs = 1001), session().copy(deltaUah = 999),
            session().copy(deltaUah = -1), session().copy(cpuSuspendMs = 1001), session().copy(endTime = 999))) {
            assertThrows(IllegalArgumentException::class.java) { HistoryPolicy.session(bad) }
        }
    }
    @Test fun v5SessionFieldsAreValidatedAndAPendingBreakdownIsNotImported() {
        val full = session().copy(chargerType = "USB", energyNwh = 3_900_000, peakPowerMw = 4500, peakTemperatureDeciC = 310,
            screenOffSuspendMs = 0, capacityEstimateMah = 4800, capacityConfidence = "HIGH", capacityBasis = "COUNTER_SPAN",
            appUsageStatus = AppUsageStatus.READY, appUsageBasis = AppUsageBasis.DELTA)
        val imported = HistoryPolicy.session(full)
        assertEquals(full.copy(sessionId = "import:session", observationId = "import:observation", endTime = 2000, activeKey = null,
            source = "import:legacy", closeReason = "Imported snapshot; monitoring was not resumed"), imported)
        assertEquals(imported, HistoryPolicy.session(imported))
        assertNull(HistoryPolicy.session(full.copy(appUsageStatus = AppUsageStatus.PENDING)).appUsageStatus)
        for (bad in listOf(full.copy(energyNwh = -1),
            full.copy(peakTemperatureDeciC = 2000), full.copy(screenOffSuspendMs = 1001), full.copy(capacityEstimateMah = 0),
            full.copy(chargerType = "=HYPERLINK()"), full.copy(capacityBasis = "@x"))) {
            assertThrows(IllegalArgumentException::class.java) { HistoryPolicy.session(bad) }
        }
    }
    @Test fun outOfRangeSessionPeakIsUnknownAndPlausiblePeaksSurviveImport() {
        for (peak in listOf(Long.MIN_VALUE, -1L, 1_000_001L, 2_000_000L, Long.MAX_VALUE)) {
            val result = runCatching { HistoryPolicy.session(session().copy(peakPowerMw = peak)) }
            assertTrue("Peak $peak must not abort import: ${result.exceptionOrNull()?.message}", result.isSuccess)
            val imported = result.getOrThrow()
            assertNull(imported.peakPowerMw)
            assertEquals(HistoryPolicy.session(session().copy(peakPowerMw = null)), imported)
            assertEquals(imported, HistoryPolicy.session(imported))
        }
        for (peak in listOf(0L, 4_500L, 1_000_000L, null)) {
            assertEquals(peak, HistoryPolicy.session(session().copy(peakPowerMw = peak)).peakPowerMw)
        }
    }
    @Test fun missingUsageOnlyClearsTheIncomingReadyClaim() {
        val ready = session().copy(appUsageStatus = AppUsageStatus.READY, appUsageBasis = AppUsageBasis.DELTA)
        val missing = HistoryPolicy.planSessionImport(null, ready)
        assertNull(missing.session.appUsageStatus)
        assertNull(missing.session.appUsageBasis)
        assertEquals(ImportSessionDisposition.ADDED, missing.disposition)
        val storedReady = HistoryPolicy.session(ready)
        val retained = HistoryPolicy.planSessionImport(storedReady, ready)
        assertEquals(ImportSessionDisposition.UNCHANGED, retained.disposition)
        assertEquals(storedReady, retained.session)
        val unavailable = ready.copy(appUsageStatus = AppUsageStatus.NO_ACCESS)
        assertEquals(AppUsageStatus.NO_ACCESS,
            HistoryPolicy.planSessionImport(null, unavailable).session.appUsageStatus)
    }

    @Test fun afterCloseValuesAreNotPartOfTheMeasurementAndAreKeptWhenAFileLacksThem() {
        val ready = session().copy(appUsageStatus = AppUsageStatus.READY, appUsageBasis = AppUsageBasis.WINDOW_RESET,
            capacityEstimateMah = 4800, capacityConfidence = "LOW", capacityBasis = "COUNTER_SPAN", closeReason = "Power state changed")
        val bare = session()
        assertTrue(HistoryPolicy.sameMeasurement(ready, bare))
        assertFalse(HistoryPolicy.sameMeasurement(ready, bare.copy(deltaUah = 999)))
        assertEquals(ready.copy(closeReason = null), HistoryPolicy.mergeDerived(ready, bare))
        assertEquals(bare.copy(appUsageStatus = AppUsageStatus.FAILED), HistoryPolicy.mergeDerived(ready, bare.copy(appUsageStatus = AppUsageStatus.FAILED))
            .copy(capacityEstimateMah = null, capacityConfidence = null, capacityBasis = null))
    }
    @Test fun retentionKeepsTheCutoffsWholeLocalDay() {
        assertEquals(0L, HistoryPolicy.retentionCutoffDay(0, ZoneOffset.UTC))
        assertEquals(-1L, HistoryPolicy.retentionCutoffDay(0, ZoneId.of("America/Los_Angeles")))
        // 04:30 local on the spring-forward day.
        assertEquals(LocalDate.of(2024, 3, 10).toEpochDay(),
            HistoryPolicy.retentionCutoffDay(Instant.parse("2024-03-10T08:30:00Z").toEpochMilli(), ZoneId.of("America/New_York")))
    }
}
