package com.akane.voltwise.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akane.voltwise.battery.measurement.CalibrationSource
import com.akane.voltwise.battery.measurement.CalibrationState
import com.akane.voltwise.battery.measurement.CurrentCalibration
import com.akane.voltwise.battery.measurement.CurrentSign
import com.akane.voltwise.battery.measurement.CurrentUnit
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.ui.PhonePreview
import com.akane.voltwise.ui.ScreenPreviews
import com.akane.voltwise.ui.ScreenshotTheme
import com.akane.voltwise.ui.TallPhonePreview
import com.akane.voltwise.viewmodel.SettingsError
import com.akane.voltwise.viewmodel.SettingsUiState
import com.android.tools.screenshot.PreviewTest

/** Scrolls a phone past Monitoring and Alerts so the Measurement panel fills the frame. */
private val MeasurementScrollOffset = 560.dp

private val detected = CurrentCalibration(CurrentUnit.MILLIAMPS, CurrentSign.INVERTED)

/** Settings content with no-op callbacks; Material You shown (API 31+). */
@Composable
private fun SettingsFixture(state: SettingsUiState = SettingsUiState(), scrollTo: Dp = 0.dp) {
    val scrollPx = with(LocalDensity.current) { scrollTo.roundToPx() }
    SettingsContent(
        state = state,
        onEvent = {},
        dynamicColorAvailable = true,
        scrollState = rememberScrollState(scrollPx),
    )
}

/** A fresh install: defaults, nothing detected yet. */
@PreviewTest
@ScreenPreviews
@Composable
fun BatterySettingsScreenPreview() {
    ScreenshotTheme {
        SettingsFixture()
    }
}

/** Pure black switched on (and applied). */
@PreviewTest
@PhonePreview
@Composable
fun BatterySettingsScreenOledPreview() {
    ScreenshotTheme(oledBlack = true) {
        SettingsFixture(SettingsUiState(settings = AppSettings(oledBlack = true)))
    }
}

/** Detection found mA with an inverted sign over 4 counter windows; a design capacity is set. */
@PreviewTest
@TallPhonePreview
@Composable
fun BatterySettingsScreenCalibrationPreview() {
    ScreenshotTheme {
        SettingsFixture(
            SettingsUiState(
                settings = AppSettings(designCapacityMah = 4_500, temperatureUnitIndex = 1),
                calibration = CalibrationState(
                    effective = detected,
                    detected = detected,
                    source = CalibrationSource.DETECTED,
                    agreeingWindows = 4,
                ),
            ),
            scrollTo = MeasurementScrollOffset,
        )
    }
}

/** Scrolls the 2x Spanish phone to the Measurement panel's two option rows. */
private val MeasurementLargeFontScrollOffset = 1_360.dp

/**
 * Spanish at 2x font on a 360 dp phone: the unit and sign options ("Automático", "Microamperios") no longer fit a
 * third of the row each, so they stack instead of cutting to "Autom…".
 */
@PreviewTest
@Preview(name = "W360H1000Font2Es", widthDp = 360, heightDp = 1000, fontScale = 2f, locale = "es")
@Composable
fun BatterySettingsScreenLargeFontOptionsPreview() {
    ScreenshotTheme {
        SettingsFixture(
            SettingsUiState(
                calibration = CalibrationState(
                    effective = detected,
                    detected = detected,
                    source = CalibrationSource.DETECTED,
                    agreeingWindows = 1,
                ),
            ),
            scrollTo = MeasurementLargeFontScrollOffset,
        )
    }
}

/** Android blocks the app's notifications (permission denied): the notice leading Alerts, with Turn on. */
@PreviewTest
@PhonePreview
@Composable
fun BatterySettingsScreenNotificationsOffPreview() {
    ScreenshotTheme {
        SettingsFixture(SettingsUiState(notificationsEnabled = false))
    }
}

/** Scrolls the tall phone to the end (clamped), where the Data panel sits on a phone. */
private val RetentionScrollOffset = 2_000.dp

/** Settings recovered from corruption without a retention choice: Keep history reads "not set", cleanup paused. */
@PreviewTest
@TallPhonePreview
@Composable
fun BatterySettingsScreenRetentionUnsetPreview() {
    ScreenshotTheme {
        SettingsFixture(SettingsUiState(retentionUnset = true), scrollTo = RetentionScrollOffset)
    }
}

/** A write the store refused: the inline error under the title. */
@PreviewTest
@PhonePreview
@Composable
fun BatterySettingsScreenErrorPreview() {
    ScreenshotTheme {
        SettingsFixture(SettingsUiState(error = SettingsError.WRITE_FAILED))
    }
}
