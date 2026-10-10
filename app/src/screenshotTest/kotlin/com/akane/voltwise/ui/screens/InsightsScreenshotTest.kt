package com.akane.voltwise.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.ui.FIXED_TIME_MS
import com.akane.voltwise.ui.PhonePreview
import com.akane.voltwise.ui.ScreenPreviews
import com.akane.voltwise.ui.ScreenshotTheme
import com.akane.voltwise.ui.TallPhonePreview
import com.akane.voltwise.ui.screens.insights.InsightApplyDetails
import com.akane.voltwise.ui.screens.insights.InsightsContent
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.AppliedInsightAction
import com.akane.voltwise.viewmodel.InsightFindingState
import com.akane.voltwise.viewmodel.InsightMessageCode
import com.akane.voltwise.viewmodel.InsightsUiState
import com.akane.voltwise.viewmodel.RecommendationState
import com.android.tools.screenshot.PreviewTest

// Times render in the suite's pinned UTC/en-US; the last analysis ran 25 min before FIXED_TIME_MS (09:20).
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

private val youtube = Subject.App(10_201, "com.google.android.youtube")
private val weather = Subject.App(10_233, "com.example.weatherlive")
private val spotify = Subject.App(10_250, "com.spotify.music")
private val facebook = Subject.App(10_260, "com.facebook.katana")

private val labels = mapOf(
    youtube.packageName to AppLabel.Named("YouTube"),
    weather.packageName to AppLabel.Named("Weather Live"),
    spotify.packageName to AppLabel.Named("Spotify"),
    facebook.packageName to AppLabel.Named("Facebook"),
    "com.whatsapp" to AppLabel.Named("WhatsApp"),
)

private fun rec(
    action: ActionType,
    privileged: Boolean = true,
    available: Boolean = true,
    applied: Boolean = false,
) = RecommendationState(action, action != ActionType.FORCE_STOP, privileged, available, applied)

private fun finding(
    type: FindingType,
    subject: Subject,
    severity: Severity,
    confidence: Confidence,
    evidence: Evidence,
    recommendations: List<RecommendationState> = emptyList(),
    direction: Direction? = null,
) = InsightFindingState(
    key = "$type:${(subject as? Subject.App)?.packageName ?: "device"}",
    type = type,
    severity = severity,
    confidence = confidence,
    score = 1.0,
    subject = subject,
    direction = direction,
    evidence = listOf(evidence),
    series = emptyList(),
    recommendations = recommendations,
    attributions = emptyList(),
)

private val drainHeadline = finding(
    FindingType.APP_DRAIN_ANOMALY, youtube, Severity.HIGH, Confidence.HIGH,
    Evidence(Metric.POWER_MAH_PER_H, 41.6, 13.0, Metric.POWER_MAH_PER_H.unit, 6),
    listOf(rec(ActionType.RESTRICT_BACKGROUND), rec(ActionType.STANDBY_BUCKET_RESTRICTED), rec(ActionType.OPEN_APP_SETTINGS, privileged = false)),
)

private val wakeups = finding(
    FindingType.WAKEUP_STORM, weather, Severity.MEDIUM, Confidence.MEDIUM,
    Evidence(Metric.WAKEUP_ALARMS_PER_H, 48.0, 6.0, Metric.WAKEUP_ALARMS_PER_H.unit, 5),
    listOf(rec(ActionType.STANDBY_BUCKET_RESTRICTED), rec(ActionType.OPEN_APP_SETTINGS, privileged = false)),
)

private val dozeBlocked = finding(
    FindingType.DOZE_BLOCKED, Subject.Device, Severity.MEDIUM, Confidence.HIGH,
    Evidence(Metric.DEEP_DOZE_SHARE, 0.12, 0.64, Metric.DEEP_DOZE_SHARE.unit, 7),
    listOf(rec(ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS, privileged = false)),
)

private val lingering = finding(
    FindingType.LINGERING_FOREGROUND_SERVICE, spotify, Severity.LOW, Confidence.LOW,
    Evidence(Metric.FGS_TO_FOREGROUND_RATIO, 4.5, null, Metric.FGS_TO_FOREGROUND_RATIO.unit, 4),
    listOf(rec(ActionType.RESTRICT_BACKGROUND, available = false, applied = true), rec(ActionType.FORCE_STOP)),
)

