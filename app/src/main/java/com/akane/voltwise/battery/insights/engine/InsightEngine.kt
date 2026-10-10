package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.device.ChargingHealth
import com.akane.voltwise.battery.insights.engine.detectors.device.DeviceDetectors
import com.akane.voltwise.battery.insights.engine.recommend.Recommender
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.InsightReport
import com.akane.voltwise.battery.insights.model.SeriesPoint
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject

object InsightEngine {
    fun analyze(inputs: InsightInputs, sdkInt: Int): InsightReport {
        val findings = (appFindings(inputs) + DeviceDetectors.detect(inputs) + Trends.detect(inputs) +
            ChargingHealth.detect(inputs) + ActionEffects.detect(inputs))
            .map { Recommender.recommend(it, inputs, sdkInt) }
            .sortedWith(findingSelectionOrder).take(12).sortedWith(findingOrder)
        return InsightReport(inputs.nowMs, findings, findings.firstOrNull { it.severity != Severity.INFO })
    }
}

internal val findingOrder = compareByDescending<Finding> { it.severity.ordinal }
    .thenByDescending { it.confidence.ordinal }.thenByDescending { it.score }.thenBy { it.key }

// Keep evidence for applied actions ahead of other INFO findings when selecting the bounded report.
private val findingSelectionOrder = compareByDescending<Finding> { it.severity.ordinal }
    .thenByDescending { it.severity == Severity.INFO && it.type == FindingType.ACTION_EFFECT }
    .then(findingOrder)

internal fun finding(
    type: FindingType,
    evidence: List<Evidence>,
    subject: Subject = Subject.Device,
    severity: Severity = Severity.MEDIUM,
    confidence: Confidence = Confidence.MEDIUM,
    direction: Direction? = Direction.UP,
    score: Double = 50.0,
    series: List<SeriesPoint> = emptyList(),
    suffix: String? = null,
): Finding {
    val target = (subject as? Subject.App)?.packageName ?: "device"
    val key = "${type.name}:$target" + (suffix?.let { ":$it" } ?: "")
    return Finding(key, type, severity, confidence, score, subject, direction, evidence, series, emptyList())
}
