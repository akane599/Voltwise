package com.akane.voltwise.ui.screens.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akane.voltwise.R
import com.akane.voltwise.battery.apps.AppInfoSource
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.insights.engine.detectors.app.AppContext
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.ui.components.EmptyState
import com.akane.voltwise.ui.components.Notice
import com.akane.voltwise.ui.components.NoticeTone
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.InsightActionMessage
import com.akane.voltwise.viewmodel.InsightFindingState
import com.akane.voltwise.viewmodel.InsightMessageCode
import com.akane.voltwise.viewmodel.InsightUiEffect
import com.akane.voltwise.viewmodel.InsightsEvent
import com.akane.voltwise.viewmodel.InsightsUiState
import com.akane.voltwise.viewmodel.InsightsViewModel
import com.akane.voltwise.viewmodel.RecommendationState
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** Two columns from this window width (the Material "expanded" breakpoint): findings start, changes and fixes end. */
private const val TWO_COLUMN_MIN_WIDTH_DP = 840

/** Sessions with app data the engine needs before it judges apps: the per-app baseline plus the current one. */
internal const val LEARNING_SESSIONS = AppContext.MIN_ELIGIBLE_WINDOWS

/**
 * Insights, wired: the Koin [InsightsViewModel], app labels from [AppInfoSource] for every app a finding or fix
 * names, and the ViewModel's one-shot effects (Android settings pages, finding details; result messages come from
 * its state instead, so they survive rotation). Navigation leaves through the lambdas; every [InsightsEvent] goes to the ViewModel.
 */
@Composable
fun InsightsScreen(
    onOpenFinding: (key: String) -> Unit,
    onOpenAccessSetup: () -> Unit,
    modifier: Modifier = Modifier,
    vm: InsightsViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appInfo: AppInfoSource = koinInject()
    val openFinding by rememberUpdatedState(onOpenFinding)
    val snackbar = remember { SnackbarHostState() }
    val settingsUnavailable by rememberUpdatedState(stringResource(R.string.insights_settings_unavailable))
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                // The snackbar follows the ViewModel's held result (state.apply.lastResult), which survives rotation.
                is InsightUiEffect.Message -> Unit
                is InsightUiEffect.OpenSettings -> if (!openSettingsPage(context, effect.spec)) {
                    launch { snackbar.showSnackbar(settingsUnavailable) }
                }
                is InsightUiEffect.OpenFinding -> openFinding(effect.key)
            }
        }
    }
    val subjects = remember(state.headline, state.keyFindings, state.changes, state.appliedActions) { appSubjects(state) }
    val labels by produceState(emptyMap<String, AppLabel>(), subjects, appInfo) {
        value = subjects.mapValues { (packageName, uid) -> AppLabel.of(uid, packageName, appInfo.infoOrNull(packageName)) }
    }
    InsightsContent(
        state = state,
        labels = labels,
        nowMs = remember(state.lastAnalyzedAt) { System.currentTimeMillis() },
        onEvent = vm::onEvent,
        onOpenAccessSetup = onOpenAccessSetup,
        modifier = modifier,
        snackbar = snackbar,
    )
}

/** Codes the screen can show as its error notice rather than as a snackbar. */
private val NOTICE_CODES = setOf(InsightMessageCode.ANALYSIS_FAILED, InsightMessageCode.FEEDBACK_FAILED)

/** Codes to keep out of the snackbar: a notice code only while the screen shows it as [error]; any other result shows. */
internal fun silentResultCodes(error: InsightMessageCode?): Set<InsightMessageCode> =
    setOfNotNull(error).intersect(NOTICE_CODES)

/**
 * Shows the ViewModel's held [result] in [snackbar] (codes in [silentCodes] skip it), then consumes that exact
 * result with [InsightsEvent.ResultShown] once it was shown or dismissed, so a newer result published meanwhile
 * stays held. A rotation mid-snackbar cancels this before the consume, so the result shows again on the new
 * screen instead of being lost. An alert turned on while notifications are off offers Turn on, which opens the
 * app's notification settings (or the next page [openSettingsPage] can open; if none, says so).
 */
