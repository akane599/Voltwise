package com.akane.voltwise.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.Attribution
import com.akane.voltwise.battery.insights.model.AttributionKind
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.MetricUnit
import com.akane.voltwise.battery.insights.model.SeriesPoint
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.ui.FIXED_TIME_MS
import com.akane.voltwise.ui.PhonePreview
import com.akane.voltwise.ui.ScreenPreviews
import com.akane.voltwise.ui.ScreenshotTheme
import com.akane.voltwise.ui.TallPhonePreview
import com.akane.voltwise.ui.screens.insights.FindingDetailsContent
import com.akane.voltwise.viewmodel.AppliedInsightAction
import com.akane.voltwise.viewmodel.FindingDetailsUiState
import com.akane.voltwise.viewmodel.InsightApplyState
import com.akane.voltwise.viewmodel.InsightFindingState
import com.akane.voltwise.viewmodel.PendingInsightApply
import com.akane.voltwise.viewmodel.RecommendationState
import com.android.tools.screenshot.PreviewTest

// Times render in the suite's pinned UTC/en-US; readings are one per session on battery over the last 9 days.
private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

private val youtube = Subject.App(10_201, "com.google.android.youtube")
private val labels = mapOf(
    youtube.packageName to AppLabel.Named("YouTube"),
    "com.google.android.gms" to AppLabel.Named("Google Play services"),
)

private fun rec(action: ActionType, privileged: Boolean = true, available: Boolean = true, applied: Boolean = false) =
    RecommendationState(action, action != ActionType.FORCE_STOP, privileged, available, applied)

/** Drain per session: usual 9–17 mAh/h; the last two sessions (and one earlier spike) are far above it. */
private val drainSeries = listOf(12.4, 10.8, 14.1, 23.5, 13.0, 11.6, 15.2, 34.0, 41.6).mapIndexed { i, value ->
    SeriesPoint(FIXED_TIME_MS - (8 - i) * DAY - 2 * HOUR, value, 9.0, 17.0)
}

private val appFinding = InsightFindingState(
    key = "APP_DRAIN_ANOMALY:com.google.android.youtube",
    type = FindingType.APP_DRAIN_ANOMALY,
    severity = Severity.HIGH,
    confidence = Confidence.HIGH,
    score = 1.0,
    subject = youtube,
    direction = Direction.UP,
    evidence = listOf(
        Evidence(Metric.POWER_MAH_PER_H, 41.6, 13.0, MetricUnit.MAH_PER_H, 6),
        Evidence(Metric.BG_TIME_SHARE, 0.62, 0.18, MetricUnit.SHARE, 6),
    ),
    series = drainSeries,
    recommendations = listOf(
        rec(ActionType.RESTRICT_BACKGROUND),
        rec(ActionType.STANDBY_BUCKET_RESTRICTED),
        rec(ActionType.FORCE_STOP),
        rec(ActionType.OPEN_APP_SETTINGS, privileged = false),
    ),
    attributions = emptyList(),
)

private val effect = appFinding.copy(
    key = "ACTION_EFFECT:com.google.android.youtube:POWER_MAH_PER_H:3",
    type = FindingType.ACTION_EFFECT,
    severity = Severity.INFO,
    evidence = listOf(Evidence(Metric.POWER_MAH_PER_H, 9.1, 21.0, MetricUnit.MAH_PER_H, 4)),
    series = emptyList(),
    recommendations = emptyList(),
)

private val normal = FindingDetailsUiState(
    loaded = true,
    finding = appFinding,
    relatedActions = listOf(
        AppliedInsightAction(
            3, "BACKGROUND_RUNAWAY:com.google.android.youtube", ActionType.STANDBY_BUCKET_RARE, youtube.packageName,
            InsightActionStatus.APPLIED, FIXED_TIME_MS - 20 * DAY, undoable = true, effect = effect,
        ),
    ),
    privileged = true,
)

/** No shell access: privileged fixes say what they need, the settings page is still offered. */
private val notPrivileged = FindingDetailsUiState(
    loaded = true,
    finding = appFinding.copy(
        recommendations = listOf(
            rec(ActionType.RESTRICT_BACKGROUND, available = false),
            rec(ActionType.FORCE_STOP, available = false),
            rec(ActionType.OPEN_APP_SETTINGS, privileged = false),
        ),
    ),
    privileged = false,
)

