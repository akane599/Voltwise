package com.akane.voltwise.battery.apps

import androidx.room.withTransaction
import com.akane.voltwise.battery.data.db.AppSnapshot
import com.akane.voltwise.battery.data.db.AppSnapshotKind
import com.akane.voltwise.battery.data.db.AppUsageDao
import com.akane.voltwise.battery.data.db.BatteryDatabase
import com.akane.voltwise.battery.data.db.SessionDao
import com.akane.voltwise.battery.data.db.SessionDeviceWaker
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.db.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The open session as far as per-app snapshots care. */
data class OpenSession(val sessionId: String, val type: SessionType)

/** What [SessionSnapshotCollector] reads and writes: [RoomSessionSnapshotStore] on device, a fake in unit tests. */
interface SessionSnapshotStore {
    /** The open (active) session, re-emitted only when it changes. Reads committed rows. */
    fun openSession(): Flow<OpenSession?>

    suspend fun hasBaseline(sessionId: String): Boolean

    /** The session's BASELINE snapshot, or null when none was captured (or it was pruned). */
    suspend fun baseline(sessionId: String): AppUsageSnapshot?

    /**
     * Stores [snapshot] as the session's one BASELINE, then prunes. Skipped (false) when the session already
     * has one, is closed, or its row does not exist (orphan pruning would delete it).
     */
    suspend fun saveBaseline(sessionId: String, snapshot: AppUsageSnapshot): Boolean

    /**
     * In one transaction: stores [end] as the closed session's END snapshot, replaces its breakdown with
     * [result]'s rows and marks it READY with [result]'s basis, then prunes. False when the session row is gone.
     */
    suspend fun saveEnd(sessionId: String, end: AppUsageSnapshot, result: AppUsageDeltaResult): Boolean

    /** Status setter for CLOSED sessions only; the open session's status lives on the repository's in-memory row. */
    suspend fun setStatus(sessionId: String, status: AppUsageStatus): Boolean

    /** Closed DISCHARGE sessions whose breakdown is still PENDING, newest first (bounded look-back). */
    suspend fun pendingClosedDischarges(): List<String>
}

class RoomSessionSnapshotStore internal constructor(
    private val sessions: SessionDao,
    private val usage: AppUsageDao,
    private val transaction: suspend (suspend () -> Boolean) -> Boolean,
) : SessionSnapshotStore {
    constructor(db: BatteryDatabase) : this(db.sessionDao(), db.appUsageDao(), { block -> db.withTransaction { block() } })

    override fun openSession(): Flow<OpenSession?> = sessions.activeFlow()
        .map { active -> active?.let { OpenSession(it.sessionId, it.type) } }
        .distinctUntilChanged()

    override suspend fun hasBaseline(sessionId: String): Boolean =
        usage.latestSnapshot(sessionId, AppSnapshotKind.BASELINE) != null

    override suspend fun baseline(sessionId: String): AppUsageSnapshot? {
        val header = usage.latestSnapshot(sessionId, AppSnapshotKind.BASELINE) ?: return null
        return AppUsageSnapshot(header.windowStartedAt, header.windowStartCount, header.capturedAt,
            usage.snapshotUids(header.id).map { it.toRow() },
            header.deepIdleMs, header.deepIdleCount, header.lightIdleMs, header.lightIdleCount, header.screenOffMs,
            usage.snapshotWakers(header.id).map { DeviceWaker(it.kind, it.name, it.count, it.totalMs) }, header.wakersComplete)
    }

    override suspend fun saveBaseline(sessionId: String, snapshot: AppUsageSnapshot): Boolean = transaction {
        val session = sessions.byId(sessionId) ?: return@transaction false
        if (session.activeKey != 1 || session.endTime != null ||
            usage.latestSnapshot(sessionId, AppSnapshotKind.BASELINE) != null) {
            return@transaction false
        }
        insert(sessionId, AppSnapshotKind.BASELINE, snapshot)
        true
    }

    override suspend fun saveEnd(sessionId: String, end: AppUsageSnapshot, result: AppUsageDeltaResult): Boolean =
        transaction {
            val session = sessions.byId(sessionId) ?: return@transaction false
            insert(sessionId, AppSnapshotKind.END, end)
            sessions.update(session.copy(appCaptureStartMs = result.captureStartMs, appCaptureEndMs = end.capturedAt))
            usage.replaceSessionUsage(sessionId, result.basis, result.rows)
            usage.insertSessionWakers(sessionId, result.deviceWakers.mapIndexed { rank, waker ->
                SessionDeviceWaker(sessionId, waker.kind, waker.name, waker.count, waker.totalMs, rank)
            })
            true
        }

    override suspend fun setStatus(sessionId: String, status: AppUsageStatus): Boolean =
        usage.setAppUsageStatus(sessionId, status, null) > 0

    override suspend fun pendingClosedDischarges(): List<String> =
        sessions.filteredSessions(SessionType.DISCHARGE, "", PENDING_LOOKBACK).first()
            .filter { it.endTime != null && it.activeKey == null && it.appUsageStatus == AppUsageStatus.PENDING }
            .map { it.sessionId }

    /** insertSnapshot retains the open baseline + the last [AppUsageDao.SNAPSHOTS_KEPT] inserted snapshots. */
    private suspend fun insert(sessionId: String, kind: AppSnapshotKind, snapshot: AppUsageSnapshot) {
        val wakers = snapshot.deviceWakers.selectSnapshotWakers()
        usage.insertSnapshot(snapshot.header(sessionId, kind, wakers), snapshot.rows.collapseByUid(), wakers.entries)
        usage.pruneOrphanSnapshots()
    }

    private companion object {
        /** Sessions are swept at every collector start and power transition, so few are ever pending. */
        const val PENDING_LOOKBACK = 50
    }
}

