package com.akane.voltwise.battery.insights.engine.detectors.app

import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric

object JobStorm {
    const val JOBS_FLOOR_PER_H = 20.0
    const val SYNCS_FLOOR_PER_H = 20.0

    fun detect(ctx: AppContext): List<Finding> {
        val type = FindingType.JOB_STORM
        val anomaly = ctx.anomaly(type, Metric.JOBS_PER_H, JOBS_FLOOR_PER_H)
            ?: ctx.anomaly(type, Metric.SYNCS_PER_H, SYNCS_FLOOR_PER_H)
            ?: return emptyList()
        return listOf(ctx.finding(type, anomaly))
    }
}
