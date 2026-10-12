package com.akane.voltwise.ui.screens.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.akane.voltwise.R
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.ui.components.AppIcon
import com.akane.voltwise.ui.components.AppIconDefaults
import com.akane.voltwise.ui.components.AppIconPlaceholder
import com.akane.voltwise.ui.components.AppLabelIcon
import com.akane.voltwise.ui.components.Panel
import com.akane.voltwise.ui.components.chart.rememberTimeAxisFormatter
import com.akane.voltwise.ui.format.dayAwareTime
import com.akane.voltwise.ui.theme.numericBody
import com.akane.voltwise.ui.theme.numericHeadline
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.AppliedInsightAction
import com.akane.voltwise.viewmodel.InsightFindingState
import com.akane.voltwise.viewmodel.RecommendationState

/**
 * Title and Analyze now (wrapping under the title at large font sizes), the last analysis time ("Loading insights…"
 * until [loaded]), progress while busy.
 */
@Composable
internal fun InsightsHeader(
    analyzing: Boolean,
    lastAnalyzedAt: Long?,
    nowMs: Long,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier,
    loaded: Boolean = true,
) {
    val spacing = MaterialTheme.spacing
    val formatter = rememberTimeAxisFormatter()
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = spacing.md, end = spacing.md, top = spacing.xs, bottom = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                stringResource(R.string.insights_title),
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = spacing.sm)
                    .semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FilledTonalButton(onClick = onAnalyze, enabled = !analyzing, modifier = Modifier.align(Alignment.CenterVertically)) {
                Icon(Icons.Rounded.QueryStats, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(if (analyzing) R.string.insights_analyzing else R.string.insights_analyze))
            }
        }
        Text(
            when {
                !loaded -> stringResource(R.string.insights_loading)
                lastAnalyzedAt != null -> stringResource(R.string.insights_last_analyzed, dayAwareTime(formatter, lastAnalyzedAt, nowMs))
                else -> stringResource(R.string.insights_never_analyzed)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (analyzing) {
            val description = stringResource(R.string.insights_analyzing_description)
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.xs)
                    .semantics { contentDescription = description },
            )
        }
    }
}

/**
 * The top finding as the page's hero: severity and confidence, who (app or this phone) and what, how far from usual
 * as one large figure, then Details and its primary fix.
 */
