package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.device.percentRate
import com.akane.voltwise.battery.insights.engine.detectors.device.share
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.engine.stats.EffectSize
import com.akane.voltwise.battery.insights.model.DayInput
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SeriesPoint
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import java.time.LocalDate
import kotlin.math.abs

object Trends {
    fun detect(inputs: InsightInputs): List<Finding> {
        // Today is incomplete: compare the last seven completed days with the preceding 21.
        val days = inputs.days.filter { it.epochDay in inputs.todayEpochDay - 28 until inputs.todayEpochDay }
            .sortedBy { it.epochDay }
        val split = inputs.todayEpochDay - 7
        fun dayStart(epochDay: Long): Long =
            LocalDate.ofEpochDay(epochDay).atStartOfDay(inputs.zone).toInstant().toEpochMilli()
        val historyStartMs = dayStart(inputs.todayEpochDay - 28)
        val todayStartMs = dayStart(inputs.todayEpochDay)
        val splitMs = dayStart(split)
        val device = floors.mapNotNull { (metric, floor) ->
            val points = days.mapNotNull { day -> dayValue(day, metric, inputs.fullUah)?.let { day.epochDay to it } }
            comparison(
                points.filter { it.first < split }.map { it.second },
                points.filter { it.first >= split }.map { it.second }, metric, floor, Subject.Device,
                points.map { SeriesPoint(dayStart(it.first), it.second, null, null) },
            )
        }
        val windows = AppWindows.select(inputs).filter {
            val start = it.session.appWindow?.captureStartMs ?: it.session.startMs
            start >= historyStartMs && it.atMs <= todayStartMs
        }
        val subjects = windows.flatMap { it.rows }.filterNot { it.isOthers }
            .map { Subject.App(it.uid, it.packageName) }.distinct().sortedWith(compareBy({ it.packageName }, { it.uid }))
        val apps = subjects.mapNotNull { subject ->
            // Exact-zero absences are measurements; censored upper bounds are not.
            val points = windows.mapNotNull { window ->
                val start = window.session.appWindow?.captureStartMs ?: window.session.startMs
                val value = AppWindows.point(window, subject, Metric.POWER_MAH_PER_H)?.value
                    ?: return@mapNotNull null
                Triple(start, window.atMs, value)
            }
            comparison(
                points.filter { it.second <= splitMs }.map { it.third },
                points.filter { it.first >= splitMs }.map { it.third },
                Metric.POWER_MAH_PER_H, 2.0, subject,
                points.map { SeriesPoint(it.second, it.third, null, null) },
            )
        }
        return device + apps
    }

    private fun comparison(
        before: List<Double>, after: List<Double>, metric: Metric, floor: Double,
        subject: Subject, series: List<SeriesPoint>,
    ): Finding? {
        if (before.size < 4 || after.size < 4) return null
        val effect = EffectSize.between(before, after) ?: return null
        if (abs(effect.absolute) < floor || (effect.relative != null && abs(effect.relative) < MIN_RELATIVE)) return null
        val direction = if (effect.absolute < 0) Direction.DOWN else Direction.UP
        return finding(
            FindingType.TREND,
            listOf(Evidence(metric, effect.after, effect.before, metric.unit, before.size + after.size)),
            subject = subject, severity = Severity.INFO,
            direction = direction,
            score = (abs(effect.absolute) / floor * 10.0).coerceAtMost(100.0), series = series,
            suffix = "${metric.name}:${direction.name}",
        )
    }

    private fun dayValue(day: DayInput, metric: Metric, fullUah: Long?): Double? = when (metric) {
        Metric.SCREEN_ON_PCT_PER_H -> percentRate(day.screenOnDischargeUah, day.screenOnCoveredMs, fullUah)
        Metric.SCREEN_OFF_PCT_PER_H -> percentRate(day.screenOffDischargeUah, day.screenOffCoveredMs, fullUah)
        Metric.DAILY_USE_PCT -> if (fullUah != null && fullUah > 0 &&
            day.screenOnCoveredMs != null && day.screenOffCoveredMs != null &&
            day.screenOnCoveredMs >= 0 && day.screenOffCoveredMs >= 0 &&
            day.screenOnCoveredMs.toDouble() + day.screenOffCoveredMs > 0 &&
            day.screenOnDischargeUah >= 0 && day.screenOffDischargeUah >= 0
        ) (day.screenOnDischargeUah.toDouble() + day.screenOffDischargeUah) / fullUah * 100.0 else null
        Metric.SCREEN_OFF_DEEP_SLEEP_SHARE -> share(day.screenOffSuspendMs, day.screenOffMs)
        Metric.DEEP_DOZE_SHARE -> share(day.screenOffDozeMs, day.screenOffMs)
        Metric.TEMPERATURE_C -> day.peakTemperatureDeciC?.toDouble()?.div(10.0)
        else -> null
    }

    private const val MIN_RELATIVE = 0.20
    private val floors = linkedMapOf(
        Metric.SCREEN_ON_PCT_PER_H to 1.0, Metric.SCREEN_OFF_PCT_PER_H to 0.5,
        Metric.DAILY_USE_PCT to 10.0, Metric.SCREEN_OFF_DEEP_SLEEP_SHARE to 0.10,
        Metric.DEEP_DOZE_SHARE to 0.10, Metric.TEMPERATURE_C to 3.0,
    )
}
