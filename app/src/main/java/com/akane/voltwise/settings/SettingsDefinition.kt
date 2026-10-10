package com.akane.voltwise.settings

import com.akane.voltwise.battery.measurement.CurrentSign
import com.akane.voltwise.battery.measurement.CurrentUnit
import io.github.mlmgames.settings.core.annotations.CategoryDefinition
import io.github.mlmgames.settings.core.annotations.Setting
import io.github.mlmgames.settings.core.types.Dropdown
import io.github.mlmgames.settings.core.types.Slider
import io.github.mlmgames.settings.core.types.TextInput
import io.github.mlmgames.settings.core.types.Toggle
import kotlinx.serialization.Serializable

/**
 * Settings schema v3 ([SettingsMigrations.CURRENT_VERSION]). Removing or renaming a key needs a
 * schema bump and a step in [SettingsMigrations]. Enum values are stored by name.
 */
@Serializable
data class AppSettings(
    // GENERAL
    @Setting(
        title = "Auto-start Monitoring",
        category = General::class,
        type = Toggle::class,
        key = "auto_start_on_boot"
    )
    val autoStartOnBoot: Boolean = true,

    @Setting(
        title = "Status Bar Icon",
        category = General::class,
        type = Dropdown::class,
        options = ["Battery level", "Current (mA)", "Power (W)", "Temperature", "Static icon"],
        key = "status_icon_value"
    )
    val statusIconValue: StatusIconValue = StatusIconValue.LEVEL,

    // NOTIFICATIONS & ALARMS
    @Setting(
        title = "Low Battery Alert",
        category = Notifications::class,
        type = Toggle::class,
        key = "low_battery_alert_enabled"
    )
    val lowBatteryAlertEnabled: Boolean = true,

    @Setting(
        title = "Low Battery Threshold",
        category = Notifications::class,
        type = Slider::class,
        min = 5f, max = 50f, step = 5f,
        dependsOn = "lowBatteryAlertEnabled",
        key = "low_battery_threshold"
    )
    val lowBatteryThreshold: Int = 20,

    @Setting(
        title = "High Battery Alert",
        description = "Notify when charging reaches threshold",
        category = Notifications::class,
        type = Toggle::class,
        key = "high_battery_alert_enabled"
    )
    val highBatteryAlertEnabled: Boolean = false,

    @Setting(
        title = "High Battery Threshold",
        category = Notifications::class,
        type = Slider::class,
        min = 50f, max = 100f, step = 5f,
        dependsOn = "highBatteryAlertEnabled",
        key = "high_battery_threshold"
    )
    val highBatteryThreshold: Int = 80,

    @Setting(
        title = "Temperature Warning",
        category = Notifications::class,
        type = Toggle::class,
        key = "temperature_warning_enabled"
    )
    val temperatureWarningEnabled: Boolean = true,

    @Setting(
        title = "Temperature Threshold",
        description = "Warning temperature in Celsius",
        category = Notifications::class,
        type = Slider::class,
        min = 35f, max = 55f, step = 1f,
        dependsOn = "temperatureWarningEnabled",
        key = "temperature_threshold"
    )
    val temperatureThreshold: Float = 45f,

    @Setting(
        title = "High Discharge Alert",
        category = Notifications::class,
        type = Toggle::class,
        key = "discharge_alert_enabled"
    )
    val dischargeAlertEnabled: Boolean = false,

    @Setting(
        title = "Discharge Threshold",
        description = "Alert after at least 3 high-current readings over at least 1 minute (mA). This is not per-app energy attribution.",
        category = Notifications::class,
        type = Slider::class,
        min = 200f, max = 2000f, step = 50f,
        dependsOn = "dischargeAlertEnabled",
        key = "discharge_current_threshold"
    )
    val dischargeCurrentThreshold: Int = 600,

    @Setting(
        title = "Charging Complete Alert",
        category = Notifications::class,
        type = Toggle::class,
        key = "charging_complete_alert"
    )
    val chargingCompleteAlert: Boolean = true,

    // MEASUREMENT (overrides win over the detected calibration; the detection itself is not a setting)
    @Setting(
        title = "Current Unit",
        category = Measurement::class,
        type = Dropdown::class,
        options = ["Auto", "µA", "mA"],
        key = "current_unit_override"
    )
    val currentUnitOverride: CurrentUnitOverride = CurrentUnitOverride.AUTO,

    @Setting(
        title = "Current Sign",
        category = Measurement::class,
        type = Dropdown::class,
        options = ["Auto", "Normal", "Inverted"],
        key = "current_sign_override"
    )
    val currentSignOverride: CurrentSignOverride = CurrentSignOverride.AUTO,

    /** mAh; 0 = automatic. Only [DesignCapacity.isValid] values are written; read through [designCapacityOverrideMah]. */
    @Setting(
        title = "Design Capacity",
        category = Measurement::class,
        type = TextInput::class,
        key = "design_capacity_mah"
    )
    val designCapacityMah: Int = DesignCapacity.AUTO,

    // DISPLAY
    @Setting(
        title = "Dynamic Colors",
        category = Display::class,
        type = Toggle::class,
        key = "dynamic_colors"
    )
    val dynamicColors: Boolean = false,

    @Setting(
        title = "Pure black (OLED)",
        category = Display::class,
        type = Toggle::class,
        key = "oled_black"
    )
    val oledBlack: Boolean = false,

    @Setting(
        title = "Temperature Unit",
        category = Display::class,
        type = Dropdown::class,
        options = ["Celsius", "Fahrenheit"],
        key = "temperature_unit_index"
    )
    val temperatureUnitIndex: Int = 0,

    // DATA
    @Setting(
        title = "Data Retention",
        description = "Also applies to imported history. Storage is periodically trimmed to 100,000 samples and 10,000 sessions, including with Forever selected.",
        category = Data::class,
        type = Dropdown::class,
        options = ["1 week", "1 month", "3 months", "6 months", "1 year", "Forever"],
        key = "data_retention_index"
    )
    val dataRetentionIndex: Int = 2,
)

