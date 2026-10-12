package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.device.deviceValue
import com.akane.voltwise.battery.insights.model.SessionKind
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.engine.stats.EffectSize
import com.akane.voltwise.battery.insights.model.ActionStatus
import com.akane.voltwise.battery.insights.model.AppliedActionInput
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SeriesPoint
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject

object ActionEffects {
    fun detect(inputs: InsightInputs): List<Finding> {
        val windows = AppWindows.select(inputs)
        val sessions = inputs.sessions.filter {
            !it.imported && it.endMs > it.startMs && it.endMs <= inputs.nowMs && it.observedMs > 0
        }.sortedWith(compareBy({ it.endMs }, { it.id }))
        val effects = inputs.actions.sortedWith(
            compareByDescending<AppliedActionInput> { it.status == ActionStatus.APPLIED }
                .thenByDescending { it.appliedAtMs }
                .thenByDescending { it.id },
        ).mapNotNull { action ->
            val appliedAt = action.appliedAtMs ?: return@mapNotNull null
            if (action.status !in setOf(ActionStatus.APPLIED, ActionStatus.REVERTED) || appliedAt > inputs.nowMs) {
                return@mapNotNull null
            }
            val subject = if (action.packageName == null) Subject.Device else {
                Subject.App(action.uid ?: return@mapNotNull null, action.packageName)
            }
            val metric = metric(action)
            val points = if (subject is Subject.App) {
                windows.mapNotNull { window ->
                    val start = window.session.appWindow?.captureStartMs ?: return@mapNotNull null
                    val point = AppWindows.point(window, subject, metric) ?: return@mapNotNull null
                    // True uncensored absence can be zero; unsupported/censored fields cannot.
                    point.value?.let { Observation(start, window.atMs, it) }
                }
            } else {
                val kind = when (metric) {
                    Metric.TEMPERATURE_C -> SessionKind.CHARGE
                    Metric.PLUGGED_AT_FULL_MS -> SessionKind.PLUGGED
                    else -> SessionKind.DISCHARGE
                }
                sessions.filter { it.kind == kind }.mapNotNull { session ->
                    val value = if (metric == Metric.PLUGGED_AT_FULL_MS) {
                        session.observedMs.toDouble().takeIf { session.startLevel == 100 && session.endLevel == 100 }
                    } else {
                        deviceValue(session, metric, inputs.fullUah)
                    }
                    value?.let { Observation(session.startMs, session.endMs, it) }
                }
            }
            val before = points.filter { it.end < appliedAt }
            val after = points.filter { it.start > appliedAt }
            if (before.size < 2 || after.size < 2) return@mapNotNull null
            val effect = EffectSize.between(before.map { it.value }, after.map { it.value }) ?: return@mapNotNull null
            if (effect.absolute == 0.0) return@mapNotNull null
            finding(
                FindingType.ACTION_EFFECT,
                listOf(Evidence(metric, effect.after, effect.before, metric.unit, before.size + after.size)),
                subject = subject, severity = Severity.INFO,
                direction = if (effect.absolute < 0) Direction.DOWN else Direction.UP,
                series = (before + after).map { SeriesPoint(it.end, it.value, null, null) },
                suffix = "${metric.name}:${action.id}",
            )
        }
        // Encode action priority for both cap selection and display, without raising the default score.
        return effects.mapIndexed { index, effect ->
            effect.copy(score = 50.0 * (effects.size - index) / effects.size)
        }
    }

    private fun metric(action: AppliedActionInput): Metric {
        // Match the measurement path, not the availability of a particular observation.
        action.metric?.takeIf { metric ->
            when (metric) {
                // AppWindows.value supports these per-window app metrics.
                Metric.POWER_MAH_PER_H,
                Metric.CPU_MS_PER_H,
                Metric.FOREGROUND_MS_PER_H,
                Metric.TOP_MS_PER_H,
                Metric.FGS_MS_PER_H,
                Metric.BG_TIME_SHARE,
                Metric.PARTIAL_WAKELOCK_BG_SHARE,
                Metric.WAKELOCK_MS_PER_H,
                Metric.WAKEUP_ALARMS_PER_H,
                Metric.PARTIAL_WAKELOCKS_PER_H,
                Metric.JOBS_PER_H,
                Metric.JOB_MS_PER_H,
                Metric.SYNCS_PER_H,
                Metric.GPS_MS_PER_H,
                Metric.SENSOR_MS_PER_H,
                Metric.RADIO_ACTIVE_MS_PER_H,
                Metric.MOBILE_BYTES_PER_H,
                Metric.WIFI_BYTES_PER_H,
                Metric.FGS_TO_FOREGROUND_RATIO -> action.packageName != null
                // deviceValue plus the plugged-at-full observation above.
                Metric.SCREEN_OFF_PCT_PER_H,
                Metric.SCREEN_ON_PCT_PER_H,
                Metric.DEEP_DOZE_SHARE,
                Metric.SCREEN_OFF_DEEP_SLEEP_SHARE,
                Metric.TEMPERATURE_C,
                Metric.PLUGGED_AT_FULL_MS -> action.packageName == null
                else -> false
            }
        }?.let { return it }
        val explicit = action.findingKey.split(':').getOrNull(2)
        Metric.entries.firstOrNull { it.name == explicit }?.let { return it }
        return when (action.findingKey.substringBefore(':')) {
            FindingType.WAKEUP_STORM.name -> Metric.WAKEUP_ALARMS_PER_H
            FindingType.JOB_STORM.name -> Metric.JOBS_PER_H
            FindingType.STUCK_WAKELOCK.name -> Metric.PARTIAL_WAKELOCK_BG_SHARE
            FindingType.BACKGROUND_RUNAWAY.name -> Metric.BG_TIME_SHARE
            FindingType.LINGERING_FOREGROUND_SERVICE.name -> Metric.FGS_MS_PER_H
            FindingType.BACKGROUND_LOCATION.name -> Metric.GPS_MS_PER_H
            FindingType.BACKGROUND_RADIO.name -> Metric.RADIO_ACTIVE_MS_PER_H
            FindingType.CHARGING_AT_FULL.name -> Metric.PLUGGED_AT_FULL_MS
            FindingType.HOT_CHARGING.name -> Metric.TEMPERATURE_C
            else -> if (action.packageName == null) Metric.SCREEN_OFF_PCT_PER_H else Metric.POWER_MAH_PER_H
        }
    }

    private data class Observation(val start: Long, val end: Long, val value: Double)
}
