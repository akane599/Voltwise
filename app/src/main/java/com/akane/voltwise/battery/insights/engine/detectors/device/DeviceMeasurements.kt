package com.akane.voltwise.battery.insights.engine.detectors.device

import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SessionInput
import com.akane.voltwise.battery.insights.model.SessionKind

internal const val DAY_MS = 86_400_000L

internal fun dischargeSessions(inputs: InsightInputs): List<SessionInput> = inputs.sessions.filter {
    it.kind == SessionKind.DISCHARGE && !it.imported && it.endMs > it.startMs &&
        it.endMs <= inputs.nowMs && it.observedMs > 0
}.sortedWith(compareBy({ it.endMs }, { it.id }))

internal fun share(numerator: Long?, denominator: Long): Double? = numerator
    ?.takeIf { denominator > 0 && it in 0..denominator }?.toDouble()?.div(denominator)

internal fun percentRate(uah: Long?, coveredMs: Long?, fullUah: Long?): Double? {
    if (uah == null || uah < 0 || coveredMs == null || coveredMs < 60_000 || fullUah == null || fullUah <= 0) return null
    return uah.toDouble() / fullUah * 100.0 / (coveredMs / AppWindows.HOUR_MS)
}

internal fun deviceValue(session: SessionInput, metric: Metric, fullUah: Long?): Double? = when (metric) {
    Metric.SCREEN_OFF_PCT_PER_H -> percentRate(session.screenOffUah, session.screenOffCoveredMs, fullUah)
    Metric.SCREEN_ON_PCT_PER_H -> percentRate(session.screenOnUah, session.screenOnCoveredMs, fullUah)
    Metric.DEEP_DOZE_SHARE -> share(session.screenOffDozeMs, session.screenOffMs)
    Metric.SCREEN_OFF_DEEP_SLEEP_SHARE -> share(session.screenOffSuspendMs, session.screenOffMs)
    Metric.TEMPERATURE_C -> session.peakTemperatureDeciC?.toDouble()?.div(10.0)
    else -> null
}
