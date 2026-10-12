package com.akane.voltwise.ui.screens.now

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.R
import com.akane.voltwise.battery.measurement.CurrentCalibration
import com.akane.voltwise.battery.measurement.CurrentSign
import com.akane.voltwise.battery.measurement.CurrentUnit
import com.akane.voltwise.battery.measurement.EtaBasis
import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.test.DeviceEnvironment
import com.akane.voltwise.ui.theme.MainTheme
import com.akane.voltwise.viewmodel.DrainState
import com.akane.voltwise.viewmodel.Eta
import com.akane.voltwise.viewmodel.HeroState
import com.akane.voltwise.viewmodel.NowEvent
import com.akane.voltwise.viewmodel.NowUiState
import com.akane.voltwise.viewmodel.Readouts
import com.akane.voltwise.viewmodel.SinceUnplugState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the stateless [NowContent] with hand-built [NowUiState]s (no ViewModel, no repository): the discharging
 * hero and readouts, charging's "time to full", monitoring off's Start button, the calibration notice's Undo/Keep,
 * and the Reset confirm flow — including the guard that closes the dialog once the since-unplug window it was
 * opened for stops being current (a plug-in while the dialog is open).
 */
@RunWith(AndroidJUnit4::class)
class NowContentDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun label(id: Int, vararg args: Any): String = DeviceEnvironment.context.getString(id, *args)

    private val hour = 3_600_000L
    private val minute = 60_000L
    private val now = 1_779_200_000_000L
    private lateinit var events: MutableList<NowEvent>

    private val sinceUnplug = SinceUnplugState(
        current = true,
        startedAtMs = now - 3 * hour,
        endedAtMs = now - 20_000L,
        screenOn = DrainState(durationMs = 62 * minute, currentMa = 412.0, percentPerHour = 9.1),
        screenOff = DrainState(durationMs = 128 * minute, currentMa = 58.0, percentPerHour = 1.3),
        deepSleepPercent = 86.0,
    )

    private fun discharging() = NowUiState(
        nowMs = now,
        hero = HeroState(
            hasReading = true, level = 67, power = PowerState.DISCHARGING,
            eta = Eta(5 * hour + 40 * minute, EtaBasis.LIVE_RATE), monitoring = true,
        ),
        readouts = Readouts(currentMa = -412.0, powerW = -1.59, temperatureC = 31.5, voltageV = 3.87),
        sinceUnplug = sinceUnplug,
    )

    private fun charging() = discharging().copy(
        hero = HeroState(
            hasReading = true, level = 54, power = PowerState.CHARGING,
            eta = Eta(hour + 12 * minute, EtaBasis.ANDROID), monitoring = true,
        ),
        readouts = Readouts(currentMa = 1_452.0, powerW = 6.21, temperatureC = 34.2, voltageV = 4.28),
    )

    /** Sets [initial] as a mutable holder so a test can push a later state without a 2nd `setContent`. */
    private fun setContent(initial: NowUiState): androidx.compose.runtime.MutableState<NowUiState> {
        events = mutableListOf()
        val holder = mutableStateOf(initial)
        compose.setContent {
            MainTheme(dynamicColor = false) {
                NowContent(state = holder.value, onEvent = { events += it })
            }
        }
        return holder
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test fun dischargingShowsTheHeroAndReadouts() {
        setContent(discharging())
        compose.onNodeWithText(label(R.string.now_state_discharging)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.percent_value, "67")).assertIsDisplayed()
        compose.onNode(hasText(label(R.string.now_eta_left), substring = true)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.now_readout_current)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.now_readout_power)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.now_readout_temperature)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.now_readout_voltage)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.now_stop_monitoring)).assertIsDisplayed()
    }

    @Test fun chargingShowsTimeToFullInsteadOfTimeLeft() {
        setContent(charging())
        compose.onNodeWithText(label(R.string.now_state_charging)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.percent_value, "54")).assertIsDisplayed()
        compose.onNode(hasText(label(R.string.now_eta_to_full), substring = true)).assertIsDisplayed()
        compose.onAllNodesWithText(label(R.string.now_eta_left), substring = true).assertCountEquals(0)
        compose.onNodeWithText(label(R.string.now_stop_monitoring)).assertIsDisplayed()
    }

    @Test fun monitoringOffShowsStartInsteadOfStop() {
        setContent(discharging().copy(hero = discharging().hero.copy(monitoring = false)))
        compose.onNodeWithText(label(R.string.now_start_monitoring)).assertIsDisplayed()
        compose.onAllNodesWithText(label(R.string.now_stop_monitoring)).assertCountEquals(0)
    }

    @Test fun calibrationNoticeFiresUndoAndKeep() {
        setContent(discharging().copy(calibrationNotice = CurrentCalibration(CurrentUnit.MILLIAMPS, CurrentSign.NORMAL)))
        compose.onNodeWithText(label(R.string.calibration_notice_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.calibration_notice_unit)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.calibration_notice_undo)).performClick()
        assertEquals(listOf(NowEvent.UndoCalibration), events)
        compose.onNodeWithText(label(R.string.calibration_notice_keep)).performClick()
        assertEquals(listOf(NowEvent.UndoCalibration, NowEvent.KeepCalibration), events)
    }

    @Test fun resetConfirmFiresTheEventAndCancelDoesNothing() {
        setContent(discharging())
        scrollTo(label(R.string.now_reset))
        compose.onNodeWithText(label(R.string.now_reset)).performClick()
        compose.onNodeWithText(label(R.string.now_reset_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.now_reset_body)).assertIsDisplayed()

        // Cancel: no event, dialog closes.
        compose.onNodeWithText(label(R.string.now_cancel)).performClick()
        compose.onAllNodesWithText(label(R.string.now_reset_title)).assertCountEquals(0)
        assertTrue(events.isEmpty())

        // Reset again, this time confirm: the event fires and the dialog closes. Its confirm button reads
        // "Reset" too (same word as the panel's own button behind it), so the click is scoped to the dialog.
        compose.onNodeWithText(label(R.string.now_reset)).performClick()
        compose.onNode(hasText(label(R.string.now_reset_confirm)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf(NowEvent.ResetObservation), events)
        compose.onAllNodesWithText(label(R.string.now_reset_title)).assertCountEquals(0)
    }

    @Test fun resetDialogClosesWhenItsWindowStopsBeingCurrent() {
        val holder = setContent(discharging())
        scrollTo(label(R.string.now_reset))
        compose.onNodeWithText(label(R.string.now_reset)).performClick()
        compose.onNodeWithText(label(R.string.now_reset_title)).assertIsDisplayed()

        // Plugging in while the dialog is open ends the window the confirmation was opened for.
        compose.runOnUiThread {
            holder.value = holder.value.copy(sinceUnplug = sinceUnplug.copy(current = false, endedAtMs = now - 5_000L))
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(label(R.string.now_reset_title)).fetchSemanticsNodes().isEmpty()
        }
        assertFalse("Reset must not fire for a window the dialog was no longer open for", events.contains(NowEvent.ResetObservation))
    }
}
