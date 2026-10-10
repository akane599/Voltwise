package com.akane.voltwise.ui.screens.insights

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.akane.voltwise.R
import com.akane.voltwise.battery.apps.AppInfo
import com.akane.voltwise.battery.apps.AppInfoSource
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.MetricUnit
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.ui.components.displayName
import com.akane.voltwise.ui.format.currentLocale
import com.akane.voltwise.ui.format.durationString
import com.akane.voltwise.ui.format.formatNumber
import com.akane.voltwise.ui.format.formatPercent
import com.akane.voltwise.ui.format.formatRate
import com.akane.voltwise.ui.format.mahText
import com.akane.voltwise.ui.format.valueWithUnit
import com.akane.voltwise.viewmodel.AppliedInsightAction
import com.akane.voltwise.viewmodel.InsightActionMessage
import com.akane.voltwise.viewmodel.InsightMessageCode
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

// The engine and the journal speak in enums and fixed codes; every user-visible word for them is mapped here.

@StringRes
internal fun FindingType.titleRes(): Int = when (this) {
    FindingType.APP_DRAIN_ANOMALY -> R.string.insights_type_app_drain_anomaly
    FindingType.NEW_HEAVY_APP -> R.string.insights_type_new_heavy_app
    FindingType.BACKGROUND_RUNAWAY -> R.string.insights_type_background_runaway
    FindingType.STUCK_WAKELOCK -> R.string.insights_type_stuck_wakelock
    FindingType.WAKEUP_STORM -> R.string.insights_type_wakeup_storm
    FindingType.JOB_STORM -> R.string.insights_type_job_storm
    FindingType.DOZE_BLOCKED -> R.string.insights_type_doze_blocked
    FindingType.DOZE_WHITELISTED_DRAINER -> R.string.insights_type_doze_whitelisted_drainer
    FindingType.SCREEN_OFF_DRAIN_HIGH -> R.string.insights_type_screen_off_drain_high
    FindingType.TREND -> R.string.insights_type_trend
    FindingType.CHARGING_AT_FULL -> R.string.insights_type_charging_at_full
    FindingType.HOT_CHARGING -> R.string.insights_type_hot_charging
    FindingType.HEALTH_DECLINE -> R.string.insights_type_health_decline
    FindingType.BACKGROUND_LOCATION -> R.string.insights_type_background_location
    FindingType.BACKGROUND_RADIO -> R.string.insights_type_background_radio
    FindingType.LINGERING_FOREGROUND_SERVICE -> R.string.insights_type_lingering_foreground_service
    FindingType.ACTION_EFFECT -> R.string.insights_type_action_effect
}

