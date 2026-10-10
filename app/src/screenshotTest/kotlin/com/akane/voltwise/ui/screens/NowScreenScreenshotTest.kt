package com.akane.voltwise.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.ui.ComponentPreviews
import com.akane.voltwise.ui.screens.now.InsightsPanel
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.InsightHeadline
import com.akane.voltwise.viewmodel.InsightsSummary
import com.akane.voltwise.battery.data.sampling.ChargerType
import com.akane.voltwise.battery.measurement.CapacityConfidence
import com.akane.voltwise.battery.measurement.CurrentCalibration
import com.akane.voltwise.battery.measurement.CurrentSign
import com.akane.voltwise.battery.measurement.CurrentUnit
import com.akane.voltwise.battery.measurement.EtaBasis
import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.ui.FIXED_TIME_MS
import com.akane.voltwise.ui.PhonePreview
import com.akane.voltwise.ui.ScreenPreviews
import com.akane.voltwise.ui.ScreenshotTheme
import com.akane.voltwise.ui.TallPhonePreview
import com.akane.voltwise.ui.components.chart.TimePoint
import com.akane.voltwise.ui.components.chart.TimeWindow
import com.akane.voltwise.ui.screens.now.NowContent
import com.akane.voltwise.viewmodel.DrainState
import com.akane.voltwise.viewmodel.Eta
import com.akane.voltwise.viewmodel.HealthState
import com.akane.voltwise.viewmodel.EtaPending
import com.akane.voltwise.viewmodel.HeroState
import com.akane.voltwise.viewmodel.NowUiState
import com.akane.voltwise.viewmodel.Readouts
import com.akane.voltwise.viewmodel.SinceUnplugState
import com.akane.voltwise.viewmodel.TodayState
import com.akane.voltwise.viewmodel.TopApp
import com.akane.voltwise.viewmodel.TopAppsState
import com.akane.voltwise.viewmodel.TraceRange
import com.akane.voltwise.viewmodel.TraceState
import com.android.tools.screenshot.PreviewTest
import kotlin.math.cos
import kotlin.math.sin

// Times render in the suite's pinned UTC/en-US; every trace ends at FIXED_TIME_MS (09:20).
private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE

/** The live window at 2 s: screen-on drain with bursts (a scroll, a video start). */
private val liveDrain = List(301) { i ->
    val t = FIXED_TIME_MS - TraceRange.LIVE.spanMs + i * 2 * SECOND
    val burst = if (i in 120..150) -520.0 * sin((i - 120) / 30.0 * Math.PI) else 0.0
    TimePoint(t, -372.0 - 64 * (0.5 + 0.5 * sin(i / 7.0)) - 22 * cos(i / 2.3) + burst)
}

/** The last hour at 30 s: on battery with the screen off, then plugged in at 08:55 and charging with a taper. */
private val hourPlugIn = List(121) { i ->
    val t = FIXED_TIME_MS - HOUR + i * 30 * SECOND
    val value = when {
        i < 70 -> -96.0 - 30 * (0.5 + 0.5 * sin(i / 3.0))
        i < 72 -> 380.0
        else -> 1_620.0 - (i - 72) * 3.4 + 45 * sin(i / 4.0)
    }
    TimePoint(t, value)
}

private val topApps = TopAppsState.Ready(
    rows = listOf(
        TopApp(10_123, "com.android.chrome", AppLabel.Named("Chrome"), 124.0, 0.31f),
        TopApp(10_201, "com.google.android.youtube", AppLabel.Named("YouTube"), 58.2, 0.15f),
        TopApp(10_311, "com.google.android.apps.maps", AppLabel.Named("Maps"), 7.4, 0.02f),
    ),
    basis = AppUsageBasis.DELTA,
    capturedAtMs = FIXED_TIME_MS - 11 * MINUTE,
)

private val sinceUnplug = SinceUnplugState(
    current = true,
    startedAtMs = FIXED_TIME_MS - 3 * HOUR - 10 * MINUTE,
    endedAtMs = FIXED_TIME_MS - 20 * SECOND,
    screenOn = DrainState(durationMs = 62 * MINUTE, currentMa = 412.0, percentPerHour = 9.1),
    screenOff = DrainState(durationMs = 128 * MINUTE, currentMa = 58.0, percentPerHour = 1.3),
    deepSleepPercent = 86.0,
)