@Composable
internal fun ResultSnackbar(
    snackbar: SnackbarHostState,
    result: InsightActionMessage?,
    onEvent: (InsightsEvent) -> Unit,
    silentCodes: Set<InsightMessageCode> = emptySet(),
) {
    val text = result?.let { stringResource(it.messageRes()) }
    val actionLabel = result?.actionLabelRes()?.let { stringResource(it) }
    val unavailable = stringResource(R.string.insights_settings_unavailable)
    val context = LocalContext.current
    // Outlives this result's effect, which the consume below cancels.
    val scope = rememberCoroutineScope()
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(result) {
        if (result == null || text == null) return@LaunchedEffect
        if (result.code !in silentCodes) {
            // An action needs time to reach (Material's default for one, Indefinite, would never time out).
            val duration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long
            val shown = snackbar.showSnackbar(text, actionLabel, duration = duration)
            if (shown == SnackbarResult.ActionPerformed && !openSettingsPage(context, notificationSettings(context.packageName))) {
                scope.launch { snackbar.showSnackbar(unavailable) }
            }
        }
        currentOnEvent(InsightsEvent.ResultShown(result))
    }
}

/** Package → uid of every app the screen names (a fix row knows no uid: -1, its label then never reads "system"). */
private fun appSubjects(state: InsightsUiState): Map<String, Int> {
    val apps = LinkedHashMap<String, Int>()
    (listOfNotNull(state.headline) + state.keyFindings + state.changes).forEach { finding ->
        (finding.subject as? Subject.App)?.let { apps[it.packageName] = it.uid }
    }
    state.appliedActions.forEach { action -> action.packageName?.let { apps.putIfAbsent(it, -1) } }
    return apps
}

/** What the body under the header shows. */
internal enum class InsightsBody { FINDINGS, ERROR, LEARNING, NEVER_ANALYZED, ALL_GOOD }

/** Anything to list: a headline, a key finding, a change or a fix. */
internal val InsightsUiState.hasContent: Boolean
    get() = headline != null || keyFindings.isNotEmpty() || changes.isNotEmpty() || appliedActions.isNotEmpty()

/**
 * Findings always win; without any, a failed run is an error (never "all good"), too few sessions is "still
 * learning", no run yet is an invitation, and only an analysed, quiet report is "all good".
 */
internal fun InsightsUiState.body(): InsightsBody = when {
    hasContent -> InsightsBody.FINDINGS
    error != null -> InsightsBody.ERROR
    lowData -> InsightsBody.LEARNING
    lastAnalyzedAt == null -> InsightsBody.NEVER_ANALYZED
    else -> InsightsBody.ALL_GOOD
}

/**
 * "Still learning" counts comparable per-app windows collected through supported ADB, Shizuku or root access.
 * Without action privileges, the copy separates app-data collection from applying app restrictions.
 */
@Composable
private fun learningBody(state: InsightsUiState): String = stringResource(
    if (state.privileged) R.string.insights_learning_body else R.string.insights_learning_body_no_access,
    state.eligibleSessionCount,
    LEARNING_SESSIONS,
)

/** Key findings under the headline card, without repeating the headline itself. */
internal fun InsightsUiState.listedFindings(): List<InsightFindingState> = keyFindings.filter { it.key != headline?.key }

/** The fix a finding offers first: one that can run now, else the one already applied, else the first (needs access). */
internal fun InsightFindingState.primaryRecommendation(): RecommendationState? =
    recommendations.firstOrNull { it.available } ?: recommendations.firstOrNull { it.alreadyApplied } ?: recommendations.firstOrNull()

/** The finding (headline, key finding or change) a pending apply refers to. */
internal fun InsightsUiState.findingFor(key: String): InsightFindingState? =
    (listOfNotNull(headline) + keyFindings + changes).firstOrNull { it.key == key }

/**
 * Insights, stateless: [state] in, [onEvent] out. A header (title, Analyze now, last analysed time), then notices
 * (error, missing access, still learning), then the headline finding, key findings, what changed and applied fixes.
 * Without findings the body is a designed state: learning (n of [LEARNING_SESSIONS] sessions), not analysed yet,
 * error, or all good. From 840 dp findings sit on the start and changes and fixes on the end. Apply always goes
 * through the confirmation dialog; [labels] names apps by package (missing while loading), [nowMs] dates the last
 * analysis. Until the first report arrives (not [InsightsUiState.loaded]) only the header shows, saying it's loading.
 * The ViewModel's held result shows in [snackbar] and is consumed with [InsightsEvent.ResultShown] once it's gone.
 */
