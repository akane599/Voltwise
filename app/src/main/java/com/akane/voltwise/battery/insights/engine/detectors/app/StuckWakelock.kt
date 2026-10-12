package com.akane.voltwise.battery.insights.engine.detectors.app

import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric

object StuckWakelock {
    const val WAKELOCK_BG_SHARE = 0.20
    const val MAX_CPU_MS_PER_H = 60_000.0

    fun detect(ctx: AppContext): List<Finding> {
        val type = FindingType.STUCK_WAKELOCK
        val cpu = ctx.value(Metric.CPU_MS_PER_H) ?: return emptyList()
        if (cpu > MAX_CPU_MS_PER_H) return emptyList()
        val anomaly = ctx.anomaly(type, Metric.PARTIAL_WAKELOCK_BG_SHARE, WAKELOCK_BG_SHARE) ?: return emptyList()
        return listOf(ctx.finding(type, anomaly, listOf(Evidence(Metric.CPU_MS_PER_H, cpu, null, Metric.CPU_MS_PER_H.unit, 1))))
    }
}
