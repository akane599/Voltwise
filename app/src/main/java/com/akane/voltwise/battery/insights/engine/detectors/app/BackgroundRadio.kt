package com.akane.voltwise.battery.insights.engine.detectors.app

import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric

object BackgroundRadio {
    const val RADIO_FLOOR_MS_PER_H = 300_000.0
    const val MAX_FOREGROUND_MS_PER_H = 60_000.0

    fun detect(ctx: AppContext): List<Finding> {
        val row = ctx.current.row(ctx.subject) ?: return emptyList()
        val foreground = AppWindows.foregroundMs(row) ?: return emptyList()
        if (foreground / ctx.current.hours > MAX_FOREGROUND_MS_PER_H) return emptyList()
        val type = FindingType.BACKGROUND_RADIO
        val anomaly = ctx.anomaly(type, Metric.RADIO_ACTIVE_MS_PER_H, RADIO_FLOOR_MS_PER_H) ?: return emptyList()
        return listOf(ctx.finding(type, anomaly))
    }
}
