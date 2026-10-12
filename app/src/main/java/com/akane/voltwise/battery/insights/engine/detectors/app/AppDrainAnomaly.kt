package com.akane.voltwise.battery.insights.engine.detectors.app

import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric

object AppDrainAnomaly {
    const val POWER_FLOOR_MAH_PER_H = 10.0

    fun detect(ctx: AppContext): List<Finding> {
        val type = FindingType.APP_DRAIN_ANOMALY
        val anomaly = ctx.anomaly(type, Metric.POWER_MAH_PER_H, POWER_FLOOR_MAH_PER_H) ?: return emptyList()
        return listOf(ctx.finding(type, anomaly))
    }
}
