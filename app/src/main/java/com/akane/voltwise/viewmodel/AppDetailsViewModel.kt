package com.akane.voltwise.viewmodel

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akane.voltwise.battery.apps.AppInfo
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.data.db.BatteryDatabase
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionAppUsage
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightReport
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.battery.util.BatteryStatsParser
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One discharge session with per-app data: this app's mAh in it, or null when the app wasn't individually recorded. */
@Immutable
data class AppSessionUsage(val sessionId: String, val startMs: Long, val powerMah: Double?)

/** [AppStatsReader] plus this app's history across stored sessions. */
interface AppDetailsRepository : AppStatsReader {
    /** InsightRepository.report owns status filtering: DISMISSED and RESOLVED never reach this flow. */
    fun findingsFor(uid: Int, packageName: String): Flow<List<Finding>>

    /**
     * The sessions overlapping [fromMs]..[toMs] that [inAppHistory] keeps, oldest first, each with this app's row
     * ([isSameApp]) when the session listed it.
     */
    suspend fun history(uid: Int, packageName: String, fromMs: Long, toMs: Long): List<AppSessionUsage>
}

/**
 * Whether a session belongs in AppDetails' history: a DISCHARGE whose breakdown is READY and measured from unplug to
 * plug-in (DELTA), as the panel's ⓘ says. ABSOLUTE (no reading at unplug: "since the last full charge") and
 * WINDOW_RESET breakdowns cover other windows, so their bars would not compare.
 */
internal fun ChargeSession.inAppHistory(): Boolean =
    type == SessionType.DISCHARGE && appUsageStatus == AppUsageStatus.READY && appUsageBasis == AppUsageBasis.DELTA

/**
 * Whether a stored row is this app's. Android can give an uninstalled app's uid to a newly installed one, so app
 * uids must also match the package the row was stored with; system appIds (see [BatteryStatsParser.isSystemUid]) are
 * shared by several packages and never reassigned, so the uid alone identifies them. A blank package matches any.
 */
internal fun SessionAppUsage.isSameApp(uid: Int, packageName: String): Boolean =
    this.uid == uid && !isOthers &&
        (BatteryStatsParser.isSystemUid(uid) || packageName.isBlank() || this.packageName.isBlank() || this.packageName == packageName)

/** [AppDetailsRepository] over the shared reader and the `session_app_usage` rows (A3's DAOs, read on IO). */
class DefaultAppDetailsRepository(
    reader: AppStatsReader,
    private val database: BatteryDatabase,
    private val insights: Flow<InsightReport?>,
) : AppDetailsRepository, AppStatsReader by reader {
    override fun findingsFor(uid: Int, packageName: String): Flow<List<Finding>> = insights.map { it.appFindings(uid, packageName) }

    override suspend fun history(uid: Int, packageName: String, fromMs: Long, toMs: Long): List<AppSessionUsage> = withContext(Dispatchers.IO) {
        val sessions = database.sessionDao().sessionsBetween(fromMs, toMs)
            .filter { it.inAppHistory() }
        if (sessions.isEmpty()) return@withContext emptyList()
        val rows = database.appUsageDao().usageForSessionsBetween(sessions.first().startTime, toMs)
            .filter { it.isSameApp(uid, packageName) }
            .associateBy { it.sessionId }
        sessions.map { AppSessionUsage(it.sessionId, it.startTime, rows[it.sessionId]?.powerMah) }
    }
}

/** Selects this uid and package from the active-only report, keeping copies in other profiles separate. */
internal fun InsightReport?.appFindings(uid: Int, packageName: String): List<Finding> =
    this?.findings?.filter {
        val app = it.subject as? Subject.App
        app != null && app.uid == uid && app.packageName == packageName
    }.orEmpty()

/** One active finding about this app; [evidence] is the finding's lead evidence (its row's one line), when it has any. */
@Immutable
data class AppFinding(
    val key: String,
    val type: FindingType,
    val severity: Severity,
    val direction: Direction?,
    val evidence: Evidence? = null,
)

/** A wakelock's effect: [CPU] keeps the processor awake (partial); [SCREEN] keeps the display on. */
enum class WakelockKind { CPU, SCREEN }

@Immutable
data class WakelockItem(val tag: String, val kind: WakelockKind, val totalMs: Long, val count: Int)
@Immutable
data class AlarmItem(val tag: String, val wakeups: Int)

/** A job or a sync: its name (job service or sync authority), runs and total run time. */
@Immutable
data class TaskItem(val name: String, val count: Int, val totalMs: Long)

/** Bytes by network and direction; [radioActiveMs] = time this app kept the mobile radio active. */
@Immutable
data class NetworkUsage(
    val mobileRxBytes: Long?,
    val mobileTxBytes: Long?,
    val wifiRxBytes: Long?,
    val wifiTxBytes: Long?,
    val radioActiveMs: Long?,
)

/** Hardware time (ms): GPS, other sensors, camera, flashlight, audio, video, Bluetooth scans. */
@Immutable
data class HardwareUsage(
    val gpsMs: Long? = null,
    val sensorsMs: Long? = null,
    val cameraMs: Long? = null,
    val flashlightMs: Long? = null,
    val audioMs: Long? = null,
    val videoMs: Long? = null,
    val bluetoothScanMs: Long? = null,
)

