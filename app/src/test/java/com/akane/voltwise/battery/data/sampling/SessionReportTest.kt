package com.akane.voltwise.battery.data.sampling

import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.data.HistoryPolicy
import com.akane.voltwise.battery.data.SessionDrain
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.measurement.BatteryReading
import com.akane.voltwise.battery.measurement.Boundary
import com.akane.voltwise.battery.measurement.CapacityBasis
import com.akane.voltwise.battery.measurement.CapacityConfidence
import com.akane.voltwise.battery.measurement.Observation
import com.akane.voltwise.battery.measurement.ObservationEngine
import com.akane.voltwise.battery.measurement.PowerState
import org.junit.Assert.*
import org.junit.Test

class SessionReportTest {
    private val engine = ObservationEngine()

    private fun sample(level: Int, charge: Long, plugged: Int, status: Int, elapsed: Long, screenOn: Boolean = true) = BatterySample(
        timestamp = 1_000_000 + elapsed, levelPercent = level, status = status, plugged = plugged,
        currentNowUa = -500_000, chargeCounterUah = charge, voltageMv = 4000, temperatureDeciC = 300, health = 2,
        screenOn = screenOn, elapsedMs = elapsed, uptimeMs = elapsed, observationId = "run", source = "BatteryManager",
    )

    private fun BatterySample.point(power: PowerState, boundary: Boundary = Boundary.SAMPLE, uptime: Long = uptimeMs!!) =
        Observation(timestamp, elapsedMs!!, uptime, levelPercent, chargeCounterUah, currentNowUa, voltageMv, power, screenOn, false,
            "run", 30_000, boundary)

    @Test fun dozeIsUnknownUntilObservedThenReportCopiesBothCounters() {
        val first = sample(80, 4_000_000, 0, 3, 0)
        val point = first.point(PowerState.DISCHARGING).copy(dozing = true)
        val session = SessionReport.open(point, first)
        val initial = SessionReport.report(session, first, engine.accept(point), SessionExtremes())
        assertNull(initial.dozeMs)
        assertNull(initial.screenOffDozeMs)
        val off = sample(80, 3_999_000, 0, 3, 60_000, screenOn = false)
        engine.accept(off.point(PowerState.DISCHARGING, Boundary.SCREEN).copy(dozing = true))
        val last = sample(80, 3_998_000, 0, 3, 120_000, screenOn = false)
        val summary = engine.accept(last.point(PowerState.DISCHARGING).copy(dozing = true))
        val report = SessionReport.report(session, last, summary, SessionExtremes())
        assertEquals(120_000L, report.dozeMs)
        assertEquals(60_000L, report.screenOffDozeMs)
        engine.reset()
        val zero = reportSamples(PowerState.PLUGGED, first, first.copy(timestamp = first.timestamp + 60_000, elapsedMs = 60_000, uptimeMs = 60_000))
        assertEquals(0L, zero.dozeMs)
        assertEquals(0L, zero.screenOffDozeMs)
    }

    @Test fun nonDischargingSessionsExcludeScreenOffSuspendFromRegularCaptures() {
        for (power in listOf(PowerState.CHARGING, PowerState.PLUGGED, PowerState.UNKNOWN)) {
            engine.reset()
            val status = when (power) {
                PowerState.CHARGING -> 2
                PowerState.PLUGGED -> 4
                else -> 1
            }
            val first = sample(80, 4_000_000, 1, status, 0, screenOn = false)
            assertEquals(power, BatteryReading.powerState(first.status, first.plugged))
            val session = SessionReport.open(first.point(power), first)
            engine.accept(first.point(power))
            val last = sample(80, 4_000_000, 1, status, 60_000, screenOn = false)
            val summary = engine.accept(last.point(power, uptime = 1_000))
            val extremes = SessionExtremes().plus(-2_000.0, 300)
            val report = SessionReport.report(session, last, summary, extremes)
            assertEquals("$power retains all-power CPU suspend", 59_000L, report.cpuSuspendMs)
            assertEquals("$power has no discharging screen-off duration", 0L, report.screenOffMs)
            assertEquals("$power excludes non-discharging screen-off suspend", 0L, report.screenOffSuspendMs)
            assertEquals(2_000L, report.peakPowerMw)
            assertEquals(300, report.peakTemperatureDeciC)
        }
    }

