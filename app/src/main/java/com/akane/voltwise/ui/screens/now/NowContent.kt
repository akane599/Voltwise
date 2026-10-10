package com.akane.voltwise.ui.screens.now

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import com.akane.voltwise.R
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.ui.components.CalibrationNotice
import com.akane.voltwise.ui.components.chart.ChartScrubState
import com.akane.voltwise.ui.components.chart.rememberChartScrubState
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.NowEvent
import com.akane.voltwise.viewmodel.NowUiState

/** Two columns from this window width (the Material "expanded" breakpoint): live on the start, cards on the end. */
private const val TWO_COLUMN_MIN_WIDTH_DP = 840

/**
 * Now, stateless: [state] in, [onEvent] out. One scrolling page under the status bar (no top bar: the hero is the
 * header). Phones stack notice, hero, trace, since unplug, Insights, Today, Health and Top apps; from 840 dp the live
 * half (notice, hero, trace) and the cards sit side by side. The Reset confirmation is local UI state, tied to the
 * window it was opened for: it closes when that window stops being the current one (plugged in, monitoring stopped).
 *
 * @param insightsApp the label of the app the headline finding names, once loaded (null for a device finding).
 * @param onOpenInsights the Insights card; [onOpenFinding] its headline. Navigation only, so not [NowEvent]s.
 */
@Composable
fun NowContent(
    state: NowUiState,
    onEvent: (NowEvent) -> Unit,
    modifier: Modifier = Modifier,
    insightsApp: AppLabel? = null,
    onOpenInsights: () -> Unit = {},
    onOpenFinding: (key: String) -> Unit = {},
    scrubState: ChartScrubState = rememberChartScrubState(),
) {
    // The start of the current window the confirmation was opened for; null when it's closed.
    var resetWindowStart by rememberSaveable { mutableStateOf<Long?>(null) }
    val currentWindowStart = state.sinceUnplug?.takeIf { it.current }?.startedAtMs
    // Plugging in (or stopping monitoring) while the dialog is open ends the window: Reset would otherwise end the
    // new CHARGE session. A new window after a gap isn't the one the user asked about either.
    LaunchedEffect(currentWindowStart) {
        if (resetWindowStart != currentWindowStart) resetWindowStart = null
    }
    val spacing = MaterialTheme.spacing
    val twoColumns = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_COLUMN_MIN_WIDTH_DP
    val column = Arrangement.spacedBy(spacing.sm)

    val live: @Composable () -> Unit = {
        state.calibrationNotice?.let { calibration ->
            CalibrationNotice(
                calibration,
                onUndo = { onEvent(NowEvent.UndoCalibration) },
                onKeep = { onEvent(NowEvent.KeepCalibration) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        NowHero(state.hero, onToggleMonitoring = { onEvent(NowEvent.ToggleMonitoring) }, modifier = Modifier.fillMaxWidth())
        NowTracePanel(
            state.trace,
            state.readouts,
            state.useFahrenheit,
            onSelectRange = { onEvent(NowEvent.SelectRange(it)) },
            modifier = Modifier.fillMaxWidth(),
            scrubState = scrubState,
        )
    }
    val cards: @Composable () -> Unit = {
        SinceUnplugPanel(
            state.sinceUnplug,
            monitoring = state.hero.monitoring,
            nowMs = state.nowMs,
            onReset = { resetWindowStart = currentWindowStart },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.showsInsights) {
            InsightsPanel(
                state.insightsSummary,
                headlineApp = insightsApp,
                onOpenInsights = onOpenInsights,
                onOpenFinding = onOpenFinding,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TodayPanel(state.today, onOpen = { onEvent(NowEvent.OpenHistory) }, modifier = Modifier.fillMaxWidth())
        HealthPanel(state.health, onOpen = { onEvent(NowEvent.OpenHealth) }, modifier = Modifier.fillMaxWidth())
        TopAppsPanel(
            state.topApps,
            nowMs = state.nowMs,
            onOpenApps = { onEvent(NowEvent.OpenApps) },
            onOpenApp = { uid, packageName -> onEvent(NowEvent.OpenApp(uid, packageName)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = column,
    ) {
        if (twoColumns) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Column(Modifier.weight(1f), verticalArrangement = column) { live() }
                Column(Modifier.weight(1f), verticalArrangement = column) { cards() }
            }
        } else {
            live()
            cards()
        }
    }

    if (resetWindowStart != null && resetWindowStart == currentWindowStart) {
        AlertDialog(
            onDismissRequest = { resetWindowStart = null },
            title = { Text(stringResource(R.string.now_reset_title)) },
            text = { Text(stringResource(R.string.now_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    resetWindowStart = null
                    onEvent(NowEvent.ResetObservation)
                }) { Text(stringResource(R.string.now_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { resetWindowStart = null }) { Text(stringResource(R.string.now_cancel)) }
            },
        )
    }
}
