package com.akane.voltwise.viewmodel

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akane.voltwise.BuildConfig
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.CalibrationStore
import com.akane.voltwise.battery.data.DesignCapacitySource
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.diagnostics.DiagnosticEvent
import com.akane.voltwise.battery.diagnostics.DiagnosticReport
import com.akane.voltwise.battery.diagnostics.DiagnosticStore
import com.akane.voltwise.battery.measurement.CalibrationSource
import com.akane.voltwise.battery.measurement.CalibrationState
import com.akane.voltwise.battery.measurement.CurrentCalibration
import com.akane.voltwise.battery.measurement.CurrentSign
import com.akane.voltwise.battery.measurement.CurrentUnit
import com.akane.voltwise.battery.shizuku.ShizukuBridge
import com.akane.voltwise.battery.util.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** The privileged backend BatStats reads per-app statistics through ([ShellRunner.Mode]). */
enum class AccessMode { SHIZUKU, ROOT, ADB, NONE }

@Immutable
data class AccessState(
    val mode: AccessMode = AccessMode.NONE,
    /** A probe is running (on open, after a Shizuku change, or "Check again"). */
    val checking: Boolean = false,
    val shizuku: ShizukuState = ShizukuState(),
    val adbCommands: List<String> = emptyList(),
    /** False from Android 16: ADB grants still read the battery, but Android refuses the per-app dump. */
    val adbCoversAppStats: Boolean = true,
) {
    val canAuthorizeShizuku: Boolean get() = shizuku.running && !shizuku.granted && !shizuku.blocked
}

/** The calibration in use and where it comes from; [notice] is the correction awaiting Undo/Keep. */
@Immutable
data class CalibrationStatus(
    val unit: CurrentUnit = CurrentUnit.MICROAMPS,
    val sign: CurrentSign = CurrentSign.NORMAL,
    val source: CalibrationSource = CalibrationSource.DEFAULT,
    val notice: CurrentCalibration? = null,
)

/** A problem from the diagnostics log, in the UI's words (each kind maps to one plain sentence). */
enum class StatusIssueKind {
    START_FAILED,
    BATTERY_UNAVAILABLE,
    BATTERY_READ_FAILED,
    STATE_EVENTS_UNAVAILABLE,
    HISTORY_WRITE_FAILED,
    OBSERVATION_GAP,
    CHARGE_UNAVAILABLE,
    ADVANCED_READ_FAILED,
    ADVANCED_FORMAT_INVALID,
    ADVANCED_INTERRUPTED,
    ALERT_FAILED,
    NOTIFICATION_FAILED,
    LOG_READ_FAILED,
}

@Immutable
data class StatusIssue(val kind: StatusIssueKind, val firstAtMs: Long, val lastAtMs: Long, val count: Int)

@Immutable
data class StatusUiState(
    val access: AccessState = AccessState(),
    val calibration: CalibrationStatus = CalibrationStatus(),
    /** Newest first. */
    val issues: List<StatusIssue> = emptyList(),
    val issueLogUnavailable: Boolean = false,
    val shareUnavailable: Boolean = false,
    val nowMs: Long = 0,
)

sealed interface StatusEvent {
    data object RecheckAccess : StatusEvent

    data object AuthorizeShizuku : StatusEvent

    /** Copy the ADB commands: handled by the screen wrapper (clipboard). */
    data object CopyCommands : StatusEvent

    data object UndoCalibration : StatusEvent

    data object KeepCalibration : StatusEvent

    /** Share the report: handled by the screen wrapper (`ACTION_SEND`, text from [StatusViewModel.report]). */
    data object ShareReport : StatusEvent
}

/** Everything Settings › Status reads or does. */
interface StatusRepository {
    val access: Flow<AccessMode>
    val shizuku: Flow<ShizukuState>
    val calibration: Flow<CalibrationState>
    val events: Flow<List<DiagnosticEvent>>
    val logUnavailable: Flow<Boolean>
    val adbCommands: List<String>
    val adbCoversAppStats: Boolean

    /** Probes the backends; [recheck] also forgets cached root results and re-reads an unknown design capacity (the user asked). */
    suspend fun detectAccess(recheck: Boolean): AccessMode
    fun requestShizukuPermission()
    fun undoCalibration()
    fun keepCalibration()

    /** The plain-text diagnostics report ([DiagnosticReport]): no app lists, raw dumps or device IDs. */
    fun report(): String
}