    @Test fun nonDischargingPowerBoundariesExcludeClosingScreenOffSuspend() {
        for (power in listOf(PowerState.CHARGING, PowerState.PLUGGED, PowerState.UNKNOWN)) {
            engine.reset()
            val status = when (power) {
                PowerState.CHARGING -> 2
                PowerState.PLUGGED -> 4
                else -> 1
            }
            val first = sample(80, 4_000_000, 1, status, 0, screenOn = false)
            assertEquals(power, BatteryReading.powerState(first.status, first.plugged))
            val session = SessionReport.open(first.point(power), first)
            engine.accept(first.point(power))
            val boundary = sample(80, 4_000_000, 0, 3, 60_000, screenOn = false)
            val closed = SessionReport.reportPowerBoundary(session, boundary,
                boundary.point(PowerState.DISCHARGING, Boundary.POWER, uptime = 1_000), engine, SessionExtremes())
            assertEquals("$power boundary retains all-power CPU suspend", 59_000L, closed.cpuSuspendMs)
            assertEquals("$power boundary has no discharging screen-off duration", 0L, closed.screenOffMs)
            assertEquals("$power boundary excludes non-discharging screen-off suspend", 0L, closed.screenOffSuspendMs)
        }
    }

    @Test fun chargerTypeFollowsExtraPlugged() {
        assertEquals(ChargerType.AC, ChargerType.of(1))
        assertEquals(ChargerType.USB, ChargerType.of(2))
        assertEquals(ChargerType.WIRELESS, ChargerType.of(4))
        assertEquals(ChargerType.DOCK, ChargerType.of(8))
        assertNull(ChargerType.of(0))
        assertNull(ChargerType.of(null))
    }

    @Test fun chargeSessionReportsDeltaRateEnergyCapacityAndCharger() {
        val first = sample(level = 40, charge = 2_000_000, plugged = 2, status = 2, elapsed = 0)
        val session = SessionReport.open(first.point(PowerState.CHARGING), first)
        assertEquals(SessionType.CHARGE, session.type)
        assertEquals("USB", session.chargerType)
        assertEquals(40, session.startLevel)
        assertEquals(1, session.activeKey)
        engine.accept(first.point(PowerState.CHARGING))
        // 2 h at 1 A: +2,000 mAh over a 40 % span → a 5,000 mAh battery, HIGH confidence.
        val last = sample(level = 80, charge = 4_000_000, plugged = 2, status = 2, elapsed = 7_200_000)
        val summary = engine.accept(last.point(PowerState.CHARGING, uptime = 60_000)) // mostly suspended: no observation gap
        val report = SessionReport.report(session, last, summary, SessionExtremes(peakPowerMw = 4_200, peakTemperatureDeciC = 330))
        assertEquals(2_000_000L, report.deltaUah)
        assertEquals(1_000_000L, report.avgCurrentUa)
        assertEquals(8_000_000_000L, report.energyNwh) // 2,000 mAh × 4.0 V
        assertEquals(80, report.endLevel)
        assertEquals(last.timestamp, report.lastSampleTime)
        assertEquals(4_200L, report.peakPowerMw)
        assertEquals(330, report.peakTemperatureDeciC)
        assertEquals(5_000, report.capacityEstimateMah)
        assertEquals(CapacityConfidence.HIGH.name, report.capacityConfidence)
        assertEquals(CapacityBasis.COUNTER_SPAN.name, report.capacityBasis)
        assertEquals("USB", report.chargerType)
    }

