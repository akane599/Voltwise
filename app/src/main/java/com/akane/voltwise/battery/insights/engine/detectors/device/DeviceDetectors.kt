package com.akane.voltwise.battery.insights.engine.detectors.device

import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.engine.MAX_CURRENT_WINDOW_AGE_MS
import com.akane.voltwise.battery.insights.engine.finding
import com.akane.voltwise.battery.insights.engine.stats.RobustBaseline
import com.akane.voltwise.battery.insights.engine.stats.TimedValue
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SeriesPoint
import com.akane.voltwise.battery.insights.model.SessionInput
import com.akane.voltwise.battery.insights.model.Subject

object DeviceDetectors {
    fun detect(inputs: InsightInputs): List<Finding> {
        val sessions = dischargeSessions(inputs)
        val current = sessions.lastOrNull()?.takeIf { it.endMs >= inputs.nowMs - MAX_CURRENT_WINDOW_AGE_MS }
        return listOfNotNull(current?.let { dozeBlocked(inputs, sessions, it) },
            current?.let { screenOffDrain(inputs, sessions, it) }).map { finding ->
                finding.copy(attributions = attributions(inputs, requireNotNull(current).id))
            } + whitelisted(inputs)
    }

    private fun dozeBlocked(inputs: InsightInputs, sessions: List<SessionInput>, current: SessionInput): Finding? {
        if (current.screenOffMs < 2 * AppWindows.HOUR_MS) return null
        val evidence = listOf(Metric.DEEP_DOZE_SHARE to 0.20, Metric.SCREEN_OFF_DEEP_SLEEP_SHARE to 0.50)
            .map { (metric, floor) ->
                val value = deviceValue(current, metric, inputs.fullUah) ?: return null
                val history = sessions.filter { it.endMs <= current.startMs && it.screenOffMs >= 2 * AppWindows.HOUR_MS }
                    .mapNotNull { s -> deviceValue(s, metric, inputs.fullUah)?.let { TimedValue(s.endMs, it) } }
                if (history.size < 4) return null
                val baseline = RobustBaseline.of(history, current.endMs, HALF_LIFE_MS) ?: return null
                if (value >= floor || baseline.median - value < SHARE_CHANGE ||
                    (baseline.robustZ(value, SHARE_CHANGE) ?: return null) > -RobustBaseline.Z_THRESHOLD
                ) return null
                Evidence(metric, value, baseline.median, metric.unit, history.size)
            }
        return finding(FindingType.DOZE_BLOCKED, evidence, direction = Direction.DOWN)
    }

    private fun screenOffDrain(inputs: InsightInputs, sessions: List<SessionInput>, current: SessionInput): Finding? {
        val metric = Metric.SCREEN_OFF_PCT_PER_H
        val value = deviceValue(current, metric, inputs.fullUah) ?: return null
        val history = sessions.filter { it.endMs <= current.startMs }.mapNotNull { s ->
            deviceValue(s, metric, inputs.fullUah)?.let { TimedValue(s.endMs, it) }
        }
        if (history.size < 4) return null
        val baseline = RobustBaseline.of(history, current.endMs, HALF_LIFE_MS) ?: return null
        val z = baseline.robustZ(value, DRAIN_FLOOR) ?: return null
        if (value < DRAIN_FLOOR || value - baseline.median < DRAIN_FLOOR || z < RobustBaseline.Z_THRESHOLD) return null
        return finding(
            FindingType.SCREEN_OFF_DRAIN_HIGH,
            listOf(Evidence(metric, value, baseline.median, metric.unit, history.size)),
            score = (z * 10).coerceAtMost(100.0),
            series = (history + TimedValue(current.endMs, value)).map { SeriesPoint(it.atMs, it.value, null, null) },
        )
    }

    private fun whitelisted(inputs: InsightInputs): List<Finding> {
        val whitelist = inputs.dozeUserWhitelist ?: return emptyList()
        val window = AppWindows.select(inputs).lastOrNull() ?: return emptyList()
        if (window.atMs < inputs.nowMs - MAX_CURRENT_WINDOW_AGE_MS) return emptyList()
        // Only measured background work is eligible; foreground-only power is not background drain.
        val rows = window.rows.filterNot { it.isOthers }.filter {
            (it.bgMs?.let { bg -> bg > 0 } == true || it.partialWakelockBgMs?.let { bg -> bg > 0 } == true) &&
                AppWindows.value(window, it, Metric.POWER_MAH_PER_H) != null
        }.sortedWith(compareByDescending<com.akane.voltwise.battery.insights.model.AppSessionInput> { it.bgMs }
            .thenByDescending { it.partialWakelockBgMs }.thenByDescending { it.powerMah }
            .thenBy { it.packageName }.thenBy { it.uid }).take(5)
        return rows.filter { it.packageName in whitelist }.map { row ->
            val evidence = listOf(Metric.BG_TIME_SHARE, Metric.PARTIAL_WAKELOCK_BG_SHARE, Metric.POWER_MAH_PER_H)
                .mapNotNull { metric -> AppWindows.value(window, row, metric)?.let { Evidence(metric, it, null, metric.unit, 1) } }
            finding(FindingType.DOZE_WHITELISTED_DRAINER, evidence, subject = Subject.App(row.uid, row.packageName))
        }
    }

    private const val HALF_LIFE_MS = 14.0 * DAY_MS
    private const val SHARE_CHANGE = 0.10
    private const val DRAIN_FLOOR = 1.0
}
