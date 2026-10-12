package com.akane.voltwise.battery.insights.engine.detectors.app

import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SeriesPoint
import com.akane.voltwise.battery.insights.model.Severity

/**
 * Cold-start exception to the app-baseline gate: four prior eligible not-heavy windows, but
 * fewer than four measured-containing windows. An absent app (including a censored below-cutoff
 * absence) counts as not-heavy; a present row needs supported drain below 15%. Unsupported
 * history does not count. After four measured windows APP_DRAIN_ANOMALY takes over.
 * Only the latest eligible window is evaluated. "New" means newly heavy, not newly installed.
 */
object NewHeavyApp {
    const val DRAIN_SHARE = 0.15
    const val MIN_HISTORY = 4

    fun detect(ctx: AppContext): List<Finding> {
        val type = FindingType.NEW_HEAVY_APP
        val history = ctx.history()
        if (history.size < MIN_HISTORY) return emptyList()
        val powerPoints = AppWindows.series(history, ctx.subject, Metric.POWER_MAH_PER_H)
        if (powerPoints.count { it.present } >= AppContext.MIN_BASELINE_SESSIONS) return emptyList()
        val notHeavy = history.count { window ->
            val point = powerPoints.firstOrNull { it.sessionId == window.session.id } ?: return@count false
            if (!point.present) return@count true
            val drain = AppWindows.drainMah(window, ctx.inputs.fullUah) ?: return@count false
            val power = point.value ?: return@count false
            power * window.hours / drain < DRAIN_SHARE
        }
        if (notHeavy < MIN_HISTORY || !ctx.backgroundDominant()) return emptyList()
        val power = ctx.value(Metric.POWER_MAH_PER_H) ?: return emptyList()
        val drain = AppWindows.drainMah(ctx.current, ctx.inputs.fullUah) ?: return emptyList()
        val share = power * ctx.current.hours / drain
        val threshold = DRAIN_SHARE * ctx.multiplier(type)
        if (!share.isFinite() || share < threshold) return emptyList()
        return listOf(
            Finding(
                ctx.key(type), type, Severity.MEDIUM, Confidence.LOW,
                (share / threshold * AppContext.SCORE_PER_Z).coerceAtMost(AppContext.MAX_SCORE), ctx.subject, Direction.UP,
                listOf(Evidence(Metric.WINDOW_DRAIN_SHARE, share, null, Metric.WINDOW_DRAIN_SHARE.unit, notHeavy)),
                listOf(SeriesPoint(ctx.current.atMs, share, null, null)), emptyList(),
            ),
        )
    }
}