    private fun reportSamples(power: PowerState, vararg samples: BatterySample) =
        SessionReport.open(samples.first().point(power), samples.first()).let { session ->
            samples.forEachIndexed { index, sample ->
                engine.accept(sample.point(power, uptime = index * 30_000L))
            }
            SessionReport.report(session, samples.last(), engine.summary, SessionExtremes())
        }

    @Test fun chargeTrickleAt100DoesNotInflateCapacity() {
        val report = reportSamples(
            PowerState.CHARGING,
            sample(20, 800_000, 1, 2, 0),
            sample(100, 4_000_000, 1, 2, 7_200_000),
            sample(100, 4_250_000, 1, 2, 9_000_000),
        )
        assertEquals(4_000, report.capacityEstimateMah)
        assertEquals(CapacityConfidence.HIGH.name, report.capacityConfidence)
        assertEquals(3_450_000L, report.deltaUah)
        assertEquals(1_380_000L, report.avgCurrentUa)
        assertEquals(13_800_000_000L, report.energyNwh)
    }

    @Test fun dischargeStartingWithA100HoldDoesNotInflate() {
        val report = reportSamples(
            PowerState.DISCHARGING,
            sample(100, 4_250_000, 0, 3, 0),
            sample(100, 4_000_000, 0, 3, 1_800_000),
            sample(99, 3_960_000, 0, 3, 1_890_000),
            sample(59, 2_360_000, 0, 3, 9_090_000),
        )
        assertEquals(4_000, report.capacityEstimateMah)
        assertEquals(CapacityConfidence.HIGH.name, report.capacityConfidence)
        assertEquals(1_890_000L, report.deltaUah)
        assertEquals(-748_514L, report.avgCurrentUa)
        assertEquals(7_560_000_000L, report.energyNwh)
    }

    @Test fun normal20To90ChargeIsUnchanged() {
        val report = reportSamples(
            PowerState.CHARGING,
            sample(20, 800_000, 1, 2, 0),
            sample(90, 3_600_000, 1, 2, 7_200_000),
        )
        assertEquals(4_000, report.capacityEstimateMah)
        assertEquals(CapacityConfidence.HIGH.name, report.capacityConfidence)
        assertEquals(2_800_000L, report.deltaUah)
    }

    @Test fun missingCounterAfterChargingReaches100DoesNotInvalidateCapacity() {
        val report = reportSamples(
            PowerState.CHARGING,
            sample(20, 800_000, 1, 2, 0),
            sample(100, 4_000_000, 1, 2, 7_200_000),
            sample(100, 4_250_000, 1, 2, 9_000_000).copy(chargeCounterUah = null),
        )
        assertEquals(4_000, report.capacityEstimateMah)
        assertTrue(report.counterCoveredMs < report.observedMs)
    }

    @Test fun missingCounterDuring100HoldDoesNotInvalidateDischargeCapacity() {
        val report = reportSamples(
            PowerState.DISCHARGING,
            sample(100, 4_250_000, 0, 3, 0).copy(chargeCounterUah = null),
            sample(99, 3_960_000, 0, 3, 1_890_000),
            sample(59, 2_360_000, 0, 3, 9_090_000),
        )
        assertEquals(4_000, report.capacityEstimateMah)
        assertTrue(report.counterCoveredMs < report.observedMs)
    }

    @Test fun missingCounterAtFirstStepBelow100WithholdsDischargeCapacity() {
        val report = reportSamples(
            PowerState.DISCHARGING,
            sample(100, 4_250_000, 0, 3, 0),
            sample(99, 3_960_000, 0, 3, 1_890_000).copy(chargeCounterUah = null),
            sample(79, 3_160_000, 0, 3, 5_490_000),
            sample(59, 2_360_000, 0, 3, 9_090_000),
        )
        assertNotNull(report.deltaUah)
        assertNull(report.capacityEstimateMah)
        assertNull(report.capacityConfidence)
        assertNull(report.capacityBasis)
    }