@Composable
fun InsightsContent(
    state: InsightsUiState,
    labels: Map<String, AppLabel>,
    nowMs: Long,
    onEvent: (InsightsEvent) -> Unit,
    onOpenAccessSetup: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val spacing = MaterialTheme.spacing
    val twoColumns = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_COLUMN_MIN_WIDTH_DP
    val column = Arrangement.spacedBy(spacing.sm)
    // Analysis and feedback failures already stand as the error notice on screen: consumed without a snackbar.
    ResultSnackbar(snackbar, state.apply.lastResult, onEvent, silentCodes = silentResultCodes(state.error))
    val busy = state.apply.working
    val open: (String) -> Unit = { key -> onEvent(InsightsEvent.OpenFinding(key)) }
    val apply: (String, RecommendationState) -> Unit = { key, rec -> onEvent(InsightsEvent.RequestApply(key, rec.action)) }

    val notices: @Composable () -> Unit = {
        when (val code = state.error) {
            null -> Unit
            InsightMessageCode.ANALYSIS_FAILED -> Notice(
                message = stringResource(R.string.insights_error_body),
                title = stringResource(R.string.insights_error_title),
                framed = true,
            ) {
                TextButton(onClick = { onEvent(InsightsEvent.AnalyzeNow) }, enabled = !state.analyzing) {
                    Text(stringResource(R.string.insights_try_again))
                }
            }
            else -> Notice(message = stringResource(code.messageRes()), framed = true)
        }
        if (!state.privileged) {
            Notice(
                message = stringResource(R.string.insights_access_body),
                title = stringResource(R.string.insights_access_title),
                tone = NoticeTone.INFO,
                framed = true,
            ) {
                TextButton(onClick = onOpenAccessSetup) { Text(stringResource(R.string.insights_access_set_up)) }
            }
        }
        if (state.lowData && state.hasContent) {
            Notice(
                message = learningBody(state),
                title = stringResource(R.string.insights_learning_title),
                tone = NoticeTone.INFO,
                icon = Icons.Rounded.HourglassTop,
                framed = true,
            )
        }
    }
    val findings: @Composable () -> Unit = {
        state.headline?.let { headline ->
            HeadlinePanel(headline, labels, busy, onOpen = { open(headline.key) }, onApply = { apply(headline.key, it) })
        }
        val listed = state.listedFindings()
        if (listed.isNotEmpty()) KeyFindingsPanel(listed, labels, busy, onOpen = open, onApply = apply)
    }
    val history: @Composable () -> Unit = {
        if (state.changes.isNotEmpty()) ChangesPanel(state.changes, labels, onOpen = open)
        if (state.appliedActions.isNotEmpty()) {
            AppliedFixesPanel(state.appliedActions, labels, nowMs, busy, onUndo = { onEvent(InsightsEvent.Undo(it)) })
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .verticalScroll(rememberScrollState())
                .padding(bottom = spacing.md),
        ) {
            InsightsHeader(
                state.analyzing,
                state.lastAnalyzedAt,
                nowMs,
                onAnalyze = { onEvent(InsightsEvent.AnalyzeNow) },
                loaded = state.loaded,
            )
            // Before the first report every field is a default: no notices or "still learning" that may not be true.
            if (state.loaded) Column(Modifier.fillMaxWidth().padding(horizontal = spacing.md), verticalArrangement = column) {
                notices()
                when (state.body()) {
                    InsightsBody.FINDINGS -> if (twoColumns) {
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            Column(Modifier.weight(1f), verticalArrangement = column) { findings() }
                            Column(Modifier.weight(1f), verticalArrangement = column) { history() }
                        }
                    } else {
                        findings()
                        history()
                    }
                    InsightsBody.ERROR -> Unit
                    InsightsBody.LEARNING -> EmptyState(
                        title = stringResource(R.string.insights_learning_title),
                        body = learningBody(state),
                        icon = Icons.Rounded.HourglassTop,
                    )
                    InsightsBody.NEVER_ANALYZED -> EmptyState(
                        title = stringResource(R.string.insights_never_title),
                        body = stringResource(R.string.insights_never_body),
                        icon = Icons.Rounded.Insights,
                        actionLabel = stringResource(R.string.insights_analyze).takeUnless { state.analyzing },
                        onAction = { onEvent(InsightsEvent.AnalyzeNow) },
                    )
                    InsightsBody.ALL_GOOD -> EmptyState(
                        title = stringResource(R.string.insights_all_good_title),
                        body = stringResource(R.string.insights_all_good_body),
                        icon = Icons.Rounded.CheckCircle,
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    state.apply.pending?.let { pending ->
        val finding = state.findingFor(pending.key)
        val rec = finding?.recommendations?.firstOrNull { it.action == pending.action }
        if (finding != null && rec != null) {
            InsightApplyDialog(
                action = rec.action,
                reversible = rec.reversible,
                requiresPrivilege = rec.requiresPrivilege,
                subjectName = subjectName(finding.subject, labels),
                onConfirm = { onEvent(InsightsEvent.ConfirmApply) },
                onDismiss = { onEvent(InsightsEvent.CancelApply) },
            )
        }
    }
}