@Composable
internal fun HeadlinePanel(
    finding: InsightFindingState,
    labels: Map<String, AppLabel>,
    busy: Boolean,
    onOpen: () -> Unit,
    onApply: (RecommendationState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val evidence = finding.evidence.firstOrNull()
    Panel(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            SeverityChip(finding.severity)
            val confidence = stringResource(finding.confidence.labelRes())
            Text(
                if (evidence != null && evidence.sessions > 0) {
                    pluralStringResource(R.plurals.insights_confidence_sessions, evidence.sessions, confidence, evidence.sessions)
                } else {
                    confidence
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            SubjectIcon(finding.subject, labels)
            Column(Modifier.weight(1f)) {
                SubjectNameText(subjectName(finding.subject, labels), MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(finding.type.titleRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        evidence?.let { HeadlineFigure(it) }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.CenterVertically)) {
                Text(stringResource(R.string.insights_details))
            }
            RecommendationControl(finding, subjectName(finding.subject, labels), busy, onApply, Modifier.align(Alignment.CenterVertically))
        }
    }
}

/** "3.2×" over "Drain vs. usual"; below usual or without a baseline, the value itself. */
@Composable
private fun HeadlineFigure(evidence: Evidence) {
    val metric = stringResource(evidence.metric.labelRes())
    val (figure, caption) = when (val comparison = evidence.comparison()) {
        is EvidenceComparison.Ratio -> ratioText(comparison.times) to stringResource(R.string.insights_headline_vs_usual, metric)
        EvidenceComparison.BelowUsual -> metricValueText(evidence.observed, evidence.unit) to stringResource(
            R.string.insights_headline_usually,
            metric,
            metricValueText(evidence.baseline ?: 0.0, evidence.unit),
        )
        EvidenceComparison.Plain -> metricValueText(evidence.observed, evidence.unit) to metric
    }
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(figure, style = MaterialTheme.typography.numericHeadline, color = MaterialTheme.colorScheme.onSurface)
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every key finding but the headline: one tappable row each (opens its details) with its primary fix under it. */
@Composable
internal fun KeyFindingsPanel(
    findings: List<InsightFindingState>,
    labels: Map<String, AppLabel>,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onApply: (String, RecommendationState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(
        modifier.fillMaxWidth(),
        title = stringResource(R.string.insights_key_findings),
        contentPadding = PaddingValues(top = MaterialTheme.spacing.md, bottom = MaterialTheme.spacing.xs),
    ) {
        findings.forEach { finding ->
            FindingRow(finding, labels, busy, onOpen = { onOpen(finding.key) }, onApply = { onApply(finding.key, it) })
        }
    }
}

@Composable
private fun FindingRow(
    finding: InsightFindingState,
    labels: Map<String, AppLabel>,
    busy: Boolean,
    onOpen: () -> Unit,
    onApply: (RecommendationState) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val openLabel = stringResource(R.string.insights_open_details)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = openLabel, onClick = onOpen)
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            SubjectIcon(finding.subject, labels)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    SubjectNameText(subjectName(finding.subject, labels), MaterialTheme.typography.bodyLarge, Modifier.weight(1f))
                    SeverityChip(finding.severity)
                }
                Text(
                    stringResource(finding.type.titleRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                finding.evidence.firstOrNull()?.let { evidence ->
                    Text(evidenceLine(evidence), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        RecommendationControl(
            finding,
            subjectName(finding.subject, labels),
            busy,
            onApply,
            Modifier.padding(start = spacing.md + AppIconDefaults.Size + spacing.sm, end = spacing.md, bottom = spacing.xs),
        )
    }
}

/**
 * The finding's primary fix: a button when it can run (announced with [subjectName], the app or phone it acts on,
 * once known), "Fix applied" once it has, "Needs Shizuku or root" otherwise.
 */
@Composable
private fun RecommendationControl(
    finding: InsightFindingState,
    subjectName: String?,
    busy: Boolean,
    onApply: (RecommendationState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rec = finding.primaryRecommendation() ?: return
    when {
        rec.available -> {
            val label = stringResource(rec.action.presentation().labelRes)
            // Read as "Restrict background for Chrome": in a list of findings the label alone doesn't say whose.
            val description = subjectName?.let { stringResource(R.string.insights_apply_description, label, it) }
            FilledTonalButton(
                onClick = { onApply(rec) },
                enabled = !busy,
                modifier = if (description != null) modifier.semantics { contentDescription = description } else modifier,
            ) {
                Text(label)
            }
        }
        rec.alreadyApplied -> Row(
            modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.IconSpacing),
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.insights_fix_applied), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Text(
            stringResource(R.string.insights_needs_access),
            modifier = modifier,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Trends and the capacity decline: up or down, who and which measure, and the old → new median (only the value
 * when there is no baseline, like the yearly capacity change). Each row opens the finding.
 */
@Composable
internal fun ChangesPanel(
    changes: List<InsightFindingState>,
    labels: Map<String, AppLabel>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(
        modifier.fillMaxWidth(),
        title = stringResource(R.string.insights_what_changed),
        contentPadding = PaddingValues(top = MaterialTheme.spacing.md, bottom = MaterialTheme.spacing.xs),
    ) {
        changes.forEach { change -> ChangeRow(change, labels, onOpen = { onOpen(change.key) }) }
    }
}

@Composable
private fun ChangeRow(change: InsightFindingState, labels: Map<String, AppLabel>, onOpen: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val evidence = change.evidence.firstOrNull()
    val name = subjectName(change.subject, labels) ?: stringResource(R.string.component_no_value)
    val title = evidence?.let { stringResource(R.string.insights_joined, name, stringResource(it.metric.labelRes())) } ?: name
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.insights_open_details), onClick = onOpen)
            .padding(horizontal = spacing.md, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Box(
            Modifier
                .size(AppIconDefaults.Size)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val up = change.direction == Direction.UP
            Icon(
                if (up) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                contentDescription = stringResource(if (up) R.string.insights_trend_up else R.string.insights_trend_down),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            evidence?.let {
                val now = metricValueText(it.observed, it.unit)
                Text(
                    it.baseline?.let { old -> stringResource(R.string.insights_change_values, metricValueText(old, it.unit), now) } ?: now,
                    style = MaterialTheme.typography.numericBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Fixes Voltwise applied: what and its status, when, the measured association when there is one, and Undo. */
@Composable
internal fun AppliedFixesPanel(
    actions: List<AppliedInsightAction>,
    labels: Map<String, AppLabel>,
    nowMs: Long,
    busy: Boolean,
    onUndo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(
        modifier.fillMaxWidth(),
        title = stringResource(R.string.insights_applied_fixes),
        contentPadding = PaddingValues(top = MaterialTheme.spacing.md, bottom = MaterialTheme.spacing.xs),
    ) {
        actions.forEach { action -> AppliedFixRow(action, labels, nowMs, busy, onUndo = { onUndo(action.id) }) }
    }
}

@Composable
private fun AppliedFixRow(action: AppliedInsightAction, labels: Map<String, AppLabel>, nowMs: Long, busy: Boolean, onUndo: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val formatter = rememberTimeAxisFormatter()
    val subject = action.packageName?.let { Subject.App(-1, it) } ?: Subject.Device
    val name = subjectName(subject, labels)
    val actionLabel = action.presentation()?.let { stringResource(it.labelRes) }
    val status = stringResource(action.statusLabelRes())
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = spacing.md, end = spacing.xs, top = spacing.xs, bottom = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        SubjectIcon(subject, labels)
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            SubjectNameText(name, MaterialTheme.typography.bodyLarge)
            Text(
                actionLabel?.let { stringResource(R.string.insights_joined, it, status) } ?: status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action.appliedAt?.let { at ->
                Text(
                    stringResource(R.string.insights_applied_at, dayAwareTime(formatter, at, nowMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            action.effect?.evidence?.firstOrNull()?.let { effectLine(it) }?.let { effect ->
                Text(effect, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (action.undoable) {
            val description = stringResource(R.string.insights_undo_description, actionLabel ?: status, name ?: "")
            TextButton(onClick = onUndo, enabled = !busy, modifier = Modifier.semantics { contentDescription = description }) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.insights_undo))
            }
        }
    }
}

/** A small tonal label for a finding's severity, read as "Severity: High". */
@Composable
internal fun SeverityChip(severity: Severity, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (severity) {
        Severity.HIGH -> colors.errorContainer to colors.onErrorContainer
        Severity.MEDIUM -> colors.tertiaryContainer to colors.onTertiaryContainer
        Severity.LOW -> colors.secondaryContainer to colors.onSecondaryContainer
        Severity.INFO -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    val label = stringResource(severity.labelRes())
    val description = stringResource(R.string.insights_severity_description, label)
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        shape = MaterialTheme.shapes.extraSmall,
        color = container,
        contentColor = content,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.xs, vertical = MaterialTheme.spacing.xxs),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** The app's icon (its monogram until the label is known), or a phone glyph for a device finding. Decorative. */
@Composable
internal fun SubjectIcon(subject: Subject, labels: Map<String, AppLabel>, modifier: Modifier = Modifier) {
    when (subject) {
        Subject.Device -> AppIconPlaceholder(stringResource(R.string.insights_subject_device), modifier, icon = Icons.Rounded.PhoneAndroid)
        is Subject.App -> labels[subject.packageName]?.let { AppLabelIcon(subject.packageName, it, modifier) }
            ?: AppIcon(subject.packageName, "", modifier)
    }
}

/** The subject's name, or a quiet dash (silent to TalkBack) while an app's label loads. */
@Composable
private fun SubjectNameText(name: String?, style: TextStyle, modifier: Modifier = Modifier) {
    Text(
        name ?: stringResource(R.string.component_no_value),
        modifier = if (name == null) modifier.clearAndSetSemantics {} else modifier,
        style = style,
        color = if (name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
