package com.akane.voltwise.battery.diagnostics

import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.measurement.ObservationSummary
import com.akane.voltwise.battery.measurement.BatteryReading
import java.time.Instant
import java.util.Locale

/** Deliberately excludes app/UID lists, observation IDs, raw dumps, device identifiers and exception payloads. */
object DiagnosticReport {
    private fun time(value: Long?): String = value?.let { Instant.ofEpochMilli(it).toString() } ?: "unavailable"
    private fun value(number: Number?, unit: String): String = number?.let {
        val formatted = when (it) {
            is Double -> String.format(Locale.ROOT, "%.3f", it).trimEnd('0').trimEnd('.')
            is Float -> String.format(Locale.ROOT, "%.3f", it).trimEnd('0').trimEnd('.')
            else -> it.toString()
        }
        "$formatted $unit"
    } ?: "unavailable"
    fun reading(sample: BatterySample?): String = buildString {
        appendLine("Source: Android BatteryManager / ACTION_BATTERY_CHANGED")
        appendLine("Captured: ${time(sample?.timestamp)} (UTC)")
        appendLine("Power state: ${BatteryReading.powerState(sample?.status ?: 1, sample?.plugged)}")
        appendLine("Level: ${value(sample?.levelPercent, "%")}")
        appendLine("Net current: ${value(sample?.currentNowUa, "µA")}")
        appendLine("Hardware average current: ${value(sample?.currentAverageUa, "µA")}")
        appendLine("Voltage: ${value(sample?.voltageMv, "mV")}")
        appendLine("Temperature: ${value(sample?.temperatureDeciC?.div(10.0), "°C")}")
        appendLine("Charge counter: ${value(sample?.chargeCounterUah, "µAh")}")
        appendLine("Remaining energy counter: ${value(sample?.energyNwh, "nWh")}")
        appendLine("Reported cycles: ${sample?.cycleCount ?: "unavailable"}")
        appendLine("Remaining time estimate: ${value(sample?.etaMs, "ms")}")
        append("Estimate basis: ${sample?.etaBasis ?: "unavailable"}")
    }
    fun observation(summary: ObservationSummary, monitoring: Boolean): String = buildString {
        appendLine("Monitoring: ${if (monitoring) "active" else "stopped"}")
        appendLine("Observed from: ${time(summary.startedAt)}")
        appendLine("Last interval endpoint: ${time(summary.latest?.wallMs)}")
        appendLine("Observed duration: ${summary.observedMs} ms")
        appendLine("Discharge counter coverage: ${summary.discharge.chargeCoveredMs} / ${summary.discharge.durationMs} ms")
        appendLine("Discharged charge: ${value(summary.discharge.chargeMah, "mAh")}")
        appendLine("Derived energy estimate: ${value(summary.discharge.estimatedEnergyMwh, "mWh")}")
        appendLine("Energy coverage: ${summary.discharge.energyCoveredMs} / ${summary.discharge.durationMs} ms")
        appendLine("Screen on / off while discharging: ${summary.screenOn.durationMs} / ${summary.screenOff.durationMs} ms")
        appendLine("CPU suspend: ${if (summary.cpuObservedMs > 0) "${summary.cpuSuspendMs} / ${summary.cpuObservedMs} ms observed" else "unavailable"}")
        appendLine("Android Doze: ${if (summary.cpuObservedMs > 0) "${summary.dozeMs} ms" else "unavailable"}")
        append("Excluded observation gaps: ${summary.gaps}; charge intervals unavailable: ${summary.counterGaps}")
    }
    fun events(events: List<DiagnosticEvent>): String = events.takeLast(DiagnosticLog.MAX_EVENTS).joinToString("\n") {
        "${time(it.firstAt)} → ${time(it.lastAt)} · ${it.code.name} · ${it.count}"
    }
}
