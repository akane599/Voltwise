package com.akane.voltwise.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.ExportImportManager
import com.akane.voltwise.battery.data.HistoryImportResult
import com.akane.voltwise.battery.data.LimitedHistoryInput
import com.akane.voltwise.battery.data.db.BatteryDatabase
import com.akane.voltwise.battery.service.BatteryMonitorService
import com.akane.voltwise.battery.service.monitoringStateStore
import com.akane.voltwise.battery.service.stopMonitoring
import com.akane.voltwise.battery.data.sampling.KeyValueStore
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.settings.SettingsImportPolicy
import io.github.mlmgames.settings.core.backup.ExportResult
import io.github.mlmgames.settings.core.backup.ImportError
import io.github.mlmgames.settings.core.backup.ImportResult
import io.github.mlmgames.settings.core.backup.SettingsBackupManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException

/** Export period: history from [days] days before the export until it starts; 0 = everything stored. */
enum class HistoryRange(val days: Int) { ALL(0), WEEK(7), MONTH(30) }

/** Everything Settings › Data can run; one at a time. The file tasks start with a system picker. */
enum class DataTask { EXPORT_JSON, EXPORT_CSV, IMPORT_JSON, IMPORT_CSV, SAVE_SETTINGS, RESTORE_SETTINGS, CLEAR }

/** The "Clear all data" confirmation: closed, asking, clearing (locked), or asking again after a failure. */
enum class ClearStep { HIDDEN, CONFIRM, RUNNING, FAILED }

/** Why a task ended without doing its job; the UI turns each into one plain sentence. */
enum class DataFailure {
    /** The history file was checked and refused as a whole ([DataOutcome.Failed.detail] says why). */
    REJECTED,

    /** Not a BatStats history export at all (not JSON, or not the expected shape). */
    NOT_AN_EXPORT,
    UNREADABLE,
    UNWRITABLE,
    SETTINGS_INVALID,
    SETTINGS_OTHER_APP,
    SETTINGS_TOO_NEW,
    SETTINGS_DAMAGED,
    NO_PICKER,
    UNEXPECTED,
}

@Immutable
data class StoredHistory(val samples: Int, val sessions: Int)

/** The result of the last task, shown in the panel that ran it until the next task starts. */
@Immutable
sealed interface DataOutcome {
    val task: DataTask

    data class Exported(override val task: DataTask) : DataOutcome

    data class HistoryImported(override val task: DataTask, val result: HistoryImportResult) : DataOutcome

    data object SettingsSaved : DataOutcome {
        override val task = DataTask.SAVE_SETTINGS
    }

    data class SettingsRestored(val applied: Int, val skipped: Int, val failed: Int) : DataOutcome {
        override val task = DataTask.RESTORE_SETTINGS
    }

    data object Cleared : DataOutcome {
        override val task = DataTask.CLEAR
    }

    /** [detail] is the history check's own reason (English), only for [DataFailure.REJECTED]. */
    data class Failed(override val task: DataTask, val reason: DataFailure, val detail: String? = null) : DataOutcome
}

@Immutable
data class DataUiState(
    /** Null while loading, or when the counts couldn't be read. */
    val stored: StoredHistory? = null,
    val range: HistoryRange = HistoryRange.ALL,
    val includeSamples: Boolean = true,
    val includeSessions: Boolean = true,
    val running: DataTask? = null,
    val outcome: DataOutcome? = null,
    val clear: ClearStep = ClearStep.HIDDEN,
) {
    val idle: Boolean get() = running == null
    val canExport: Boolean get() = idle && (includeSamples || includeSessions)
}

sealed interface DataEvent {
    data class SelectRange(val range: HistoryRange) : DataEvent

    data object ToggleSamples : DataEvent

    data object ToggleSessions : DataEvent

    /** Snapshot an export request in the VM, then open the system picker in the wrapper. */
    data class Pick(val task: DataTask) : DataEvent

    data object RequestClear : DataEvent

    data object DismissClear : DataEvent

    data object ConfirmClear : DataEvent
}

sealed interface SettingsRestore {
    data class Applied(val applied: Int, val skipped: Int, val failed: Int) : SettingsRestore

    data class Rejected(val reason: SettingsRejection) : SettingsRestore
}