/**
 * One row per uid: `app_snapshot_uids` REPLACEs on (snapshotId, uid), so duplicates would silently keep only
 * the last. Totals add up; the first package name wins; a field stays null only when every row lacks it.
 */
internal fun List<AppUsageRow>.collapseByUid(): List<AppUsageRow> = groupBy { it.uid }.map { (_, rows) ->
    rows.reduce { a, b ->
        a.copy(
            powerMah = a.powerMah + b.powerMah,
            cpuTimeMs = addOrNull(a.cpuTimeMs, b.cpuTimeMs),
            foregroundTimeMs = addOrNull(a.foregroundTimeMs, b.foregroundTimeMs),
            backgroundTimeMs = addOrNull(a.backgroundTimeMs, b.backgroundTimeMs),
            wakelockTimeMs = addOrNull(a.wakelockTimeMs, b.wakelockTimeMs),
            mobileBytes = addOrNull(a.mobileBytes, b.mobileBytes),
            wifiBytes = addOrNull(a.wifiBytes, b.wifiBytes),
            wakeupAlarms = addOrNull(a.wakeupAlarms, b.wakeupAlarms),
            partialWakelockCount = addOrNull(a.partialWakelockCount, b.partialWakelockCount),
            partialWakelockBgMs = addOrNull(a.partialWakelockBgMs, b.partialWakelockBgMs),
            jobCount = addOrNull(a.jobCount, b.jobCount),
            jobMs = addOrNull(a.jobMs, b.jobMs),
            syncCount = addOrNull(a.syncCount, b.syncCount),
            fgServiceMs = addOrNull(a.fgServiceMs, b.fgServiceMs),
            topMs = addOrNull(a.topMs, b.topMs),
            mobileActiveMs = addOrNull(a.mobileActiveMs, b.mobileActiveMs),
            gpsMs = addOrNull(a.gpsMs, b.gpsMs),
            sensorMs = addOrNull(a.sensorMs, b.sensorMs),
        )
    }
}

private fun addOrNull(a: Long?, b: Long?): Long? = if (a == null && b == null) null else (a ?: 0L) + (b ?: 0L)

internal fun AppUsageSnapshot.header(
    sessionId: String,
    kind: AppSnapshotKind,
    wakers: SnapshotWakerSelection = deviceWakers.selectSnapshotWakers(),
) = AppSnapshot(
    sessionId = sessionId, kind = kind, capturedAt = capturedAt,
    windowStartedAt = windowStartedAt, windowStartCount = windowStartCount,
    deepIdleMs = deepIdleMs, deepIdleCount = deepIdleCount, lightIdleMs = lightIdleMs, lightIdleCount = lightIdleCount,
    screenOffMs = screenOffMs, wakersComplete = wakersComplete?.let { it && wakers.complete },
)
