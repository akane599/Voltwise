package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.sampling.SessionExtremes
import com.akane.voltwise.battery.data.sampling.SessionReport
import com.akane.voltwise.battery.measurement.Boundary
import com.akane.voltwise.battery.measurement.Observation
import com.akane.voltwise.battery.measurement.ObservationEngine
import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.viewmodel.SessionDetailsMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCloseSuspendTest {
    private fun point(
        elapsed: Long,
        uptime: Long,
        screenOn: Boolean,
        power: PowerState = PowerState.DISCHARGING,
        boundary: Boundary = Boundary.SAMPLE,
    ) = Observation(
        wallMs = 1_000_000 + elapsed,
        elapsedMs = elapsed,
        uptimeMs = uptime,
        level = 80,
        chargeUah = 4_000_000,
        currentUa = if (power == PowerState.CHARGING) 3_000_000 else -500_000,
        voltageMv = 4000,
        power = power,
        interactive = screenOn,
        dozing = false,
        generation = "run",
        boundary = boundary,
    )

    private fun sample(point: Observation) = BatterySample(
        timestamp = point.wallMs,
        levelPercent = point.level,
        status = if (point.power == PowerState.CHARGING) 2 else 3,
        plugged = if (point.power == PowerState.CHARGING) 1 else 0,
        currentNowUa = point.currentUa,
        chargeCounterUah = point.chargeUah,
        voltageMv = point.voltageMv,
        temperatureDeciC = if (point.power == PowerState.CHARGING) 450 else 300,
        health = 2,
        screenOn = point.interactive,
        elapsedMs = point.elapsedMs,
        uptimeMs = point.uptimeMs,
        observationId = point.generation,
        source = "BatteryManager",
    )

    private fun closeAtPowerBoundary(screenOn: Boolean): ChargeSession {
        val engine = ObservationEngine()
        val first = point(0, 0, screenOn)
        val current = SessionReport.open(first, sample(first))
        engine.accept(first)
        val extremes = SessionExtremes().plus(-2_000.0, 300)
        val boundary = point(3_600_000, 1_000, screenOn, PowerState.CHARGING, Boundary.POWER)
        val closing = SessionReport.reportPowerBoundary(current, sample(boundary), boundary, engine, extremes)
        assertEquals("Confirmed power boundary is not a gap", 0, engine.summary.gaps)
        assertEquals(PowerState.CHARGING, engine.summary.latest?.power)
        return closing
    }

    @Test fun screenOffPowerBoundaryIncludesClosingSuspendWithoutChangingCpuSuspend() {
        val closing = closeAtPowerBoundary(screenOn = false)
        assertEquals(3_600_000L, closing.observedMs)
        assertEquals(3_600_000L, closing.screenOffMs)
        assertEquals(0L, closing.screenOffCoveredMs)
        assertEquals(0L, closing.screenOnMs)
        assertEquals(3_599_000L, closing.cpuSuspendMs)
        assertEquals(3_599_000L, closing.screenOffSuspendMs)
        assertEquals("Single screen-off closing interval keeps its deep-sleep percentage",
            99.97, SessionDetailsMapping.drain(closing, null)!!.deepSleepPercent!!, 0.01)
    }

    @Test fun screenOnPowerBoundaryDoesNotAddScreenOffSuspend() {
        val closing = closeAtPowerBoundary(screenOn = true)
        assertEquals(3_600_000L, closing.observedMs)
        assertEquals(3_600_000L, closing.screenOnMs)
        assertEquals(0L, closing.screenOnCoveredMs)
        assertEquals(0L, closing.screenOffMs)
        assertEquals(3_599_000L, closing.cpuSuspendMs)
        assertEquals(0L, closing.screenOffSuspendMs)
    }

    @Test fun powerBoundaryWithoutObservedCpuKeepsScreenOffSuspendUnavailable() {
        val engine = ObservationEngine()
        val first = point(0, 0, screenOn = false)
        val current = SessionReport.open(first, sample(first))
        engine.accept(first)
        val boundary = point(0, 0, false, PowerState.CHARGING, Boundary.POWER)
        val closing = SessionReport.reportPowerBoundary(current, sample(boundary), boundary, engine, SessionExtremes())
        assertEquals(0L, engine.summary.cpuObservedMs)
        assertNull(closing.screenOffSuspendMs)
    }

    @Test fun powerBoundaryKeepsOldPeaksInsteadOfChargingEndpoint() {
        // The charging endpoint is 12,000 mW and 45 C, outside the old discharge session.
        val closing = closeAtPowerBoundary(screenOn = false)
        assertEquals(2_000L, closing.peakPowerMw)
        assertEquals(300, closing.peakTemperatureDeciC)
    }

    @Test fun powerBoundaryAddsClosingIntervalToExistingScreenOffSuspend() {
        val engine = ObservationEngine()
        val first = point(0, 0, screenOn = false)
        val current = SessionReport.open(first, sample(first))
        engine.accept(first)
        val middle = point(1_800_000, 500, screenOn = false)
        val middleSummary = engine.accept(middle)
        val extremes = SessionExtremes().plus(-2_000.0, 300)
        val boundary = point(3_600_000, 1_000, false, PowerState.CHARGING, Boundary.POWER)
        val closing = SessionReport.reportPowerBoundary(current, sample(boundary), boundary, engine, extremes)
        assertEquals(3_599_000L, closing.cpuSuspendMs)
        assertEquals(3_599_000L, closing.screenOffSuspendMs)
        assertEquals(3_600_000L, closing.screenOffMs)
        assertEquals(1_800_000L, closing.screenOffCoveredMs)
        assertEquals(1_799_500L, middleSummary.screenOffSuspendMs)
        assertEquals("Existing and closing screen-off intervals keep their deep-sleep percentage",
            99.97, SessionDetailsMapping.drain(closing, null)!!.deepSleepPercent!!, 0.01)
    }
}
