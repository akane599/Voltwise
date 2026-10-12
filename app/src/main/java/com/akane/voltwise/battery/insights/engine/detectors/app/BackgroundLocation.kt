package com.akane.voltwise.battery.insights.engine.detectors.app

import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric

object BackgroundLocation {
    const val LOCATION_FLOOR_MS_PER_H = 300_000.0
    const val MAX_FOREGROUND_MS_PER_H = 60_000.0

    fun detect(ctx: AppContext): List<Finding> {
        val row = ctx.current.row(ctx.subject) ?: return emptyList()
        val foreground = AppWindows.foregroundMs(row) ?: return emptyList()
        if (foreground / ctx.current.hours > MAX_FOREGROUND_MS_PER_H) return emptyList()
        val type = FindingType.BACKGROUND_LOCATION
        val anomaly = ctx.anomaly(type, Metric.GPS_MS_PER_H, LOCATION_FLOOR_MS_PER_H)
            ?: ctx.anomaly(type, Metric.SENSOR_MS_PER_H, LOCATION_FLOOR_MS_PER_H)
            ?: return emptyList()
        return listOf(ctx.finding(type, anomaly))
    }
}
