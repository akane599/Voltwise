package com.akane.voltwise.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akane.voltwise.R
import com.akane.voltwise.battery.NOTIFICATION_PERMISSION_ASKED
import com.akane.voltwise.battery.NOTIFICATION_PERMISSION_PREFS
import com.akane.voltwise.battery.measurement.CalibrationState
import com.akane.voltwise.battery.measurement.CurrentSign
import com.akane.voltwise.battery.measurement.CurrentUnit
import com.akane.voltwise.battery.util.Notifier
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.settings.CurrentSignOverride
import com.akane.voltwise.settings.CurrentUnitOverride
import com.akane.voltwise.settings.DesignCapacity
import com.akane.voltwise.settings.useFahrenheit
import com.akane.voltwise.ui.components.InfoSheet
import com.akane.voltwise.ui.components.Notice
import com.akane.voltwise.ui.components.Panel
import com.akane.voltwise.ui.components.QuietText
import com.akane.voltwise.ui.components.SegmentedTabs
import com.akane.voltwise.ui.components.StatCell
import com.akane.voltwise.ui.format.currentLocale
import com.akane.voltwise.ui.theme.numericBody
import com.akane.voltwise.ui.theme.numericHeadline
import com.akane.voltwise.ui.theme.spacing
import com.akane.voltwise.viewmodel.NotificationsAction
import com.akane.voltwise.viewmodel.SettingsChoice
import com.akane.voltwise.viewmodel.SettingsError
import com.akane.voltwise.viewmodel.SettingsEvent
import com.akane.voltwise.viewmodel.SettingsSwitch
import com.akane.voltwise.viewmodel.SettingsThreshold
import com.akane.voltwise.viewmodel.SettingsUiState
import com.akane.voltwise.viewmodel.SettingsViewModel
import com.akane.voltwise.viewmodel.notificationsAction
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** Two columns from this window width (the Material "expanded" breakpoint), as on Now. */
private const val TWO_COLUMN_MIN_WIDTH_DP = 840

/** Longest design capacity the field accepts (30000). */

/** M3's content alpha for a disabled control. */
private const val DISABLED_ALPHA = 0.38f

/**
 * Settings, wired: the Koin [SettingsViewModel], the links to Data and Status, Android's settings for the alert
 * channel (created first: Android makes a channel only when it is first used), and turning the app's notifications
 * back on (checked on every resume and after the permission dialog). Everything else goes to the ViewModel.
 */