    @Test fun missingCounterAtFirst100ReadingWithholdsChargeCapacity() {
        val report = reportSamples(
            PowerState.CHARGING,
            sample(20, 800_000, 1, 2, 0),
            sample(60, 2_400_000, 1, 2, 3_600_000),
            sample(100, 4_000_000, 1, 2, 7_200_000).copy(chargeCounterUah = null),
            sample(100, 4_250_000, 1, 2, 9_000_000),
        )
        assertNotNull(report.deltaUah)
        assertNull(report.capacityEstimateMah)
        assertNull(report.capacityConfidence)
        assertNull(report.capacityBasis)
    }

    @Test fun sessionsEntirelyAt100HaveNoCapacityEstimate() {
        for (power in listOf(PowerState.CHARGING, PowerState.DISCHARGING)) {
            engine.reset()
            val charging = power == PowerState.CHARGING
            val report = reportSamples(
                power,
                sample(100, 4_000_000, if (charging) 1 else 0, if (charging) 2 else 3, 0),
                sample(100, if (charging) 4_250_000 else 3_750_000,
                    if (charging) 1 else 0, if (charging) 2 else 3, 1_800_000),
            )
            assertEquals(250_000L, report.deltaUah)
            assertNull(report.capacityEstimateMah)
        }
    }

    @Test fun dischargeSessionKeepsInMemoryFieldsAndCountsScreenOffSuspend() {
        val first = sample(level = 90, charge = 4_000_000, plugged = 0, status = 3, elapsed = 0)
        val session = SessionReport.open(first.point(PowerState.DISCHARGING), first).copy(appUsageStatus = AppUsageStatus.PENDING)
        assertNull(session.chargerType)
        var extremes = SessionExtremes()
        var previous = engine.summary
        fun add(next: BatterySample, uptime: Long, boundary: Boundary = Boundary.SAMPLE) {
            val summary = engine.accept(next.point(PowerState.DISCHARGING, boundary, uptime))
            extremes = extremes.plus(-2_000.0, next.temperatureDeciC)
            previous = summary
        }
        engine.accept(first.point(PowerState.DISCHARGING)); previous = engine.summary
        add(sample(89, 3_990_000, 0, 3, 60_000), uptime = 50_000) // screen on: 10 s suspend, not screen-off
        add(sample(89, 3_985_000, 0, 3, 120_000, screenOn = false), uptime = 100_000, Boundary.SCREEN)
        val last = sample(88, 3_960_000, 0, 3, 420_000, screenOn = false)
        add(last, uptime = 110_000) // screen off: 290 s suspended
        val report = SessionReport.report(session, last, previous, extremes)
        assertEquals(AppUsageStatus.PENDING, report.appUsageStatus)
        assertEquals(40_000L, report.deltaUah)
        assertEquals(290_000L, report.screenOffSuspendMs)
        assertEquals(310_000L, report.cpuSuspendMs)
        assertEquals(2_000L, report.peakPowerMw)
        assertTrue(report.avgCurrentUa!! < 0)
        assertNull("A 2 % span gives no capacity estimate", report.capacityEstimateMah)
    }

