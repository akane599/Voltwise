package com.akane.voltwise.battery.insights.engine.detectors.device

import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.Attribution
import com.akane.voltwise.battery.insights.model.AttributionKind
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.MetricUnit
import com.akane.voltwise.battery.insights.model.WakerKind

internal fun attributions(inputs: InsightInputs, sessionId: String): List<Attribution> {
    val (kernels, reasons) = inputs.deviceWakers.filter { it.sessionId == sessionId }.mapNotNull { waker ->
        val kernel = waker.kind == WakerKind.KERNEL_WAKELOCK
        val value = if (kernel) waker.totalMs else waker.count
        if (value <= 0) return@mapNotNull null
        Attribution(
            if (kernel) AttributionKind.KERNEL_WAKELOCK else AttributionKind.WAKEUP_REASON,
            waker.name, null, value.toDouble(), if (kernel) MetricUnit.MS else MetricUnit.COUNT, 1,
        )
    }.partition { it.kind == AttributionKind.KERNEL_WAKELOCK }
    val byRank = compareByDescending<Attribution> { it.value }.thenBy { it.name }.thenBy { it.kind.ordinal }
    val rankedKernels = kernels.sortedWith(byRank)
    val rankedReasons = reasons.sortedWith(byRank)
    val selectedKernels = rankedKernels.take(3)
    val selectedReasons = rankedReasons.take(2)
    val remainingSlots = 5 - selectedKernels.size - selectedReasons.size
    val device = (selectedKernels + selectedReasons +
        if (selectedKernels.size < 3) rankedReasons.drop(selectedReasons.size).take(remainingSlots)
        else rankedKernels.drop(selectedKernels.size).take(remainingSlots))
        .sortedWith(byRank)
    val window = AppWindows.select(inputs).firstOrNull { it.session.id == sessionId } ?: return device
    val apps = window.rows.filterNot { it.isOthers }.mapNotNull { row ->
        val alarms = AppWindows.value(window, row, Metric.WAKEUP_ALARMS_PER_H)
        val background = row.partialWakelockBgMs?.takeIf { it >= 0 }?.toDouble()?.div(window.hours)
        if (alarms == null && background == null) return@mapNotNull null
        Triple(row, alarms, background)
    }.sortedWith(compareByDescending<Triple<com.akane.voltwise.battery.insights.model.AppSessionInput, Double?, Double?>> { it.second }
        .thenByDescending { it.third }.thenBy { it.first.packageName }.thenBy { it.first.uid })
        .filter { (it.second ?: 0.0) > 0.0 || (it.third ?: 0.0) > 0.0 }.take(5).map { (row, alarms, background) ->
            val useAlarms = alarms != null && alarms > 0
            Attribution(
                AttributionKind.APP, row.packageName, row.packageName,
                if (useAlarms) requireNotNull(alarms) else requireNotNull(background),
                if (useAlarms) MetricUnit.COUNT_PER_H else MetricUnit.MS_PER_H, 1,
            )
        }
    return device + apps
}