/** Why a settings file was refused; in every case no setting changed. */
enum class SettingsRejection { INVALID, OTHER_APP, TOO_NEW, DAMAGED }

/**
 * Everything Settings › Data reads or does. Documents are content URIs as strings (the wrapper's pickers give
 * them); failures are thrown ([IOException]/[SecurityException] for storage, [IllegalArgumentException] when a
 * history file is refused), except settings files, whose refusals are a [SettingsRestore.Rejected].
 */
interface DataRepository {
    suspend fun storedHistory(): StoredHistory
    suspend fun exportJson(uri: String, fromMs: Long, toMs: Long, samples: Boolean, sessions: Boolean)
    suspend fun exportCsv(treeUri: String, fromMs: Long, toMs: Long, samples: Boolean, sessions: Boolean)
    suspend fun importJson(uri: String): HistoryImportResult
    suspend fun importCsv(uri: String): HistoryImportResult
    suspend fun saveSettings(uri: String)
    suspend fun restoreSettings(uri: String): SettingsRestore

    /** Stops monitoring and deletes history; settings and the current calibration stay. */
    suspend fun clearAll()
}

/**
 * Settings › Data: history export/import ([ExportImportManager], format 3), the settings backup
 * ([SettingsImportPolicy]) and "Clear all data". One task runs at a time; its result stays on screen
 * ([DataUiState.outcome]) until the next one starts. The clear confirmation lives here too, so a
 * rotation keeps it and a running clear can't be dismissed.
 */