/** What the status-bar icon of the monitoring notification shows (stored now, rendered in P5a). */
enum class StatusIconValue { LEVEL, CURRENT_MA, POWER_W, TEMPERATURE, STATIC }

/** Settings override for the unit `CURRENT_NOW` reports in; [AUTO] (null [unit]) uses the detected calibration. */
enum class CurrentUnitOverride(val unit: CurrentUnit?) {
    AUTO(null),
    MICROAMPS(CurrentUnit.MICROAMPS),
    MILLIAMPS(CurrentUnit.MILLIAMPS),
}

/** Settings override for the sign of `CURRENT_NOW`; [AUTO] (null [sign]) uses the detected calibration. */
enum class CurrentSignOverride(val sign: CurrentSign?) {
    AUTO(null),
    NORMAL(CurrentSign.NORMAL),
    INVERTED(CurrentSign.INVERTED),
}

/** The design-capacity setting: 0 means automatic (sysfs `charge_full_design`), otherwise mAh in [RANGE_MAH]. */
object DesignCapacity {
    const val AUTO = 0
    val RANGE_MAH = 1_000..30_000

    fun isValid(mAh: Int): Boolean = mAh == AUTO || mAh in RANGE_MAH
}

/**
 * The design capacity for the capacity/health estimators: the setting when it is valid, otherwise
 * [DesignCapacity.AUTO], so an out-of-range stored value can never reach them.
 */
val AppSettings.designCapacityOverrideMah: Int
    get() = designCapacityMah.takeIf(DesignCapacity::isValid) ?: DesignCapacity.AUTO

val AppSettings.useFahrenheit: Boolean get() = temperatureUnitIndex == 1

/** The "Forever" option of [AppSettings.dataRetentionIndex]. */
const val RETENTION_FOREVER_INDEX = 5
@CategoryDefinition(order = 0)
object General

@CategoryDefinition(order = 1)
object Notifications

@CategoryDefinition(order = 2)
object Measurement

@CategoryDefinition(order = 3)
object Display

@CategoryDefinition(order = 4)
object Data