private fun discharging() = NowUiState(
    nowMs = FIXED_TIME_MS,
    hero = HeroState(
        hasReading = true,
        level = 67,
        power = PowerState.DISCHARGING,
        eta = Eta(5 * HOUR + 40 * MINUTE, EtaBasis.LIVE_RATE),
        monitoring = true,
    ),
    readouts = Readouts(currentMa = -412.0, powerW = -1.59, temperatureC = 31.5, voltageV = 3.87),
    trace = TraceState(
        range = TraceRange.LIVE,
        points = liveDrain,
        window = TimeWindow(FIXED_TIME_MS - TraceRange.LIVE.spanMs, FIXED_TIME_MS),
        maxGapMs = 95_000L,
    ),
    sinceUnplug = sinceUnplug,
    // Over the same 4,210 mAh as Since unplug's %/h.
    today = TodayState(usedMah = 1_240.0, chargedMah = 800.0, screenOnMs = 2 * HOUR + 10 * MINUTE, usedPercent = 29.5, chargedPercent = 19.0),
    health = HealthState(capacityMah = 4_210, confidence = CapacityConfidence.MEDIUM, healthPercent = 94.0),
    topApps = topApps,
    insightsSummary = chromeHeadline,
)

/** Chrome draining more than usual, the first of three active findings. */
private val chromeHeadline = InsightsSummary(
    headline = InsightHeadline("APP_DRAIN_ANOMALY:com.android.chrome", FindingType.APP_DRAIN_ANOMALY, Severity.HIGH, "com.android.chrome"),
    activeFindingCount = 3,
)

/** A device finding: no app to name. */
private val dozeHeadline = InsightsSummary(
    headline = InsightHeadline("DOZE_BLOCKED:device", FindingType.DOZE_BLOCKED, Severity.MEDIUM, null),
    activeFindingCount = 1,
)

private val allGood = InsightsSummary(headline = null, activeFindingCount = 0)

/** Analysed, nothing found, but too few sessions with app data to judge apps yet. */
private val learning = InsightsSummary(headline = null, activeFindingCount = 0, learning = true)

private val changes = InsightsSummary(headline = null, activeFindingCount = 0, changeCount = 1)

private fun charging() = discharging().copy(
    hero = HeroState(
        hasReading = true,
        level = 54,
        power = PowerState.CHARGING,
        charger = ChargerType.AC,
        eta = Eta(HOUR + 12 * MINUTE, EtaBasis.ANDROID),
        monitoring = true,
    ),
    readouts = Readouts(currentMa = 1_452.0, powerW = 6.21, temperatureC = 34.2, voltageV = 4.28),
    trace = TraceState(
        range = TraceRange.HOUR,
        points = hourPlugIn,
        window = TimeWindow(FIXED_TIME_MS - HOUR, FIXED_TIME_MS),
    ),
    // Plugged in 25 min ago: the last window on battery, which began yesterday.
    sinceUnplug = sinceUnplug.copy(current = false, startedAtMs = FIXED_TIME_MS - 20 * HOUR, endedAtMs = FIXED_TIME_MS - 25 * MINUTE),
    // Today's time on battery had no counter data: unavailable, not 0.
    today = TodayState(usedMah = null, chargedMah = 950.0, screenOnMs = 40 * MINUTE, chargedPercent = 22.6),
    // A two-day-old cache (its time carries the date) with a system-process row.
    topApps = topApps.copy(
        rows = topApps.rows.take(2) + TopApp(1_000, "System UID 1000", AppLabel.SystemProcess, 21.0, 0.05f),
        basis = AppUsageBasis.ABSOLUTE,
        capturedAtMs = FIXED_TIME_MS - 2 * 24 * HOUR,
    ),
    insightsSummary = allGood,
)

/**
 * Monitoring stopped: no time left, the last on-battery window as it ended, the live trace from demand polls only;
 * and no capacity known, so drain and Today fall back to mA and mAh.
 */
private fun monitoringOff() = discharging().copy(
    hero = HeroState(
        hasReading = true,
        level = 67,
        power = PowerState.DISCHARGING,
        monitoring = false,
        etaPending = EtaPending.NEEDS_MONITORING_LEFT,
    ),
    trace = discharging().trace.copy(points = liveDrain.takeLast(90)),
    // No full capacity yet (no usable counter reading, no estimate): mA and mAh stand in.
    sinceUnplug = sinceUnplug.copy(
        current = false,
        endedAtMs = FIXED_TIME_MS - 40 * MINUTE,
        screenOn = sinceUnplug.screenOn.copy(percentPerHour = null),
        screenOff = sinceUnplug.screenOff.copy(percentPerHour = null),
    ),
    today = TodayState(usedMah = 1_240.0, chargedMah = 800.0, screenOnMs = 2 * HOUR + 10 * MINUTE),
    health = null,
    // Sessions recorded, never analysed: the Insights card invites a first run.
    insightsSummary = null,
)