@StringRes
internal fun Metric.labelRes(): Int = when (this) {
    Metric.POWER_MAH_PER_H -> R.string.insights_metric_power
    Metric.WAKEUP_ALARMS_PER_H -> R.string.insights_metric_wakeup_alarms
    Metric.PARTIAL_WAKELOCKS_PER_H -> R.string.insights_metric_partial_wakelocks
    Metric.PARTIAL_WAKELOCK_BG_SHARE -> R.string.insights_metric_partial_wakelock_bg_share
    Metric.BG_TIME_SHARE -> R.string.insights_metric_bg_time_share
    Metric.FGS_TO_FOREGROUND_RATIO -> R.string.insights_metric_fgs_to_foreground_ratio
    Metric.CPU_MS_PER_H -> R.string.insights_metric_cpu
    Metric.FOREGROUND_MS_PER_H -> R.string.insights_metric_foreground
    Metric.FGS_MS_PER_H -> R.string.insights_metric_fgs
    Metric.TOP_MS_PER_H -> R.string.insights_metric_top
    Metric.WAKELOCK_MS_PER_H -> R.string.insights_metric_wakelock
    Metric.JOBS_PER_H -> R.string.insights_metric_jobs
    Metric.JOB_MS_PER_H -> R.string.insights_metric_job_time
    Metric.SYNCS_PER_H -> R.string.insights_metric_syncs
    Metric.GPS_MS_PER_H -> R.string.insights_metric_gps
    Metric.SENSOR_MS_PER_H -> R.string.insights_metric_sensor
    Metric.RADIO_ACTIVE_MS_PER_H -> R.string.insights_metric_radio_active
    Metric.MOBILE_BYTES_PER_H -> R.string.insights_metric_mobile_bytes
    Metric.WIFI_BYTES_PER_H -> R.string.insights_metric_wifi_bytes
    Metric.WINDOW_DRAIN_SHARE -> R.string.insights_metric_window_drain_share
    Metric.SCREEN_OFF_PCT_PER_H -> R.string.insights_metric_screen_off_drain
    Metric.DEEP_DOZE_SHARE -> R.string.insights_metric_deep_doze
    Metric.SCREEN_OFF_DEEP_SLEEP_SHARE -> R.string.insights_metric_screen_off_deep_sleep
    Metric.SCREEN_ON_PCT_PER_H -> R.string.insights_metric_screen_on_drain
    Metric.DAILY_USE_PCT -> R.string.insights_metric_daily_use
    Metric.TEMPERATURE_C -> R.string.insights_metric_temperature
    Metric.CAPACITY_MAH -> R.string.insights_metric_capacity
    Metric.CAPACITY_CHANGE_PCT_PER_YEAR -> R.string.insights_metric_capacity_change
    Metric.PLUGGED_AT_FULL_MS -> R.string.insights_metric_plugged_at_full
}

/** The button label: names the outcome ("Restrict background"). */
@StringRes
internal fun ActionType.labelRes(): Int = when (this) {
    ActionType.RESTRICT_BACKGROUND -> R.string.insights_action_restrict_background
    ActionType.STANDBY_BUCKET_RESTRICTED -> R.string.insights_action_standby_restricted
    ActionType.STANDBY_BUCKET_RARE -> R.string.insights_action_standby_rare
    ActionType.FORCE_STOP -> R.string.insights_action_force_stop
    ActionType.REMOVE_DOZE_WHITELIST -> R.string.insights_action_remove_doze_whitelist
    ActionType.OPEN_APP_SETTINGS -> R.string.insights_action_open_app_settings
    ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS -> R.string.insights_action_open_battery_optimization
    ActionType.ENABLE_HIGH_BATTERY_ALERT -> R.string.insights_action_enable_high_battery_alert
}

/** Exactly what the action changes, for the apply confirmation. */
@StringRes
internal fun ActionType.effectRes(): Int = when (this) {
    ActionType.RESTRICT_BACKGROUND -> R.string.insights_effect_restrict_background
    ActionType.STANDBY_BUCKET_RESTRICTED -> R.string.insights_effect_standby_restricted
    ActionType.STANDBY_BUCKET_RARE -> R.string.insights_effect_standby_rare
    ActionType.FORCE_STOP -> R.string.insights_effect_force_stop
    ActionType.REMOVE_DOZE_WHITELIST -> R.string.insights_effect_remove_doze_whitelist
    ActionType.OPEN_APP_SETTINGS -> R.string.insights_effect_open_app_settings
    ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS -> R.string.insights_effect_open_battery_optimization
    ActionType.ENABLE_HIGH_BATTERY_ALERT -> R.string.insights_effect_enable_high_battery_alert
}

/** What else the user may notice afterwards, for the apply confirmation. */
@StringRes
internal fun ActionType.sideEffectsRes(): Int = when (this) {
    ActionType.RESTRICT_BACKGROUND -> R.string.insights_side_restrict_background
    ActionType.STANDBY_BUCKET_RESTRICTED -> R.string.insights_side_standby_restricted
    ActionType.STANDBY_BUCKET_RARE -> R.string.insights_side_standby_rare
    ActionType.FORCE_STOP -> R.string.insights_side_force_stop
    ActionType.REMOVE_DOZE_WHITELIST -> R.string.insights_side_remove_doze_whitelist
    ActionType.OPEN_APP_SETTINGS -> R.string.insights_side_open_app_settings
    ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS -> R.string.insights_side_open_battery_optimization
    ActionType.ENABLE_HIGH_BATTERY_ALERT -> R.string.insights_side_enable_high_battery_alert
}

