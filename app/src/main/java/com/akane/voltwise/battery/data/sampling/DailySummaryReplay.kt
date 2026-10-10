package com.akane.voltwise.battery.data.sampling

import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.DailySummary
import com.akane.voltwise.battery.measurement.BatteryReading
import com.akane.voltwise.battery.measurement.Boundary
import com.akane.voltwise.battery.measurement.DailySummaryAggregator
import com.akane.voltwise.battery.measurement.Observation
import com.akane.voltwise.battery.measurement.ObservationEngine
import com.akane.voltwise.battery.measurement.SamplingPolicy
import java.time.ZoneId

/**
 * Rebuilds `daily_summaries` from stored samples, for the one-time backfill of history written
 * before the table existed. Every stored row counts as a persisted sample: one ObservationEngine
 * replays them in timestamp order and each interval goes through [DailySummaryAggregator], as the
 * live path does. Only this app's own observations count (legacy rows without clocks and imported
 * rows are skipped). The stored rows carry no boundary label, so a screen or power change on an
 * unlabelled row is taken as that event, while a row with a `boundaryReason` stays a gap; Doze is
 * not stored (never dozing). Pure and deterministic, so an interrupted backfill can simply rerun.
 */
class DailySummaryReplay(private val zone: ZoneId, private val updatedAt: Long) {
    private val engine = ObservationEngine()
    private var previous: Observation? = null
    private val rows = sortedMapOf<Long, DailySummary>()

    /** Feed samples in timestamp order. */
    fun add(sample: BatterySample) {
        if (sample.source != SAMPLE_SOURCE) return
        val point = observation(sample) ?: return
        val before = engine.summary
        val after = engine.accept(point)
        previous = point
        val interval = DailySummaryAggregator.interval(before, after, sample.temperatureDeciC) ?: return
        // Samples cannot recover Doze; keep the new daily observation fields unknown during replay.
        val replayed = interval.copy(dozeMs = null, screenOffDozeMs = null, screenOffSuspendMs = null)
        DailySummaryAggregator.apply(rows, replayed, zone, updatedAt).forEach { rows[it.epochDay] = it }
    }

    /** Every touched day, ascending. */
    val result: List<DailySummary> get() = rows.values.toList()

    private fun observation(sample: BatterySample): Observation? {
        val generation = sample.observationId ?: return null
        val elapsed = sample.elapsedMs ?: return null
        val uptime = sample.uptimeMs ?: return null
        val power = BatteryReading.powerState(sample.status, sample.plugged)
        val before = previous?.takeIf { it.generation == generation }
        val boundary = when {
            sample.boundaryReason != null -> Boundary.GAP
            before == null -> Boundary.SAMPLE
            before.interactive != sample.screenOn -> Boundary.SCREEN
            before.power != power -> Boundary.POWER
            else -> Boundary.SAMPLE
        }
        val confirmed = if (sample.boundaryReason == null && before != null) buildSet {
            if (before.interactive != sample.screenOn) add(Boundary.SCREEN)
            if (before.power != power) add(Boundary.POWER)
        } else emptySet()
        // Stored rows do not say which cadence they came from: the longest one keeps a sparse
        // screen-off run from reading as a gap.
        return Observation(sample.timestamp, elapsed, uptime, sample.levelPercent, sample.chargeCounterUah,
            sample.currentNowUa, sample.voltageMv, power, sample.screenOn, false, generation,
            SamplingPolicy.SCREEN_OFF_INTERVAL_MS, boundary, confirmed)
    }

    companion object {
        /** `BatterySample.source` of this app's own captures. */
        const val SAMPLE_SOURCE = "BatteryManager"
    }
}