    @Test fun fullyCoveredDischargeKeepsCapacityWhenClosedByChargingBoundary() {
        val first = sample(level = 90, charge = 4_000_000, plugged = 0, status = 3, elapsed = 0)
        val session = SessionReport.open(first.point(PowerState.DISCHARGING), first)
        engine.accept(first.point(PowerState.DISCHARGING))
        // Constant 800 mA drain: 1,600 mAh over 40 points gives 4,000 mAh capacity.
        val middle = sample(level = 70, charge = 3_200_000, plugged = 0, status = 3, elapsed = 3_600_000)
        engine.accept(middle.point(PowerState.DISCHARGING, uptime = 30_000))
        val last = sample(level = 50, charge = 2_400_000, plugged = 0, status = 3, elapsed = 7_200_000)
        val summary = engine.accept(last.point(PowerState.DISCHARGING, uptime = 60_000))
        val current = SessionReport.report(session, last, summary, SessionExtremes())
        assertEquals(current.observedMs, current.counterCoveredMs)
        assertEquals(4_000, current.capacityEstimateMah)
        assertEquals(CapacityConfidence.HIGH.name, current.capacityConfidence)
        assertEquals(CapacityBasis.COUNTER_SPAN.name, current.capacityBasis)

        val boundary = sample(level = 49, charge = 2_390_000, plugged = 1, status = 2, elapsed = 7_230_000)
        val closed = SessionReport.reportPowerBoundary(
            current, boundary, boundary.point(PowerState.CHARGING, Boundary.POWER, uptime = 90_000),
            engine, SessionExtremes(),
        )
        assertEquals(0, engine.summary.gaps)
        assertEquals(7_230_000L, closed.observedMs)
        assertEquals(7_200_000L, closed.counterCoveredMs)
        assertEquals(current.deltaUah, closed.deltaUah)
        assertEquals(49, closed.endLevel)
        assertEquals(boundary.timestamp, closed.lastSampleTime)
        assertEquals("Power boundary must keep the last same-state capacity", current.capacityEstimateMah, closed.capacityEstimateMah)
        assertEquals(current.capacityConfidence, closed.capacityConfidence)
        assertEquals(current.capacityBasis, closed.capacityBasis)
    }

    @Test fun fullyCoveredDischargeKeepsDrainRatesWhenClosedByChargingBoundary() {
        val first = sample(level = 90, charge = 4_000_000, plugged = 0, status = 3, elapsed = 0)
        val session = SessionReport.open(first.point(PowerState.DISCHARGING), first)
        engine.accept(first.point(PowerState.DISCHARGING))
        val screenOff = sample(level = 70, charge = 3_200_000, plugged = 0, status = 3,
            elapsed = 3_600_000, screenOn = false)
        engine.accept(screenOff.point(PowerState.DISCHARGING, Boundary.SCREEN, uptime = 30_000))
        val last = sample(level = 50, charge = 2_400_000, plugged = 0, status = 3,
            elapsed = 7_200_000, screenOn = false)
        val summary = engine.accept(last.point(PowerState.DISCHARGING, uptime = 60_000))
        val extremes = SessionExtremes()
        val current = SessionReport.report(session, last, summary, extremes)
        assertEquals(current.observedMs, current.counterCoveredMs)

        val boundary = sample(level = 49, charge = 2_390_000, plugged = 1, status = 2,
            elapsed = 7_230_000, screenOn = false)
        val closed = SessionReport.reportPowerBoundary(
            current, boundary, boundary.point(PowerState.CHARGING, Boundary.POWER, uptime = 61_000),
            engine, extremes,
        )
        val drain = SessionDrain.of(closed, null)
        assertNotNull("Screen-on current must survive the charging boundary", drain.screenOn.currentMa)
        assertNotNull("Screen-off current must survive the charging boundary", drain.screenOff.currentMa)
        assertEquals(800.0, drain.screenOn.currentMa!!, 0.0)
        assertEquals(800.0, drain.screenOff.currentMa!!, 0.0)
        assertEquals("Keep observed time even when boundary charge is unknown",
            7_230_000L, closed.screenOnMs + closed.screenOffMs)
        assertEquals(current.screenOnMs, closed.screenOnMs)
        assertEquals(current.screenOffMs + 30_000, closed.screenOffMs)
        assertTrue("Screen-off suspend must not exceed its screen duration",
            closed.screenOffSuspendMs!! <= closed.screenOffMs)
        assertEquals("Screen-off suspend must include the closing interval paired with its duration",
            current.screenOffSuspendMs!! + 29_000, closed.screenOffSuspendMs)
        assertEquals(current.cpuSuspendMs!! + 29_000, closed.cpuSuspendMs)
        assertEquals(current.screenOnUah, closed.screenOnUah)
        assertEquals(current.screenOffUah, closed.screenOffUah)
        assertEquals(7_230_000L, closed.observedMs)
        assertEquals(7_200_000L, closed.counterCoveredMs)
        val capacityDrain = SessionDrain.of(closed, 4_000_000)
        assertEquals(20.0, capacityDrain.screenOn.percentPerHour!!, 0.0)
        assertEquals(20.0, capacityDrain.screenOff.percentPerHour!!, 0.0)
    }

