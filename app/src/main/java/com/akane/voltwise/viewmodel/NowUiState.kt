package com.akane.voltwise.viewmodel

import androidx.compose.runtime.Immutable
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.data.sampling.ChargerType
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.measurement.CapacityConfidence
import com.akane.voltwise.battery.measurement.CurrentCalibration
import com.akane.voltwise.battery.measurement.EtaBasis
import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.ui.components.chart.TimePoint
import com.akane.voltwise.ui.components.chart.TimeWindow

/**
 * Everything the Now screen renders, as plain values: numbers stay numbers (formatting and units happen in the UI,
 * with the viewer's locale), stored enum names are already parsed, and a `null` part means "nothing to show yet".
 */
@Immutable
data class NowUiState(
    /** The latest reading's time (the clock before one): "today" for dates in titles and captions. */
    val nowMs: Long = 0,
    val hero: HeroState = HeroState(),
    val readouts: Readouts = Readouts(),
    val trace: TraceState = TraceState(),
    /** The newest on-battery window; null when no DISCHARGE session was ever recorded. */
    val sinceUnplug: SinceUnplugState? = null,
    /** Null when today's summary row doesn't exist yet. */
    val today: TodayState? = null,
    /** Null until some session produced a capacity estimate. */
    val health: HealthState? = null,
    val topApps: TopAppsState = TopAppsState.Empty,
    /**
     * Null until analysis has completed and a report is loaded. Like Insights: findings first, then changes, then
     * [InsightsSummary.learning], and only then [InsightsSummary.allGood].
     */
    val insightsSummary: InsightsSummary? = null,
    /** The applied correction while its notice is pending (Undo / Keep), else null. */
    val calibrationNotice: CurrentCalibration? = null,
    val useFahrenheit: Boolean = false,
)

@Immutable
data class InsightsSummary(
    val headline: InsightHeadline?,
    val activeFindingCount: Int,
    /** Informational trends with a direction: Insights lists them under Changes, so Now can't call them "all good". */
    val changeCount: Int = 0,
    /** No findings or changes and too few sessions with app data to judge apps yet: "still learning", not "all good". */
    val learning: Boolean = false,
) {
    val allGood: Boolean get() = activeFindingCount == 0 && changeCount == 0 && !learning
}

@Immutable
data class InsightHeadline(
    val key: String,
    val type: FindingType,
    val severity: Severity,
    /** Null for a device finding. */
    val packageName: String?,
)

@Immutable
data class HeroState(
    /** False until Android delivered a first reading. */
    val hasReading: Boolean = false,
    val level: Int? = null,
    val power: PowerState = PowerState.UNKNOWN,
    val charger: ChargerType? = null,
    /** Time left (discharging; only while monitoring) or to full (charging; Android's, also without monitoring). */
    val eta: Eta? = null,
    val monitoring: Boolean = false,
    /** Android refused the foreground-service start; cleared by the next attempt or once monitoring runs. */
    val startBlocked: Boolean = false,
    /** Why there is no [eta] yet, in words for this power state; null when nothing should be said. */
    val etaPending: EtaPending? = null,
)

/**
 * The line in place of a missing estimate: time left only while discharging, time to full only while charging, and
 * nothing when plugged but not charging (e.g. full at 100 %) or when Android doesn't say.
 */
enum class EtaPending {
    /** Discharging, monitoring: the writer's estimate is on its way. */
    ESTIMATING_LEFT,

    /** Charging, monitoring, and Android gave no time to full yet. */
    ESTIMATING_FULL,

    /** Discharging without monitoring: time left needs it. */
    NEEDS_MONITORING_LEFT,

    /** Charging without monitoring and without Android's own estimate. */
    NEEDS_MONITORING_FULL,
}

/** [basis] is null when the stored name is unknown (e.g. written by a newer build). */
@Immutable
data class Eta(val remainingMs: Long, val basis: EtaBasis?)

/** Live values, calibrated; current and power are signed: positive = into the battery. */
@Immutable
data class Readouts(
    val currentMa: Double? = null,
    val powerW: Double? = null,
    val temperatureC: Double? = null,
    val voltageV: Double? = null,
)

enum class TraceRange(val spanMs: Long) {
    LIVE(10 * 60_000L),
    HOUR(60 * 60_000L),
    SIX_HOURS(6 * 60 * 60_000L),
    DAY(24 * 60 * 60_000L),
}

/**
 * The current trace in mA (calibrated with today's calibration; stored rows keep raw values). [points] carry gap
 * markers (`value = null`) where monitoring restarted or collection was interrupted; [maxGapMs] also breaks the
 * line across missing readings. Chart types, because the ViewModel downsamples them with ChartMath.
 */
@Immutable
data class TraceState(
    val range: TraceRange = TraceRange.LIVE,
    val points: List<TimePoint> = emptyList(),
    val window: TimeWindow? = null,
    val maxGapMs: Long? = null,
)

/**
 * The on-battery window "Since unplug" shows: the open DISCHARGE session ([current]: since the last unplug or Reset,
 * [endedAtMs] = its latest save), or, while plugged in or not monitoring, the newest closed one ("Last on battery").
 * Every figure comes from that one session row.
 */
@Immutable
data class SinceUnplugState(
    val current: Boolean,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val screenOn: DrainState,
    val screenOff: DrainState,
    /** CPU deep sleep as a percent of the session's observed time; null before any interval was observed. */
    val deepSleepPercent: Double?,
)

/** One screen state's discharge: its duration, average drain in mA (positive) and in % of capacity per hour. */
@Immutable
data class DrainState(
    val durationMs: Long = 0,
    val currentMa: Double? = null,
    val percentPerHour: Double? = null,
)

/**
 * Today's discharge and charge. A null mAh is unavailable (time on battery with no counter-measured charge), not 0;
 * the percents are the same charge as a share of the full capacity, null when the capacity is unknown.
 */
@Immutable
data class TodayState(
    val usedMah: Double?,
    val chargedMah: Double?,
    val screenOnMs: Long,
    val usedPercent: Double? = null,
    val chargedPercent: Double? = null,
)

@Immutable
data class HealthState(
    val capacityMah: Int,
    val confidence: CapacityConfidence,
    /** Capacity vs the design capacity (Settings, else the battery's sysfs value); null when neither is known. */
    val healthPercent: Double?,
)

@Immutable
sealed interface TopAppsState {
    /** No cached per-app data (Now never runs a privileged dump), or nothing used power. */
    data object Empty : TopAppsState

    @Immutable
    data class Ready(
        val rows: List<TopApp>,
        val basis: AppUsageBasis,
        val capturedAtMs: Long,
    ) : TopAppsState
}

/** [label] is never a raw id ([AppLabel]); [share] is this app's part of all apps' power over the same window. */
@Immutable
data class TopApp(
    val uid: Int,
    val packageName: String,
    val label: AppLabel,
    val powerMah: Double,
    val share: Float,
)

/** What the Now screen asks for; the ViewModel handles state changes, NowScreen handles navigation. */
sealed interface NowEvent {
    data object ToggleMonitoring : NowEvent
    data class SelectRange(val range: TraceRange) : NowEvent
    data object ResetObservation : NowEvent
    data object UndoCalibration : NowEvent
    data object KeepCalibration : NowEvent
    data object OpenHistory : NowEvent
    data object OpenHealth : NowEvent
    data object OpenApps : NowEvent
    data class OpenApp(val uid: Int, val packageName: String) : NowEvent
}