@Composable
fun SettingsScreen(
    onOpenData: () -> Unit,
    onOpenStatus: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.alert_settings_unavailable)
    val checkNotifications = {
        vm.onEvent(SettingsEvent.NotificationsChecked(NotificationManagerCompat.from(context).areNotificationsEnabled()))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        checkNotifications()
    }
    LifecycleResumeEffect(Unit) {
        checkNotifications()
        onPauseOrDispose {}
    }
    SettingsContent(
        state = state,
        onEvent = { event ->
            when (event) {
                SettingsEvent.OpenData -> onOpenData()
                SettingsEvent.OpenStatus -> onOpenStatus()
                SettingsEvent.OpenAlertSound -> {
                    if (!openAlertChannelSettings(context)) scope.launch { snackbarHostState.showSnackbar(unavailable) }
                }
                SettingsEvent.EnableNotifications -> {
                    if (!enableNotifications(context, activity, notificationPermission::launch)) {
                        scope.launch { snackbarHostState.showSnackbar(unavailable) }
                    }
                }
                else -> vm.onEvent(event)
            }
        },
        dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

private fun openAlertChannelSettings(context: Context): Boolean {
    Notifier.ensureAlertChannel(context)
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.ALERT_CHANNEL_ID)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * Asks for POST_NOTIFICATIONS while Android would still show its dialog (see [notificationsAction]), recording the
 * ask as first launch does; otherwise opens the app's notification settings. False when neither could open.
 */
private fun enableNotifications(context: Context, activity: Activity?, request: (String) -> Unit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activity != null) {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val asked = context.getSharedPreferences(NOTIFICATION_PERMISSION_PREFS, Context.MODE_PRIVATE)
        val action = notificationsAction(
            sdkInt = Build.VERSION.SDK_INT,
            granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
            askedBefore = asked.getBoolean(NOTIFICATION_PERMISSION_ASKED, false),
            rationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission),
        )
        if (action == NotificationsAction.REQUEST_PERMISSION) {
            asked.edit().putBoolean(NOTIFICATION_PERMISSION_ASKED, true).apply()
            return try {
                request(permission)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * Settings, stateless: [state] in, [onEvent] out. One scrolling page of tonal panels under the status bar — Monitoring,
 * Alerts, Measurement (the detected calibration and its overrides), Appearance, Data — in two columns from 840 dp.
 * Prose lives in each panel's ⓘ sheet. Pickers and confirmations are local dialogs; a failed write shows inline at
 * the top. [dynamicColorAvailable] (API 31+) shows the Material You row.
 */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    dynamicColorAvailable: Boolean,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    scrollState: ScrollState = rememberScrollState(),
) {
    val spacing = MaterialTheme.spacing
    val scope = rememberCoroutineScope()
    val twoColumns = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_COLUMN_MIN_WIDTH_DP
    val settings = state.settings

    var choice by rememberSaveable { mutableStateOf<SettingsChoice?>(null) }
    var threshold by rememberSaveable { mutableStateOf<SettingsThreshold?>(null) }
    var editDesignCapacity by rememberSaveable { mutableStateOf(false) }
    var confirmCalibrationReset by rememberSaveable { mutableStateOf(false) }

    val monitoring: @Composable () -> Unit = {
        MonitoringPanel(settings, onEvent, onChoose = { choice = it }, Modifier.fillMaxWidth())
    }
    val alerts: @Composable () -> Unit = {
        AlertsPanel(settings, state.notificationsEnabled, onEvent, onEditThreshold = { threshold = it }, Modifier.fillMaxWidth())
    }
    val measurement: @Composable () -> Unit = {
        MeasurementPanel(
            settings,
            state.calibration,
            onEvent,
            onEditDesignCapacity = { editDesignCapacity = true },
            onResetCalibration = { confirmCalibrationReset = true },
            Modifier.fillMaxWidth(),
        )
    }
    val appearance: @Composable () -> Unit = {
        AppearancePanel(settings, dynamicColorAvailable, onEvent, Modifier.fillMaxWidth())
    }
    val data: @Composable () -> Unit = {
        DataPanel(settings, state.retentionUnset, onEvent, onChoose = { choice = it }, Modifier.fillMaxWidth())
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .verticalScroll(scrollState)
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                stringResource(R.string.settings),
                modifier = Modifier.padding(vertical = spacing.xs).semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            state.error?.let { error ->
                ErrorBanner(error, onDismiss = { onEvent(SettingsEvent.DismissError) }, Modifier.fillMaxWidth())
            }
            if (twoColumns) {
                // Data sits under Alerts here so the two columns end close together.
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        monitoring()
                        alerts()
                        data()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        measurement()
                        appearance()
                    }
                }
            } else {
                monitoring()
                alerts()
                measurement()
                appearance()
                data()
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(spacing.md))
    }

    choice?.let { setting ->
        ChoiceDialog(
            title = stringResource(setting.titleRes()),
            options = setting.optionLabels(),
            // An unset retention preselects nothing, so confirming the decoded default is a deliberate pick.
            selectedIndex = if (setting == SettingsChoice.RETENTION && state.retentionUnset) -1 else setting.selectedIndex(settings),
            onSelect = { index ->
                choice = null
                onEvent(SettingsEvent.SetChoice(setting, index))
            },
            onDismiss = { choice = null },
        )
    }
    threshold?.let { setting ->
        ThresholdDialog(
            setting,
            initial = setting.value(settings),
            fahrenheit = settings.useFahrenheit,
            onSave = { value ->
                threshold = null
                onEvent(SettingsEvent.SetThreshold(setting, value))
            },
            onDismiss = { threshold = null },
        )
    }
    if (editDesignCapacity) {
        DesignCapacityDialog(
            current = settings.designCapacityMah,
            onSave = { mAh ->
                editDesignCapacity = false
                onEvent(SettingsEvent.SetDesignCapacity(mAh))
            },
            onDismiss = { editDesignCapacity = false },
        )
    }
    if (confirmCalibrationReset) {
        val done = stringResource(R.string.settings_calibration_reset_done)
        AlertDialog(
            onDismissRequest = { confirmCalibrationReset = false },
            title = { Text(stringResource(R.string.settings_reset_calibration_title)) },
            text = { Text(stringResource(R.string.settings_reset_calibration_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCalibrationReset = false
                    onEvent(SettingsEvent.ResetCalibration)
                    scope.launch { snackbarHostState.showSnackbar(done) }
                }) { Text(stringResource(R.string.settings_reset_calibration_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCalibrationReset = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun MonitoringPanel(
    settings: AppSettings,
    onEvent: (SettingsEvent) -> Unit,
    onChoose: (SettingsChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPanel(
        stringResource(R.string.settings_monitoring_title),
        info = stringResource(R.string.settings_monitoring_info_title) to stringResource(R.string.settings_monitoring_info_body),
        modifier = modifier,
    ) {
        Rows {
            SwitchRow(
                stringResource(R.string.settings_auto_start),
                checked = settings.autoStartOnBoot,
                onCheckedChange = { onEvent(SettingsEvent.SetSwitch(SettingsSwitch.AUTO_START, it)) },
            )
            ChoiceRow(SettingsChoice.STATUS_ICON, settings, onClick = { onChoose(SettingsChoice.STATUS_ICON) })
            LinkRow(
                stringResource(R.string.settings_status_link),
                summary = stringResource(R.string.settings_status_link_summary),
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                onClick = { onEvent(SettingsEvent.OpenStatus) },
            )
        }
    }
}

/**
 * The five alerts; the four with a threshold carry it as a value button (dimmed while the alert is off). While Android
 * blocks the app's notifications a notice leads, with the one way to turn them back on.
 */
@Composable
private fun AlertsPanel(
    settings: AppSettings,
    notificationsEnabled: Boolean,
    onEvent: (SettingsEvent) -> Unit,
    onEditThreshold: (SettingsThreshold) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPanel(
        stringResource(R.string.settings_alerts_title),
        info = stringResource(R.string.settings_alerts_info_title) to stringResource(R.string.settings_alerts_info_body),
        modifier = modifier,
    ) {
        Rows {
            if (!notificationsEnabled) {
                Notice(
                    stringResource(R.string.settings_notifications_off_body),
                    Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.md),
                    title = stringResource(R.string.settings_notifications_off_title),
                ) {
                    TextButton(onClick = { onEvent(SettingsEvent.EnableNotifications) }) {
                        Text(stringResource(R.string.settings_notifications_turn_on))
                    }
                }
            }
            SettingsThreshold.entries.forEach { setting ->
                val title = stringResource(setting.titleRes())
                val on = setting.alert.isOn(settings)
                val value = thresholdText(setting, setting.value(settings), settings.useFahrenheit)
                SwitchRow(
                    title,
                    checked = on,
                    onCheckedChange = { onEvent(SettingsEvent.SetSwitch(setting.alert, it)) },
                ) {
                    ValuePill(
                        value,
                        enabled = on,
                        onClick = { onEditThreshold(setting) },
                        description = stringResource(R.string.settings_threshold_description, title, value),
                    )
                }
            }
            SwitchRow(
                stringResource(R.string.settings_alert_full),
                checked = settings.chargingCompleteAlert,
                onCheckedChange = { onEvent(SettingsEvent.SetSwitch(SettingsSwitch.FULL_CHARGE_ALERT, it)) },
            )
            LinkRow(
                stringResource(R.string.settings_alert_sound),
                summary = stringResource(R.string.settings_alert_sound_summary),
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                onClick = { onEvent(SettingsEvent.OpenAlertSound) },
            )
        }
    }
}

/**
 * The detected calibration (what the charge counter says `CURRENT_NOW` means), how it was found and Reset; then the
 * unit and sign overrides, and the design capacity used for health.
 */
@Composable
private fun MeasurementPanel(
    settings: AppSettings,
    calibration: CalibrationState,
    onEvent: (SettingsEvent) -> Unit,
    onEditDesignCapacity: () -> Unit,
    onResetCalibration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val noValue = stringResource(R.string.component_no_value)
    val detected = calibration.detected
    SettingsPanel(
        stringResource(R.string.settings_measurement_title),
        info = stringResource(R.string.settings_measurement_info_title) to stringResource(R.string.settings_measurement_info_body),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                StatCell(
                    stringResource(R.string.settings_detected_unit),
                    detected?.let { stringResource(unitLabel(it.unit)) } ?: noValue,
                    Modifier.weight(1f),
                )
                StatCell(
                    stringResource(R.string.settings_detected_sign),
                    detected?.let { stringResource(signLabel(it.sign)) } ?: noValue,
                    Modifier.weight(1f),
                )
            }
            QuietText(
                when {
                    detected == null -> stringResource(R.string.settings_evidence_none)
                    calibration.agreeingWindows > 0 ->
                        pluralStringResource(R.plurals.settings_evidence_windows, calibration.agreeingWindows, calibration.agreeingWindows)
                    else -> stringResource(R.string.settings_evidence_sign_only)
                },
            )
            if (settings.currentUnitOverride != CurrentUnitOverride.AUTO || settings.currentSignOverride != CurrentSignOverride.AUTO) {
                QuietText(stringResource(R.string.settings_override_note))
            }
        }
        // Under the evidence it acts on; the xxs inset puts the button's label on the md gutter.
        TextButton(onClick = onResetCalibration, modifier = Modifier.padding(horizontal = spacing.xxs)) {
            Text(stringResource(R.string.settings_reset_calibration))
        }
        Rows {
            SegmentedRow(SettingsChoice.CURRENT_UNIT, settings, onEvent)
            SegmentedRow(SettingsChoice.CURRENT_SIGN, settings, onEvent)
            val capacity = settings.designCapacityMah
            SettingRow(
                stringResource(R.string.settings_design_capacity),
                Modifier.clickable(role = Role.Button, onClick = onEditDesignCapacity),
            ) {
                ValuePill(
                    if (capacity == DesignCapacity.AUTO) {
                        stringResource(R.string.option_auto)
                    } else {
                        stringResource(R.string.settings_value_mah, formatWhole(capacity.toFloat(), currentLocale()))
                    },
                )
            }
        }
    }
}

@Composable
private fun AppearancePanel(
    settings: AppSettings,
    dynamicColorAvailable: Boolean,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPanel(stringResource(R.string.settings_appearance_title), modifier = modifier) {
        Rows {
            SwitchRow(
                stringResource(R.string.settings_pure_black),
                checked = settings.oledBlack,
                onCheckedChange = { onEvent(SettingsEvent.SetSwitch(SettingsSwitch.OLED_BLACK, it)) },
                summary = stringResource(R.string.settings_pure_black_summary),
            )
            if (dynamicColorAvailable) {
                SwitchRow(
                    stringResource(R.string.settings_material_you),
                    checked = settings.dynamicColors,
                    onCheckedChange = { onEvent(SettingsEvent.SetSwitch(SettingsSwitch.DYNAMIC_COLORS, it)) },
                    summary = stringResource(R.string.settings_material_you_summary),
                )
            }
            SegmentedRow(SettingsChoice.TEMPERATURE_UNIT, settings, onEvent)
        }
    }
}

@Composable
private fun DataPanel(
    settings: AppSettings,
    retentionUnset: Boolean,
    onEvent: (SettingsEvent) -> Unit,
    onChoose: (SettingsChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPanel(
        stringResource(R.string.settings_data_title),
        info = stringResource(R.string.settings_data_info_title) to stringResource(R.string.settings_data_info_body),
        modifier = modifier,
    ) {
        Rows {
            ChoiceRow(
                SettingsChoice.RETENTION,
                settings,
                onClick = { onChoose(SettingsChoice.RETENTION) },
                unsetSummary = if (retentionUnset) stringResource(R.string.settings_retention_unset) else null,
            )
            LinkRow(
                stringResource(R.string.settings_data_link),
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                onClick = { onEvent(SettingsEvent.OpenData) },
            )
        }
    }
}

/** A failed write, as the app's quiet [Notice], until dismissed or the next write succeeds; announced politely. */
@Composable
private fun ErrorBanner(error: SettingsError, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val message = when (error) {
        SettingsError.WRITE_FAILED -> R.string.settings_write_failed
        SettingsError.INVALID_DESIGN_CAPACITY -> R.string.settings_design_capacity_invalid
    }
    Notice(stringResource(message), modifier, framed = true) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dismiss)) }
    }
}

// Building blocks

/** A titled panel whose rows run edge to edge (their ripple spans the panel); [info] = ⓘ sheet title to body. */
@Composable
private fun SettingsPanel(
    title: String,
    modifier: Modifier = Modifier,
    info: Pair<String, String>? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Panel(
        modifier,
        title = title,
        trailing = if (info != null) {
            { InfoSheet(info.first, info.second) }
        } else {
            null
        },
        contentPadding = PaddingValues(top = spacing.md, bottom = spacing.xs),
        content = content,
    )
}

/** Rows stacked without gaps (a [Panel] spaces its children). */
@Composable
private fun Rows(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), content = content)
}

