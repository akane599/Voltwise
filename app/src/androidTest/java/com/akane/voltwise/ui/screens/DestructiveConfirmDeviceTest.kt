package com.akane.voltwise.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.R
import com.akane.voltwise.battery.data.HistoryImportResult
import com.akane.voltwise.test.DeviceEnvironment
import com.akane.voltwise.ui.theme.MainTheme
import com.akane.voltwise.viewmodel.DataRepository
import com.akane.voltwise.viewmodel.DataViewModel
import com.akane.voltwise.viewmodel.SettingsRestore
import com.akane.voltwise.viewmodel.StoredHistory
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Data's "Clear all data" runs only after its confirmation; while it runs the dialog is locked (no
 * dismiss by Back or Cancel); a failed clear keeps the dialog open with its error and can be retried. Drives
 * [DataContent] with the real [DataViewModel] over a fake repository; the result is on screen (no snackbar).
 */
@RunWith(AndroidJUnit4::class)
class DestructiveConfirmDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun label(id: Int) = DeviceEnvironment.context.getString(id)

    private class FakeData(private val onClear: suspend () -> Unit) : DataRepository {
        override suspend fun storedHistory() = StoredHistory(12_480, 86)
        override suspend fun exportJson(uri: String, fromMs: Long, toMs: Long, samples: Boolean, sessions: Boolean) = Unit
        override suspend fun exportCsv(treeUri: String, fromMs: Long, toMs: Long, samples: Boolean, sessions: Boolean) = Unit
        override suspend fun importJson(uri: String) = HistoryImportResult(0, 0, 0, 0)
        override suspend fun importCsv(uri: String) = HistoryImportResult(0, 0, 0, 0)
        override suspend fun saveSettings(uri: String) = Unit
        override suspend fun restoreSettings(uri: String) = SettingsRestore.Applied(0, 0, 0)
        override suspend fun clearAll() = onClear()
    }

    private fun openClearDialog(onClear: suspend () -> Unit) {
        val vm = DataViewModel(FakeData(onClear), SavedStateHandle())
        compose.setContent {
            MainTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                DataContent(state = state, onEvent = vm::onEvent, onBack = {})
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(R.string.data_clear_action)))
        compose.onNodeWithText(label(R.string.data_clear_action)).performClick()
        compose.onNodeWithText(label(R.string.data_clear_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.data_clear_body)).assertIsDisplayed()
    }

    private fun awaitText(id: Int) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertDialogClosed() = compose.onAllNodesWithText(label(R.string.data_clear_title)).assertCountEquals(0)

    @Test fun clearDataRunsOnlyAfterConfirmation() {
        var clears = 0
        openClearDialog(onClear = { clears++ })
        assertEquals(0, clears)
        compose.onNodeWithText(label(R.string.data_clear_confirm)).performClick()
        awaitText(R.string.data_clear_done)
        assertEquals(1, clears)
        assertDialogClosed()
    }

    @Test fun cancellingClearDataDoesNothing() {
        var clears = 0
        openClearDialog(onClear = { clears++ })
        compose.onNodeWithText(label(R.string.data_clear_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(0, clears)
        assertDialogClosed()
        compose.onAllNodesWithText(label(R.string.data_clear_done)).assertCountEquals(0)
    }

    @Test fun clearDataLocksTheDialogWhileRunning() {
        val finish = CompletableDeferred<Unit>()
        var clears = 0
        openClearDialog(onClear = {
            clears++
            finish.await()
        })
        compose.onNodeWithText(label(R.string.data_clear_confirm)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.data_clear_confirm)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.data_clear_cancel)).assertIsNotEnabled()
        DeviceEnvironment.device.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.data_clear_title)).assertIsDisplayed()
        finish.complete(Unit)
        awaitText(R.string.data_clear_done)
        assertEquals(1, clears)
        assertDialogClosed()
    }

    @Test fun failedClearDataKeepsDialogOpenAndCanBeRetried() {
        var clears = 0
        openClearDialog(onClear = {
            clears++
            if (clears == 1) throw IllegalStateException("simulated storage failure")
        })
        compose.onNodeWithText(label(R.string.data_clear_confirm)).performClick()
        awaitText(R.string.data_clear_failed)
        assertEquals(1, clears)
        compose.onNodeWithText(label(R.string.data_clear_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.data_clear_confirm)).assertIsEnabled().performClick()
        awaitText(R.string.data_clear_done)
        assertEquals(2, clears)
        assertDialogClosed()
    }
}