/** Deep Doze share per night (no band from the engine): the latest night against the 64% usual. */
private val device = FindingDetailsUiState(
    loaded = true,
    finding = InsightFindingState(
        key = "DOZE_BLOCKED:device",
        type = FindingType.DOZE_BLOCKED,
        severity = Severity.MEDIUM,
        confidence = Confidence.MEDIUM,
        score = 1.0,
        subject = Subject.Device,
        direction = Direction.DOWN,
        evidence = listOf(Evidence(Metric.DEEP_DOZE_SHARE, 0.12, 0.64, MetricUnit.SHARE, 7)),
        series = listOf(0.66, 0.61, 0.70, 0.58, 0.63, 0.31, 0.12).mapIndexed { i, value ->
            SeriesPoint(FIXED_TIME_MS - (6 - i) * DAY - 3 * HOUR, value, null, null)
        },
        recommendations = listOf(rec(ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS, privileged = false)),
        attributions = listOf(
            Attribution(AttributionKind.KERNEL_WAKELOCK, "NETLINK", null, 1_860_000.0, MetricUnit.MS_PER_H, 3),
            Attribution(AttributionKind.WAKEUP_REASON, "qcom,glink-smem-native-xprt-modem", null, 22.0, MetricUnit.COUNT_PER_H, 3),
            Attribution(AttributionKind.APP, "com.google.android.gms", "com.google.android.gms", 0.41, MetricUnit.SHARE, 2),
        ),
    ),
    privileged = true,
)

/** Capacity estimates about every 10 days, 4,612 down to 4,540 mAh: the chart reads mAh, the evidence the yearly change. */
private val capacityDecline = FindingDetailsUiState(
    loaded = true,
    finding = InsightFindingState(
        key = "HEALTH_DECLINE:device",
        type = FindingType.HEALTH_DECLINE,
        severity = Severity.INFO,
        confidence = Confidence.MEDIUM,
        score = 1.0,
        subject = Subject.Device,
        direction = Direction.DOWN,
        evidence = listOf(Evidence(Metric.CAPACITY_CHANGE_PCT_PER_YEAR, -8.1, null, MetricUnit.PCT_PER_YEAR, 8)),
        series = listOf(4_612.0, 4_604.0, 4_597.0, 4_583.0, 4_580.0, 4_566.0, 4_551.0, 4_540.0).mapIndexed { i, value ->
            SeriesPoint(FIXED_TIME_MS - (7 - i) * 10 * DAY - 5 * HOUR, value, null, null)
        },
        recommendations = emptyList(),
        attributions = emptyList(),
    ),
    privileged = true,
)

@Composable
private fun FindingDetailsPreviewContent(state: FindingDetailsUiState) {
    FindingDetailsContent(state = state, labels = labels, nowMs = FIXED_TIME_MS, onEvent = {}, onBack = {}, onOpenAccessSetup = {})
}

@PreviewTest
@ScreenPreviews
@Composable
fun FindingDetailsScreenPreview() {
    ScreenshotTheme { FindingDetailsPreviewContent(normal) }
}

/** The whole page: explanation, evidence, chart with the usual band, fixes, feedback and the related applied fix. */
@PreviewTest
@TallPhonePreview
@Composable
fun FindingDetailsFullTallPreview() {
    ScreenshotTheme { FindingDetailsPreviewContent(normal) }
}

/** Force stop asked for: the confirmation names the effect and says it can't be undone. */
@PreviewTest
@PhonePreview
@Composable
fun FindingDetailsIrreversibleConfirmPreview() {
    ScreenshotTheme {
        FindingDetailsPreviewContent(normal.copy(apply = InsightApplyState(pending = PendingInsightApply(appFinding.key, ActionType.FORCE_STOP))))
    }
}

@PreviewTest
@TallPhonePreview
@Composable
fun FindingDetailsNotPrivilegedPreview() {
    ScreenshotTheme { FindingDetailsPreviewContent(notPrivileged) }
}

/** Two columns, so the fixes show too: each privileged one names what it needs, the settings page still runs. */
@PreviewTest
@Preview(name = "W900H1000", widthDp = 900, heightDp = 1000)
@Composable
fun FindingDetailsNotPrivilegedWidePreview() {
    ScreenshotTheme { FindingDetailsPreviewContent(notPrivileged) }
}

/** A device finding: no app, a dashed usual line instead of a band, and possible causes marked as hints. */
@PreviewTest
@TallPhonePreview
@Composable
fun FindingDetailsDevicePreview() {
    ScreenshotTheme { FindingDetailsPreviewContent(device) }
}

/** A capacity decline: the capacity estimates under a mAh caption, no usual line; the evidence gives the change per year. */
@PreviewTest
@TallPhonePreview
@Composable
fun FindingDetailsCapacityDeclinePreview() {
    ScreenshotTheme { FindingDetailsPreviewContent(capacityDecline) }
}

@PreviewTest
@PhonePreview
@Composable
fun FindingDetailsRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ScreenshotTheme { FindingDetailsPreviewContent(normal) }
    }
}

/** Dismissed elsewhere or no longer in the latest analysis. */
@PreviewTest
@PhonePreview
@Composable
fun FindingDetailsGonePreview() {
    ScreenshotTheme { FindingDetailsPreviewContent(FindingDetailsUiState(loaded = true, privileged = true)) }
}

/** Before the first report (also right after a restore): a quiet loading line, never "finding gone". */
@PreviewTest
@PhonePreview
@Composable
fun FindingDetailsLoadingPreview() {
    ScreenshotTheme { FindingDetailsPreviewContent(FindingDetailsUiState()) }
}
