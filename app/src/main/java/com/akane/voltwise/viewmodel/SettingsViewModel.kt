package com.akane.voltwise.viewmodel

import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akane.voltwise.battery.data.CalibrationStore
import com.akane.voltwise.battery.measurement.CalibrationState
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.settings.AppSettingsSchema
import com.akane.voltwise.settings.Retention
import com.akane.voltwise.settings.SettingsWrites
import com.akane.voltwise.settings.resolveRetention
import io.github.mlmgames.settings.core.SettingMeta
import io.github.mlmgames.settings.core.SettingsRepository
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What Settings reads and writes: [KmpSettingsStore] in the app, a fake in tests. */
interface SettingsStore {
    val settings: Flow<AppSettings>

    /**
     * True while the raw history retention is invalid or absent after settings recovery; age cleanup
     * stays paused (HistoryRetention) until a valid period is picked.
     */
    val retentionUnset: Flow<Boolean>

    /** Writes one [AppSettingsSchema] field by name; throws when storage fails. */
    suspend fun set(fieldName: String, value: Any)
}

/**
 * [SettingsStore] over the app's settings DataStore: kmp-settings for the typed values, the raw keys for
 * [retentionUnset] (as HistoryRetention reads them). The store is the one source of truth; this
 * [SettingsRepository] over it only differs from the app's in change listeners, which the app does not use.
 */
class KmpSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {
    private val repository = SettingsRepository(dataStore, AppSettingsSchema)

    override val settings: Flow<AppSettings> get() = repository.flow

    override val retentionUnset: Flow<Boolean> =
        dataStore.data.map { resolveRetention(it) == Retention.Unset }.distinctUntilChanged()

    override suspend fun set(fieldName: String, value: Any) = repository.set(fieldName, value)
}

private fun schemaMeta(field: String): SettingMeta =
    checkNotNull(AppSettingsSchema.fields.firstOrNull { it.name == field }?.meta) { "No @Setting field $field" }

/** An on/off setting, by its [AppSettingsSchema] field name. */
enum class SettingsSwitch(val fieldName: String) {
    AUTO_START("autoStartOnBoot"),
    LOW_BATTERY_ALERT("lowBatteryAlertEnabled"),
    HIGH_BATTERY_ALERT("highBatteryAlertEnabled"),
    TEMPERATURE_ALERT("temperatureWarningEnabled"),
    DISCHARGE_ALERT("dischargeAlertEnabled"),
    FULL_CHARGE_ALERT("chargingCompleteAlert"),
    OLED_BLACK("oledBlack"),
    DYNAMIC_COLORS("dynamicColors"),
    ;

    fun isOn(settings: AppSettings): Boolean = when (this) {
        AUTO_START -> settings.autoStartOnBoot
        LOW_BATTERY_ALERT -> settings.lowBatteryAlertEnabled
        HIGH_BATTERY_ALERT -> settings.highBatteryAlertEnabled
        TEMPERATURE_ALERT -> settings.temperatureWarningEnabled
        DISCHARGE_ALERT -> settings.dischargeAlertEnabled
        FULL_CHARGE_ALERT -> settings.chargingCompleteAlert
        OLED_BLACK -> settings.oledBlack
        DYNAMIC_COLORS -> settings.dynamicColors
    }
}

/**
 * A setting with a fixed list of options, chosen by index: the enum constant's position for enum settings, the
 * stored index otherwise. The option count is the schema's; the labels are the screen's.
 */
enum class SettingsChoice(val fieldName: String) {
    STATUS_ICON("statusIconValue"),
    CURRENT_UNIT("currentUnitOverride"),
    CURRENT_SIGN("currentSignOverride"),
    TEMPERATURE_UNIT("temperatureUnitIndex"),
    RETENTION("dataRetentionIndex"),
    ;

    val optionCount: Int get() = schemaMeta(fieldName).options.size

    fun selectedIndex(settings: AppSettings): Int = when (this) {
        STATUS_ICON -> settings.statusIconValue.ordinal
        CURRENT_UNIT -> settings.currentUnitOverride.ordinal
        CURRENT_SIGN -> settings.currentSignOverride.ordinal
        TEMPERATURE_UNIT -> settings.temperatureUnitIndex
        RETENTION -> settings.dataRetentionIndex
    }
}

/**
 * An alert threshold. Range and step come from the schema's slider metadata; [alert] is the switch that enables it.
 * [wholeNumber] thresholds are stored as Int, the temperature (°C) as Float.
 */
enum class SettingsThreshold(val fieldName: String, val alert: SettingsSwitch, private val wholeNumber: Boolean) {
    LOW_BATTERY("lowBatteryThreshold", SettingsSwitch.LOW_BATTERY_ALERT, wholeNumber = true),
    HIGH_BATTERY("highBatteryThreshold", SettingsSwitch.HIGH_BATTERY_ALERT, wholeNumber = true),
    TEMPERATURE("temperatureThreshold", SettingsSwitch.TEMPERATURE_ALERT, wholeNumber = false),
    DISCHARGE_CURRENT("dischargeCurrentThreshold", SettingsSwitch.DISCHARGE_ALERT, wholeNumber = true),
    ;

    val range: ClosedFloatingPointRange<Float> get() = schemaMeta(fieldName).let { it.min..it.max }
    val step: Float get() = schemaMeta(fieldName).step

    fun value(settings: AppSettings): Float = when (this) {
        LOW_BATTERY -> settings.lowBatteryThreshold.toFloat()
        HIGH_BATTERY -> settings.highBatteryThreshold.toFloat()
        TEMPERATURE -> settings.temperatureThreshold
        DISCHARGE_CURRENT -> settings.dischargeCurrentThreshold.toFloat()
    }

