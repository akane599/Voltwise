package com.akane.voltwise.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akane.voltwise.R
import com.akane.voltwise.battery.shizuku.ShizukuBridge
import com.akane.voltwise.ui.components.AppIconDefaults
import com.akane.voltwise.ui.components.AppLabelIcon
import com.akane.voltwise.ui.components.AppRow
import com.akane.voltwise.ui.components.EmptyState
import com.akane.voltwise.ui.components.InfoSheet
import com.akane.voltwise.ui.components.Notice
import com.akane.voltwise.ui.components.NoticeTone
import com.akane.voltwise.ui.components.Panel
import com.akane.voltwise.ui.components.QuietText
import com.akane.voltwise.ui.components.StatCell
import com.akane.voltwise.ui.components.chart.ValueFormatter
import com.akane.voltwise.ui.components.chart.rememberTimeAxisFormatter
import com.akane.voltwise.ui.components.displayName
import com.akane.voltwise.ui.format.CompactDuration
import com.akane.voltwise.ui.format.compactDuration
import com.akane.voltwise.ui.format.currentLocale
import com.akane.voltwise.ui.format.dayAwareTime
import com.akane.voltwise.ui.format.formatNumber
import com.akane.voltwise.ui.format.valueWithUnit
import com.akane.voltwise.ui.format.formatMah
import com.akane.voltwise.ui.theme.batColors
import com.akane.voltwise.ui.theme.chartColors
import com.akane.voltwise.ui.theme.numericBody
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.AccessProblem
import com.akane.voltwise.viewmodel.AppListRow
import com.akane.voltwise.viewmodel.AppSort
import com.akane.voltwise.viewmodel.AppsEvent
import com.akane.voltwise.viewmodel.AppsUiState
import com.akane.voltwise.viewmodel.AppsViewModel
import com.akane.voltwise.viewmodel.ReadProblem
import com.akane.voltwise.viewmodel.StatsProblem
import com.akane.voltwise.viewmodel.StatsSummary
import java.util.Locale
import kotlin.math.pow
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import rikka.shizuku.ShizukuProvider

/** Two panes from this window width (the Material "expanded" breakpoint): overview on the start, the list on the end. */
internal const val TWO_PANE_MIN_WIDTH_DP = 840

/** Name-bar widths of the loading rows, as fractions of the row, so the placeholder list doesn't look stamped. */
private val SKELETON_WIDTHS = listOf(0.62f, 0.45f, 0.7f, 0.38f, 0.55f, 0.66f)

/**
 * Apps, wired: the Koin [AppsViewModel], a read of Android's per-app stats each time the screen starts (within the
 * 60 s cache after a recent one), Shizuku's permission prompt, and navigation out through the lambdas.
 */
@Composable
fun AppsScreen(
    onOpenApp: (uid: Int, packageName: String) -> Unit,
    onOpenAccessSetup: () -> Unit,
    modifier: Modifier = Modifier,
    vm: AppsViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shizuku: ShizukuBridge = koinInject()
    LifecycleStartEffect(vm) {
        vm.onStart()
        onStopOrDispose { vm.onStop() }
    }
    AppsContent(
        state = state,
        query = vm.query,
        onEvent = { event ->
            when (event) {
                is AppsEvent.OpenApp -> onOpenApp(event.uid, event.packageName)
                AppsEvent.OpenAccessSetup -> onOpenAccessSetup()
                AppsEvent.AllowShizuku -> allowShizuku(context, state.problem, shizuku)
                else -> vm.onEvent(event)
            }
        },
        modifier = modifier,
    )
}

/**
 * Apps, stateless: [state] in (with the live search [query]), [onEvent] out. A header, the access banner or read
 * failure when there is one, "Android stats since …", then search, sort chips, the system-apps switch and the rows
 * (icon, name, value, share). Pull to refresh forces a new read. From 840 dp the overview and the list sit side by
 * side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsContent(
    state: AppsUiState,
    query: String,
    onEvent: (AppsEvent) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val spacing = MaterialTheme.spacing
    val twoPanes = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_PANE_MIN_WIDTH_DP
    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = { onEvent(AppsEvent.Refresh) },
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
    ) {
        if (twoPanes) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = spacing.md),
                ) {
                    AppsHeader(state.loading, onRefresh = { onEvent(AppsEvent.Refresh) })
                    AppsOverview(state, onEvent, Modifier.padding(horizontal = spacing.md))
                }
                LazyColumn(
                    Modifier
                        .weight(1.4f)
                        .fillMaxHeight(),
                    state = listState,
                    contentPadding = PaddingValues(top = spacing.xs, bottom = spacing.md),
                ) {
                    appList(state, query, onEvent)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = spacing.md),
            ) {
                item(key = "header") { AppsHeader(state.loading, onRefresh = { onEvent(AppsEvent.Refresh) }) }
                item(key = "overview") { AppsOverview(state, onEvent, Modifier.padding(horizontal = spacing.md)) }
                appList(state, query, onEvent)
            }
        }
    }
}

/** Title and a refresh button (the accessible alternative to pull-to-refresh). */
@Composable
private fun AppsHeader(loading: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = spacing.md, end = spacing.xxs, top = spacing.xs, bottom = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.apps_title),
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onRefresh, enabled = !loading) {
            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.apps_refresh))
        }
    }
}

