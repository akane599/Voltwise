package com.akane.voltwise.ui.screens.insights

import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.os.bundleOf
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.akane.voltwise.R
import com.akane.voltwise.battery.apps.AppInfoSource
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.Attribution
import com.akane.voltwise.battery.insights.model.AttributionKind
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.ui.components.DetailTopBar
import com.akane.voltwise.ui.components.EmptyState
import com.akane.voltwise.ui.components.Notice
import com.akane.voltwise.ui.components.NoticeTone
import com.akane.voltwise.ui.components.Panel
import com.akane.voltwise.ui.components.QuietText
import com.akane.voltwise.ui.theme.numericBody
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.FindingDetailsUiState
import com.akane.voltwise.viewmodel.FindingDetailsViewModel
import com.akane.voltwise.viewmodel.InsightFindingState
import com.akane.voltwise.viewmodel.InsightMessageCode
import com.akane.voltwise.viewmodel.InsightUiEffect
import com.akane.voltwise.viewmodel.InsightsEvent
import com.akane.voltwise.viewmodel.RecommendationState
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** Two columns from this window width (the Material "expanded" breakpoint): the finding start, what to do end. */
private const val TWO_COLUMN_MIN_WIDTH_DP = 840

/** The SavedStateHandle entry [FindingDetailsViewModel] reads the finding key from. */
private const val FINDING_KEY_ARG = "key"

/**
 * Finding details, wired: the Koin [FindingDetailsViewModel] for [findingKey], app labels from [AppInfoSource], and
 * the ViewModel's one-shot effects (Android settings pages; result snackbars come from its state). After Not a problem or Dismiss the
 * screen leaves once the finding drops out of the report; a failed write keeps it here with a message.
 */
@Composable
fun FindingDetailsScreen(
    findingKey: String,
    onBack: () -> Unit,
    onOpenAccessSetup: () -> Unit,
    modifier: Modifier = Modifier,
    vm: FindingDetailsViewModel = koinViewModel(extras = findingKeyExtras(findingKey)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appInfo: AppInfoSource = koinInject()
    val back by rememberUpdatedState(onBack)
    var leaving by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val settingsUnavailable by rememberUpdatedState(stringResource(R.string.insights_settings_unavailable))
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                // The snackbar follows the ViewModel's held result (state.apply.lastResult), which survives rotation.
                is InsightUiEffect.Message -> if (effect.result.code == InsightMessageCode.FEEDBACK_FAILED) leaving = false
                is InsightUiEffect.OpenSettings -> if (!openSettingsPage(context, effect.spec)) {
                    launch { snackbar.showSnackbar(settingsUnavailable) }
                }
                is InsightUiEffect.OpenFinding -> Unit
            }
        }
    }
    // Before the first report the finding is absent only because nothing has loaded yet: not a reason to leave.
    val gone = state.loaded && state.finding == null
    LaunchedEffect(leaving, gone) {
        if (leaving && gone) back()
    }
    val subjects = remember(state.finding, state.relatedActions) { appSubjects(state) }
    val labels by produceState(emptyMap<String, AppLabel>(), subjects, appInfo) {
        value = subjects.mapValues { (packageName, uid) -> AppLabel.of(uid, packageName, appInfo.infoOrNull(packageName)) }
    }
    FindingDetailsContent(
        state = state,
        labels = labels,
        nowMs = remember(state.relatedActions) { System.currentTimeMillis() },
        onEvent = { event ->
            if (event is InsightsEvent.Dismiss || event is InsightsEvent.NotAProblem) leaving = true
            vm.onEvent(event)
        },
        onBack = onBack,
        onOpenAccessSetup = onOpenAccessSetup,
        modifier = modifier,
        snackbar = snackbar,
    )
}

