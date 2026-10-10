package com.akane.voltwise.viewmodel

import com.akane.voltwise.battery.apps.AppStatsRepository
import com.akane.voltwise.battery.apps.AppUsageSnapshot
import com.akane.voltwise.battery.apps.SessionSnapshotStore
import com.akane.voltwise.battery.apps.toAppUsageSnapshot
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.CalibrationStore
import com.akane.voltwise.battery.data.DesignCapacityReading
import com.akane.voltwise.battery.data.DesignCapacitySource
import com.akane.voltwise.battery.data.db.BatteryDatabase
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.DailySummary
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.insights.model.InsightReport
import com.akane.voltwise.battery.measurement.CalibrationState
import com.akane.voltwise.settings.AppSettings
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * What the Now screen reads and does, as one seam: [DefaultNowRepository] on device, a fake in unit tests. Nothing
 * here starts privileged work: per-app data is only the last cached dump (the Apps screen fetches on demand).
 */
interface NowRepository {
    /** The latest capture, calibrated; every 2 s while the screen holds SamplingDemand. */
    val realtime: StateFlow<BatteryRepository.Realtime>
    val calibration: StateFlow<CalibrationState>
    val settings: Flow<AppSettings>
    /** InsightRepository.report: active findings only; reading never starts analysis. */
    val insights: Flow<InsightReport?>
    /** InsightRepository.lastAnalyzedAt: null until an analysis has completed. */
    val lastAnalyzedAt: Flow<Long?>
    /** InsightsRepository.eligibleSessionCount: sessions with app data to compare (what the app baseline needs). */
    val eligibleSessionCount: Flow<Int>

    /**
     * The app-wide design capacity shared with Health, only when already known (the Settings override, or a root
     * read Health or Status started): Now never starts the root read itself.
     */
    val design: Flow<DesignCapacityReading>

    /** The last privileged dump's per-app usage, or null; reading it never starts a dump. */
    val cachedAppUsage: Flow<AppUsageSnapshot?>
    val activeSession: Flow<ChargeSession?>

    /** Stored samples from [fromMs] on, oldest first; re-emits when rows are saved. */
    fun samplesSince(fromMs: Long): Flow<List<BatterySample>>
    fun day(epochDay: Long): Flow<DailySummary?>

    /** The newest [limit] sessions of any type, newest first. */
    fun recentSessions(limit: Int): Flow<List<ChargeSession>>

    /** The newest [limit] DISCHARGE sessions (the open one first while on battery), newest first. */
    fun dischargeSessions(limit: Int): Flow<List<ChargeSession>>

    /** The session's BASELINE snapshot, when one was captured. */
    suspend fun baseline(sessionId: String): AppUsageSnapshot?

    /** Closes the open session and starts a new one (and a new observation window) at the next capture. */
    fun resetObservation()
    fun undoCalibration()
    fun dismissCalibrationNotice()
}

/** [NowRepository] over the app's repositories; every read is a Flow or a main-safe suspend call. */
class DefaultNowRepository(
    private val repository: BatteryRepository,
    private val database: BatteryDatabase,
    private val calibrationStore: CalibrationStore,
    appStats: AppStatsRepository,
    private val snapshots: SessionSnapshotStore,
    settingsRepository: SettingsRepository<AppSettings>,
    designCapacity: DesignCapacitySource,
    override val insights: Flow<InsightReport?>,
    override val lastAnalyzedAt: Flow<Long?>,
    override val eligibleSessionCount: Flow<Int>,
) : NowRepository {
    override val realtime = repository.realtimeFlow
    override val calibration = calibrationStore.state
    override val settings = settingsRepository.flow
    override val design = designCapacity.known
    override val cachedAppUsage = appStats.cached.map { it?.toAppUsageSnapshot() }
    override val activeSession = repository.activeSessionFlow
    override fun samplesSince(fromMs: Long) = repository.samplesBetween(fromMs, Long.MAX_VALUE)
    override fun day(epochDay: Long) = database.dailySummaryDao().day(epochDay)
    override fun recentSessions(limit: Int) = repository.sessionDao.filteredSessions(null, "", limit)
    override fun dischargeSessions(limit: Int) = repository.sessionDao.filteredSessions(SessionType.DISCHARGE, "", limit)
    override suspend fun baseline(sessionId: String) = snapshots.baseline(sessionId)
    override fun resetObservation() = repository.resetObservation()
    override fun undoCalibration() = calibrationStore.undoLastCorrection()
    override fun dismissCalibrationNotice() = calibrationStore.dismissNotice()
}