/**
 * Settings › Status: advanced access (mode, Shizuku authorization, setup with the ADB commands), the current
 * calibration with its correction notice (Undo/Keep through [CalibrationStore]), recent issues from the
 * diagnostics log, and the shareable report. Access is probed on open and whenever Shizuku starts, stops or is
 * authorized; "Check again" probes afresh.
 */
class StatusViewModel(
    private val repository: StatusRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val checking = MutableStateFlow(false)
    private val shareUnavailable = MutableStateFlow(false)
    private var probe: Job? = null
    private var pendingRecheck: Boolean? = null

    private val access = combine(repository.access, checking, repository.shizuku) { mode, checking, shizuku ->
        AccessState(mode, checking, shizuku, repository.adbCommands, repository.adbCoversAppStats)
    }
    private val log = combine(repository.events, repository.logUnavailable, shareUnavailable) { events, unavailable, share ->
        Triple(issues(events), unavailable, share)
    }

    val state: StateFlow<StatusUiState> = combine(access, repository.calibration, log) { access, calibration, (issues, unavailable, share) ->
        StatusUiState(access, calibration.toStatus(), issues, unavailable, share, clock())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatusUiState(nowMs = clock()))

    init {
        viewModelScope.launch {
            repository.shizuku.distinctUntilChanged().collect { detect(recheck = false) }
        }
    }

    fun onEvent(event: StatusEvent) {
        when (event) {
            StatusEvent.RecheckAccess -> detect(recheck = true)
            StatusEvent.AuthorizeShizuku -> repository.requestShizukuPermission()
            StatusEvent.UndoCalibration -> repository.undoCalibration()
            StatusEvent.KeepCalibration -> repository.keepCalibration()
            StatusEvent.CopyCommands, StatusEvent.ShareReport -> Unit // The wrapper's (clipboard, Intents).
        }
    }

    fun report(): String = repository.report()

    /** The share sheet opened ([shared]) or no app could take the report. */
    fun onShareResult(shared: Boolean) {
        shareUnavailable.value = !shared
    }

    /** One probe at a time; requests during it coalesce into one fresh probe, preserving a manual recheck. */
    private fun detect(recheck: Boolean) {
        pendingRecheck = pendingRecheck == true || recheck
        if (probe?.isActive == true) return
        probe = viewModelScope.launch {
            checking.value = true
            try {
                while (pendingRecheck != null) {
                    val refresh = pendingRecheck == true
                    pendingRecheck = null
                    try {
                        repository.detectAccess(refresh)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The mode flow keeps its last value; a queued request still probes afresh.
                    }
                }
            } finally {
                checking.value = false
            }
        }
    }

    internal companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** Problems only (monitoring started/stopped, access changes and recoveries are context, not issues), newest first. */
        fun issues(events: List<DiagnosticEvent>): List<StatusIssue> = events
            .mapNotNull { event -> event.code.issueKind()?.let { StatusIssue(it, event.firstAt, event.lastAt, event.count) } }
            .sortedByDescending { it.lastAtMs }

        fun CalibrationState.toStatus() = CalibrationStatus(
            unit = effective.unit,
            sign = effective.sign,
            source = source,
            notice = if (noticePending) detected ?: effective else null,
        )

        private fun DiagnosticCode.issueKind(): StatusIssueKind? = when (this) {
            DiagnosticCode.START_FAILED -> StatusIssueKind.START_FAILED
            DiagnosticCode.BATTERY_UNAVAILABLE -> StatusIssueKind.BATTERY_UNAVAILABLE
            DiagnosticCode.BATTERY_READ_FAILED -> StatusIssueKind.BATTERY_READ_FAILED
            DiagnosticCode.STATE_EVENTS_UNAVAILABLE -> StatusIssueKind.STATE_EVENTS_UNAVAILABLE
            DiagnosticCode.HISTORY_WRITE_FAILED -> StatusIssueKind.HISTORY_WRITE_FAILED
            DiagnosticCode.OBSERVATION_GAP -> StatusIssueKind.OBSERVATION_GAP
            DiagnosticCode.CHARGE_UNAVAILABLE -> StatusIssueKind.CHARGE_UNAVAILABLE
            DiagnosticCode.ADVANCED_READ_FAILED -> StatusIssueKind.ADVANCED_READ_FAILED
            DiagnosticCode.ADVANCED_FORMAT_INVALID -> StatusIssueKind.ADVANCED_FORMAT_INVALID
            DiagnosticCode.ADVANCED_INTERRUPTED -> StatusIssueKind.ADVANCED_INTERRUPTED
            DiagnosticCode.ALERT_FAILED -> StatusIssueKind.ALERT_FAILED
            DiagnosticCode.NOTIFICATION_FAILED -> StatusIssueKind.NOTIFICATION_FAILED
            DiagnosticCode.LOG_READ_FAILED -> StatusIssueKind.LOG_READ_FAILED
            DiagnosticCode.MONITORING_STARTED, DiagnosticCode.MONITORING_STOPPED,
            DiagnosticCode.ACCESS_NONE, DiagnosticCode.ACCESS_SHIZUKU, DiagnosticCode.ACCESS_ROOT, DiagnosticCode.ACCESS_ADB,
            DiagnosticCode.ADVANCED_RECOVERED, DiagnosticCode.SYSTEM_STATS_RESET, DiagnosticCode.APP_SCOPE_FAILED,
            DiagnosticCode.ADVANCED_INCOMPLETE -> null
        }
    }
}

