package com.akane.voltwise.battery.insights.engine.detectors.app

import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric

object LingeringForegroundService {
    const val FGS_FLOOR_MS_PER_H = 600_000.0
    const val FGS_RATIO_FLOOR = 5.0

    fun detect(ctx: AppContext): List<Finding> {
        val type = FindingType.LINGERING_FOREGROUND_SERVICE
        val fgs = ctx.value(Metric.FGS_MS_PER_H) ?: return emptyList()
        if (fgs < FGS_FLOOR_MS_PER_H * ctx.multiplier(type)) return emptyList()
        val anomaly = ctx.anomaly(type, Metric.FGS_TO_FOREGROUND_RATIO, FGS_RATIO_FLOOR) ?: return emptyList()
        return listOf(ctx.finding(type, anomaly, listOf(Evidence(Metric.FGS_MS_PER_H, fgs, null, Metric.FGS_MS_PER_H.unit, 1))))
    }
}