/** One settings row: title (and optional summary) in the gutter, [trailing] at the end; 56 dp minimum. */
@Composable
private fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = spacing.xxl + spacing.xs)
            .padding(horizontal = spacing.md, vertical = spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            summary?.let { QuietText(it) }
        }
        trailing()
    }
}

/** The whole row toggles (one TalkBack switch); [value] (a threshold) sits before the switch. */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: (@Composable () -> Unit)? = null,
) {
    SettingRow(
        title,
        modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        summary,
    ) {
        value?.invoke()
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A setting with several options: its current option (or [unsetSummary] when none is chosen) under the title; opens the picker. */
@Composable
private fun ChoiceRow(
    setting: SettingsChoice,
    settings: AppSettings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unsetSummary: String? = null,
) {
    val options = setting.optionLabels()
    val noValue = stringResource(R.string.component_no_value)
    SettingRow(
        stringResource(setting.titleRes()),
        modifier.clickable(role = Role.Button, onClick = onClick),
        summary = unsetSummary ?: options.getOrElse(setting.selectedIndex(settings)) { noValue },
    )
}

/**
 * Two or three short options, chosen in place. When a label is wider than its equal share of the row (large font,
 * longer translations) the options stack as radio rows instead, so none is cut to "Autom…".
 */
@Composable
private fun SegmentedRow(setting: SettingsChoice, settings: AppSettings, onEvent: (SettingsEvent) -> Unit) {
    val spacing = MaterialTheme.spacing
    val title = stringResource(setting.titleRes())
    val labels = setting.optionLabels()
    val selectedIndex = setting.selectedIndex(settings)
    val onSelect = { index: Int -> onEvent(SettingsEvent.SetChoice(setting, index)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.xxs)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val style = MaterialTheme.typography.labelLarge
            // SegmentedTabs insets each equal share by xxs, then its label by xs, on both sides.
            val inset = with(LocalDensity.current) { ((spacing.xxs + spacing.xs) * 2).roundToPx() }
            val share = constraints.maxWidth / labels.size - inset
            val fits = labels.all { measurer.measure(it, style, maxLines = 1).size.width <= share }
            if (fits) {
                SegmentedTabs(labels = labels, selectedIndex = selectedIndex, onSelect = onSelect)
            } else {
                RadioOptions(labels, selectedIndex, onSelect)
            }
        }
    }
}

/** One 48 dp radio row per option, as a selectable group (the picker dialog and stacked segmented options). */
@Composable
private fun RadioOptions(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.selectableGroup()) {
        options.forEachIndexed { index, option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MaterialTheme.spacing.xxl)
                    .selectable(selected = index == selectedIndex, role = Role.RadioButton, onClick = { onSelect(index) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                RadioButton(selected = index == selectedIndex, onClick = null)
                Text(option, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Leaves the screen: a chevron for Data and Status, "open in new" for Android's own settings. */
@Composable
private fun LinkRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    SettingRow(title, modifier.clickable(role = Role.Button, onClick = onClick), summary) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A number in a small tonal pill (tabular Space Grotesk). With [onClick] it is its own 48 dp button inside a row;
 * without, it only shows the row's value.
 */
@Composable
private fun ValuePill(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    description: String? = null,
) {
    val spacing = MaterialTheme.spacing
    val onSurface = MaterialTheme.colorScheme.onSurface
    val label: @Composable () -> Unit = {
        Text(
            text,
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
            style = MaterialTheme.typography.numericBody,
            color = if (enabled) onSurface else onSurface.copy(alpha = DISABLED_ALPHA),
            maxLines = 1,
        )
    }
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.semantics { description?.let { contentDescription = it } },
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            color = color,
            content = label,
        )
    } else {
        Surface(modifier, shape = MaterialTheme.shapes.small, color = color, content = label)
    }
}


// Dialogs

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { RadioOptions(options, selectedIndex, onSelect, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/**
 * The threshold as a large number over a stepped slider (range and step from the schema). In Fahrenheit the
 * temperature slider steps in whole °F across the °C range and saves the matching °C.
 */
@Composable
private fun ThresholdDialog(
    setting: SettingsThreshold,
    initial: Float,
    fahrenheit: Boolean,
    onSave: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val inFahrenheit = fahrenheit && setting == SettingsThreshold.TEMPERATURE
    val range = if (inFahrenheit) wholeFahrenheitRange(setting.range) else setting.range
    val step = if (inFahrenheit) 1f else setting.step
    var position by rememberSaveable(setting, inFahrenheit) {
        val start = if (inFahrenheit) celsiusToFahrenheit(initial).roundToInt().toFloat() else initial
        mutableFloatStateOf(start.coerceIn(range))
    }
    val value = if (inFahrenheit) fahrenheitToCelsius(position) else position
    val display = thresholdText(setting, value, fahrenheit)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.titleRes())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                Text(display, style = MaterialTheme.typography.numericHeadline, color = MaterialTheme.colorScheme.onSurface)
                QuietText(stringResource(setting.hintRes()))
                Slider(
                    value = position,
                    onValueChange = { position = it },
                    valueRange = range,
                    steps = sliderSteps(range, step),
                    modifier = Modifier.semantics { stateDescription = display },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

// Labels and formatting

private fun SettingsChoice.titleRes(): Int = when (this) {
    SettingsChoice.STATUS_ICON -> R.string.settings_status_icon
    SettingsChoice.CURRENT_UNIT -> R.string.settings_current_unit
    SettingsChoice.CURRENT_SIGN -> R.string.settings_current_sign
    SettingsChoice.TEMPERATURE_UNIT -> R.string.settings_temperature_unit
    SettingsChoice.RETENTION -> R.string.settings_retention
}

/** Option labels in the schema's option order (enum order for enum settings). */
@Composable
private fun SettingsChoice.optionLabels(): List<String> = when (this) {
    SettingsChoice.STATUS_ICON -> listOf(
        R.string.option_status_level,
        R.string.option_status_current,
        R.string.option_status_power,
        R.string.option_status_temperature,
        R.string.option_status_static,
    )
    SettingsChoice.CURRENT_UNIT -> listOf(R.string.option_auto, R.string.option_microamps, R.string.option_milliamps)
    SettingsChoice.CURRENT_SIGN -> listOf(R.string.option_auto, R.string.option_sign_normal, R.string.option_sign_inverted)
    SettingsChoice.TEMPERATURE_UNIT -> listOf(R.string.option_celsius, R.string.option_fahrenheit)
    SettingsChoice.RETENTION -> listOf(
        R.string.option_1_week,
        R.string.option_1_month,
        R.string.option_3_months,
        R.string.option_6_months,
        R.string.option_1_year,
        R.string.option_forever,
    )
}.map { stringResource(it) }

private fun SettingsThreshold.titleRes(): Int = when (this) {
    SettingsThreshold.LOW_BATTERY -> R.string.settings_alert_low
    SettingsThreshold.HIGH_BATTERY -> R.string.settings_alert_high
    SettingsThreshold.TEMPERATURE -> R.string.settings_alert_temperature
    SettingsThreshold.DISCHARGE_CURRENT -> R.string.settings_alert_discharge
}

private fun SettingsThreshold.hintRes(): Int = when (this) {
    SettingsThreshold.LOW_BATTERY -> R.string.settings_threshold_low_hint
    SettingsThreshold.HIGH_BATTERY -> R.string.settings_threshold_high_hint
    SettingsThreshold.TEMPERATURE -> R.string.settings_threshold_temperature_hint
    SettingsThreshold.DISCHARGE_CURRENT -> R.string.settings_threshold_discharge_hint
}

private fun unitLabel(unit: CurrentUnit): Int = when (unit) {
    CurrentUnit.MICROAMPS -> R.string.option_microamps
    CurrentUnit.MILLIAMPS -> R.string.option_milliamps
}

private fun signLabel(sign: CurrentSign): Int = when (sign) {
    CurrentSign.NORMAL -> R.string.option_sign_normal
    CurrentSign.INVERTED -> R.string.option_sign_inverted
}

/** The threshold with its unit; the temperature (stored in °C) in the display unit. */
@Composable
private fun thresholdText(setting: SettingsThreshold, value: Float, fahrenheit: Boolean): String {
    val locale = currentLocale()
    return when (setting) {
        SettingsThreshold.LOW_BATTERY, SettingsThreshold.HIGH_BATTERY ->
            stringResource(R.string.percent_value, formatWhole(value, locale))
        SettingsThreshold.TEMPERATURE ->
            if (fahrenheit) {
                stringResource(R.string.settings_value_fahrenheit, formatWhole(celsiusToFahrenheit(value), locale))
            } else {
                stringResource(R.string.settings_value_celsius, formatWhole(value, locale))
            }
        SettingsThreshold.DISCHARGE_CURRENT -> stringResource(R.string.settings_value_ma, formatWhole(value, locale))
    }
}

private fun formatWhole(value: Float, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value.roundToLong())

internal fun celsiusToFahrenheit(celsius: Float): Float = celsius * 9 / 5 + 32

internal fun fahrenheitToCelsius(fahrenheit: Float): Float = (fahrenheit - 32) * 5 / 9

/** The whole °F that lie inside a °C [range] (35..55 °C → 95..131 °F). */
internal fun wholeFahrenheitRange(range: ClosedFloatingPointRange<Float>): ClosedFloatingPointRange<Float> =
    ceil(celsiusToFahrenheit(range.start))..floor(celsiusToFahrenheit(range.endInclusive))

/** Slider `steps` (the stops between the ends) for [step]-wide stops across [range]. */
internal fun sliderSteps(range: ClosedFloatingPointRange<Float>, step: Float): Int =
    ((range.endInclusive - range.start) / step).roundToInt() - 1