/**
 * This app's row of one dump. [share] is its part of every app's mAh. Time by state: [foregroundMs] (activity in
 * the foreground, as the Apps list's Foreground order), [foregroundServiceMs], [backgroundMs], [cachedMs]. Lists are for this uid only, largest first; [network] is null and
 * [hardware] empty when Android counted nothing.
 */
@Immutable
data class AppUsageDetails(
    val powerMah: Double,
    val share: Float,
    val foregroundMs: Long? = null,
    val foregroundServiceMs: Long? = null,
    val backgroundMs: Long? = null,
    val cachedMs: Long? = null,
    val cpuTimeMs: Long? = null,
    val wakelockTimeMs: Long? = null,
    val wakelocks: List<WakelockItem> = emptyList(),
    val alarms: List<AlarmItem> = emptyList(),
    val jobs: List<TaskItem> = emptyList(),
    val syncs: List<TaskItem> = emptyList(),
    val network: NetworkUsage? = null,
    val hardware: HardwareUsage = HardwareUsage(),
)

/** Sessions on battery with unplug-to-plug-in per-app data (oldest first), and in how many this app was listed. */
@Immutable
data class AppHistory(val sessions: List<AppSessionUsage>) {
    val listedIn: Int get() = sessions.count { it.powerMah != null }
}

/** The history read: [Loading] until it answers, then [Loaded] (possibly no sessions) or [Failed] (the read threw). */
@Immutable
sealed interface AppHistoryState {
    data object Loading : AppHistoryState
    data class Loaded(val history: AppHistory) : AppHistoryState
    data object Failed : AppHistoryState
}

/**
 * AppDetails for one uid. [label] is null until the app lookup returns; [canOpenAppInfo] when the package is
 * installed. [capturedAtMs]/[startedAtMs] describe the dump shown (null before a good read); with a dump, a null
 * [usage] means Android counted nothing for this app.
 */
@Immutable
data class AppDetailsUiState(
    val uid: Int,
    val packageName: String,
    val nowMs: Long = 0,
    val label: AppLabel? = null,
    val canOpenAppInfo: Boolean = false,
    val loading: Boolean = false,
    val problem: StatsProblem? = null,
    val capturedAtMs: Long? = null,
    val startedAtMs: Long? = null,
    val usage: AppUsageDetails? = null,
    val history: AppHistoryState = AppHistoryState.Loading,
    /** An unmodifiable snapshot of this uid and package's active findings. */
    val findings: List<AppFinding> = emptyList(),
)

sealed interface AppDetailsEvent {
    data object Refresh : AppDetailsEvent
    data object Back : AppDetailsEvent
    data object OpenAppInfo : AppDetailsEvent
    data object OpenAccessSetup : AppDetailsEvent
    data object AllowShizuku : AppDetailsEvent
    /** Reads the history again after [AppHistoryState.Failed]. */
    data object RetryHistory : AppDetailsEvent
    /** Opens the details of the finding with [key]. */
    data class OpenFinding(val key: String) : AppDetailsEvent
}

/**
 * One app's details from the same on-demand dump as Apps (opening the screen reads it, within the 60 s cache when
 * coming from Apps; pull-to-refresh forces a new one) plus its history across stored discharge sessions.
 */
