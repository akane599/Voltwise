package com.akane.voltwise.battery.measurement

import com.akane.voltwise.battery.data.db.DailySummary
import java.time.Instant
import java.time.ZoneId

/**
 * What one stored interval adds to the daily summaries: from the previous persisted sample
 * ([startWallMs]) to this one ([endWallMs], whose reading is [endLevelPercent] and
 * [endTemperatureDeciC]). Durations in ms are discharging time by screen state; charges in µAh
 * are ≥ 0; [cpuSuspendMs] is non-null only when the interval was discharging. Doze and screen-off
 * suspend deltas are non-null only when the engine observed time, including measured zero.
 */
data class DayInterval(
    val startWallMs: Long,
    val endWallMs: Long,
    val screenOnMs: Long = 0,
    val screenOffMs: Long = 0,
    val screenOnDischargeUah: Long = 0,
    val screenOffDischargeUah: Long = 0,
    val chargedUah: Long = 0,
    val cpuSuspendMs: Long? = null,
    val endLevelPercent: Int? = null,
    val endTemperatureDeciC: Int? = null,
    val screenOnCoveredMs: Long = 0,
    val screenOffCoveredMs: Long = 0,
    val dozeMs: Long? = null,
    val screenOffDozeMs: Long? = null,
    val screenOffSuspendMs: Long? = null,
)

/**
 * Folds intervals into one [DailySummary] row per local day. An interval crossing local midnight
 * is split in proportion to the wall time on each side, with day boundaries from the zone's
 * rules, so 23 h and 25 h DST days get their real share. The end reading updates only its own day.
 */
object DailySummaryAggregator {
    fun epochDay(wallMs: Long, zone: ZoneId): Long = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate().toEpochDay()

    /**
     * The interval ObservationEngine accounted between two summaries: [before] as it stood at the
     * previous persisted sample, [after] including this one (its `latest` is the end reading).
     * Gaps, restarts and resets add nothing. Null when [after] has no observation.
     * The wall span is capped by elapsed time so a forward clock jump cannot invent skipped days.
     *
     * The whole CPU-suspend delta is attributed to discharge when any discharging time was added.
     * That is exact only because PersistPolicy persists every status/plugged change, so each
     * persisted interval has a single power state; an interval mixing states would over-count.
     */
    fun interval(before: ObservationSummary, after: ObservationSummary, endTemperatureDeciC: Int?): DayInterval? {
        val end = after.latest ?: return null
        val startWallMs = before.latest?.let { start ->
            val elapsedMs = (end.elapsedMs - start.elapsedMs).coerceAtLeast(0)
            maxOf(start.wallMs, end.wallMs - elapsedMs)
        } ?: end.wallMs
        fun grew(now: Long, then: Long) = (now - then).coerceAtLeast(0)
        val dischargeMs = grew(after.discharge.durationMs, before.discharge.durationMs)
        val observed = grew(after.observedMs, before.observedMs) > 0
        return DayInterval(
            startWallMs = startWallMs,
            endWallMs = end.wallMs,
            screenOnMs = grew(after.screenOn.durationMs, before.screenOn.durationMs),
            screenOffMs = grew(after.screenOff.durationMs, before.screenOff.durationMs),
            screenOnCoveredMs = grew(after.screenOn.chargeCoveredMs, before.screenOn.chargeCoveredMs),
            screenOffCoveredMs = grew(after.screenOff.chargeCoveredMs, before.screenOff.chargeCoveredMs),
            screenOnDischargeUah = grew(after.screenOn.chargeChangeUah, before.screenOn.chargeChangeUah),
            screenOffDischargeUah = grew(after.screenOff.chargeChangeUah, before.screenOff.chargeChangeUah),
            chargedUah = grew(after.charging.chargeChangeUah, before.charging.chargeChangeUah),
            cpuSuspendMs = if (dischargeMs > 0) grew(after.cpuSuspendMs, before.cpuSuspendMs) else null,
            dozeMs = if (observed) grew(after.dozeMs, before.dozeMs) else null,
            screenOffDozeMs = if (observed) grew(after.screenOffDozeMs, before.screenOffDozeMs) else null,
            screenOffSuspendMs = if (observed) grew(after.screenOffSuspendMs, before.screenOffSuspendMs) else null,
            endLevelPercent = end.level,
            endTemperatureDeciC = endTemperatureDeciC,
        )
    }

    /** The days [interval] touches: load these rows before [apply]. */
    fun days(interval: DayInterval, zone: ZoneId): LongRange = split(interval, zone).let { it.first().first..it.last().first }