/** Copy shared by recommendation buttons, consent and applied history. */
internal data class InsightActionPresentation(
    @param:StringRes val labelRes: Int,
    @param:StringRes val effectRes: Int,
    @param:StringRes val sideEffectsRes: Int,
)

internal fun ActionType.presentation(
    sdkInt: Int = Build.VERSION.SDK_INT,
    targetState: String? = null,
): InsightActionPresentation {
    // A known journal target remains authoritative after an OS upgrade. Requests keep their original enum.
    val effective = when {
        this == ActionType.STANDBY_BUCKET_RESTRICTED || this == ActionType.STANDBY_BUCKET_RARE -> when (targetState) {
            "RARE" -> ActionType.STANDBY_BUCKET_RARE
            "RESTRICTED" -> ActionType.STANDBY_BUCKET_RESTRICTED
            else -> if (this == ActionType.STANDBY_BUCKET_RESTRICTED && sdkInt in 28..29) ActionType.STANDBY_BUCKET_RARE else this
        }
        else -> this
    }
    return InsightActionPresentation(effective.labelRes(), effective.effectRes(), effective.sideEffectsRes())
}

internal fun AppliedInsightAction.presentation(sdkInt: Int = Build.VERSION.SDK_INT): InsightActionPresentation? =
    action?.presentation(sdkInt, targetState)

@StringRes
internal fun Severity.labelRes(): Int = when (this) {
    Severity.INFO -> R.string.insights_severity_info
    Severity.LOW -> R.string.insights_severity_low
    Severity.MEDIUM -> R.string.insights_severity_medium
    Severity.HIGH -> R.string.insights_severity_high
}

@StringRes
internal fun Confidence.labelRes(): Int = when (this) {
    Confidence.LOW -> R.string.insights_confidence_low
    Confidence.MEDIUM -> R.string.insights_confidence_medium
    Confidence.HIGH -> R.string.insights_confidence_high
}

@StringRes
internal fun InsightActionStatus.labelRes(): Int = when (this) {
    InsightActionStatus.PREPARED -> R.string.insights_status_prepared
    InsightActionStatus.APPLIED -> R.string.insights_status_applied
    InsightActionStatus.FAILED -> R.string.insights_status_failed
    InsightActionStatus.UNKNOWN -> R.string.insights_status_unknown
    InsightActionStatus.REVERTED -> R.string.insights_status_reverted
    InsightActionStatus.ONE_SHOT -> R.string.insights_status_one_shot
}

/** A history row's status; an undo that found the setting already changed outside Voltwise isn't "Undone". */
@StringRes
internal fun AppliedInsightAction.statusLabelRes(): Int =
    if (changedExternally) R.string.insights_status_changed_externally else status.labelRes()