/** The nav entry's default extras plus the finding key, which the ViewModel's SavedStateHandle starts from. */
@Composable
private fun findingKeyExtras(key: String): CreationExtras {
    val owner = LocalViewModelStoreOwner.current
    return remember(owner, key) {
        val defaults = (owner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty
        MutableCreationExtras(defaults).apply { set(DEFAULT_ARGS_KEY, bundleOf(FINDING_KEY_ARG to key)) }
    }
}

/** Package → uid of every app the screen names (a fix row knows no uid: -1). */
private fun appSubjects(state: FindingDetailsUiState): Map<String, Int> {
    val apps = LinkedHashMap<String, Int>()
    (state.finding?.subject as? Subject.App)?.let { apps[it.packageName] = it.uid }
    state.relatedActions.forEach { action -> action.packageName?.let { apps.putIfAbsent(it, -1) } }
    state.finding?.attributions?.forEach { attribution -> attribution.packageName?.let { apps.putIfAbsent(it, -1) } }
    return apps
}

/** Opening a settings page changes nothing by itself, so it skips the confirmation and goes straight there. */
internal val ActionType.opensSettings: Boolean
    get() = this == ActionType.OPEN_APP_SETTINGS || this == ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS

/**
 * Finding details, stateless: [state] in, [onEvent] out. The subject and what was found, a plain-language
 * explanation (what we saw, why it matters, what it isn't), the evidence against usual, the readings over time
 * against the usual range, possible causes (device findings), then what to do: each fix with its effect (privileged
 * ones only through the confirmation dialog; Force stop marked as permanent), settings pages, Not a problem and
 * Dismiss, and fixes already applied with Undo and their measured association. From 840 dp the finding sits on the
 * start and what to do on the end. [labels] names apps by package (missing while loading). Until the first report
 * arrives (not [FindingDetailsUiState.loaded]) it says it's loading; "finding gone" is only for a loaded report
 * without it. The ViewModel's held result shows in [snackbar], consumed with [InsightsEvent.ResultShown].
 */
@Composable
fun FindingDetailsContent(
    state: FindingDetailsUiState,
    labels: Map<String, AppLabel>,
    nowMs: Long,
    onEvent: (InsightsEvent) -> Unit,
    onBack: () -> Unit,
    onOpenAccessSetup: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val spacing = MaterialTheme.spacing
    val twoColumns = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_COLUMN_MIN_WIDTH_DP
    val column = Arrangement.spacedBy(spacing.sm)
    ResultSnackbar(snackbar, state.apply.lastResult, onEvent)
    val finding = state.finding

    Scaffold(
        modifier = modifier,
        topBar = { DetailTopBar(title = null, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = spacing.md, end = spacing.md, bottom = spacing.md),
            verticalArrangement = column,
        ) {
            if (!state.loaded) {
                QuietText(stringResource(R.string.finding_loading), Modifier.padding(top = spacing.xs))
                return@Column
            }
            if (finding == null) {
                EmptyState(
                    title = stringResource(R.string.finding_gone_title),
                    body = stringResource(R.string.finding_gone_body),
                    icon = Icons.Rounded.Insights,
                )
                return@Column
            }
            val busy = state.apply.working
            val primary: @Composable () -> Unit = {
                FindingHero(finding, labels)
                // Up front, like Insights' notices: why some fixes below can't run, and how to allow them.
                if (!state.privileged && finding.recommendations.any { it.requiresPrivilege }) {
                    Notice(
                        message = stringResource(R.string.insights_access_body),
                        title = stringResource(R.string.insights_access_title),
                        tone = NoticeTone.INFO,
                        framed = true,
                    ) {
                        TextButton(onClick = onOpenAccessSetup) { Text(stringResource(R.string.insights_access_set_up)) }
                    }
                }
                FindingExplanation(finding.type, Modifier.fillMaxWidth())
                if (finding.evidence.isNotEmpty()) EvidencePanel(finding.type, finding.subject, finding.evidence, Modifier.fillMaxWidth())
                val measure = chartMeasure(finding.type, finding.evidence)
                if (finding.series.isNotEmpty() && measure != null) {
                    Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.finding_over_time)) {
                        FindingChart(finding.series, measure.metric.unit, stringResource(measure.metric.labelRes()), measure.usual)
                    }
                }
            }
            val secondary: @Composable () -> Unit = {
                if (finding.attributions.isNotEmpty()) CausesPanel(finding.attributions, labels, Modifier.fillMaxWidth())
                FixesPanel(
                    finding = finding,
                    busy = busy,
                    onRun = { rec ->
                        onEvent(InsightsEvent.RequestApply(finding.key, rec.action))
                        // Nothing changes on a settings page until the user changes it there: no dialog.
                        if (rec.action.opensSettings) onEvent(InsightsEvent.ConfirmApply)
                    },
                    onNotAProblem = { onEvent(InsightsEvent.NotAProblem(finding.key)) },
                    onDismiss = { onEvent(InsightsEvent.Dismiss(finding.key)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.relatedActions.isNotEmpty()) {
                    AppliedFixesPanel(state.relatedActions, labels, nowMs, busy, onUndo = { onEvent(InsightsEvent.Undo(it)) })
                }
            }
            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Column(Modifier.weight(1f), verticalArrangement = column) { primary() }
                    Column(Modifier.weight(1f), verticalArrangement = column) { secondary() }
                }
            } else {
                primary()
                secondary()
            }
        }
    }

    state.apply.pending?.takeIf { finding != null && it.key == finding.key }?.let { pending ->
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

/** Severity and confidence, then who (app or this phone) and what was found. */
@Composable
private fun FindingHero(finding: InsightFindingState, labels: Map<String, AppLabel>) {
    val spacing = MaterialTheme.spacing
    Panel(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            SubjectIcon(finding.subject, labels)
            Column(Modifier.weight(1f)) {
                Text(
                    subjectName(finding.subject, labels) ?: stringResource(R.string.component_no_value),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(finding.type.titleRes()),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SeverityChip(finding.severity)
            Text(
                stringResource(finding.confidence.labelRes()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What we saw, why it matters, and what it isn't: plain language per [FindingType]. */
@Composable
private fun FindingExplanation(type: FindingType, modifier: Modifier = Modifier) {
    Panel(modifier) {
        ExplanationPart(stringResource(R.string.finding_what_we_saw), stringResource(type.seenRes()))
        ExplanationPart(stringResource(R.string.finding_why_it_matters), stringResource(type.mattersRes()))
        ExplanationPart(stringResource(R.string.finding_what_it_is_not), stringResource(type.notRes()))
    }
}

@Composable
private fun ExplanationPart(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Each measure: what it was against usual, and how many sessions (or days) back it. */
@Composable
private fun EvidencePanel(type: FindingType, subject: Subject, evidence: List<Evidence>, modifier: Modifier = Modifier) {
    Panel(modifier, title = stringResource(R.string.finding_evidence)) {
        evidence.forEachIndexed { i, item ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            EvidenceRow(type, subject, item)
        }
    }
}

/** What [Evidence.sessions] counts: a device trend is built from daily points, so its count is days. */
@PluralsRes
internal fun evidenceCountRes(type: FindingType, subject: Subject): Int =
    if (type == FindingType.TREND && subject == Subject.Device) R.plurals.finding_days else R.plurals.finding_sessions

@Composable
private fun EvidenceRow(type: FindingType, subject: Subject, evidence: Evidence) {
    val spacing = MaterialTheme.spacing
    val joined = stringResource(R.string.insights_joined)
    val details = buildList {
        if (type == FindingType.ACTION_EFFECT) {
            effectLine(evidence)?.let(::add)
        } else {
            evidence.baseline?.takeIf { it.isFinite() }?.let { add(stringResource(R.string.finding_usually, metricValueText(it, evidence.unit))) }
        }
        if (evidence.sessions > 0) add(pluralStringResource(evidenceCountRes(type, subject), evidence.sessions, evidence.sessions))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                stringResource(evidence.metric.labelRes()),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                metricValueText(evidence.observed, evidence.unit),
                style = MaterialTheme.typography.numericBody,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (details.isNotEmpty()) {
            Text(
                details.reduce { first, second -> joined.format(first, second) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Named wakers or apps the engine associates with a device finding: hints from the measured session, never proof. */
@Composable
private fun CausesPanel(attributions: List<Attribution>, labels: Map<String, AppLabel>, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    Panel(modifier, title = stringResource(R.string.finding_causes)) {
        Text(
            stringResource(R.string.finding_causes_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        attributions.forEach { attribution ->
            val name = attribution.packageName?.let { pkg -> subjectName(Subject.App(-1, pkg), labels) } ?: attribution.name
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.xxs)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(attribution.kind.labelRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    metricValueText(attribution.value, attribution.unit),
                    style = MaterialTheme.typography.numericBody,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Every fix the finding offers, each with exactly what it changes and a button naming it; permanent ones say so.
 * A privileged fix without access says what it needs; an applied one says so (Undo lives under Applied fixes).
 * Then the two kinds of feedback, with what each does.
 */
@Composable
private fun FixesPanel(
    finding: InsightFindingState,
    busy: Boolean,
    onRun: (RecommendationState) -> Unit,
    onNotAProblem: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Panel(modifier, title = stringResource(R.string.finding_what_you_can_do)) {
        finding.recommendations.forEach { rec ->
            FixRow(rec, busy, onRun = { onRun(rec) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Text(
            stringResource(R.string.finding_feedback_hint),
            modifier = Modifier.padding(top = spacing.xs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            OutlinedButton(onClick = onNotAProblem) { Text(stringResource(R.string.finding_not_a_problem)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.finding_dismiss)) }
        }
    }
}

@Composable
private fun FixRow(rec: RecommendationState, busy: Boolean, onRun: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val presentation = rec.action.presentation()
    val label = stringResource(presentation.labelRes)
    Column(Modifier.fillMaxWidth().padding(vertical = spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            stringResource(if (rec.action.opensSettings) R.string.finding_opens_settings else presentation.effectRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!rec.reversible && rec.requiresPrivilege) {
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.IconSpacing),
            ) {
                Icon(Icons.Rounded.Block, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize), tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.finding_irreversible), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            when {
                rec.available && rec.action.opensSettings -> OutlinedButton(onClick = onRun, enabled = !busy) { Text(label) }
                rec.available -> FilledTonalButton(onClick = onRun, enabled = !busy) { Text(label) }
                rec.alreadyApplied -> Row(
                    Modifier.semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.IconSpacing),
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.insights_fix_applied), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Text(
                    stringResource(R.string.finding_needs_access, label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun FindingType.seenRes(): Int = when (this) {
    FindingType.APP_DRAIN_ANOMALY -> R.string.finding_seen_app_drain_anomaly
    FindingType.NEW_HEAVY_APP -> R.string.finding_seen_new_heavy_app
    FindingType.BACKGROUND_RUNAWAY -> R.string.finding_seen_background_runaway
    FindingType.STUCK_WAKELOCK -> R.string.finding_seen_stuck_wakelock
    FindingType.WAKEUP_STORM -> R.string.finding_seen_wakeup_storm
    FindingType.JOB_STORM -> R.string.finding_seen_job_storm
    FindingType.DOZE_BLOCKED -> R.string.finding_seen_doze_blocked
    FindingType.DOZE_WHITELISTED_DRAINER -> R.string.finding_seen_doze_whitelisted_drainer
    FindingType.SCREEN_OFF_DRAIN_HIGH -> R.string.finding_seen_screen_off_drain_high
    FindingType.TREND -> R.string.finding_seen_trend
    FindingType.CHARGING_AT_FULL -> R.string.finding_seen_charging_at_full
    FindingType.HOT_CHARGING -> R.string.finding_seen_hot_charging
    FindingType.HEALTH_DECLINE -> R.string.finding_seen_health_decline
    FindingType.BACKGROUND_LOCATION -> R.string.finding_seen_background_location
    FindingType.BACKGROUND_RADIO -> R.string.finding_seen_background_radio
    FindingType.LINGERING_FOREGROUND_SERVICE -> R.string.finding_seen_lingering_foreground_service
    FindingType.ACTION_EFFECT -> R.string.finding_seen_action_effect
}

private fun FindingType.mattersRes(): Int = when (this) {
    FindingType.APP_DRAIN_ANOMALY -> R.string.finding_matters_app_drain_anomaly
    FindingType.NEW_HEAVY_APP -> R.string.finding_matters_new_heavy_app
    FindingType.BACKGROUND_RUNAWAY -> R.string.finding_matters_background_runaway
    FindingType.STUCK_WAKELOCK -> R.string.finding_matters_stuck_wakelock
    FindingType.WAKEUP_STORM -> R.string.finding_matters_wakeup_storm
    FindingType.JOB_STORM -> R.string.finding_matters_job_storm
    FindingType.DOZE_BLOCKED -> R.string.finding_matters_doze_blocked
    FindingType.DOZE_WHITELISTED_DRAINER -> R.string.finding_matters_doze_whitelisted_drainer
    FindingType.SCREEN_OFF_DRAIN_HIGH -> R.string.finding_matters_screen_off_drain_high
    FindingType.TREND -> R.string.finding_matters_trend
    FindingType.CHARGING_AT_FULL -> R.string.finding_matters_charging_at_full
    FindingType.HOT_CHARGING -> R.string.finding_matters_hot_charging
    FindingType.HEALTH_DECLINE -> R.string.finding_matters_health_decline
    FindingType.BACKGROUND_LOCATION -> R.string.finding_matters_background_location
    FindingType.BACKGROUND_RADIO -> R.string.finding_matters_background_radio
    FindingType.LINGERING_FOREGROUND_SERVICE -> R.string.finding_matters_lingering_foreground_service
    FindingType.ACTION_EFFECT -> R.string.finding_matters_action_effect
}

/** The caveat: app numbers are activity proxies, device wakers are hints, trends and effects are associations. */
private fun FindingType.notRes(): Int = when (this) {
    FindingType.APP_DRAIN_ANOMALY, FindingType.NEW_HEAVY_APP -> R.string.finding_not_app_usage
    FindingType.BACKGROUND_RUNAWAY, FindingType.STUCK_WAKELOCK, FindingType.WAKEUP_STORM, FindingType.JOB_STORM,
    FindingType.DOZE_WHITELISTED_DRAINER, FindingType.BACKGROUND_LOCATION, FindingType.BACKGROUND_RADIO,
    FindingType.LINGERING_FOREGROUND_SERVICE,
    -> R.string.finding_not_proxy
    FindingType.DOZE_BLOCKED, FindingType.SCREEN_OFF_DRAIN_HIGH -> R.string.finding_not_device
    FindingType.TREND -> R.string.finding_not_trend
    FindingType.CHARGING_AT_FULL, FindingType.HOT_CHARGING -> R.string.finding_not_charging
    FindingType.HEALTH_DECLINE -> R.string.finding_not_health
    FindingType.ACTION_EFFECT -> R.string.finding_not_effect
}

private fun AttributionKind.labelRes(): Int = when (this) {
    AttributionKind.KERNEL_WAKELOCK -> R.string.finding_cause_kernel_wakelock
    AttributionKind.WAKEUP_REASON -> R.string.finding_cause_wakeup_reason
    AttributionKind.APP -> R.string.finding_cause_app
}