/**
 * [StatusRepository] over [ShellRunner], [ShizukuBridge], [CalibrationStore], [DiagnosticStore], [BatteryRepository]
 * and [DesignCapacitySource].
 */
class DefaultStatusRepository(
    context: Context,
    private val shell: ShellRunner,
    private val bridge: ShizukuBridge,
    private val calibrationStore: CalibrationStore,
    private val diagnostics: DiagnosticStore,
    private val battery: BatteryRepository,
    private val designCapacity: DesignCapacitySource,
) : StatusRepository {
    override val access: Flow<AccessMode> = shell.access.map { it.toAccessMode() }
    override val shizuku: Flow<ShizukuState> = combine(bridge.running, bridge.granted, bridge.blocked, ::ShizukuState)
    override val calibration: Flow<CalibrationState> = calibrationStore.state
    override val events: Flow<List<DiagnosticEvent>> = diagnostics.events
    override val logUnavailable: Flow<Boolean> = diagnostics.storageUnavailable
    override val adbCommands: List<String> = context.packageName.let { pkg ->
        listOf(
            "adb shell pm grant $pkg android.permission.DUMP",
            "adb shell pm grant $pkg android.permission.PACKAGE_USAGE_STATS",
            "adb shell appops set $pkg GET_USAGE_STATS allow",
        )
    }

    // Android 16 refuses `dumpsys batterystats` for an app uid without cross-user access (B2 report).
    override val adbCoversAppStats: Boolean = Build.VERSION.SDK_INT < ANDROID_16

    override suspend fun detectAccess(recheck: Boolean): AccessMode {
        if (recheck) shell.invalidateMode()
        val mode = shell.detectMode(forceRefresh = true).toAccessMode()
        // After the fresh root probe: a grant that answered too late for the first read can now be read.
        if (recheck) designCapacity.recheck()
        return mode
    }

    override fun requestShizukuPermission() = bridge.requestPermission()

    override fun undoCalibration() = calibrationStore.undoLastCorrection()

    override fun keepCalibration() = calibrationStore.dismissNotice()

    override fun report(): String = buildString {
        val calibration = calibrationStore.state.value
        appendLine("Voltwise ${BuildConfig.VERSION_NAME} · Android API ${Build.VERSION.SDK_INT}")
        appendLine("Report generated: ${Instant.now()} (UTC)")
        appendLine()
        appendLine(DiagnosticReport.reading(battery.realtimeFlow.value.sample))
        appendLine()
        appendLine(DiagnosticReport.observation(battery.observation.value, battery.isMonitoringFlow.value))
        appendLine()
        appendLine("Access: ${shell.access.value.name}; Shizuku running: ${bridge.running.value}; authorized: ${bridge.granted.value}")
        appendLine(
            "Calibration: unit ${calibration.effective.unit.name}, sign ${calibration.effective.sign.name}, " +
                "source ${calibration.source.name}, agreeing windows ${calibration.agreeingWindows}, " +
                "correction pending: ${calibration.noticePending}",
        )
        appendLine("Diagnostic persistence unavailable: ${diagnostics.storageUnavailable.value}")
        appendLine()
        append(DiagnosticReport.events(diagnostics.events.value))
    }

    private companion object {
        const val ANDROID_16 = 36

        fun ShellRunner.Mode.toAccessMode() = when (this) {
            ShellRunner.Mode.SHIZUKU -> AccessMode.SHIZUKU
            ShellRunner.Mode.ROOT -> AccessMode.ROOT
            ShellRunner.Mode.ADB -> AccessMode.ADB
            ShellRunner.Mode.NONE -> AccessMode.NONE
        }
    }
}