    /**
     * [raw] snapped to [step] from the range start, kept in [range], typed as the field stores it. The temperature (°C)
     * is only kept in range: the °F slider sends whole °F as °C, which a 1 °C snap would move to another °F value.
     */
    fun stored(raw: Float): Any {
        require(raw.isFinite()) { "Threshold must be finite" }
        if (!wholeNumber) return raw.coerceIn(range)
        val start = range.start
        return (start + ((raw - start) / step).roundToInt() * step).coerceIn(range).roundToInt()
    }
}

/** What "Turn on" does while the app's notifications are off. */
enum class NotificationsAction { REQUEST_PERMISSION, OPEN_SETTINGS }

/**
 * [NotificationsAction.REQUEST_PERMISSION] while Android would still show its dialog: API 33+, not granted, and never
 * asked or denied only once (Android then reports a [rationale]). After a second denial Android stops showing it, so
 * only the app's notification settings can turn notifications back on; that also covers notifications switched off
 * there with the permission granted. A first dialog dismissed without an answer lands in settings too, which works.
 */
fun notificationsAction(sdkInt: Int, granted: Boolean, askedBefore: Boolean, rationale: Boolean): NotificationsAction =
    if (sdkInt >= 33 && !granted && (!askedBefore || rationale)) {
        NotificationsAction.REQUEST_PERMISSION
    } else {
        NotificationsAction.OPEN_SETTINGS
    }

/** A write the screen reports inline, until dismissed or the next successful write. */
enum class SettingsError { WRITE_FAILED, INVALID_DESIGN_CAPACITY }

/**
 * Plain values; the screen formats them for the viewer's locale. [notificationsEnabled] is Android's last reported
 * answer (true until the screen first checks, so the "notifications are off" row never flashes in).
 */
@Immutable
data class SettingsUiState(
    val settings: AppSettings = AppSettingsSchema.default,
    val calibration: CalibrationState = CalibrationState(),
    val error: SettingsError? = null,
    val notificationsEnabled: Boolean = true,
    /** [SettingsStore.retentionUnset]: the retention row shows "not set" instead of the decoded default. */
    val retentionUnset: Boolean = false,
)

sealed interface SettingsEvent {
    data class SetSwitch(val setting: SettingsSwitch, val on: Boolean) : SettingsEvent
    data class SetChoice(val setting: SettingsChoice, val index: Int) : SettingsEvent
    data class SetThreshold(val setting: SettingsThreshold, val value: Float) : SettingsEvent

    /** 0 = automatic; anything else must be in 1,000..30,000 mAh. */
    data class SetDesignCapacity(val mAh: Int) : SettingsEvent
    data object ResetCalibration : SettingsEvent
    data object DismissError : SettingsEvent

    /** What Android reports now for the app's notifications; the screen checks on every resume. */
    data class NotificationsChecked(val enabled: Boolean) : SettingsEvent

    /** Handled by the screen: ask for the permission again, or open Android's notification settings for the app. */
    data object EnableNotifications : SettingsEvent

    /** Handled by the screen: Android's settings for the alert channel. */
    data object OpenAlertSound : SettingsEvent

    /** Handled by the screen: Settings › Data. */
    data object OpenData : SettingsEvent

    /** Handled by the screen: Settings › Status. */
    data object OpenStatus : SettingsEvent
}

/**
 * Settings v3: every write goes through [SettingsWrites.normalize] (dropdown index → enum, design-capacity range) to
 * the kmp-settings store. The detected calibration is [CalibrationStore]'s, never a setting: Reset calibration
 * forgets it and leaves the settings (and their unit/sign overrides) alone.
 */
class SettingsViewModel(
    private val store: SettingsStore,
    private val calibration: CalibrationStore,
) : ViewModel() {
    private val error = MutableStateFlow<SettingsError?>(null)
    private val notificationsEnabled = MutableStateFlow(true)

    val state: StateFlow<SettingsUiState> =
        combine(store.settings, calibration.state, error, notificationsEnabled, store.retentionUnset, ::SettingsUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SetSwitch -> write(event.setting.fieldName) { event.on }
            is SettingsEvent.SetChoice -> write(event.setting.fieldName) {
                require(event.index in 0 until event.setting.optionCount) { "Option ${event.index} out of range" }
                event.index
            }
            is SettingsEvent.SetThreshold -> write(event.setting.fieldName) { event.setting.stored(event.value) }
            is SettingsEvent.SetDesignCapacity -> write(DESIGN_CAPACITY_FIELD) { event.mAh }
            SettingsEvent.ResetCalibration -> calibration.reset()
            SettingsEvent.DismissError -> error.value = null
            is SettingsEvent.NotificationsChecked -> notificationsEnabled.value = event.enabled
            SettingsEvent.EnableNotifications, SettingsEvent.OpenAlertSound, SettingsEvent.OpenData,
            SettingsEvent.OpenStatus -> Unit
        }
    }

    private fun write(field: String, value: () -> Any) {
        val stored = try {
            SettingsWrites.normalize(field, value())
        } catch (_: IllegalArgumentException) {
            error.value = if (field == DESIGN_CAPACITY_FIELD) SettingsError.INVALID_DESIGN_CAPACITY else SettingsError.WRITE_FAILED
            return
        }
        viewModelScope.launch {
            try {
                store.set(field, stored)
                error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = SettingsError.WRITE_FAILED
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val DESIGN_CAPACITY_FIELD = "designCapacityMah"
    }
}