class AppDetailsViewModel(
    private val source: AppDetailsRepository,
    private val uid: Int,
    private val packageName: String,
    private val clock: () -> Long = System::currentTimeMillis,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val warn: (String) -> Unit = { Log.w(LOG_TAG, it) },
) : ViewModel() {
    private val loader = StatsLoader(viewModelScope, source)
    private val info = MutableStateFlow<AppInfo?>(null)
    private val infoLoaded = MutableStateFlow(false)
    private var infoJob: Job? = null
    private val history = MutableStateFlow<AppHistoryState>(AppHistoryState.Loading)
    private var historyJob: Job? = null
    private var started = false

    init {
        loadInfo()
        loadHistory()
    }

    val state: StateFlow<AppDetailsUiState> = combine(
        source.cached,
        combine(info, infoLoaded, ::Pair),
        loader.loading,
        loader.problem,
        combine(history, source.findingsFor(uid, packageName), ::Pair),
    ) { snapshot, (info, loaded), loading, problem, (history, findings) ->
        AppDetailsUiState(
            uid = uid,
            packageName = packageName,
            nowMs = clock(),
            label = if (loaded) AppLabel.of(uid, packageName, info) else null,
            canOpenAppInfo = info?.installed == true,
            loading = loading,
            problem = problem,
            capturedAtMs = snapshot?.capturedAt,
            startedAtMs = snapshot?.startedAt,
            usage = snapshot?.let { details(it, uid, packageName) },
            history = history,
            findings = Collections.unmodifiableList(findings.map { AppFinding(it.key, it.type, it.severity, it.direction, it.evidence.firstOrNull()) }),
        )
    }.flowOn(computeDispatcher).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        AppDetailsUiState(uid, packageName),
    )

    fun onStart() {
        if (!started) {
            loadInfo()
            loadHistory()
        }
        started = true
        loader.start()
    }

    fun onStop() {
        started = false
        loader.stop()
    }

    fun onEvent(event: AppDetailsEvent) {
        when (event) {
            AppDetailsEvent.Refresh -> {
                loader.load(force = true)
                loadHistory()
            }
            AppDetailsEvent.RetryHistory -> loadHistory()
            // Navigation, the App info intent and Shizuku's permission prompt are the screen wrapper's.
            AppDetailsEvent.Back, AppDetailsEvent.OpenAppInfo, AppDetailsEvent.OpenAccessSetup, AppDetailsEvent.AllowShizuku,
            is AppDetailsEvent.OpenFinding -> Unit
        }
    }

    private fun loadInfo() {
        infoJob?.cancel()
        infoJob = viewModelScope.launch {
            info.value = source.infoOrNull(packageName)
            infoLoaded.value = true
        }
    }

    private fun loadHistory() {
        // A retry shows its read; a refresh keeps the bars shown until the new ones arrive.
        if (history.value == AppHistoryState.Failed) history.value = AppHistoryState.Loading
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            val now = clock()
            history.value = try {
                val sessions = source.history(uid, packageName, now - HISTORY_WINDOW_MS, now)
                AppHistoryState.Loaded(AppHistory(sessions.sortedBy { it.startMs }.takeLast(HISTORY_SESSIONS)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The exception's type only: its message can carry the query or the package.
                warn("History read failed (${e.javaClass.simpleName})")
                AppHistoryState.Failed
            }
        }
    }

    companion object {
        /** History covers the sessions of the last 30 days, at most the newest [HISTORY_SESSIONS]. */
        const val HISTORY_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        const val HISTORY_SESSIONS = 14
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val LOG_TAG = "AppDetails"

        /** Details for this identity, or null when its row or application package membership is unavailable. */
        fun details(snapshot: BatteryStatsParser.FullSnapshot, uid: Int, packageName: String): AppUsageDetails? {
            val app = snapshot.apps.firstOrNull { it.uid == uid } ?: return null
            if (!BatteryStatsParser.isSystemUid(uid) && packageName !in app.packages &&
                !(app.packages.isEmpty() && packageName == BatteryStatsParser.displayNameFor(uid, emptyList()))
            ) return null
            val total = snapshot.apps.sumOf { it.powerMah.coerceAtLeast(0.0) }
            val network = snapshot.network.firstOrNull { it.uid == uid }
            return AppUsageDetails(
                powerMah = app.powerMah,
                share = if (total > 0) (app.powerMah.coerceAtLeast(0.0) / total).toFloat() else 0f,
                foregroundMs = app.foregroundTimeMs,
                foregroundServiceMs = app.foregroundServiceTimeMs,
                backgroundMs = app.backgroundTimeMs,
                cachedMs = app.cachedTimeMs,
                cpuTimeMs = app.cpuTimeMs,
                wakelockTimeMs = app.wakeLockTimeMs,
                wakelocks = snapshot.wakelocks.filter { it.uid == uid }
                    .map { lock ->
                        val kind = if (lock.type == BatteryStatsParser.WakelockType.PARTIAL) WakelockKind.CPU else WakelockKind.SCREEN
                        WakelockItem(lock.tag, kind, lock.totalTimeMs, lock.count)
                    }
                    .sortedByDescending { it.totalMs },
                alarms = snapshot.alarms.filter { it.uid == uid }.map { AlarmItem(it.tag, it.wakeups) }.sortedByDescending { it.wakeups },
                jobs = snapshot.jobs.filter { it.uid == uid }.map { TaskItem(it.jobName, it.count, it.totalTimeMs) }.sortedByDescending { it.totalMs },
                syncs = snapshot.syncs.filter { it.uid == uid }.map { TaskItem(it.authority, it.count, it.totalTimeMs) }.sortedByDescending { it.totalMs },
                network = NetworkUsage(
                    mobileRxBytes = app.mobileRxBytes,
                    mobileTxBytes = app.mobileTxBytes,
                    wifiRxBytes = app.wifiRxBytes,
                    wifiTxBytes = app.wifiTxBytes,
                    radioActiveMs = network?.mobileActiveTimeMs,
                ).takeIf { it.hasData() },
                hardware = HardwareUsage(
                    gpsMs = app.gpsTimeMs.positive(),
                    sensorsMs = app.sensorTimeMs.positive(),
                    cameraMs = app.cameraTimeMs.positive(),
                    flashlightMs = app.flashlightTimeMs.positive(),
                    audioMs = app.audioTimeMs.positive(),
                    videoMs = app.videoTimeMs.positive(),
                    bluetoothScanMs = app.bluetoothScanTimeMs.positive(),
                ),
            )
        }

        private fun Long?.positive(): Long? = this?.takeIf { it > 0 }

        private fun NetworkUsage.hasData(): Boolean =
            listOfNotNull(mobileRxBytes, mobileTxBytes, wifiRxBytes, wifiTxBytes, radioActiveMs).any { it > 0 }
    }
}
