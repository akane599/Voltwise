package com.akane.voltwise.ui.screens.now

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import com.akane.voltwise.R
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.ui.components.Panel
import com.akane.voltwise.ui.components.QuietText
import com.akane.voltwise.ui.components.displayName
import com.akane.voltwise.ui.screens.insights.SeverityChip
import com.akane.voltwise.ui.screens.insights.labelRes
import com.akane.voltwise.ui.screens.insights.titleRes
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.InsightHeadline
import com.akane.voltwise.viewmodel.InsightsSummary
import com.akane.voltwise.viewmodel.NowUiState

/**
 * Whether Now shows the Insights card: always once something was found, otherwise only after a first session on
 * battery (before that there is nothing to analyse, and "all good" or "not analysed yet" would mean nothing).
 */
internal val NowUiState.showsInsights: Boolean
    get() = (insightsSummary?.activeFindingCount ?: 0) > 0 || sinceUnplug != null

/**
 * The analysis at a glance; the whole panel opens Insights. With concerns: the headline finding (severity, app and
 * what was found; it opens that finding's details) over the number of active findings. With only informational
 * changes (trends Insights lists under Changes): "no concerns" and how many to review. Otherwise "still learning"
 * while too few sessions have app data to compare, then "all good", or an invitation before the first analysis.
 *
 * @param headlineApp the headline app's label; null for a device finding or while it loads (the line then names
 *   only what was found).
 */
@Composable
internal fun InsightsPanel(
    summary: InsightsSummary?,
    headlineApp: AppLabel?,
    onOpenInsights: () -> Unit,
    onOpenFinding: (key: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier, title = stringResource(R.string.now_insights_title), trailing = { Chevron() }, onClick = onOpenInsights) {
        when {
            summary == null -> QuietText(stringResource(R.string.now_insights_never))
            summary.learning -> QuietText(stringResource(R.string.now_insights_learning))
            summary.allGood -> QuietText(stringResource(R.string.now_insights_all_good))
            // No concerns, but changes Insights lists: a neutral pointer to them, never "all good".
            summary.activeFindingCount == 0 -> QuietText(
                pluralStringResource(R.plurals.now_insights_changes, summary.changeCount, summary.changeCount),
            )
            else -> {
                summary.headline?.let { headline ->
                    InsightHeadlineRow(headline, headlineApp, onOpen = { onOpenFinding(headline.key) })
                }
                Text(
                    pluralStringResource(R.plurals.now_insights_findings, summary.activeFindingCount, summary.activeFindingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Its own tap target inside the panel, read as "Severity: High. Chrome · Draining more than usual". */
@Composable
private fun InsightHeadlineRow(headline: InsightHeadline, app: AppLabel?, onOpen: () -> Unit) {
    val title = stringResource(headline.type.titleRes())
    val text = if (headline.packageName != null && app != null) {
        stringResource(R.string.now_insights_headline_app, app.displayName(), title)
    } else {
        title
    }
    val description = stringResource(
        R.string.now_insights_headline_description,
        stringResource(R.string.insights_severity_description, stringResource(headline.severity.labelRes())),
        text,
    )
    val spacing = MaterialTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = stringResource(R.string.now_insights_open_finding), role = Role.Button, onClick = onOpen)
            // Keeps the click action above; replaces the chip's and the text's own reading with one sentence.
            .clearAndSetSemantics { contentDescription = description }
            .padding(start = spacing.sm, top = spacing.xxs, bottom = spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        SeverityChip(headline.severity)
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Chevron()
    }
}