class DataViewModel(
    private val repository: DataRepository,
    private val savedState: SavedStateHandle,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val range = savedState.getStateFlow(KEY_RANGE, HistoryRange.ALL.name)
    private val includeSamples = savedState.getStateFlow(KEY_SAMPLES, true)
    private val includeSessions = savedState.getStateFlow(KEY_SESSIONS, true)

    // Keep publication synchronous: a Pick immediately after a selection must snapshot that selection.
    private val _state = MutableStateFlow(
        DataUiState(
            range = HistoryRange.entries.firstOrNull { it.name == range.value } ?: HistoryRange.ALL,
            includeSamples = includeSamples.value,
            includeSessions = includeSessions.value,
        ),
    )
    val state: StateFlow<DataUiState> = _state.asStateFlow()

    init {
        refreshStored()
    }

    fun onEvent(event: DataEvent) {
        when (event) {
            is DataEvent.SelectRange -> {
                savedState[KEY_RANGE] = event.range.name
                _state.update { it.copy(range = event.range) }
            }
            DataEvent.ToggleSamples -> {
                savedState[KEY_SAMPLES] = !includeSamples.value
                _state.update { it.copy(includeSamples = includeSamples.value) }
            }
            DataEvent.ToggleSessions -> {
                savedState[KEY_SESSIONS] = !includeSessions.value
                _state.update { it.copy(includeSessions = includeSessions.value) }
            }
            is DataEvent.Pick -> when (event.task) {
                DataTask.EXPORT_JSON, DataTask.EXPORT_CSV -> saveExportRequest(event.task)
                else -> Unit // The wrapper opens the picker.
            }
            DataEvent.RequestClear -> _state.update {
                if (it.idle && it.clear == ClearStep.HIDDEN) it.copy(clear = ClearStep.CONFIRM) else it
            }
            DataEvent.DismissClear -> _state.update { if (it.clear == ClearStep.RUNNING) it else it.copy(clear = ClearStep.HIDDEN) }
            DataEvent.ConfirmClear -> clear()
        }
    }

    /** The picker for [task] returned [uri]; a cancelled picker never calls this. */
    fun onFileChosen(task: DataTask, uri: String) {
        when (task) {
            DataTask.EXPORT_JSON, DataTask.EXPORT_CSV -> export(task, uri)
            DataTask.IMPORT_JSON -> run(task) { DataOutcome.HistoryImported(task, repository.importJson(uri)) }
            DataTask.IMPORT_CSV -> run(task) { DataOutcome.HistoryImported(task, repository.importCsv(uri)) }
            DataTask.SAVE_SETTINGS -> run(task) {
                repository.saveSettings(uri)
                DataOutcome.SettingsSaved
            }
            DataTask.RESTORE_SETTINGS -> run(task) {
                when (val result = repository.restoreSettings(uri)) {
                    is SettingsRestore.Applied -> DataOutcome.SettingsRestored(result.applied, result.skipped, result.failed)
                    is SettingsRestore.Rejected -> DataOutcome.Failed(task, result.reason.failure())
                }
            }
            DataTask.CLEAR -> Unit // Only through the confirmation.
        }
    }

    private fun saveExportRequest(task: DataTask) {
        val current = _state.value
        if (!current.canExport) return
        val from = if (current.range.days == 0) 0L else clock() - current.range.days * DAY_MS
        savedState[exportKey(task)] = ExportRequest(from, current.includeSamples, current.includeSessions)
    }

    private fun export(task: DataTask, uri: String) {
        val request = savedState.remove<ExportRequest>(exportKey(task))
        run(task) {
            if (request == null) return@run DataOutcome.Failed(task, DataFailure.UNEXPECTED)
            if (task == DataTask.EXPORT_JSON) {
                repository.exportJson(uri, request.fromMs, NOW, request.samples, request.sessions)
            } else {
                repository.exportCsv(uri, request.fromMs, NOW, request.samples, request.sessions)
            }
            DataOutcome.Exported(task)
        }
    }

    /** Serializable so SavedStateHandle can restore the launch-time request after process death. */
    private data class ExportRequest(val fromMs: Long, val samples: Boolean, val sessions: Boolean) : java.io.Serializable

    private fun exportKey(task: DataTask): String = "pendingExport.${task.name}"

    /** No app could open a picker for [task]. */
    fun onPickerUnavailable(task: DataTask) {
        savedState.remove<ExportRequest>(exportKey(task))
        _state.update { if (it.idle) it.copy(outcome = DataOutcome.Failed(task, DataFailure.NO_PICKER)) else it }
    }

    private fun run(task: DataTask, block: suspend () -> DataOutcome) {
        if (!begin(task)) return
        viewModelScope.launch {
            val outcome = try {
                block()
            } catch (e: CancellationException) {
                // No outcome to publish, but a surviving ViewModel must not stay busy (that also locks Back).
                _state.update { it.copy(running = null) }
                throw e
            } catch (e: Exception) {
                failure(task, e)
            }
            _state.update { it.copy(running = null, outcome = outcome) }
            if (task == DataTask.IMPORT_JSON || task == DataTask.IMPORT_CSV) refreshStored()
        }
    }

    private fun begin(task: DataTask): Boolean {
        var started = false
        _state.update {
            started = it.running == null
            if (started) it.copy(running = task, outcome = null) else it
        }
        return started
    }

    private fun clear() {
        var started = false
        _state.update {
            started = it.idle && (it.clear == ClearStep.CONFIRM || it.clear == ClearStep.FAILED)
            if (started) it.copy(clear = ClearStep.RUNNING, running = DataTask.CLEAR, outcome = null) else it
        }
        if (!started) return
        viewModelScope.launch {
            val cleared = try {
                repository.clearAll()
                true
            } catch (e: CancellationException) {
                _state.update { it.copy(clear = ClearStep.CONFIRM, running = null) }
                throw e
            } catch (_: Exception) {
                false
            }
            _state.update {
                if (cleared) it.copy(clear = ClearStep.HIDDEN, running = null, outcome = DataOutcome.Cleared)
                else it.copy(clear = ClearStep.FAILED, running = null)
            }
            if (cleared) refreshStored()
        }
    }

    private fun refreshStored() {
        viewModelScope.launch {
            val stored = try {
                repository.storedHistory()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _state.update { it.copy(stored = stored) }
        }
    }

    private companion object {
        const val KEY_RANGE = "data.range"
        const val KEY_SAMPLES = "data.includeSamples"
        const val KEY_SESSIONS = "data.includeSessions"
        const val DAY_MS = 86_400_000L

        /** `to = 0` asks the exporter for "until the export starts". */
        const val NOW = 0L

        fun failure(task: DataTask, e: Exception): DataOutcome.Failed {
            val writes = task == DataTask.EXPORT_JSON || task == DataTask.EXPORT_CSV || task == DataTask.SAVE_SETTINGS
            return when (e) {
                is IOException, is SecurityException ->
                    DataOutcome.Failed(task, if (writes) DataFailure.UNWRITABLE else DataFailure.UNREADABLE)
                // A parser error is technical; say what the file is not instead.
                is SerializationException -> DataOutcome.Failed(task, DataFailure.NOT_AN_EXPORT)
                is IllegalArgumentException, is IllegalStateException ->
                    if (task == DataTask.SAVE_SETTINGS) DataOutcome.Failed(task, DataFailure.UNEXPECTED)
                    else DataOutcome.Failed(task, DataFailure.REJECTED, e.message?.take(200)?.takeIf { it.isNotBlank() })
                else -> DataOutcome.Failed(task, DataFailure.UNEXPECTED)
            }
        }

        fun SettingsRejection.failure(): DataFailure = when (this) {
            SettingsRejection.INVALID -> DataFailure.SETTINGS_INVALID
            SettingsRejection.OTHER_APP -> DataFailure.SETTINGS_OTHER_APP
            SettingsRejection.TOO_NEW -> DataFailure.SETTINGS_TOO_NEW
            SettingsRejection.DAMAGED -> DataFailure.SETTINGS_DAMAGED
        }
    }
}

/** [DataRepository] over Room counts, [ExportImportManager], the kmp-settings backup and [BatteryRepository]. */
class DefaultDataRepository(
    private val context: Context,
    private val db: BatteryDatabase,
    private val history: ExportImportManager,
    private val backup: SettingsBackupManager<AppSettings>,
    private val battery: BatteryRepository,
) : DataRepository {
    override suspend fun storedHistory() = StoredHistory(db.batteryDao().count(), db.sessionDao().count())

    override suspend fun exportJson(uri: String, fromMs: Long, toMs: Long, samples: Boolean, sessions: Boolean) {
        history.exportJson(Uri.parse(uri), fromMs, toMs, samples, sessions)
    }

    override suspend fun exportCsv(treeUri: String, fromMs: Long, toMs: Long, samples: Boolean, sessions: Boolean) {
        history.exportCsvToFolder(Uri.parse(treeUri), fromMs, toMs, samples, sessions)
    }

    override suspend fun importJson(uri: String) = history.importJson(Uri.parse(uri))

    override suspend fun importCsv(uri: String) = history.importCsv(Uri.parse(uri))

    override suspend fun saveSettings(uri: String): Unit = withContext(Dispatchers.IO) {
        val json = when (val result = backup.export()) {
            is ExportResult.Success -> result.json
            is ExportResult.Error -> error("Settings export failed")
        }
        (context.contentResolver.openOutputStream(Uri.parse(uri), "wt") ?: throw IOException("Cannot open the destination"))
            .use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun restoreSettings(uri: String): SettingsRestore = withContext(Dispatchers.IO) {
        val text = try {
            (context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("Cannot open the file")).use { input ->
                LimitedHistoryInput(input, SettingsImportPolicy.MAX_BYTES.toLong()).readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: IllegalArgumentException) {
            return@withContext SettingsRestore.Rejected(SettingsRejection.INVALID) // Larger than a settings backup can be.
        }
        try {
            when (val result = SettingsImportPolicy.import(text, backup)) {
                is ImportResult.Success -> SettingsRestore.Applied(result.appliedCount, result.skippedCount, result.errors.size)
                is ImportResult.Error -> SettingsRestore.Rejected(
                    when (result.error) {
                        ImportError.PARSE_ERROR -> SettingsRejection.INVALID
                        ImportError.APP_MISMATCH -> SettingsRejection.OTHER_APP
                        ImportError.VERSION_TOO_NEW -> SettingsRejection.TOO_NEW
                        ImportError.CHECKSUM_MISMATCH -> SettingsRejection.DAMAGED
                    },
                )
            }
        } catch (_: IllegalArgumentException) {
            SettingsRestore.Rejected(SettingsRejection.INVALID) // SettingsImportPolicy.validate: nothing was written.
        }
    }

    override suspend fun clearAll() = clearAll(battery::clearHistory, monitoringStateStore(context)) {
        context.stopService(Intent(context, BatteryMonitorService::class.java))
    }

    internal companion object {
        suspend fun clearAll(
            clearHistory: suspend (() -> Unit) -> Unit,
            store: KeyValueStore,
            stopService: () -> Unit,
        ) {
            clearHistory { stopMonitoring(store, stopService) }
        }
    }
}