/** The access banner or read failure (if any) over the summary; a placeholder summary while the first read runs. */
@Composable
private fun AppsOverview(state: AppsUiState, onEvent: (AppsEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        StatsProblemNotice(
            state.problem,
            onSetUp = { onEvent(AppsEvent.OpenAccessSetup) },
            onAllowShizuku = { onEvent(AppsEvent.AllowShizuku) },
            onRetry = { onEvent(AppsEvent.Refresh) },
        )
        val summary = state.summary
        when {
            summary != null -> StatsSummaryPanel(summary, state.nowMs, Modifier.fillMaxWidth())
            state.problem == null -> SummaryPlaceholder(Modifier.fillMaxWidth())
        }
    }
}

/** The list half: controls and rows once a read succeeded; loading rows before; an invitation when there's no access. */
private fun LazyListScope.appList(state: AppsUiState, query: String, onEvent: (AppsEvent) -> Unit) {
    if (state.summary == null) {
        when (state.problem) {
            null -> item(key = "loading") { LoadingRows() }
            is StatsProblem.NoAccess -> item(key = "no-access") {
                EmptyState(
                    title = stringResource(R.string.apps_no_access_title),
                    body = stringResource(R.string.apps_no_access_body),
                    icon = Icons.Rounded.Apps,
                )
            }
            is StatsProblem.Failed -> Unit
        }
        return
    }
    item(key = "controls") { ListControls(state, query, onEvent) }
    if (state.rows.isEmpty()) {
        item(key = "empty") { NoRows(state, query, onEvent) }
    }
    items(state.rows, key = { it.uid }) { row ->
        AppListItem(row, state.sort, onClick = { onEvent(AppsEvent.OpenApp(row.uid, row.packageName)) })
    }
}

@Composable
private fun AppListItem(row: AppListRow, sort: AppSort, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AppRow(
        icon = { AppLabelIcon(row.packageName, row.label) },
        label = row.label.displayName(),
        value = row.value?.let { sortValueText(it, sort) } ?: stringResource(R.string.component_no_value),
        share = row.share,
        modifier = modifier,
        onClick = onClick,
        // Energy in the drain color; activity metrics (time, traffic) in the neutral info color.
        shareColor = if (sort == AppSort.BATTERY) MaterialTheme.chartColors.drain else MaterialTheme.batColors.info,
        // The figures are the whole uid's: the label is only a representative of the packages sharing it.
        supportingText = if (row.sharedBy > 1) pluralStringResource(R.plurals.apps_shared_uid, row.sharedBy, row.sharedBy) else null,
    )
}