private val facebookUp = finding(
    FindingType.TREND, facebook, Severity.INFO, Confidence.MEDIUM,
    Evidence(Metric.POWER_MAH_PER_H, 3.4, 1.2, Metric.POWER_MAH_PER_H.unit, 14),
    direction = Direction.UP,
)

private val screenOffDown = finding(
    FindingType.TREND, Subject.Device, Severity.INFO, Confidence.HIGH,
    Evidence(Metric.SCREEN_OFF_PCT_PER_H, 0.8, 1.9, Metric.SCREEN_OFF_PCT_PER_H.unit, 21),
    direction = Direction.DOWN,
)

private val restrictEffect = finding(
    FindingType.ACTION_EFFECT, spotify, Severity.INFO, Confidence.MEDIUM,
    Evidence(Metric.POWER_MAH_PER_H, 3.8, 10.0, Metric.POWER_MAH_PER_H.unit, 4),
)

private val full = InsightsUiState(
    loaded = true,
    headline = drainHeadline,
    keyFindings = listOf(drainHeadline, wakeups, dozeBlocked, lingering),
    changes = listOf(facebookUp, screenOffDown),
    appliedActions = listOf(
        AppliedInsightAction(
            7, "BACKGROUND_RUNAWAY:com.spotify.music", ActionType.RESTRICT_BACKGROUND, spotify.packageName,
            InsightActionStatus.APPLIED, FIXED_TIME_MS - 2 * DAY - 3 * HOUR, undoable = true, effect = restrictEffect,
        ),
        AppliedInsightAction(
            5, "STUCK_WAKELOCK:com.whatsapp", ActionType.FORCE_STOP, "com.whatsapp",
            InsightActionStatus.ONE_SHOT, FIXED_TIME_MS - 3 * HOUR, undoable = false, effect = null,
        ),
    ),
    lastAnalyzedAt = FIXED_TIME_MS - 25 * MINUTE,
    privileged = true,
    empty = false,
    eligibleSessionCount = 9,
    lowData = false,
)

/** Shell access missing: device findings only, privileged fixes say what they need, manual paths still offered. */
private val notPrivileged = InsightsUiState(
    loaded = true,
    headline = dozeBlocked,
    keyFindings = listOf(
        dozeBlocked,
        finding(
            FindingType.SCREEN_OFF_DRAIN_HIGH, Subject.Device, Severity.LOW, Confidence.MEDIUM,
            Evidence(Metric.SCREEN_OFF_PCT_PER_H, 2.6, 1.1, Metric.SCREEN_OFF_PCT_PER_H.unit, 6),
            listOf(rec(ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS, privileged = false)),
        ),
        drainHeadline.copy(
            severity = Severity.MEDIUM,
            recommendations = listOf(rec(ActionType.RESTRICT_BACKGROUND, available = false)),
        ),
    ),
    changes = listOf(screenOffDown),
    lastAnalyzedAt = FIXED_TIME_MS - 3 * HOUR,
    privileged = false,
    empty = false,
    eligibleSessionCount = 7,
    lowData = false,
)

@Composable
private fun InsightsPreviewContent(state: InsightsUiState) {
    InsightsContent(state = state, labels = labels, nowMs = FIXED_TIME_MS, onEvent = {}, onOpenAccessSetup = {})
}

@PreviewTest
@ScreenPreviews
@Composable
fun InsightsScreenPreview() {
    ScreenshotTheme { InsightsPreviewContent(full) }
}

/** The whole full page on a tall phone: key findings, what changed and applied fixes below the fold. */
@PreviewTest
@TallPhonePreview
@Composable
fun InsightsFullTallPreview() {
    ScreenshotTheme { InsightsPreviewContent(full) }
}

@PreviewTest
@TallPhonePreview
@Composable
fun InsightsNotPrivilegedPreview() {
    ScreenshotTheme { InsightsPreviewContent(notPrivileged) }
}

/** 2 of 5 sessions so far, while an analysis runs: progress under the header, "still learning" as the body. */
@PreviewTest
@PhonePreview
@Composable
fun InsightsLearningPreview() {
    ScreenshotTheme {
        InsightsPreviewContent(InsightsUiState(loaded = true, privileged = true, eligibleSessionCount = 2, lowData = true, analyzing = true))
    }
}

/** No Shizuku or root: no session gets app data, so "still learning" says findings about apps need access. */
@PreviewTest
@TallPhonePreview
@Composable
fun InsightsLearningNoAccessPreview() {
    ScreenshotTheme {
        InsightsPreviewContent(
            InsightsUiState(loaded = true, lastAnalyzedAt = FIXED_TIME_MS - 10 * MINUTE, privileged = false, eligibleSessionCount = 0, lowData = true),
        )
    }
}

