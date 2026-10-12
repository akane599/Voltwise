package com.akane.voltwise.ui.screens.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.akane.voltwise.R
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.ui.theme.spacing

/**
 * The one confirmation before any fix runs (Insights here, Finding details too): exactly what changes, its side
 * effects, and whether it can be undone. Nothing runs until [onConfirm]; the confirm button repeats the action's
 * name so the outcome is clear.
 *
 * @param subjectName the app (or "This phone") the fix applies to; null while an app label is still loading.
 */
@Composable
fun InsightApplyDialog(
    action: ActionType,
    reversible: Boolean,
    requiresPrivilege: Boolean,
    subjectName: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(action.presentation().labelRes)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.insights_apply_title, label)) },
        text = { InsightApplyDetails(action, reversible, requiresPrivilege, subjectName) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(label) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.insights_cancel)) } },
    )
}

/** The dialog's body: subject, effect, side effects, how it's applied, and whether it can be undone. Scrolls. */
@Composable
fun InsightApplyDetails(
    action: ActionType,
    reversible: Boolean,
    requiresPrivilege: Boolean,
    subjectName: String?,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val presentation = action.presentation()
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        subjectName?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface) }
        Text(stringResource(presentation.effectRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                stringResource(R.string.insights_apply_side_effects),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(presentation.sideEffectsRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (requiresPrivilege) {
            Text(
                stringResource(R.string.insights_apply_privileged),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        reversibility(action, reversible)?.let { (icon, text) ->
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                    tint = if (reversible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * How the change can be taken back: opening a settings page changes nothing; the alert is switched off in
 * Voltwise's settings (Insights records it as done once, without Undo); journaled fixes undo under Applied fixes.
 */
private fun reversibility(action: ActionType, reversible: Boolean): Pair<ImageVector, Int>? = when {
    action == ActionType.OPEN_APP_SETTINGS || action == ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS -> null
    action == ActionType.ENABLE_HIGH_BATTERY_ALERT -> Icons.Rounded.Notifications to R.string.insights_apply_alert_reversible
    reversible -> Icons.AutoMirrored.Rounded.Undo to R.string.insights_apply_reversible
    else -> Icons.Rounded.Block to R.string.insights_apply_irreversible
}