/** First launch: a reading, nothing recorded yet, no per-app data (and so no Insights card). */
private fun empty() = NowUiState(
    nowMs = FIXED_TIME_MS,
    hero = HeroState(
        hasReading = true,
        level = 80,
        power = PowerState.DISCHARGING,
        monitoring = false,
        etaPending = EtaPending.NEEDS_MONITORING_LEFT,
    ),
    readouts = Readouts(currentMa = -388.0, powerW = -1.54, temperatureC = 29.8, voltageV = 3.97),
    trace = TraceState(window = TimeWindow(FIXED_TIME_MS - TraceRange.LIVE.spanMs, FIXED_TIME_MS), maxGapMs = 95_000L),
)

@Composable
private fun NowPreviewContent(state: NowUiState) {
    // The headline's app label is the screen's lookup; previews name it directly.
    val insightsApp = state.insightsSummary?.headline?.packageName?.let { packageName ->
        topApps.rows.firstOrNull { it.packageName == packageName }?.label
    }
    NowContent(state = state, onEvent = {}, insightsApp = insightsApp)
}

@PreviewTest
@ScreenPreviews
@Composable
fun NowScreenPreview() {
    ScreenshotTheme { NowPreviewContent(discharging()) }
}

/** Charging on AC: time to full from Android, the 1 h trace across the plug-in (amber, then chartreuse). */
@PreviewTest
@TallPhonePreview
@Composable
fun NowScreenChargingPreview() {
    ScreenshotTheme { NowPreviewContent(charging()) }
}

@PreviewTest
@TallPhonePreview
@Composable
fun NowScreenMonitoringOffPreview() {
    ScreenshotTheme { NowPreviewContent(monitoringOff()) }
}

@PreviewTest
@TallPhonePreview
@Composable
fun NowScreenEmptyPreview() {
    ScreenshotTheme { NowPreviewContent(empty()) }
}

/** A detected correction was applied (mA reporting): the notice with Undo / Keep above the hero. */
@PreviewTest
@PhonePreview
@Composable
fun NowScreenCalibrationPreview() {
    ScreenshotTheme {
        NowPreviewContent(discharging().copy(calibrationNotice = CurrentCalibration(CurrentUnit.MILLIAMPS, CurrentSign.NORMAL)))
    }
}

/** The Insights card's states: an app headline, a device headline, all good, and not analysed yet. */
@Composable
private fun InsightsCardStates() {
    ScreenshotTheme {
        Column(
            Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            val card = Modifier.fillMaxWidth()
            InsightsPanel(chromeHeadline, AppLabel.Named("Chrome"), onOpenInsights = {}, onOpenFinding = {}, modifier = card)
            InsightsPanel(dozeHeadline, null, onOpenInsights = {}, onOpenFinding = {}, modifier = card)
            InsightsPanel(allGood, null, onOpenInsights = {}, onOpenFinding = {}, modifier = card)
            InsightsPanel(null, null, onOpenInsights = {}, onOpenFinding = {}, modifier = card)
        }
    }
}

/** Analysed and quiet, but too few sessions with app data: "still learning" instead of "all good". */
@PreviewTest
@ComponentPreviews
@Composable
fun NowInsightsCardLearningPreview() {
    ScreenshotTheme {
        InsightsPanel(
            learning, null, onOpenInsights = {}, onOpenFinding = {},
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.md),
        )
    }
}

/** No concerns, but a trend Insights lists under Changes: a neutral pointer, never "all good". */
@PreviewTest
@ComponentPreviews
@Composable
fun NowInsightsCardChangesPreview() {
    ScreenshotTheme {
        InsightsPanel(
            changes, null, onOpenInsights = {}, onOpenFinding = {},
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.md),
        )
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun NowInsightsCardPreview() {
    InsightsCardStates()
}

@PreviewTest
@Preview(name = "RtlLargeFont", widthDp = 400, fontScale = 1.5f)
@Composable
fun NowInsightsCardRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { InsightsCardStates() }
}