/** Every apply, undo and analysis result (the ViewModel's fixed codes for each ActionResult). */
@StringRes
internal fun InsightMessageCode.messageRes(): Int = when (this) {
    InsightMessageCode.APPLIED -> R.string.insights_message_applied
    InsightMessageCode.RESTRICTED_TO_RARE -> R.string.insights_message_restricted_to_rare
    InsightMessageCode.UNKNOWN -> R.string.insights_message_unknown
    InsightMessageCode.REVERTED -> R.string.insights_message_reverted
    InsightMessageCode.CHANGED_EXTERNALLY -> R.string.insights_message_changed_externally
    InsightMessageCode.ONE_SHOT -> R.string.insights_message_one_shot
    InsightMessageCode.READ_FAILED -> R.string.insights_message_read_failed
    InsightMessageCode.EXECUTION_FAILED -> R.string.insights_message_execution_failed
    InsightMessageCode.NOT_APPLIED -> R.string.insights_message_not_applied
    InsightMessageCode.STATE_MISMATCH -> R.string.insights_message_state_mismatch
    InsightMessageCode.NOT_UNDOABLE -> R.string.insights_message_not_undoable
    InsightMessageCode.INVALID_JOURNAL -> R.string.insights_message_invalid_journal
    InsightMessageCode.NOT_PRIVILEGED -> R.string.insights_message_not_privileged
    InsightMessageCode.PROTECTED -> R.string.insights_message_protected
    InsightMessageCode.NOT_INSTALLED -> R.string.insights_message_not_installed
    InsightMessageCode.UID_MISMATCH -> R.string.insights_message_uid_mismatch
    InsightMessageCode.SHARED_UID -> R.string.insights_message_shared_uid
    InsightMessageCode.ROLE_HOLDER -> R.string.insights_message_role_holder
    InsightMessageCode.UNSUPPORTED_SDK -> R.string.insights_message_unsupported_sdk
    InsightMessageCode.INVALID_SUBJECT -> R.string.insights_message_invalid_subject
    InsightMessageCode.INVALID_PACKAGE -> R.string.insights_message_invalid_package
    InsightMessageCode.UNRESTORABLE_PRIOR -> R.string.insights_message_unrestorable_prior
    InsightMessageCode.INSPECTION_FAILED -> R.string.insights_message_inspection_failed
    InsightMessageCode.ALREADY_AT_TARGET -> R.string.insights_message_already_at_target
    InsightMessageCode.FINDING_UNAVAILABLE -> R.string.insights_message_finding_unavailable
    InsightMessageCode.RECOMMENDATION_UNAVAILABLE -> R.string.insights_message_recommendation_unavailable
    InsightMessageCode.ANALYSIS_FAILED -> R.string.insights_message_analysis_failed
    InsightMessageCode.FEEDBACK_FAILED -> R.string.insights_message_feedback_failed
}

/** A result's snackbar text: its code's message, except an alert that was turned on but can't post says so. */
@StringRes
internal fun InsightActionMessage.messageRes(): Int =
    if (notificationsBlocked) R.string.insights_message_one_shot_notifications_off else code.messageRes()

/** The snackbar action a result offers: opening the app's notification settings when its alert can't post. */
@StringRes
internal fun InsightActionMessage.actionLabelRes(): Int? =
    if (notificationsBlocked) R.string.settings_notifications_turn_on else null

/** How one [Evidence] compares with its baseline, which decides its wording. */
internal sealed interface EvidenceComparison {
    /** At or above a positive baseline: "3.2× usual". */
    data class Ratio(val times: Double) : EvidenceComparison

    /** Below a positive baseline: "12% (usually 64%)". */
    data object BelowUsual : EvidenceComparison

    /** No usable baseline: the value alone. */
    data object Plain : EvidenceComparison
}

internal fun Evidence.comparison(): EvidenceComparison {
    val usual = baseline
    return when {
        usual == null || usual <= 0.0 || !usual.isFinite() || !observed.isFinite() -> EvidenceComparison.Plain
        observed >= usual -> EvidenceComparison.Ratio(observed / usual)
        else -> EvidenceComparison.BelowUsual
    }
}

/**
 * The relative change an ACTION_EFFECT evidence shows after the fix (observed) against before it (baseline), as a
 * signed fraction (−0.62 = fell 62%); null without a positive baseline. An association, never proof of cause.
 */
internal fun Evidence.relativeChange(): Double? {
    val before = baseline ?: return null
    if (before <= 0.0 || !before.isFinite() || !observed.isFinite()) return null
    return (observed - before) / before
}

/** Under this relative change (5%) an effect reads as "about the same". */
internal const val EFFECT_SAME_THRESHOLD = 0.05

/** The name a finding or fix row shows for its subject; null while an app's label is still loading. */
@Composable
@ReadOnlyComposable
internal fun subjectName(subject: Subject, labels: Map<String, AppLabel>): String? = when (subject) {
    Subject.Device -> stringResource(R.string.insights_subject_device)
    is Subject.App -> labels[subject.packageName]?.displayName()
}

