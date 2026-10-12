package com.akane.voltwise.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akane.voltwise.R
import com.akane.voltwise.battery.measurement.CalibrationSource
import com.akane.voltwise.battery.measurement.CurrentSign
import com.akane.voltwise.battery.measurement.CurrentUnit
import com.akane.voltwise.ui.components.CalibrationNotice
import com.akane.voltwise.ui.components.DetailTopBar
import com.akane.voltwise.ui.components.InfoSheet
import com.akane.voltwise.ui.components.Notice
import com.akane.voltwise.ui.components.Panel
import com.akane.voltwise.ui.components.QuietText
import com.akane.voltwise.ui.components.StatCell
import com.akane.voltwise.ui.components.chart.TimeGranularity
import com.akane.voltwise.ui.components.chart.rememberTimeAxisFormatter
import com.akane.voltwise.ui.format.sameLocalDay
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.AccessMode
import com.akane.voltwise.viewmodel.AccessState
import com.akane.voltwise.viewmodel.CalibrationStatus
import com.akane.voltwise.viewmodel.StatusEvent
import com.akane.voltwise.viewmodel.StatusIssue
import com.akane.voltwise.viewmodel.StatusIssueKind
import com.akane.voltwise.viewmodel.StatusUiState
import com.akane.voltwise.viewmodel.StatusViewModel
import org.koin.androidx.compose.koinViewModel
import java.util.TimeZone

/** Two columns from this window width, as on Now: access on the start side, the rest on the end. */
private const val TWO_COLUMN_MIN_WIDTH_DP = 840

/** Issues shown before "Show all". */
private const val ISSUES_COLLAPSED = 5

/**
 * Settings › Status, wired: the Koin [StatusViewModel]; copying the ADB commands (clipboard), sharing the
 * report (`ACTION_SEND`) and opening the Shizuku app while BatStats is blocked there happen here. Every other event
 * goes to the ViewModel.
 */
@Composable
fun StatusScreen(onBack: () -> Unit, modifier: Modifier = Modifier, vm: StatusViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val commandsLabel = stringResource(R.string.status_setup_commands)
    val subject = stringResource(R.string.status_report_subject)
    val chooserTitle = stringResource(R.string.status_report_share)
    StatusContent(
        state = state,
        onEvent = { event ->
            when (event) {
                StatusEvent.CopyCommands -> copy(context, commandsLabel, state.access.adbCommands.joinToString("\n"))
                StatusEvent.ShareReport -> vm.onShareResult(share(context, vm.report(), subject, chooserTitle))
                StatusEvent.AuthorizeShizuku ->
                    if (state.access.shizukuBlocked) openShizuku(context) else vm.onEvent(event)
                else -> vm.onEvent(event)
            }
        },
        onBack = onBack,
        modifier = modifier,
    )
}

private fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** True when a share sheet opened. */
private fun share(context: Context, report: String, subject: String, chooserTitle: String): Boolean = try {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, report)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
    context.startActivity(Intent.createChooser(send, chooserTitle))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/**
 * Settings › Status, stateless: the calibration correction notice (Undo/Keep, as on Now), advanced access with
 * its setup steps and copyable ADB commands, the calibration in use, recent issues in plain words, and the
 * shareable report. Explanations live in the panels' ⓘ sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusContent(
    state: StatusUiState,
    onEvent: (StatusEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val twoColumns = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_COLUMN_MIN_WIDTH_DP
    val column = Arrangement.spacedBy(spacing.sm)
    Scaffold(
        modifier = modifier,
        topBar = {
            DetailTopBar(stringResource(R.string.status_title), onBack)
        },
    ) { padding ->
        val access: @Composable () -> Unit = {
            state.calibration.notice?.let { notice ->
                CalibrationNotice(
                    notice,
                    onUndo = { onEvent(StatusEvent.UndoCalibration) },
                    onKeep = { onEvent(StatusEvent.KeepCalibration) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AccessPanel(state.access, onEvent, Modifier.fillMaxWidth())
        }
        val rest: @Composable () -> Unit = {
            CalibrationPanel(state.calibration, Modifier.fillMaxWidth())
            IssuesPanel(state, Modifier.fillMaxWidth())
            ReportPanel(state.shareUnavailable, onShare = { onEvent(StatusEvent.ShareReport) }, Modifier.fillMaxWidth())
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md)
                .padding(bottom = spacing.md),
            verticalArrangement = column,
        ) {
            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Column(Modifier.weight(1f), verticalArrangement = column) { access() }
                    Column(Modifier.weight(1f), verticalArrangement = column) { rest() }
                }
            } else {
                access()
                rest()
            }
        }
    }
}

/** The mode in use, what it gives, and how to set one up (open while there is none). */
@Composable
private fun AccessPanel(access: AccessState, onEvent: (StatusEvent) -> Unit, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    val active = access.mode != AccessMode.NONE
    val probing = access.checking && !active
    Panel(
        modifier,
        title = stringResource(R.string.status_access_title),
        trailing = { InfoSheet(stringResource(R.string.status_access_info_title), stringResource(R.string.status_access_info_body)) },
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (active) Icons.Rounded.CheckCircle else Icons.Rounded.RemoveCircleOutline,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (probing) stringResource(R.string.status_access_checking) else modeName(access.mode),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (!probing) {
                QuietText(
                    stringResource(
                        when {
                            !active -> R.string.status_access_off
                            access.mode == AccessMode.ADB && !access.adbCoversAppStats -> R.string.status_access_adb_limited
                            else -> R.string.status_access_on
                        },
                    ),
                )
            }
        }
        if (access.canAuthorizeShizuku && access.mode != AccessMode.SHIZUKU) {
            QuietText(stringResource(R.string.status_access_authorize_hint))
            FilledTonalButton(onClick = { onEvent(StatusEvent.AuthorizeShizuku) }) {
                Text(stringResource(R.string.status_access_authorize))
            }
        } else if (access.shizukuBlocked && access.mode != AccessMode.SHIZUKU) {
            // Blocked in Shizuku: no dialog to ask for, so the wrapper opens the Shizuku app instead.
            QuietText(stringResource(R.string.status_access_blocked_hint))
            FilledTonalButton(onClick = { onEvent(StatusEvent.AuthorizeShizuku) }) {
                Text(stringResource(R.string.status_access_open_shizuku))
            }
        }
        val check: @Composable () -> Unit = {
            FilledTonalButton(onClick = { onEvent(StatusEvent.RecheckAccess) }, enabled = !access.checking) {
                Text(stringResource(if (access.checking) R.string.status_access_checking_short else R.string.status_access_check))
            }
        }
        if (active) {
            // In use: checking again and the setup steps (folded) are secondary.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                check()
                TextButton(onClick = { setupOpen = !setupOpen }) {
                    Text(stringResource(if (setupOpen) R.string.status_setup_hide else R.string.status_setup_show))
                }
            }
            if (setupOpen) SetupSteps(access, onCopy = { onEvent(StatusEvent.CopyCommands) })
        } else {
            // None yet: the steps first, then check again once one is done.
            SetupSteps(access, onCopy = { onEvent(StatusEvent.CopyCommands) })
            check()
        }
    }
}

/** Shizuku runs but BatStats got "Deny and don't ask again" there, so only the Shizuku app can allow it now. */
private val AccessState.shizukuBlocked: Boolean get() = shizuku.running && !shizuku.granted && shizuku.blocked

@Composable
private fun modeName(mode: AccessMode): String = stringResource(
    when (mode) {
        AccessMode.SHIZUKU -> R.string.status_access_shizuku
        AccessMode.ROOT -> R.string.status_access_root
        AccessMode.ADB -> R.string.status_access_adb
        AccessMode.NONE -> R.string.status_access_none
    },
)