/**
 * No Shizuku or root, yet the capacity is falling: the device-only decline is listed under "What changed" (no
 * baseline, a yearly rate) while the learning notice still explains the missing app data.
 */
@PreviewTest
@TallPhonePreview
@Composable
fun InsightsHealthDeclineNoAccessPreview() {
    ScreenshotTheme {
        InsightsPreviewContent(
            InsightsUiState(
                loaded = true,
                changes = listOf(
                    finding(
                        FindingType.HEALTH_DECLINE, Subject.Device, Severity.INFO, Confidence.MEDIUM,
                        Evidence(Metric.CAPACITY_CHANGE_PCT_PER_YEAR, -6.4, null, Metric.CAPACITY_CHANGE_PCT_PER_YEAR.unit, 7),
                        direction = Direction.DOWN,
                    ),
                ),
                lastAnalyzedAt = FIXED_TIME_MS - 10 * MINUTE,
                privileged = false,
                empty = false,
                eligibleSessionCount = 0,
                lowData = true,
            ),
        )
    }
}

/** Before the first report (also right after a restore): only the header, saying it's loading, no "still learning". */
@PreviewTest
@PhonePreview
@Composable
fun InsightsLoadingPreview() {
    ScreenshotTheme { InsightsPreviewContent(InsightsUiState()) }
}

@PreviewTest
@PhonePreview
@Composable
fun InsightsAllGoodPreview() {
    ScreenshotTheme {
        InsightsPreviewContent(
            InsightsUiState(loaded = true, lastAnalyzedAt = FIXED_TIME_MS - 10 * MINUTE, privileged = true, eligibleSessionCount = 12, lowData = false),
        )
    }
}

/** A failed analysis with nothing to show: the error and Try again, never "nothing needs attention". */
@PreviewTest
@PhonePreview
@Composable
fun InsightsErrorPreview() {
    ScreenshotTheme {
        InsightsPreviewContent(
            InsightsUiState(
                loaded = true,
                lastAnalyzedAt = FIXED_TIME_MS - 2 * DAY,
                privileged = true,
                eligibleSessionCount = 12,
                lowData = false,
                error = InsightMessageCode.ANALYSIS_FAILED,
            ),
        )
    }
}

/** Applied fixes after two undos: one found the setting already changed outside Voltwise, one really undone. */
@PreviewTest
@PhonePreview
@Composable
fun InsightsUndoHistoryPreview() {
    ScreenshotTheme {
        InsightsPreviewContent(
            InsightsUiState(
                loaded = true,
                appliedActions = listOf(
                    AppliedInsightAction(
                        7, "BACKGROUND_RUNAWAY:com.spotify.music", ActionType.RESTRICT_BACKGROUND, spotify.packageName,
                        InsightActionStatus.REVERTED, FIXED_TIME_MS - 2 * DAY - 3 * HOUR, undoable = false, effect = null,
                        changedExternally = true,
                    ),
                    AppliedInsightAction(
                        3, "BACKGROUND_RUNAWAY:com.google.android.youtube", ActionType.STANDBY_BUCKET_RARE, youtube.packageName,
                        InsightActionStatus.REVERTED, FIXED_TIME_MS - 5 * DAY, undoable = false, effect = null,
                    ),
                ),
                lastAnalyzedAt = FIXED_TIME_MS - 25 * MINUTE,
                privileged = true,
                empty = false,
                eligibleSessionCount = 9,
                lowData = false,
            ),
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun InsightsRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ScreenshotTheme { InsightsPreviewContent(full) }
    }
}

/** The apply confirmation's body: a reversible privileged fix, then Force stop (can't be undone). */
@PreviewTest
@TallPhonePreview
@Composable
fun InsightApplyDetailsPreview() {
    ScreenshotTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            // No outer scroll: each body scrolls itself, as inside the dialog.
            Column(
                Modifier.padding(MaterialTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xl),
            ) {
                InsightApplyDetails(ActionType.RESTRICT_BACKGROUND, reversible = true, requiresPrivilege = true, subjectName = "YouTube")
                InsightApplyDetails(ActionType.FORCE_STOP, reversible = false, requiresPrivilege = true, subjectName = "WhatsApp")
            }
        }
    }
}
