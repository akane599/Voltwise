package com.akane.voltwise.battery.insights.engine.detectors.app

import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric

object BackgroundRunaway {
    const val PERSISTENCE_SESSIONS = 2
    const val CPU_FLOOR_MS_PER_H = 120_000.0
    const val WAKELOCK_BG_SHARE = 0.20
    const val BG_SHARE_FLOOR = 0.20

    fun detect(ctx: AppContext): List<Finding> {
        val type = FindingType.BACKGROUND_RUNAWAY
        val multiplier = ctx.multiplier(type)
        for (window in ctx.windows.takeLast(PERSISTENCE_SESSIONS)) {
            val cpu = ctx.value(Metric.CPU_MS_PER_H, window) ?: return emptyList()
            val wakelock = ctx.value(Metric.PARTIAL_WAKELOCK_BG_SHARE, window) ?: return emptyList()
            val fgs = ctx.value(Metric.FGS_TO_FOREGROUND_RATIO, window)
            if ((!ctx.backgroundDominant(window) && (fgs == null || fgs < AppContext.BACKGROUND_RATIO)) ||
                cpu < CPU_FLOOR_MS_PER_H * multiplier || wakelock < WAKELOCK_BG_SHARE * multiplier
            ) return emptyList()
        }
        val anomaly = ctx.anomaly(type, Metric.BG_TIME_SHARE, BG_SHARE_FLOOR, PERSISTENCE_SESSIONS)
            ?: ctx.anomaly(type, Metric.FGS_MS_PER_H, BG_SHARE_FLOOR * 3_600_000.0, PERSISTENCE_SESSIONS)
            ?: return emptyList()
        val extra = listOf(Metric.CPU_MS_PER_H, Metric.PARTIAL_WAKELOCK_BG_SHARE).mapNotNull { metric ->
            ctx.value(metric)?.let { Evidence(metric, it, null, metric.unit, PERSISTENCE_SESSIONS) }
        }
        return listOf(ctx.finding(type, anomaly, extra))
    }
}