    /**
     * Adds [interval] to [rows] (existing rows by epochDay; missing days start empty) and returns
     * every touched row with [updatedAt], in day order. Upsert them with the sample.
     */
    fun apply(rows: Map<Long, DailySummary>, interval: DayInterval, zone: ZoneId, updatedAt: Long): List<DailySummary> {
        val segments = split(interval, zone)
        val weights = segments.map { it.second }
        val screenOn = apportion(interval.screenOnMs, weights)
        val screenOff = apportion(interval.screenOffMs, weights)
        val screenOnCovered = apportionCoverage(interval.screenOnCoveredMs, screenOn)
        val screenOffCovered = apportionCoverage(interval.screenOffCoveredMs, screenOff)
        val screenOnUah = apportion(interval.screenOnDischargeUah, weights)
        val screenOffUah = apportion(interval.screenOffDischargeUah, weights)
        val charged = apportion(interval.chargedUah, weights)
        val suspend = interval.cpuSuspendMs?.let { apportion(it, weights) }
        // Accepted elapsed time survives a nonincreasing wall clock in the sole end-day bucket.
        // A zero-length midnight endpoint beside observed segments still receives no measurement.
        val observedWeights = if (weights.singleOrNull() == 0L) listOf(1L) else weights
        fun observedShares(value: Long): List<Long> {
            val shares = apportion(value, observedWeights.filter { it > 0 }).iterator()
            return observedWeights.map { if (it > 0) shares.next() else 0L }
        }
        val doze = interval.dozeMs?.let(::observedShares)
        val screenOffDoze = interval.screenOffDozeMs?.let(::observedShares)
        val screenOffSuspend = interval.screenOffSuspendMs?.let(::observedShares)
        val endDay = segments.last().first
        return segments.mapIndexed { i, (day, _) ->
            fun contribute(previous: Long?, shares: List<Long>?): Long? =
                if (observedWeights[i] > 0 && shares != null) (previous ?: 0L) + shares[i] else previous
            val row = rows[day] ?: DailySummary(epochDay = day, screenOnCoveredMs = 0, screenOffCoveredMs = 0)
            val added = row.copy(
                screenOnMs = row.screenOnMs + screenOn[i],
                screenOffMs = row.screenOffMs + screenOff[i],
                // Unknown historical coverage stays unknown: its charge cannot be divided by only new time.
                screenOnCoveredMs = row.screenOnCoveredMs?.plus(screenOnCovered[i]),
                screenOffCoveredMs = row.screenOffCoveredMs?.plus(screenOffCovered[i]),
                screenOnDischargeUah = row.screenOnDischargeUah + screenOnUah[i],
                screenOffDischargeUah = row.screenOffDischargeUah + screenOffUah[i],
                chargedUah = row.chargedUah + charged[i],
                cpuSuspendMs = suspend?.let { (row.cpuSuspendMs ?: 0L) + it[i] } ?: row.cpuSuspendMs,
                dozeMs = contribute(row.dozeMs, doze),
                // Prior unknown screen-off observations cannot be recovered from new numerators.
                screenOffDozeMs = if (row.screenOffMs > 0 && row.screenOffDozeMs == null) null
                    else contribute(row.screenOffDozeMs, screenOffDoze),
                screenOffSuspendMs = if (row.screenOffMs > 0 && row.screenOffSuspendMs == null) null
                    else contribute(row.screenOffSuspendMs, screenOffSuspend),
                updatedAt = updatedAt,
            )
            if (day == endDay) added.withReading(interval.endLevelPercent, interval.endTemperatureDeciC) else added
        }
    }

    private fun DailySummary.withReading(level: Int?, temperatureDeciC: Int?) = copy(
        minLevel = listOfNotNull(minLevel, level).minOrNull(),
        maxLevel = listOfNotNull(maxLevel, level).maxOrNull(),
        peakTemperatureDeciC = listOfNotNull(peakTemperatureDeciC, temperatureDeciC).maxOrNull(),
    )

    /**
     * (epochDay, wall ms on that day) for each local day the interval covers. An interval with
     * nothing to add, or running backwards, is a single zero-length segment on the end day.
     */
    private fun split(interval: DayInterval, zone: ZoneId): List<Pair<Long, Long>> {
        val start = interval.startWallMs
        val end = interval.endWallMs
        val empty = interval.screenOnMs == 0L && interval.screenOffMs == 0L && interval.screenOnDischargeUah == 0L &&
            interval.screenOffDischargeUah == 0L && interval.chargedUah == 0L && (interval.cpuSuspendMs ?: 0L) == 0L &&
            interval.dozeMs == null && interval.screenOffDozeMs == null && interval.screenOffSuspendMs == null
        if (empty || end <= start) return listOf(epochDay(end, zone) to 0L)
        val segments = mutableListOf<Pair<Long, Long>>()
        var date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        var cursor = start
        while (cursor < end) {
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val segmentEnd = minOf(end, nextMidnight)
            segments += date.toEpochDay() to segmentEnd - cursor
            cursor = segmentEnd
            date = date.plusDays(1)
        }
        if (segments.last().first != epochDay(end, zone)) segments += epochDay(end, zone) to 0L
        return segments
    }

    /** Distribute rounding across nonempty buckets, never spilling coverage into a zero-time day. */
    private fun apportionCoverage(value: Long, durations: List<Long>): List<Long> {
        var remainingMs = durations.sum()
        var remainingCovered = value
        return durations.map { duration ->
            val share = if (remainingMs == 0L) 0L
                else (remainingCovered.toDouble() * duration / remainingMs).toLong().coerceAtMost(duration)
            remainingMs -= duration
            remainingCovered -= share
            share
        }
    }

    /** Splits [value] by [weights], flooring each share; the last share takes the remainder. */
    private fun apportion(value: Long, weights: List<Long>): List<Long> {
        val total = weights.sum()
        if (total <= 0) return List(weights.size) { if (it == weights.lastIndex) value else 0 }
        val shares = weights.dropLast(1).map { (value.toDouble() * it / total).toLong() }
        return shares + (value - shares.sum())
    }
}
