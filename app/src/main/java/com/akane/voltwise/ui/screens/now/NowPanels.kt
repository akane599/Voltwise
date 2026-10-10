package com.akane.voltwise.ui.screens.now

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.akane.voltwise.R
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.measurement.CapacityConfidence
import com.akane.voltwise.ui.components.AppLabelIcon
import com.akane.voltwise.ui.screens.DrainCell
import com.akane.voltwise.ui.components.AppRow
import com.akane.voltwise.ui.components.InfoSheet
import com.akane.voltwise.ui.components.Panel
import com.akane.voltwise.ui.components.QuietText
import com.akane.voltwise.ui.components.StatCell
import com.akane.voltwise.ui.components.displayName
import com.akane.voltwise.ui.components.chart.rememberTimeAxisFormatter
import com.akane.voltwise.ui.format.compactDuration
import com.akane.voltwise.ui.format.currentLocale
import com.akane.voltwise.ui.format.dayAwareTime
import com.akane.voltwise.ui.format.formatNumber
import com.akane.voltwise.ui.format.percentUnit
import com.akane.voltwise.ui.format.mahText
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.HealthState
import com.akane.voltwise.viewmodel.SinceUnplugState
import com.akane.voltwise.viewmodel.TodayState
import com.akane.voltwise.viewmodel.TopAppsState

private const val TODAY_MAH_TEMPLATE = 8_888.0
private const val TODAY_PERCENT_TEMPLATE = 888.0

/**
 * The on-battery window from one DISCHARGE session row: screen on and screen off as %/h (mA and duration below) and
 * deep sleep. The open session reads "Since unplug · 9:12 AM" with a confirmed Reset; while plugged in (or not
 * monitoring) the newest closed one reads "Last on battery · 6:10–9:20 AM". Times carry a date when not today.
 */
@Composable
internal fun SinceUnplugPanel(
    state: SinceUnplugState?,
    monitoring: Boolean,
    nowMs: Long,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberTimeAxisFormatter()
    val title = when {
        state == null -> stringResource(R.string.now_since_title)
        state.current -> stringResource(R.string.now_since_title_at, dayAwareTime(formatter, state.startedAtMs, nowMs))
        else -> stringResource(
            R.string.now_since_title_last,
            dayAwareTime(formatter, state.startedAtMs, nowMs),
            dayAwareTime(formatter, state.endedAtMs, state.startedAtMs),
        )
    }
    Panel(
        modifier,
        title = title,
        trailing = { InfoSheet(stringResource(R.string.now_since_info_title), stringResource(R.string.now_since_info_body)) },
    ) {
        if (state == null) {
            QuietText(stringResource(if (monitoring) R.string.now_since_empty_monitoring else R.string.now_since_empty))
            return@Panel
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            DrainCell(stringResource(R.string.now_screen_on), state.screenOn, Modifier.weight(1f))
            DrainCell(stringResource(R.string.now_screen_off), state.screenOff, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatCell(
                stringResource(R.string.now_deep_sleep),
                state.deepSleepPercent?.let { formatNumber(it, 0, currentLocale()) } ?: stringResource(R.string.component_no_value),
                Modifier.weight(1f),
                unit = percentUnit().sign,
                unitFirst = percentUnit().first,
            )
            // Reset starts a new window; it has nothing to do with a window that already ended.
            if (state.current) TextButton(onClick = onReset) { Text(stringResource(R.string.now_reset)) }
        }
    }
}

/** Today's row from the daily summary; the whole panel opens History. */
@Composable
internal fun TodayPanel(today: TodayState?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier, title = stringResource(R.string.now_today_title), trailing = { Chevron() }, onClick = onOpen) {
        if (today == null) {
            QuietText(stringResource(R.string.now_today_empty))
            return@Panel
        }
        val locale = currentLocale()
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            TodayChargeCell(stringResource(R.string.now_today_used), today.usedMah, today.usedPercent, Modifier.weight(1f))
            TodayChargeCell(stringResource(R.string.now_today_charged), today.chargedMah, today.chargedPercent, Modifier.weight(1f))
            val screenOn = compactDuration(today.screenOnMs, locale)
            StatCell(stringResource(R.string.now_today_screen_on), screenOn.value, Modifier.weight(1f), unit = stringResource(screenOn.unit))
        }
    }
}

/**
 * Used or charged today: % of the battery with the mAh underneath when the capacity is known, else mAh; a dash when
 * nothing was measured. One template per unit, so both cells share a size (also at large font).
 */