/** Search, sort chips, and the row count with the system-apps switch. */
@Composable
private fun ListControls(state: AppsUiState, query: String, onEvent: (AppsEvent) -> Unit, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    Column(modifier.padding(top = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        SearchField(
            query,
            onQueryChange = { onEvent(AppsEvent.SetQuery(it)) },
            modifier = Modifier
                .padding(horizontal = spacing.md)
                .fillMaxWidth(),
        )
        val sortLabel = stringResource(R.string.apps_sort_label)
        LazyRow(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = sortLabel }
                .selectableGroup(),
            contentPadding = PaddingValues(horizontal = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            items(AppSort.entries, key = { it.name }) { sort ->
                FilterChip(
                    selected = sort == state.sort,
                    onClick = { onEvent(AppsEvent.SetSort(sort)) },
                    label = { Text(stringResource(sortLabel(sort))) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    border = null,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = spacing.md, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No count over an empty list: the empty state below says why.
            Text(
                if (state.rows.isEmpty()) "" else pluralStringResource(R.plurals.apps_count, state.rows.size, state.rows.size),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier
                    .minimumInteractiveComponentSize()
                    .toggleable(
                        value = state.showSystem,
                        role = Role.Switch,
                        onValueChange = { onEvent(AppsEvent.SetShowSystem(it)) },
                    )
                    .padding(horizontal = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    stringResource(R.string.apps_system_toggle),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Switch(checked = state.showSystem, onCheckedChange = null)
            }
        }
    }
}

/** A tonal search field: no outline or underline, focus shown by a lighter container and the cursor. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val scheme = MaterialTheme.colorScheme
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.apps_search)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.apps_search_clear))
                }
            }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        shape = MaterialTheme.shapes.small,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceContainerHigh,
            unfocusedContainerColor = scheme.surfaceContainer,
            // The indicator line takes the container's color, so the field has no underline.
            focusedIndicatorColor = scheme.surfaceContainerHigh,
            unfocusedIndicatorColor = scheme.surfaceContainer,
        ),
    )
}

/** Nothing to list: no search match (with a way out), only system apps, or no app use at all. */
@Composable
private fun NoRows(state: AppsUiState, query: String, onEvent: (AppsEvent) -> Unit, modifier: Modifier = Modifier) {
    val searching = query.isNotBlank()
    val showSystem = { onEvent(AppsEvent.SetShowSystem(true)) }
    when {
        searching && state.hiddenSystem > 0 -> EmptyState(
            title = stringResource(R.string.apps_empty_search_title, query.trim()),
            modifier = modifier,
            body = pluralStringResource(R.plurals.apps_empty_search_system, state.hiddenSystem, state.hiddenSystem),
            icon = Icons.Rounded.SearchOff,
            actionLabel = stringResource(R.string.apps_show_system),
            onAction = showSystem,
        )
        searching -> EmptyState(
            title = stringResource(R.string.apps_empty_search_title, query.trim()),
            modifier = modifier,
            body = stringResource(R.string.apps_empty_search_body),
            icon = Icons.Rounded.SearchOff,
            actionLabel = stringResource(R.string.apps_search_clear),
            onAction = { onEvent(AppsEvent.SetQuery("")) },
        )
        state.hiddenSystem > 0 -> EmptyState(
            title = stringResource(R.string.apps_empty_only_system_title),
            modifier = modifier,
            icon = Icons.Rounded.Apps,
            actionLabel = stringResource(R.string.apps_show_system),
            onAction = showSystem,
        )
        else -> EmptyState(
            title = stringResource(R.string.apps_empty_title),
            modifier = modifier,
            body = stringResource(R.string.apps_empty_body),
            icon = Icons.Rounded.Apps,
        )
    }
}

/**
 * "Android stats since …": the stats window's device-wide times from the same dump as the rows — on battery,
 * screen on/off (with the battery share each used), deep and light doze, and Android's capacity estimate.
 */
@Composable
private fun StatsSummaryPanel(summary: StatsSummary, nowMs: Long, modifier: Modifier = Modifier) {
    val formatter = rememberTimeAxisFormatter()
    val locale = currentLocale()
    val title = summary.startedAtMs?.let { stringResource(R.string.apps_summary_title_since, dayAwareTime(formatter, it, summary.capturedAtMs)) }
        ?: stringResource(R.string.apps_summary_title)
    Panel(
        modifier,
        title = title,
        trailing = { InfoSheet(stringResource(R.string.apps_summary_info_title), stringResource(R.string.apps_summary_info_body)) },
    ) {
        QuietText(stringResource(R.string.apps_updated, dayAwareTime(formatter, summary.capturedAtMs, nowMs)))
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            DurationCell(stringResource(R.string.apps_summary_on_battery), summary.onBatteryMs, Modifier.weight(1f))
            DurationCell(stringResource(R.string.apps_summary_screen_on), summary.screenOnMs, Modifier.weight(1f), usedText(summary.screenOnUsedPercent))
            DurationCell(stringResource(R.string.apps_summary_screen_off), summary.screenOffMs, Modifier.weight(1f), usedText(summary.screenOffUsedPercent))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            DurationCell(stringResource(R.string.apps_summary_deep_doze), summary.deepDozeMs, Modifier.weight(1f))
            DurationCell(stringResource(R.string.apps_summary_light_doze), summary.lightDozeMs, Modifier.weight(1f))
            StatCell(
                stringResource(R.string.apps_summary_capacity),
                summary.capacityMah?.let { formatNumber(it, 0, locale) } ?: stringResource(R.string.component_no_value),
                Modifier.weight(1f),
                unit = summary.capacityMah?.let { stringResource(R.string.now_unit_mah) },
                // Android's own full-capacity figure, not the app's measured one (Now's Health, Health).
                supporting = summary.capacityMah?.let { stringResource(R.string.apps_summary_capacity_source) },
            )
        }
    }
}

/** "27% used": the battery share used in a screen state. */
@Composable
private fun usedText(percent: Int?): String? =
    percent?.let { stringResource(R.string.apps_summary_used, formatNumber(it.toDouble(), 0, currentLocale())) }

/** The summary's shape while the first read runs: its title, a line saying what's happening, no numbers. */
@Composable
private fun SummaryPlaceholder(modifier: Modifier = Modifier) {
    Panel(modifier, title = stringResource(R.string.apps_summary_title)) {
        QuietText(stringResource(R.string.apps_loading))
    }
}

/** Placeholder rows (icon circle, name bar, share track) while the first read runs; read as one "Loading apps". */
@Composable
private fun LoadingRows(modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    val tone = MaterialTheme.colorScheme.surfaceContainerHigh
    val description = stringResource(R.string.apps_loading_description)
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = spacing.md)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        SKELETON_WIDTHS.forEach { fraction ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.xxl + spacing.xs)
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Box(Modifier.size(AppIconDefaults.Size).background(tone, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(spacing.sm)
                            .background(tone, MaterialTheme.shapes.extraSmall),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(spacing.xxs)
                            .background(tone, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * "Allow in Shizuku" from Apps or AppDetails: Shizuku's permission dialog, or, once BatStats is blocked there ("Deny and
 * don't ask again", after which Shizuku shows no dialog), the Shizuku app itself, where BatStats can be switched on.
 */
internal fun allowShizuku(context: Context, problem: StatsProblem?, shizuku: ShizukuBridge) {
    if ((problem as? StatsProblem.NoAccess)?.blocked == true) openShizuku(context) else shizuku.requestPermission()
}

/**
 * Opens the Shizuku app (visible through QUERY_ALL_PACKAGES). Without its launcher entry (Sui has none) there is
 * nothing to open, and the blocked copy alone says what to do.
 */
internal fun openShizuku(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(ShizukuProvider.MANAGER_APPLICATION_ID) ?: return
    try {
        context.startActivity(launch)
    } catch (_: ActivityNotFoundException) {
        // Uninstalled between the lookup and the launch.
    }
}

/**
 * The last read's problem as the app's quiet [Notice]: an access hint (info accent; "Set up access" opens Settings ›
 * Status, and Shizuku can be asked directly when it runs, or opened when BatStats is blocked there) or a read failure
 * (error accent) with Try again. Nothing when there's none.
 */
@Composable
internal fun StatsProblemNotice(
    problem: StatsProblem?,
    onSetUp: () -> Unit,
    onAllowShizuku: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (problem) {
        null -> Unit
        is StatsProblem.NoAccess -> Notice(
            message = stringResource(
                if (problem.blocked) R.string.apps_access_shizuku_blocked else accessBody(problem.problem),
            ),
            modifier = modifier,
            title = stringResource(R.string.apps_access_title),
            tone = NoticeTone.INFO,
            icon = Icons.Rounded.Key,
            framed = true,
        ) {
            if (problem.problem == AccessProblem.SHIZUKU_NOT_ALLOWED) {
                val label = if (problem.blocked) R.string.apps_access_open_shizuku else R.string.apps_access_allow
                TextButton(onClick = onAllowShizuku) { Text(stringResource(label)) }
            }
            TextButton(onClick = onSetUp) { Text(stringResource(R.string.apps_access_set_up)) }
        }
        is StatsProblem.Failed -> Notice(
            message = stringResource(failedBody(problem.problem)),
            modifier = modifier,
            title = stringResource(R.string.apps_failed_title),
            framed = true,
        ) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.apps_retry)) }
        }
    }
}

private fun accessBody(problem: AccessProblem): Int = when (problem) {
    AccessProblem.NOT_SET_UP -> R.string.apps_access_not_set_up
    AccessProblem.SHIZUKU_NOT_ALLOWED -> R.string.apps_access_shizuku
    AccessProblem.ADB_NOT_ENOUGH -> R.string.apps_access_adb
    AccessProblem.REFUSED -> R.string.apps_access_refused
}

private fun failedBody(problem: ReadProblem): Int = when (problem) {
    ReadProblem.FORMAT -> R.string.apps_failed_format
    ReadProblem.SHIZUKU -> R.string.apps_failed_shizuku
    ReadProblem.OTHER -> R.string.apps_failed_other
}

private fun sortLabel(sort: AppSort): Int = when (sort) {
    AppSort.BATTERY -> R.string.apps_sort_battery
    AppSort.CPU -> R.string.apps_sort_cpu
    AppSort.FOREGROUND -> R.string.apps_sort_foreground
    AppSort.BACKGROUND -> R.string.apps_sort_background
    AppSort.NETWORK -> R.string.apps_sort_network
}

/** A row's value for [sort]: "124 mAh", "1:24 h" / "12 min", or "12.4 MB". */
@Composable
private fun sortValueText(value: Double, sort: AppSort): String = when (sort) {
    AppSort.BATTERY -> valueWithUnit(formatMah(value, currentLocale()), stringResource(R.string.now_unit_mah))
    AppSort.CPU, AppSort.FOREGROUND, AppSort.BACKGROUND -> appDurationText(value.toLong())
    AppSort.NETWORK -> bytesText(value.toLong())
}

// Shared with AppDetails.


/**
 * Now's compact duration ("1:24" h, "12" min), plus whole seconds under a minute: wakelocks, jobs and CPU time are
 * often seconds long, and "0 min" would hide them.
 */
internal fun appDuration(ms: Long, locale: Locale): CompactDuration =
    if (ms < MINUTE_MS) {
        CompactDuration(formatNumber((ms.coerceAtLeast(0) / SECOND_MS).toDouble(), 0, locale), R.string.apps_unit_seconds)
    } else {
        compactDuration(ms, locale)
    }

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60 * SECOND_MS

/** "1:24 h", "12 min" or "40 s" as one string. */
@Composable
internal fun appDurationText(ms: Long): String {
    val duration = appDuration(ms, currentLocale())
    return valueWithUnit(duration.value, stringResource(duration.unit))
}

/** A byte count in SI units (as Android shows data use): the number and its unit's string resource. */
internal class ByteSize(val value: String, val unit: Int)

internal fun byteSize(bytes: Long, locale: Locale): ByteSize {
    val units = listOf(R.string.apps_unit_bytes, R.string.apps_unit_kb, R.string.apps_unit_mb, R.string.apps_unit_gb)
    var value = bytes.coerceAtLeast(0).toDouble()
    var index = 0
    while (value >= BYTES_STEP && index < units.lastIndex) {
        value /= BYTES_STEP
        index++
    }
    // Round before settling on the unit and decimals, so 999.999 kB reads "1.0 MB" and 99.96 kB reads "100 kB".
    fun decimalsFor(v: Double) = if (index == 0 || kotlin.math.round(v * 10) / 10 >= 100) 0 else 1
    fun rounded(v: Double) = decimalsFor(v).let { d -> kotlin.math.round(v * 10.0.pow(d)) / 10.0.pow(d) }
    if (rounded(value) >= BYTES_STEP && index < units.lastIndex) {
        value = rounded(value) / BYTES_STEP
        index++
    }
    return ByteSize(formatNumber(value, decimalsFor(value), locale), units[index])
}

private const val BYTES_STEP = 1000.0

@Composable
internal fun bytesText(bytes: Long): String {
    val size = byteSize(bytes, currentLocale())
    return valueWithUnit(size.value, stringResource(size.unit))
}

/** Durations for chart legends and readouts, formatted like [appDurationText]. */
@Composable
internal fun rememberDurationFormatter(): ValueFormatter {
    val locale = currentLocale()
    val units = mapOf(
        R.string.now_unit_hours to stringResource(R.string.now_unit_hours),
        R.string.now_unit_minutes to stringResource(R.string.now_unit_minutes),
        R.string.apps_unit_seconds to stringResource(R.string.apps_unit_seconds),
    )
    val template = stringResource(R.string.value_unit)
    return remember(locale, units, template) {
        ValueFormatter { ms ->
            val duration = appDuration(ms.toLong(), locale)
            String.format(locale, template, duration.value, units[duration.unit].orEmpty())
        }
    }
}

/** A [StatCell] for a duration: "14:20" with "h", "45" with "min" or "40" with "s"; "—" when Android gave none. */
@Composable
internal fun DurationCell(label: String, ms: Long?, modifier: Modifier = Modifier, supporting: String? = null) {
    val duration = ms?.let { appDuration(it, currentLocale()) }
    StatCell(
        label,
        duration?.value ?: stringResource(R.string.component_no_value),
        modifier,
        unit = duration?.let { stringResource(it.unit) },
        supporting = supporting,
    )
}


/** One entry of a details list: a name (up to two lines), its value at the end, and an optional line under it. */
@Composable
internal fun DetailItem(title: String, value: String, modifier: Modifier = Modifier, supporting: String? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(MaterialTheme.spacing.sm))
            Text(
                value,
                style = MaterialTheme.typography.numericBody,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