    @Test fun partialCounterCoverageKeepsMeasuredChargeButWithholdsCapacityFields() {
        for (intervalCount in listOf(4, 10)) {
            engine.reset()
            val first = sample(level = 90, charge = 4_000_000, plugged = 0, status = 3, elapsed = 0)
                .copy(chargeCounterUah = null)
            val session = SessionReport.open(first.point(PowerState.DISCHARGING), first)
            engine.accept(first.point(PowerState.DISCHARGING))
            var last = first
            for (index in 1..intervalCount) {
                last = sample(
                    level = 90 - 40 * index / intervalCount,
                    charge = 4_000_000L - 1_600_000L * index / intervalCount,
                    plugged = 0,
                    status = 3,
                    elapsed = index * 30_000L,
                )
                engine.accept(last.point(PowerState.DISCHARGING))
            }
            // One uncovered interval leaves 75 % or 90 % coverage of the full 40-point level drop.
            val report = SessionReport.report(session, last, engine.summary, SessionExtremes())
            assertEquals(intervalCount * 30_000L, report.observedMs)
            assertEquals((intervalCount - 1) * 30_000L, report.counterCoveredMs)
            assertEquals(1_600_000L * (intervalCount - 1) / intervalCount, report.deltaUah)
            assertNotNull(report.avgCurrentUa)
            assertNull(report.capacityEstimateMah)
            assertNull(report.capacityConfidence)
            assertNull(report.capacityBasis)
            val boundary = last.copy(timestamp = last.timestamp + 30_000, elapsedMs = last.elapsedMs!! + 30_000,
                uptimeMs = last.uptimeMs!! + 30_000, plugged = 1, status = 2)
            val closed = SessionReport.reportPowerBoundary(
                report, boundary, boundary.point(PowerState.CHARGING, Boundary.POWER), engine, SessionExtremes(),
            )
            assertNull(closed.capacityEstimateMah)
            assertNull(closed.capacityConfidence)
            assertNull(closed.capacityBasis)
        }
    }

    @Test fun implausibleCalibratedPowerNeverMakesTheWrittenSessionUnimportable() {
        val first = sample(level = 90, charge = 4_000_000, plugged = 0, status = 3, elapsed = 0)
        val point = first.point(PowerState.DISCHARGING)
        val session = SessionReport.open(point, first)
        val extremes = SessionExtremes().plus(1.56e6, first.temperatureDeciC)
        val written = SessionReport.report(session, first, engine.accept(point), extremes)
        val result = runCatching { HistoryPolicy.session(written) }
        assertTrue("Writer's own session must import: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertNull("Implausible calibrated power must not become a stored peak", written.peakPowerMw)
        assertNull(result.getOrThrow().peakPowerMw)
        assertEquals(first.temperatureDeciC, written.peakTemperatureDeciC)
    }

    @Test fun extremesIgnoreImplausiblePowerWithoutLosingOtherMeasurementsOrPlausiblePeaks() {
        val missing = SessionExtremes()
            .plus(1_000_000.1, 310)
            .plus(-1.56e6, 290)
        assertEquals(SessionExtremes(peakTemperatureDeciC = 310), missing)
        val plausible = missing.plus(-1_000_000.0, 320)
            .plus(1.56e6, 300)
        assertEquals(SessionExtremes(peakPowerMw = 1_000_000, peakTemperatureDeciC = 320), plausible)
        assertEquals(0L, SessionExtremes().plus(0.0, null).peakPowerMw)
    }

    @Test fun extremesKeepMaximaAndIgnoreMissingValues() {
        val extremes = SessionExtremes()
            .plus(-1_500.0, 310)
            .plus(null, null)
            .plus(900.0, 290)
        assertEquals(SessionExtremes(peakPowerMw = 1_500, peakTemperatureDeciC = 310), extremes)
    }
}