/** [value] of [unit] as text with its unit, in the locale's number format ("4.2 mAh/h", "38%", "12 min/h"). */
@Composable
internal fun metricValueText(value: Double, unit: MetricUnit): String {
    val locale = currentLocale()
    return when (unit) {
        MetricUnit.MAH_PER_H -> valueWithUnit(formatRate(value, locale), stringResource(R.string.insights_unit_mah_per_h))
        MetricUnit.COUNT_PER_H -> stringResource(R.string.insights_unit_per_h, formatRate(value, locale))
        MetricUnit.MS_PER_H -> valueWithUnit(formatRate(value / MINUTE_MS, locale), stringResource(R.string.insights_unit_min_per_h))
        MetricUnit.BYTES_PER_H -> valueWithUnit(formatRate(value / BYTES_PER_MB, locale), stringResource(R.string.insights_unit_mb_per_h))
        MetricUnit.SHARE -> formatPercent(value * 100)
        MetricUnit.RATIO -> stringResource(R.string.insights_ratio, formatNumber(value, 1, locale))
        MetricUnit.PCT_PER_H -> stringResource(R.string.insights_unit_per_h, formatPercent(value, if (abs(value) < 10) 1 else 0))
        MetricUnit.PCT -> formatPercent(value)
        MetricUnit.CELSIUS -> valueWithUnit(formatNumber(value, 0, locale), stringResource(R.string.insights_unit_celsius))
        MetricUnit.MAH -> mahText(value)
        MetricUnit.MS -> durationString(value.toLong())
        MetricUnit.COUNT -> formatNumber(value, 0, locale)
        MetricUnit.PCT_PER_YEAR -> stringResource(R.string.insights_unit_per_year, formatPercent(value, 1))
    }
}

/** "3.2×" for a [EvidenceComparison.Ratio]. */
@Composable
internal fun ratioText(times: Double): String = stringResource(R.string.insights_ratio, formatNumber(times, 1, currentLocale()))

/** The one-line evidence of a finding row: "Drain: 3.2× usual", "Time in deep Doze: 12% (usually 64%)". */
@Composable
internal fun evidenceLine(evidence: Evidence): String {
    val metric = stringResource(evidence.metric.labelRes())
    val observed = metricValueText(evidence.observed, evidence.unit)
    return when (val comparison = evidence.comparison()) {
        is EvidenceComparison.Ratio -> stringResource(R.string.insights_evidence_ratio, metric, ratioText(comparison.times))
        EvidenceComparison.BelowUsual -> stringResource(
            R.string.insights_evidence_usually,
            metric,
            observed,
            metricValueText(evidence.baseline ?: 0.0, evidence.unit),
        )
        EvidenceComparison.Plain -> stringResource(R.string.insights_evidence_plain, metric, observed)
    }
}

/** "Drain fell 62% since the change" (association wording only); null when the evidence has no usable baseline. */
@Composable
internal fun effectLine(evidence: Evidence): String? {
    val change = evidence.relativeChange() ?: return null
    val metric = stringResource(evidence.metric.labelRes())
    val amount = formatPercent(abs(change) * 100)
    return when {
        abs(change) < EFFECT_SAME_THRESHOLD -> stringResource(R.string.insights_effect_same, metric)
        change < 0 -> stringResource(R.string.insights_effect_fell, metric, amount)
        else -> stringResource(R.string.insights_effect_rose, metric, amount)
    }
}

/**
 * The app's label and install state, or null when the lookup failed. Cancellation propagates: a produceState producer
 * cancelled by a key change must not resume and overwrite the newer key's labels with "Unknown app".
 */
internal suspend fun AppInfoSource.infoOrNull(packageName: String): AppInfo? = try {
    info(packageName)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

private const val MINUTE_MS = 60_000.0
private const val BYTES_PER_MB = 1_000_000.0