/** The three ways in; the ADB one with its commands, selectable and copyable. */
@Composable
private fun SetupSteps(access: AccessState, onCopy: () -> Unit) {
    val spacing = MaterialTheme.spacing
    var copied by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        QuietText(stringResource(R.string.status_setup_intro))
        SetupStep(stringResource(R.string.status_setup_shizuku_title), stringResource(R.string.status_setup_shizuku_body))
        SetupStep(stringResource(R.string.status_setup_root_title), stringResource(R.string.status_setup_root_body))
        SetupStep(stringResource(R.string.status_setup_adb_title), stringResource(R.string.status_setup_adb_body)) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                SelectionContainer {
                    Column(Modifier.padding(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        access.adbCommands.forEach { command ->
                            Text(
                                command,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            if (!access.adbCoversAppStats) QuietText(stringResource(R.string.status_setup_adb_limited))
            FilledTonalButton(onClick = {
                onCopy()
                copied = true
            }) {
                Icon(
                    if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Text(
                    stringResource(if (copied) R.string.status_setup_copied else R.string.status_setup_copy),
                    modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                )
            }
        }
    }
}

@Composable
private fun SetupStep(title: String, body: String, extra: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extra()
    }
}

/** The unit and sign in use, and where they come from. */
@Composable
private fun CalibrationPanel(calibration: CalibrationStatus, modifier: Modifier = Modifier) {
    val milliamps = calibration.unit == CurrentUnit.MILLIAMPS
    val flipped = calibration.sign == CurrentSign.INVERTED
    Panel(
        modifier,
        title = stringResource(R.string.status_calibration_title),
        trailing = {
            InfoSheet(stringResource(R.string.status_calibration_info_title), stringResource(R.string.status_calibration_info_body))
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            StatCell(
                stringResource(R.string.status_calibration_unit),
                stringResource(if (milliamps) R.string.status_unit_ma else R.string.status_unit_ua),
                Modifier.weight(1f),
                supporting = stringResource(if (milliamps) R.string.status_calibration_unit_ma else R.string.status_calibration_unit_ua),
            )
            StatCell(
                stringResource(R.string.status_calibration_sign),
                stringResource(if (flipped) R.string.status_calibration_positive else R.string.status_calibration_negative),
                Modifier.weight(1f),
                supporting = stringResource(if (flipped) R.string.status_calibration_flipped else R.string.status_calibration_standard),
            )
        }
        QuietText(
            stringResource(
                when (calibration.source) {
                    CalibrationSource.DETECTED -> R.string.status_calibration_detected
                    CalibrationSource.OVERRIDE -> R.string.status_calibration_override
                    CalibrationSource.DEFAULT -> R.string.status_calibration_default
                },
            ),
        )
    }
}

/** Problems from the diagnostics log, newest first, each as one plain sentence with when it happened. */
@Composable
private fun IssuesPanel(state: StatusUiState, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    var showAll by rememberSaveable { mutableStateOf(false) }
    val issues = state.issues
    Panel(
        modifier,
        title = stringResource(R.string.status_issues_title),
        trailing = { InfoSheet(stringResource(R.string.status_issues_info_title), stringResource(R.string.status_issues_info_body)) },
    ) {
        if (state.issueLogUnavailable) Notice(stringResource(R.string.status_issues_log_unavailable))
        if (issues.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                QuietText(stringResource(R.string.status_issues_none))
            }
        } else {
            val formatter = rememberTimeAxisFormatter()
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                (if (showAll) issues else issues.take(ISSUES_COLLAPSED)).forEach { issue ->
                    IssueRow(issue, whenText(issue, formatter.zone, state.nowMs) { time, sameDay ->
                        formatter.format(time, if (sameDay) TimeGranularity.MINUTES else TimeGranularity.DATE_TIME)
                    })
                }
            }
            if (issues.size > ISSUES_COLLAPSED) {
                // Shifted by its content padding so the label lines up with the rows above.
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.offset(x = -spacing.sm)) {
                    Text(
                        if (showAll) stringResource(R.string.status_issues_show_fewer)
                        else stringResource(R.string.status_issues_show_all, issues.size.toString()),
                    )
                }
            }
        }
    }
}

@Composable
private fun whenText(issue: StatusIssue, zone: TimeZone, nowMs: Long, format: (Long, Boolean) -> String): String {
    val last = format(issue.lastAtMs, sameLocalDay(issue.lastAtMs, nowMs, zone))
    return if (issue.count > 1) stringResource(R.string.status_issues_repeated, last, issue.count.toString()) else last
}

@Composable
private fun IssueRow(issue: StatusIssue, whenText: String) {
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(stringResource(issue.kind.text()), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(whenText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun StatusIssueKind.text(): Int = when (this) {
    StatusIssueKind.START_FAILED -> R.string.status_issue_start_failed
    StatusIssueKind.BATTERY_UNAVAILABLE -> R.string.status_issue_battery_unavailable
    StatusIssueKind.BATTERY_READ_FAILED -> R.string.status_issue_battery_failed
    StatusIssueKind.STATE_EVENTS_UNAVAILABLE -> R.string.status_issue_state_events
    StatusIssueKind.HISTORY_WRITE_FAILED -> R.string.status_issue_history_failed
    StatusIssueKind.OBSERVATION_GAP -> R.string.status_issue_gap
    StatusIssueKind.CHARGE_UNAVAILABLE -> R.string.status_issue_charge_counter
    StatusIssueKind.ADVANCED_READ_FAILED -> R.string.status_issue_advanced_failed
    StatusIssueKind.DOZE_WHITELIST_UNREADABLE -> R.string.status_issue_doze_whitelist_unreadable
    StatusIssueKind.ADVANCED_FORMAT_INVALID -> R.string.status_issue_format_invalid
    StatusIssueKind.ADVANCED_INTERRUPTED -> R.string.status_issue_interrupted
    StatusIssueKind.ALERT_FAILED -> R.string.status_issue_alert_failed
    StatusIssueKind.NOTIFICATION_FAILED -> R.string.status_issue_notification_failed
    StatusIssueKind.LOG_READ_FAILED -> R.string.status_issue_log_failed
}

@Composable
private fun ReportPanel(shareUnavailable: Boolean, onShare: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier, title = stringResource(R.string.status_report_title)) {
        QuietText(stringResource(R.string.status_report_body))
        FilledTonalButton(onClick = onShare) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.status_report_share), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
        if (shareUnavailable) Notice(stringResource(R.string.status_report_unavailable))
    }
}