@Composable
private fun TodayChargeCell(label: String, mah: Double?, percent: Double?, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    when {
        mah == null -> StatCell(label, stringResource(R.string.component_no_value), modifier)
        percent != null -> StatCell(
            label,
            formatNumber(percent, 0, locale),
            modifier,
            unit = percentUnit().sign,
            unitFirst = percentUnit().first,
            supporting = mahText(mah),
            sizingTemplate = formatNumber(TODAY_PERCENT_TEMPLATE, 0, locale),
        )
        else -> StatCell(
            label,
            formatNumber(mah, 0, locale),
            modifier,
            unit = stringResource(R.string.now_unit_mah),
            sizingTemplate = formatNumber(TODAY_MAH_TEMPLATE, 0, locale),
        )
    }
}

/** The combined capacity estimate, its confidence and (with a design capacity set) health; opens Health. */
@Composable
internal fun HealthPanel(health: HealthState?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier, title = stringResource(R.string.now_health_title), trailing = { Chevron() }, onClick = onOpen) {
        if (health == null) {
            QuietText(stringResource(R.string.now_health_empty))
            return@Panel
        }
        val locale = currentLocale()
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            StatCell(
                stringResource(R.string.now_health_capacity),
                formatNumber(health.capacityMah.toDouble(), 0, locale),
                Modifier.weight(1f),
                unit = stringResource(R.string.now_unit_mah),
                supporting = stringResource(confidenceLabel(health.confidence)),
            )
            health.healthPercent?.let { percent ->
                StatCell(
                    stringResource(R.string.now_health_of_design),
                    formatNumber(percent, 0, locale),
                    Modifier.weight(1f),
                    unit = percentUnit().sign,
                    unitFirst = percentUnit().first,
                )
            }
        }
    }
}

private fun confidenceLabel(confidence: CapacityConfidence): Int = when (confidence) {
    CapacityConfidence.LOW -> R.string.now_health_confidence_low
    CapacityConfidence.MEDIUM -> R.string.now_health_confidence_medium
    CapacityConfidence.HIGH -> R.string.now_health_confidence_high
}

/**
 * The top apps from the last cached dump (never a new one): icon · label · mAh · share, each opening its details.
 * With no data, a quiet line and a link to Apps, which fetches on demand.
 */
@Composable
internal fun TopAppsPanel(
    apps: TopAppsState,
    nowMs: Long,
    onOpenApps: () -> Unit,
    onOpenApp: (uid: Int, packageName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Panel(
        modifier,
        title = stringResource(R.string.now_apps_title),
        trailing = if (apps is TopAppsState.Ready) {
            { TextButton(onClick = onOpenApps) { Text(stringResource(R.string.now_apps_all)) } }
        } else null,
        contentPadding = PaddingValues(vertical = spacing.md),
    ) {
        when (apps) {
            TopAppsState.Empty -> Column(Modifier.padding(horizontal = spacing.md)) {
                QuietText(stringResource(R.string.now_apps_empty))
                TextButton(onClick = onOpenApps) { Text(stringResource(R.string.now_apps_open)) }
            }
            is TopAppsState.Ready -> {
                val formatter = rememberTimeAxisFormatter()
                QuietText(
                    // The cache can be days old: then the time carries its date.
                    stringResource(basisLabel(apps.basis), dayAwareTime(formatter, apps.capturedAtMs, nowMs)),
                    Modifier.padding(horizontal = spacing.md),
                )
                apps.rows.forEach { app ->
                    val label = app.label.displayName()
                    AppRow(
                        icon = { AppLabelIcon(app.packageName, app.label) },
                        label = label,
                        value = mahText(app.powerMah),
                        share = app.share,
                        onClick = { onOpenApp(app.uid, app.packageName) },
                    )
                }
            }
        }
    }
}

private fun basisLabel(basis: AppUsageBasis): Int = when (basis) {
    AppUsageBasis.DELTA -> R.string.now_apps_basis_delta
    AppUsageBasis.WINDOW_RESET -> R.string.now_apps_basis_reset
    AppUsageBasis.ABSOLUTE -> R.string.now_apps_basis_absolute
}

/** A navigation hint for a tappable panel, sized like a touch target so it lines up with the gutter. */
@Composable
internal fun Chevron() {
    Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(MaterialTheme.spacing.lg),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
