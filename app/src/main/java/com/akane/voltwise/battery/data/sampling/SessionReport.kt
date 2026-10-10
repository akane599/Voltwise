package com.akane.voltwise.battery.data.sampling

import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.measurement.CapacityEstimator
import com.akane.voltwise.battery.measurement.Observation
import com.akane.voltwise.battery.measurement.ObservationEngine
import com.akane.voltwise.battery.measurement.ObservationSummary
import com.akane.voltwise.battery.measurement.PowerState
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

/** `BatteryManager.EXTRA_PLUGGED` sources, stored by name in `charge_sessions.chargerType`. */
enum class ChargerType(val plugged: Int) {
    AC(1),
    USB(2),
    WIRELESS(4),
    DOCK(8),
    ;

    companion object {
        fun of(plugged: Int?): ChargerType? = entries.firstOrNull { it.plugged == plugged }
    }
}

/**
 * What an open session accumulates from every observed capture, saved or not: peak |power| in mW
 * (from calibrated current) and peak temperature. Interval totals come from the session engine.
 */
data class SessionExtremes(
    val peakPowerMw: Long? = null,
    val peakTemperatureDeciC: Int? = null,
) {
    fun plus(powerMw: Double?, temperatureDeciC: Int?) = SessionExtremes(
        peakPowerMw = listOfNotNull(peakPowerMw, powerMw?.takeIf { abs(it) <= 1_000_000 }?.let { abs(it).roundToLong() }).maxOrNull(),
        peakTemperatureDeciC = listOfNotNull(peakTemperatureDeciC, temperatureDeciC).maxOrNull(),
    )
}

/**
 * The in-memory open session and its row. Every saved sample rewrites the whole row, so fields
 * set on the open session (e.g. `appUsageStatus`) must live on this object: [report] copies it.
 */
object SessionReport {
    const val SOURCE = "BatteryManager observed interval"

    fun sessionType(power: PowerState): SessionType = when (power) {
        PowerState.CHARGING -> SessionType.CHARGE
        PowerState.DISCHARGING -> SessionType.DISCHARGE
        PowerState.PLUGGED -> SessionType.PLUGGED
        PowerState.UNKNOWN -> SessionType.UNKNOWN
    }

    /** A new open session starting at this capture. */
    fun open(point: Observation, sample: BatterySample): ChargeSession {
        val type = sessionType(point.power)
        val plugged = type == SessionType.CHARGE || type == SessionType.PLUGGED
        return ChargeSession(
            UUID.randomUUID().toString(), type, sample.timestamp, null, sample.levelPercent, null, null, null, null,
            observationId = point.generation, source = SOURCE,
            screenOnCoveredMs = 0, screenOffCoveredMs = 0,
            chargerType = if (plugged) ChargerType.of(sample.plugged)?.name else null,
        )
    }

    /** Account the closing interval, but leave the new power state's endpoint peaks to its own session. */
    fun reportPowerBoundary(
        current: ChargeSession,
        sample: BatterySample,
        point: Observation,
        engine: ObservationEngine,
        extremes: SessionExtremes,
    ): ChargeSession {
        val summary = engine.accept(point)
        // Keep the observed boundary duration and its screen-off suspend; cross-power charge is excluded.
        // The capacity estimate still ends at the last same-state sample.
        return report(current, sample, summary, extremes).copy(
            capacityEstimateMah = current.capacityEstimateMah,
            capacityConfidence = current.capacityConfidence,
            capacityBasis = current.capacityBasis,
        )
    }

    /** [current] updated through [sample]; [summary] is the session engine's summary including it. */
    fun report(current: ChargeSession, sample: BatterySample, summary: ObservationSummary, extremes: SessionExtremes): ChargeSession {
        val charging = current.type == SessionType.CHARGE
        val bucket = if (charging) summary.charging else summary.discharge
        val deltaUah = bucket.chargeChangeUah.takeIf { bucket.chargeCoveredMs > 0 }
        val span = summary.capacity
        val capacity = if (charging || current.type == SessionType.DISCHARGE) {
            CapacityEstimator.fromSession(
                span.bucket.chargeChangeUah, span.startLevel, span.endLevel,
                span.bucket.durationMs, span.bucket.chargeCoveredMs,
            )
        } else null
        return current.copy(
            lastSampleTime = sample.timestamp, endLevel = sample.levelPercent,
            observedMs = summary.observedMs, counterCoveredMs = bucket.chargeCoveredMs,
            deltaUah = deltaUah,
            avgCurrentUa = bucket.rateMa?.times(if (charging) 1000 else -1000)?.toLong(),
            screenOnMs = summary.screenOn.durationMs, screenOffMs = summary.screenOff.durationMs,
            screenOnCoveredMs = summary.screenOn.chargeCoveredMs, screenOffCoveredMs = summary.screenOff.chargeCoveredMs,
            screenOnUah = summary.screenOn.chargeChangeUah.takeIf { summary.screenOn.chargeCoveredMs > 0 },
            screenOffUah = summary.screenOff.chargeChangeUah.takeIf { summary.screenOff.chargeCoveredMs > 0 },
            cpuSuspendMs = summary.cpuSuspendMs,
            dozeMs = summary.dozeMs.takeIf { summary.observedMs > 0 },
            screenOffDozeMs = summary.screenOffDozeMs.takeIf { summary.observedMs > 0 },
            energyNwh = bucket.estimatedEnergyMwh?.let { (it * 1_000_000).roundToLong() },
            peakPowerMw = extremes.peakPowerMw,
            peakTemperatureDeciC = extremes.peakTemperatureDeciC,
            screenOffSuspendMs = summary.screenOffSuspendMs.takeIf { summary.cpuObservedMs > 0 },
            capacityEstimateMah = capacity?.fullMah,
            capacityConfidence = capacity?.confidence?.name,
            capacityBasis = capacity?.basis?.name,
        )
    }
}
