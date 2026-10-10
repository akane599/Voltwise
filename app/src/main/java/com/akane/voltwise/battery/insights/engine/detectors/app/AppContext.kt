package com.akane.voltwise.battery.insights.engine.detectors.app

import com.akane.voltwise.battery.insights.engine.eligibility.AppMetricPoint
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.engine.eligibility.EligibleAppWindow
import com.akane.voltwise.battery.insights.engine.stats.RobustBaseline
import com.akane.voltwise.battery.insights.engine.stats.TimedValue
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SeriesPoint
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject

/** Shared context for the app family. Only the latest eligible window is evaluated. */
data class AppContext(val inputs: InsightInputs, val windows: List<EligibleAppWindow>, val subject: Subject.App) {
    val current: EligibleAppWindow get() = windows.last()
    fun key(type: FindingType): String = "${type.name}:${subject.packageName}"
    fun multiplier(type: FindingType): Double =
        inputs.feedback[key(type)]?.takeIf { it.isFinite() && it >= 1.0 } ?: 1.0

    fun value(metric: Metric, window: EligibleAppWindow = current): Double? =
        window.row(subject)?.let { AppWindows.value(window, it, metric) }

    fun backgroundDominant(window: EligibleAppWindow = current, ratio: Double = BACKGROUND_RATIO): Boolean {
        val row = window.row(subject) ?: return false
        val foreground = AppWindows.foregroundMs(row) ?: return false
        val background = row.bgMs?.takeIf { it >= 0 } ?: return false
        return background.toDouble() >= ratio * maxOf(foreground, AppWindows.FOREGROUND_FLOOR_MS * window.hours)
    }

    /** Excludes the entire evaluated suffix, so persistence cannot train its own baseline. */
    fun history(evaluated: Int = 1): List<EligibleAppWindow> {
        val first = windows.getOrNull(windows.size - evaluated) ?: return emptyList()
        val start = first.session.appWindow?.captureStartMs ?: return emptyList()
        return windows.dropLast(evaluated).filter { it.atMs <= start }
    }

    fun anomaly(type: FindingType, metric: Metric, floor: Double, evaluated: Int = 1): AppAnomaly? {
        val points = AppWindows.series(history(evaluated), subject, metric)
        val measured = points.count { it.present }
        if (measured < MIN_BASELINE_SESSIONS) return null
        // Use censored upper bounds conservatively, never silently turn a truncated row into zero.
        val baseline = RobustBaseline.of(
            points.mapNotNull { point -> (point.value ?: point.upperBound)?.let { TimedValue(point.atMs, it) } },
            current.atMs,
            HALF_LIFE_MS,
        ) ?: return null
        // A small MAD must not turn ordinary proportional variation into a large z score.
        // At z = 3 this requires at least a doubling, while retaining the metric's absolute floor.
        val changeFloor = maxOf(floor, baseline.median)
        val multiplier = multiplier(type)
        val recent = windows.takeLast(evaluated)
        if (recent.size != evaluated) return null
        for (window in recent) {
            val value = value(metric, window) ?: return null
            val z = baseline.robustZ(value, changeFloor) ?: return null
            if (value < floor * multiplier || value - baseline.median < floor * multiplier ||
                z < RobustBaseline.Z_THRESHOLD * multiplier
            ) return null
        }
        val observed = value(metric) ?: return null
        val z = baseline.robustZ(observed, changeFloor) ?: return null
        return AppAnomaly(metric, observed, baseline, measured, points, z, changeFloor)
    }

    fun finding(type: FindingType, anomaly: AppAnomaly, extra: List<Evidence> = emptyList()): Finding {
        val censoredShare = anomaly.points.count { it.censored }.toDouble() / anomaly.points.size
        val confidence = when {
            anomaly.measured >= HIGH_CONFIDENCE_SESSIONS && censoredShare <= HIGH_CONFIDENCE_CENSORING -> Confidence.HIGH
            censoredShare <= MEDIUM_CONFIDENCE_CENSORING -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val band = maxOf(RobustBaseline.Z_THRESHOLD * RobustBaseline.MAD_SCALE * anomaly.baseline.mad, anomaly.floor)
        val series = AppWindows.series(windows, subject, anomaly.metric).mapNotNull { point ->
            // The public chart point has no censoring flag. Omit upper bounds rather than show them as measurements.
            point.value?.let { value ->
                SeriesPoint(point.atMs, value, (anomaly.baseline.median - band).coerceAtLeast(0.0), anomaly.baseline.median + band)
            }
        }
        return Finding(
            key(type), type, if (anomaly.z >= HIGH_SEVERITY_Z) Severity.HIGH else Severity.MEDIUM,
            confidence, (anomaly.z * SCORE_PER_Z).coerceAtMost(MAX_SCORE), subject, Direction.UP,
            listOf(Evidence(anomaly.metric, anomaly.observed, anomaly.baseline.median, anomaly.metric.unit, anomaly.measured)) + extra,
            series, emptyList(),
        )
    }

    companion object {
        const val MIN_BASELINE_SESSIONS = 4

        /**
         * Eligible windows before any app finding is possible: [MIN_BASELINE_SESSIONS] history windows (the baseline,
         * see [history]) plus the current one being evaluated. Below this, Now and Insights say "still learning".
         */
        const val MIN_ELIGIBLE_WINDOWS = MIN_BASELINE_SESSIONS + 1
        const val HALF_LIFE_MS = 14.0 * 24.0 * AppWindows.HOUR_MS
        const val BACKGROUND_RATIO = 3.0
        const val HIGH_CONFIDENCE_SESSIONS = 12
        const val HIGH_CONFIDENCE_CENSORING = 0.10
        const val MEDIUM_CONFIDENCE_CENSORING = 0.25
        const val HIGH_SEVERITY_Z = 6.0
        const val SCORE_PER_Z = 10.0
        const val MAX_SCORE = 100.0
    }
}

data class AppAnomaly(
    val metric: Metric,
    val observed: Double,
    val baseline: RobustBaseline,
    val measured: Int,
    val points: List<AppMetricPoint>,
    val z: Double,
    val floor: Double,
)
