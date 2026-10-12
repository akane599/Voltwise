package com.akane.voltwise.battery.insights.engine.detectors.device

import com.akane.voltwise.battery.insights.engine.finding
import com.akane.voltwise.battery.insights.engine.stats.TheilSen
import com.akane.voltwise.battery.insights.engine.stats.TimedValue
import com.akane.voltwise.battery.insights.engine.stats.median
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SeriesPoint
import com.akane.voltwise.battery.insights.model.SessionKind
import com.akane.voltwise.battery.insights.model.Severity

object ChargingHealth {
    fun detect(inputs: InsightInputs): List<Finding> {
        val recent = inputs.sessions.filter {
            !it.imported && it.endMs > it.startMs && it.startMs >= inputs.nowMs - 14 * DAY_MS && it.endMs <= inputs.nowMs
        }
        val full = recent.filter {
            it.kind == SessionKind.PLUGGED && it.startLevel == 100 && it.endLevel == 100 &&
                it.observedMs >= FULL_HOLD_MS
        }.map { TimedValue(it.endMs, it.observedMs.toDouble()) }
        val hot = recent.filter { it.kind == SessionKind.CHARGE }.mapNotNull { session ->
            session.peakTemperatureDeciC?.takeIf { it >= 400 }?.let { TimedValue(session.endMs, it / 10.0) }
        }
        return listOfNotNull(
            repeated(FindingType.CHARGING_AT_FULL, Metric.PLUGGED_AT_FULL_MS, full),
            repeated(FindingType.HOT_CHARGING, Metric.TEMPERATURE_C, hot),
            health(inputs),
        )
    }

    private fun repeated(type: FindingType, metric: Metric, points: List<TimedValue>): Finding? {
        if (points.size < 3) return null
        return finding(
            type, listOf(Evidence(metric, median(points.map { it.value }) ?: return null, null, metric.unit, points.size)),
            series = points.sortedBy { it.atMs }.map { SeriesPoint(it.atMs, it.value, null, null) },
        )
    }

    private fun health(inputs: InsightInputs): Finding? {
        val points = inputs.capacity.filter {
            it.confidence >= 2 && it.mah.isFinite() && it.mah > 0 && it.atMs in inputs.nowMs - 90 * DAY_MS..inputs.nowMs
        }.groupBy { it.atMs }.map { (at, values) -> TimedValue(at, requireNotNull(median(values.map { it.mah }))) }
            .sortedBy { it.atMs }
        if (points.size < 6 || points.last().atMs - points.first().atMs < 30 * DAY_MS) return null
        val trend = TheilSen.trend(points) ?: return null
        if (trend.mannKendallZ > MAX_DECLINE_MANN_KENDALL_Z) return null
        val reference = median(points.map { it.value }) ?: return null
        val annualPct = trend.slope * (365.25 * DAY_MS) / reference * 100.0
        if (annualPct > MAX_DECLINE_ANNUAL_PCT) return null
        val metric = Metric.CAPACITY_CHANGE_PCT_PER_YEAR
        return finding(
            FindingType.HEALTH_DECLINE, listOf(Evidence(metric, annualPct, null, metric.unit, points.size)),
            severity = Severity.INFO, direction = Direction.DOWN,
            series = points.map { SeriesPoint(it.atMs, it.value, null, null) },
        )
    }

    private const val MAX_DECLINE_MANN_KENDALL_Z = -2.33
    private const val MAX_DECLINE_ANNUAL_PCT = -3.0
    private const val FULL_HOLD_MS = 2 * 3_600_000L
}
