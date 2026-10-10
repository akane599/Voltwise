package com.akane.voltwise.battery.insights.engine.detectors.app

import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric

object WakeupStorm {
    const val ALARMS_FLOOR_PER_H = 30.0

    fun detect(ctx: AppContext): List<Finding> {
        val type = FindingType.WAKEUP_STORM
        val anomaly = ctx.anomaly(type, Metric.WAKEUP_ALARMS_PER_H, ALARMS_FLOOR_PER_H) ?: return emptyList()
        return listOf(ctx.finding(type, anomaly))
    }
}
