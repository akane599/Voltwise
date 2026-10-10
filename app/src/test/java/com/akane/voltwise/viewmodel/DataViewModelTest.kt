package com.akane.voltwise.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.akane.voltwise.battery.data.HistoryImportResult
import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.service.MonitoringControl
import com.akane.voltwise.battery.service.MonitoringController
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DataViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeDataRepository()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.start(saved: SavedStateHandle = SavedStateHandle(), now: Long = NOW): DataViewModel =
        DataViewModel(repo, saved, clock = { now }).also { runCurrent() }

    @Test fun clearAllPersistsMonitoringOptOutBeforeStopAndDeletion() = runTest {
        val store = FakeKeyValueStore(mapOf("monitoring_wanted" to "true"))
        fun controller() = MonitoringController(
            MutableStateFlow(false), store, { MonitoringControl.StartResult.STARTED }, {},
        )
        val maintenance = HistoryMaintenance()
        val events = mutableListOf<String>()
        DefaultDataRepository.clearAll(
            clearHistory = { stop ->
                maintenance.clear(
                    stopMonitoring = { stop() },
                    delete = {
                        assertFalse("Cleared history must not resume monitoring after an update", controller().monitoringWanted)
                        events += "delete"
                    },
                )
            },
            store = store,
            stopService = {
                assertTrue("The history-clear guard must be held while stopping", maintenance.isClearing)
                assertFalse("Clear must persist the opt-out before stopping the service", controller().monitoringWanted)
                events += "stop"
            },
        )
        assertEquals(listOf("stop", "delete"), events)
        assertFalse(controller().monitoringWanted)
    }

    @Test fun storedCountsLoadOnOpenAndRefreshAfterAnImport() = runTest {
        repo.stored = StoredHistory(samples = 12_480, sessions = 86)
        val vm = start()
        assertEquals(StoredHistory(12_480, 86), vm.state.value.stored)

        repo.stored = StoredHistory(samples = 13_000, sessions = 90)
        vm.onFileChosen(DataTask.IMPORT_JSON, "content://import.json")
        runCurrent()
        assertEquals(StoredHistory(13_000, 90), vm.state.value.stored)

        // Counts that can't be read show as unknown, not as zero.
        repo.storedFailure = IOException("disk")
        vm.onFileChosen(DataTask.IMPORT_CSV, "content://import.csv")
        runCurrent()
        assertNull(vm.state.value.stored)
    }

    @Test fun selectedRangeAndBothIncludesSurviveViewModelAndSavedStateRecreation() = runTest {
        val saved = SavedStateHandle()
        val vm = start(saved)
        vm.onEvent(DataEvent.SelectRange(HistoryRange.MONTH))
        vm.onEvent(DataEvent.ToggleSamples)
        vm.onEvent(DataEvent.ToggleSessions)
        assertEquals(HistoryRange.MONTH, vm.state.value.range)
        assertEquals(false, vm.state.value.includeSamples)
        assertEquals(false, vm.state.value.includeSessions)

        val recreated = start(saved)
        assertEquals("the shared saved handle restores the selected range", HistoryRange.MONTH, recreated.state.value.range)
        assertEquals(false, recreated.state.value.includeSamples)
        assertEquals(false, recreated.state.value.includeSessions)

        val restored = restoreSavedState(saved)
        val afterDeath = start(restored)
        assertEquals("a restored saved handle retains the selected range", HistoryRange.MONTH, afterDeath.state.value.range)
        assertEquals(false, afterDeath.state.value.includeSamples)
        assertEquals(false, afterDeath.state.value.includeSessions)
        assertEquals(false, afterDeath.state.value.canExport)
        assertTrue("enum selections are saved by name", saved.keys().any { saved.get<Any?>(it) == HistoryRange.MONTH.name })

        afterDeath.onEvent(DataEvent.ToggleSessions)
        afterDeath.onEvent(DataEvent.Pick(DataTask.EXPORT_JSON))
        afterDeath.onFileChosen(DataTask.EXPORT_JSON, "content://restored-selections.json")
        runCurrent()
        assertEquals(
            Export("json", "content://restored-selections.json", NOW - 30 * DAY, 0L, false, true),
            repo.exports.single(),
        )
    }

    @Test fun exportUsesTheSelectedPeriodAndIncludesAndReportsItsFormat() = runTest {
        val vm = start()
        vm.onEvent(DataEvent.SelectRange(HistoryRange.WEEK))
        vm.onEvent(DataEvent.ToggleSessions)
        vm.onEvent(DataEvent.Pick(DataTask.EXPORT_JSON))
        vm.onFileChosen(DataTask.EXPORT_JSON, "content://out.json")
        runCurrent()
        assertEquals(listOf(Export("json", "content://out.json", NOW - 7 * DAY, 0L, true, false)), repo.exports)
        assertEquals(DataOutcome.Exported(DataTask.EXPORT_JSON), vm.state.value.outcome)

        vm.onEvent(DataEvent.SelectRange(HistoryRange.ALL))
        vm.onEvent(DataEvent.ToggleSessions)
        vm.onEvent(DataEvent.Pick(DataTask.EXPORT_CSV))
        vm.onFileChosen(DataTask.EXPORT_CSV, "content://tree")
        runCurrent()
        assertEquals(Export("csv", "content://tree", 0L, 0L, true, true), repo.exports.last())
        assertEquals(DataOutcome.Exported(DataTask.EXPORT_CSV), vm.state.value.outcome)

        // Neither readings nor sessions: nothing to export.
        vm.onEvent(DataEvent.ToggleSamples)
        vm.onEvent(DataEvent.ToggleSessions)
        assertEquals(false, vm.state.value.canExport)
        vm.onEvent(DataEvent.Pick(DataTask.EXPORT_JSON))
        vm.onFileChosen(DataTask.EXPORT_JSON, "content://none.json")
        runCurrent()
        assertEquals(2, repo.exports.size)
    }

    @Test fun launchedExportSurvivesProcessDeathAndIsConsumedOnce() = runTest {
        for (task in listOf(DataTask.EXPORT_JSON, DataTask.EXPORT_CSV)) {
            val saved = SavedStateHandle()
            val vm = start(saved)
            vm.onEvent(DataEvent.SelectRange(HistoryRange.WEEK))
            vm.onEvent(DataEvent.ToggleSamples)
            vm.onEvent(DataEvent.Pick(task))
            // Later edits must not change the request that already launched the picker.
            vm.onEvent(DataEvent.SelectRange(HistoryRange.ALL))
            vm.onEvent(DataEvent.ToggleSamples)
            vm.onEvent(DataEvent.ToggleSessions)

            val restored = restoreSavedState(saved)
            val recreated = start(restored, now = NOW + DAY)
            assertEquals(HistoryRange.ALL, recreated.state.value.range)
            assertTrue(recreated.state.value.includeSamples)
            assertEquals(false, recreated.state.value.includeSessions)
            recreated.onFileChosen(task, "content://restored")
            runCurrent()
            val format = if (task == DataTask.EXPORT_JSON) "json" else "csv"
            assertEquals(
                Export(format, "content://restored", NOW - 7 * DAY, 0L, false, true),
                repo.exports.last(),
            )
            assertEquals(DataOutcome.Exported(task), recreated.state.value.outcome)
            assertNull("the launched request is cleared after completion", restored.get<Any?>("pendingExport.${task.name}"))

            val count = repo.exports.size
            recreated.onFileChosen(task, "content://duplicate")
            runCurrent()
            assertEquals("a consumed request cannot export again", count, repo.exports.size)
            assertEquals(DataOutcome.Failed(task, DataFailure.UNEXPECTED), recreated.state.value.outcome)
        }
    }

    @Test fun missingLaunchedRequestFailsWithoutExportingDefaults() = runTest {
        val vm = start()
        for (task in listOf(DataTask.EXPORT_JSON, DataTask.EXPORT_CSV)) {
            vm.onFileChosen(task, "content://no-request")
            runCurrent()
            assertEquals(DataOutcome.Failed(task, DataFailure.UNEXPECTED), vm.state.value.outcome)
            assertTrue("no saved request must not export all history", repo.exports.isEmpty())
        }
    }

    @Test fun unavailableExportPickerDiscardsItsLaunchedRequest() = runTest {
        val saved = SavedStateHandle()
        val vm = start(saved)
        vm.onEvent(DataEvent.Pick(DataTask.EXPORT_JSON))
        vm.onPickerUnavailable(DataTask.EXPORT_JSON)
        assertEquals(DataOutcome.Failed(DataTask.EXPORT_JSON, DataFailure.NO_PICKER), vm.state.value.outcome)
        assertNull("an unavailable picker discards only its launched request", saved.get<Any?>("pendingExport.EXPORT_JSON"))
        vm.onFileChosen(DataTask.EXPORT_JSON, "content://late")
        runCurrent()
        assertTrue(repo.exports.isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun restoreSavedState(saved: SavedStateHandle): SavedStateHandle {
        // Round-trip the payload through serialization, not shared in-memory request objects.
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { output ->
            output.writeObject(HashMap(saved.keys().associateWith { saved.get<Any?>(it) }))
        }
        val values = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { input ->
            input.readObject() as Map<String, Any?>
        }
        return SavedStateHandle(values)
    }

    @Test fun oneTaskAtATimeAndItsResultReplacesThePreviousOne() = runTest {
        val vm = start()
        vm.onFileChosen(DataTask.SAVE_SETTINGS, "content://settings.json")
        runCurrent()
        assertEquals(DataOutcome.SettingsSaved, vm.state.value.outcome)

        val gate = CompletableDeferred<Unit>()
        repo.importGate = gate
        vm.onFileChosen(DataTask.IMPORT_JSON, "content://a.json")
        runCurrent()
        assertEquals(DataTask.IMPORT_JSON, vm.state.value.running)
        assertNull("a new task clears the last result", vm.state.value.outcome)
        assertEquals(false, vm.state.value.idle)

        // Ignored while busy: another file task, the clear confirmation and a missing picker.
        vm.onFileChosen(DataTask.IMPORT_CSV, "content://b.csv")
        vm.onEvent(DataEvent.RequestClear)
        vm.onPickerUnavailable(DataTask.EXPORT_JSON)
        runCurrent()
        assertEquals(listOf("content://a.json"), repo.imports)
        assertEquals(ClearStep.HIDDEN, vm.state.value.clear)
        assertNull(vm.state.value.outcome)

        gate.complete(Unit)
        runCurrent()
        assertNull(vm.state.value.running)
        assertEquals(DataOutcome.HistoryImported(DataTask.IMPORT_JSON, IMPORTED), vm.state.value.outcome)
    }

    /** `running` gates Back on the Data screen: busy for the task's whole commit, idle again after any ending. */
    @Test fun runningFlagThatLocksBackLastsExactlyAsLongAsTheTask() = runTest {
        val vm = start()
        assertTrue("Back is free before any task", vm.state.value.idle)

        // Success.
        repo.importGate = CompletableDeferred()
        vm.onFileChosen(DataTask.IMPORT_CSV, "content://ok.csv")
        runCurrent()
        assertEquals(DataTask.IMPORT_CSV, vm.state.value.running)
        repo.importGate?.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.idle)
        assertEquals(DataOutcome.HistoryImported(DataTask.IMPORT_CSV, IMPORTED), vm.state.value.outcome)

        // Failure.
        repo.importGate = CompletableDeferred()
        repo.importFailure = IOException("gone")
        vm.onFileChosen(DataTask.IMPORT_JSON, "content://gone.json")
        runCurrent()
        assertEquals(DataTask.IMPORT_JSON, vm.state.value.running)
        repo.importGate?.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.idle)
        assertEquals(DataOutcome.Failed(DataTask.IMPORT_JSON, DataFailure.UNREADABLE), vm.state.value.outcome)

        // Cancelled work while the ViewModel lives on: no outcome is invented, and Back is not locked forever.
        repo.importGate = null
        repo.importFailure = CancellationException("cancelled under the task")
        vm.onFileChosen(DataTask.IMPORT_JSON, "content://cancelled.json")
        runCurrent()
        assertTrue(vm.state.value.idle)
        assertNull(vm.state.value.outcome)

        repo.clearFailure = CancellationException("cancelled under the clear")
        vm.onEvent(DataEvent.RequestClear)
        vm.onEvent(DataEvent.ConfirmClear)
        runCurrent()
        assertTrue(vm.state.value.idle)
        assertEquals("the confirmation asks again instead of staying locked", ClearStep.CONFIRM, vm.state.value.clear)
        assertNull(vm.state.value.outcome)
    }

    @Test fun historyFailuresBecomePlainReasons() = runTest {
        val vm = start()
        repo.importFailure = IllegalArgumentException("Import conflicts with a local observation")
        vm.onFileChosen(DataTask.IMPORT_JSON, "content://conflict.json")
        runCurrent()
        assertEquals(
            DataOutcome.Failed(DataTask.IMPORT_JSON, DataFailure.REJECTED, "Import conflicts with a local observation"),
            vm.state.value.outcome,
        )

        repo.importFailure = SerializationException("Unexpected JSON token at offset 0")
        vm.onFileChosen(DataTask.IMPORT_JSON, "content://photo.jpg")
        runCurrent()
        assertEquals(DataOutcome.Failed(DataTask.IMPORT_JSON, DataFailure.NOT_AN_EXPORT), vm.state.value.outcome)

        repo.importFailure = IOException("gone")
        vm.onFileChosen(DataTask.IMPORT_JSON, "content://gone.json")
        runCurrent()
        assertEquals(DataOutcome.Failed(DataTask.IMPORT_JSON, DataFailure.UNREADABLE), vm.state.value.outcome)

        vm.onFileChosen(DataTask.IMPORT_CSV, "content://gone.csv")
        runCurrent()
        assertEquals(DataOutcome.Failed(DataTask.IMPORT_CSV, DataFailure.UNREADABLE), vm.state.value.outcome)

        repo.exportFailure = SecurityException("revoked")
        vm.onEvent(DataEvent.Pick(DataTask.EXPORT_JSON))
        vm.onFileChosen(DataTask.EXPORT_JSON, "content://revoked.json")
        runCurrent()
        assertEquals(DataOutcome.Failed(DataTask.EXPORT_JSON, DataFailure.UNWRITABLE), vm.state.value.outcome)

        repo.exportFailure = IOException("destination could not be opened")
        vm.onEvent(DataEvent.Pick(DataTask.EXPORT_JSON))
        vm.onFileChosen(DataTask.EXPORT_JSON, "content://unwritable.json")
        runCurrent()
        assertEquals(DataOutcome.Failed(DataTask.EXPORT_JSON, DataFailure.UNWRITABLE), vm.state.value.outcome)

        repo.exportFailure = UnsupportedOperationException("x")
        vm.onEvent(DataEvent.Pick(DataTask.EXPORT_CSV))
        vm.onFileChosen(DataTask.EXPORT_CSV, "content://tree")
        runCurrent()
        assertEquals(DataOutcome.Failed(DataTask.EXPORT_CSV, DataFailure.UNEXPECTED), vm.state.value.outcome)

        vm.onPickerUnavailable(DataTask.RESTORE_SETTINGS)
        assertEquals(DataOutcome.Failed(DataTask.RESTORE_SETTINGS, DataFailure.NO_PICKER), vm.state.value.outcome)
    }

    @Test fun settingsExportFailureIsNotAnImportRejection() = runTest {
        val vm = start()
        repo.saveFailure = IllegalStateException("Settings export failed")

        vm.onFileChosen(DataTask.SAVE_SETTINGS, "content://settings.json")
        runCurrent()

        assertEquals(
            "a failed settings export must not report an import rejection or expose its technical detail",
            DataOutcome.Failed(DataTask.SAVE_SETTINGS, DataFailure.UNEXPECTED),
            vm.state.value.outcome,
        )
        assertTrue("a failed settings export releases the running task", vm.state.value.idle)
    }

    @Test fun settingsRestoreAppliesOrSaysWhyNothingChanged() = runTest {
        val vm = start()
        repo.restore = SettingsRestore.Applied(applied = 17, skipped = 1, failed = 0)
        vm.onFileChosen(DataTask.RESTORE_SETTINGS, "content://settings.json")
        runCurrent()
        assertEquals(DataOutcome.SettingsRestored(17, 1, 0), vm.state.value.outcome)

        val expected = mapOf(
            SettingsRejection.INVALID to DataFailure.SETTINGS_INVALID,
            SettingsRejection.OTHER_APP to DataFailure.SETTINGS_OTHER_APP,
            SettingsRejection.TOO_NEW to DataFailure.SETTINGS_TOO_NEW,
            SettingsRejection.DAMAGED to DataFailure.SETTINGS_DAMAGED,
        )
        expected.forEach { (rejection, failure) ->
            repo.restore = SettingsRestore.Rejected(rejection)
            vm.onFileChosen(DataTask.RESTORE_SETTINGS, "content://bad.json")
            runCurrent()
            assertEquals(DataOutcome.Failed(DataTask.RESTORE_SETTINGS, failure), vm.state.value.outcome)
        }
        assertEquals(5, repo.restores)

        repo.saveFailure = IOException("full")
        vm.onFileChosen(DataTask.SAVE_SETTINGS, "content://out.json")
        runCurrent()
        assertEquals(DataOutcome.Failed(DataTask.SAVE_SETTINGS, DataFailure.UNWRITABLE), vm.state.value.outcome)
    }

    @Test fun clearRunsOnlyFromTheConfirmationIsLockedWhileRunningAndCanBeRetried() = runTest {
        repo.stored = StoredHistory(500, 4)
        val vm = start()

        // Only through the dialog: a stray clear task or confirmation does nothing.
        vm.onFileChosen(DataTask.CLEAR, "content://x")
        vm.onEvent(DataEvent.ConfirmClear)
        runCurrent()
        assertEquals(0, repo.clears)

        vm.onEvent(DataEvent.RequestClear)
        assertEquals(ClearStep.CONFIRM, vm.state.value.clear)
        vm.onEvent(DataEvent.DismissClear)
        assertEquals(ClearStep.HIDDEN, vm.state.value.clear)
        assertEquals(0, repo.clears)

        // A failure keeps the dialog open to retry.
        repo.clearFailure = IllegalStateException("History is already being cleared")
        vm.onEvent(DataEvent.RequestClear)
        vm.onEvent(DataEvent.ConfirmClear)
        runCurrent()
        assertEquals(ClearStep.FAILED, vm.state.value.clear)
        assertTrue(vm.state.value.idle)
        assertEquals(1, repo.clears)

        // While running it can't be dismissed or started twice.
        repo.clearFailure = null
        val gate = CompletableDeferred<Unit>()
        repo.clearGate = gate
        vm.onEvent(DataEvent.ConfirmClear)
        runCurrent()
        assertEquals(ClearStep.RUNNING, vm.state.value.clear)
        assertEquals(DataTask.CLEAR, vm.state.value.running)
        vm.onEvent(DataEvent.DismissClear)
        vm.onEvent(DataEvent.ConfirmClear)
        runCurrent()
        assertEquals(ClearStep.RUNNING, vm.state.value.clear)
        assertEquals(2, repo.clears)

        repo.stored = StoredHistory(0, 0)
        gate.complete(Unit)
        runCurrent()
        with(vm.state.value) {
            assertEquals(ClearStep.HIDDEN, clear)
            assertNull(running)
            assertEquals(DataOutcome.Cleared, outcome)
            assertEquals(StoredHistory(0, 0), stored)
        }
    }

    data class Export(val format: String, val uri: String, val from: Long, val to: Long, val samples: Boolean, val sessions: Boolean)

    private class FakeDataRepository : DataRepository {
        var stored = StoredHistory(0, 0)
        var storedFailure: Exception? = null
        val exports = mutableListOf<Export>()
        var exportFailure: Exception? = null
        val imports = mutableListOf<String>()
        var importFailure: Exception? = null
        var importGate: CompletableDeferred<Unit>? = null
        var saveFailure: Exception? = null
        var restore: SettingsRestore = SettingsRestore.Applied(0, 0, 0)
        var restores = 0
        var clears = 0
        var clearFailure: Exception? = null
        var clearGate: CompletableDeferred<Unit>? = null

        override suspend fun storedHistory(): StoredHistory = storedFailure?.let { throw it } ?: stored

        override suspend fun exportJson(uri: String, fromMs: Long, toMs: Long, samples: Boolean, sessions: Boolean) {
            exportFailure?.let { throw it }
            exports += Export("json", uri, fromMs, toMs, samples, sessions)
        }

        override suspend fun exportCsv(treeUri: String, fromMs: Long, toMs: Long, samples: Boolean, sessions: Boolean) {
            exportFailure?.let { throw it }
            exports += Export("csv", treeUri, fromMs, toMs, samples, sessions)
        }

        override suspend fun importJson(uri: String): HistoryImportResult = import(uri)

        override suspend fun importCsv(uri: String): HistoryImportResult = import(uri)

        private suspend fun import(uri: String): HistoryImportResult {
            imports += uri
            importGate?.await()
            importFailure?.let { throw it }
            return IMPORTED
        }

        override suspend fun saveSettings(uri: String) {
            saveFailure?.let { throw it }
        }

        override suspend fun restoreSettings(uri: String): SettingsRestore {
            restores++
            return restore
        }

        override suspend fun clearAll() {
            clears++
            clearGate?.await()
            clearFailure?.let { throw it }
        }
    }

    private companion object {
        const val NOW = 1_760_001_600_000L
        const val DAY = 86_400_000L
        val IMPORTED = HistoryImportResult(samplesAdded = 1_240, sessionsAdded = 3, sessionsUpdated = 2, unchanged = 5)
    }
}
