package com.akane.voltwise.battery.data.db

import androidx.room.*
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageRow
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.apps.DeviceWaker
import com.akane.voltwise.battery.data.HistoryLimits
import com.akane.voltwise.battery.data.SessionEvidence
import kotlinx.coroutines.flow.Flow

@Dao
interface BatteryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSample(sample: BatterySample): Long

    @Query("SELECT * FROM battery_samples WHERE id = :id")
    suspend fun byId(id: Long): BatterySample?

    @Query("SELECT * FROM battery_samples WHERE timestamp = :timestamp")
    suspend fun atTimestamp(timestamp: Long): List<BatterySample>

    @Query("SELECT * FROM battery_samples WHERE observationId = :observationId AND elapsedMs = :elapsedMs LIMIT 1")
    suspend fun observedPoint(observationId: String, elapsedMs: Long): BatterySample?

    @Query("SELECT COUNT(*) FROM battery_samples")
    suspend fun count(): Int

    @Query("SELECT * FROM battery_samples ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastSample(): BatterySample?

    @Query("SELECT * FROM battery_samples WHERE substr(source, 1, 7) != 'import:' ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastLocalSample(): BatterySample?

    @Query("SELECT * FROM battery_samples WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    fun samplesBetween(from: Long, to: Long): Flow<List<BatterySample>>

    /** ALL exports omit only oldest overflow awaiting the writer's next trim, without deleting it. */
    @Query("SELECT * FROM (SELECT * FROM battery_samples WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC, id DESC LIMIT :limit) ORDER BY timestamp ASC, id ASC")
    suspend fun latestSamplesBetween(from: Long, to: Long, limit: Int): List<BatterySample>

    @Query("SELECT * FROM battery_samples WHERE id IN (SELECT MAX(id) FROM battery_samples WHERE timestamp BETWEEN :from AND :to AND source = 'BatteryManager' GROUP BY timestamp / :bucketMs) ORDER BY timestamp")
    fun chartSamples(from: Long, to: Long, bucketMs: Long): Flow<List<BatterySample>>

    @Query("SELECT * FROM battery_samples WHERE sessionId = :sessionId ORDER BY elapsedMs, id")
    fun samplesForSession(sessionId: String): Flow<List<BatterySample>>

    @Query("WITH buckets AS (SELECT MAX(id) AS representativeId, MAX(CASE WHEN observationId IS NULL OR boundaryReason IS NOT NULL OR currentNowUa IS NULL OR voltageMv IS NULL OR temperatureDeciC IS NULL THEN 1 ELSE 0 END) OR COUNT(DISTINCT observationId) > 1 AS discontinuity FROM battery_samples WHERE sessionId = :sessionId AND timestamp BETWEEN :from AND :to GROUP BY (timestamp - :from) / :bucketMs) SELECT s.timestamp, s.currentNowUa, s.voltageMv, s.temperatureDeciC, s.observationId, s.source, b.discontinuity FROM battery_samples s JOIN buckets b ON s.id = b.representativeId ORDER BY s.timestamp, s.id")
    suspend fun sessionChartSamples(sessionId: String, from: Long, to: Long, bucketMs: Long): List<SessionChartReading>

    /**
     * Keeps the newest [limit] samples by timestamp: one seek on the timestamp index finds the
     * (limit + 1)-th newest timestamp and everything at or before it goes. Nothing is deleted while
     * fewer rows exist (the subquery is NULL).
     */
    @Query("DELETE FROM battery_samples WHERE timestamp <= (SELECT timestamp FROM battery_samples ORDER BY timestamp DESC LIMIT 1 OFFSET :limit)")
    suspend fun boundStorage(limit: Int = HistoryLimits.SAMPLE_TRIM_TARGET)

    @Query("DELETE FROM battery_samples")
    suspend fun clearAll()

    @Query("DELETE FROM battery_samples WHERE timestamp < :olderThan")
    suspend fun purge(olderThan: Long)
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: ChargeSession)

    @Update
    suspend fun update(session: ChargeSession): Int

    @Transaction
    suspend fun upsert(session: ChargeSession) {
        if (update(session) == 0) insert(session)
    }

    @Query("SELECT * FROM charge_sessions WHERE sessionId = :id")
    suspend fun byId(id: String): ChargeSession?

    @Query("SELECT COUNT(*) FROM charge_sessions")
    suspend fun count(): Int

    @Query("DELETE FROM charge_sessions WHERE activeKey IS NULL AND sessionId NOT IN (SELECT sessionId FROM charge_sessions ORDER BY activeKey IS NOT NULL DESC, startTime DESC LIMIT :limit)")
    suspend fun boundStorage(limit: Int = HistoryLimits.SESSION_TRIM_TARGET)

    @Query("SELECT * FROM charge_sessions WHERE activeKey = 1 LIMIT 1")
    suspend fun active(): ChargeSession?

    @Query("SELECT * FROM charge_sessions WHERE activeKey = 1 LIMIT 1")
    fun activeFlow(): Flow<ChargeSession?>

    @Query("SELECT * FROM charge_sessions WHERE startTime <= :to AND COALESCE(endTime, lastSampleTime, startTime) >= :from ORDER BY startTime")
    suspend fun sessionsBetween(from: Long, to: Long): List<ChargeSession>

    /** Closed sessions of every type whose endpoint is in the inclusive analysis window. */
    @Query("SELECT * FROM charge_sessions WHERE endTime BETWEEN :from AND :to ORDER BY endTime, sessionId")
    suspend fun closedSessionsBetween(from: Long, to: Long): List<ChargeSession>

    /** Closed local discharge sessions whose endpoints fall in the ETA seed's seven-day window. */
    @Query("SELECT * FROM charge_sessions WHERE type = 'DISCHARGE' AND endTime BETWEEN :from AND :to AND substr(source, 1, 7) != 'import:'")
    suspend fun closedDischargeSessionsBetween(from: Long, to: Long): List<ChargeSession>

    @Query("UPDATE charge_sessions SET endTime=COALESCE(lastSampleTime, startTime), activeKey=NULL, closeReason=:reason WHERE activeKey=1")
    suspend fun closeInterrupted(reason: String)

    @Query("DELETE FROM charge_sessions WHERE activeKey IS NULL AND COALESCE(endTime,startTime) < :olderThan")
    suspend fun purge(olderThan: Long)

    @Query("DELETE FROM charge_sessions")
    suspend fun clearAll()

    @Query("SELECT * FROM charge_sessions WHERE (:type IS NULL OR type = :type) AND (:query = '' OR instr(lower(sessionId), lower(:query)) > 0 OR instr(lower(source), lower(:query)) > 0) ORDER BY startTime DESC, sessionId LIMIT :limit")
    fun filteredSessions(type: SessionType?, query: String, limit: Int): Flow<List<ChargeSession>>

    @Query("SELECT * FROM charge_sessions WHERE sessionId = :id")
    fun session(id: String): Flow<ChargeSession?>

    /** The newest [limit] sessions that stored a capacity estimate, newest first (the Health trend). */
    @Query("SELECT sessionId, type, startTime, endTime, lastSampleTime, startLevel, endLevel, capacityEstimateMah, capacityConfidence, capacityBasis, source FROM charge_sessions WHERE capacityEstimateMah IS NOT NULL ORDER BY startTime DESC, sessionId LIMIT :limit")
    fun capacityEstimates(limit: Int): Flow<List<CapacityEstimateRow>>

    @Query("DELETE FROM battery_samples WHERE sessionId = :id")
    suspend fun deleteSessionSamples(id: String): Int

    @Query("DELETE FROM app_snapshots WHERE sessionId = :id")
    suspend fun deleteSessionSnapshots(id: String): Int

    @Query("DELETE FROM charge_sessions WHERE sessionId = :id")
    suspend fun deleteSessionRow(id: String): Int

    /**
     * Deletes one session with its readings and app snapshots, all or nothing; the snapshots' uids and the session's
     * `session_app_usage` rows cascade. Refuses (false) a missing row and the session the writer is recording
     * ([recordingGeneration], null while monitoring is off; [SessionEvidence.isRecording]): it rewrites that row at
     * every save, so the session would come straight back. Daily totals are kept.
     */
    @Transaction
    suspend fun deleteSession(id: String, recordingGeneration: String?): Boolean {
        val row = byId(id) ?: return false
        if (SessionEvidence.isRecording(row, recordingGeneration)) return false
        deleteSessionSnapshots(id)
        deleteSessionSamples(id)
        return deleteSessionRow(id) > 0
    }

    @Query("UPDATE charge_sessions SET endTime=:end, activeKey=NULL, endLevel=:endLevel, deltaUah=:delta, avgCurrentUa=:avg, estCapacityMah=:cap WHERE sessionId=:id")
    suspend fun complete(id: String, end: Long, endLevel: Int?, delta: Long?, avg: Long?, cap: Int?)
}

/** PersistPolicy's write path: each saved sample commits together with its session row and day rows. */
@Dao
interface PersistDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSample(sample: BatterySample): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: ChargeSession)

    @Update
    suspend fun updateSession(session: ChargeSession): Int

    @Upsert
    suspend fun upsertDays(days: List<DailySummary>)

    /**
     * Upserts [days] (the local days the sample's interval touched: today, plus yesterday across midnight),
     * updates or inserts [session] (never REPLACE: that would cascade-delete its app usage) and inserts [sample],
     * all or nothing. Returns the sample row id, or -1 when that observed point already exists (session and days
     * still commit).
     */
    @Transaction
    suspend fun persistSample(sample: BatterySample, session: ChargeSession, days: List<DailySummary>): Long {
        upsertDays(days)
        if (updateSession(session) == 0) insertSession(session)
        return insertSample(sample)
    }
}

@Dao
interface DailySummaryDao {
    @Upsert
    suspend fun upsert(summary: DailySummary)

    @Upsert
    suspend fun upsertAll(summaries: List<DailySummary>)

    @Query("SELECT * FROM daily_summaries WHERE epochDay = :epochDay")
    suspend fun byDay(epochDay: Long): DailySummary?

    @Query("SELECT * FROM daily_summaries WHERE epochDay = :epochDay")
    fun day(epochDay: Long): Flow<DailySummary?>

    @Query("SELECT * FROM daily_summaries WHERE epochDay BETWEEN :fromDay AND :toDay ORDER BY epochDay")
    fun between(fromDay: Long, toDay: Long): Flow<List<DailySummary>>

    @Query("SELECT * FROM daily_summaries WHERE epochDay BETWEEN :fromDay AND :toDay ORDER BY epochDay")
    suspend fun range(fromDay: Long, toDay: Long): List<DailySummary>

    @Query("SELECT COUNT(*) FROM daily_summaries")
    suspend fun count(): Int

    @Query("DELETE FROM daily_summaries WHERE epochDay < :epochDay")
    suspend fun purgeBefore(epochDay: Long): Int

    @Query("DELETE FROM daily_summaries")
    suspend fun clearAll()
}

/** Per-app snapshots (transient, bounded) and each discharge session's stored breakdown. */
@Dao
interface AppUsageDao {
    @Insert
    suspend fun insertSnapshotHeader(snapshot: AppSnapshot): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshotUids(rows: List<AppSnapshotUid>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshotWakers(rows: List<SnapshotDeviceWaker>)

    @Query("SELECT * FROM snapshot_device_wakers WHERE snapshotId = :snapshotId ORDER BY totalMs DESC, kind, name")
    suspend fun snapshotWakers(snapshotId: Long): List<SnapshotDeviceWaker>

    @Query("SELECT * FROM session_device_wakers WHERE sessionId IN (:sessionIds) ORDER BY sessionId, rank, kind, name")
    suspend fun sessionWakers(sessionIds: List<String>): List<SessionDeviceWaker>

    @Query("SELECT * FROM session_app_usage WHERE sessionId IN (:sessionIds) ORDER BY sessionId, rank")
    suspend fun usageRowsForSessions(sessionIds: List<String>): List<SessionAppUsage>

    @Query("DELETE FROM session_device_wakers WHERE sessionId = :sessionId")
    suspend fun deleteSessionWakers(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSessionWakerRows(rows: List<SessionDeviceWaker>)

    /** Replaces the entire session set, including when the new set is empty. */
    @Transaction
    suspend fun insertSessionWakers(sessionId: String, rows: List<SessionDeviceWaker>) {
        require(rows.all { it.sessionId == sessionId }) { "Invalid session waker rows" }
        deleteSessionWakers(sessionId)
        insertSessionWakerRows(rows)
    }

    /** Stores the header, per-uid rows and wakers before pruning; the newly inserted snapshot always survives. */
    @Transaction
    suspend fun insertSnapshot(
        snapshot: AppSnapshot,
        rows: List<AppUsageRow>,
        wakers: List<DeviceWaker> = emptyList(),
    ): Long {
        val id = insertSnapshotHeader(snapshot.copy(id = 0))
        insertSnapshotUids(rows.map { it.toSnapshotUid(id) })
        insertSnapshotWakers(wakers.map { SnapshotDeviceWaker(id, it.kind, it.name, it.count, it.totalMs) })
        pruneSnapshots(SNAPSHOTS_KEPT)
        return id
    }

    /** Keeps the [keepLatest] last inserted snapshots plus open-session BASELINEs; uid and waker rows cascade. */
    @Query("DELETE FROM app_snapshots WHERE id NOT IN (SELECT id FROM app_snapshots ORDER BY id DESC LIMIT :keepLatest) AND id NOT IN (SELECT a.id FROM app_snapshots a JOIN charge_sessions s ON s.sessionId = a.sessionId WHERE s.activeKey = 1 AND a.kind = 'BASELINE')")
    suspend fun pruneSnapshots(keepLatest: Int = SNAPSHOTS_KEPT): Int

    @Query("DELETE FROM app_snapshots WHERE sessionId IS NOT NULL AND sessionId NOT IN (SELECT sessionId FROM charge_sessions)")
    suspend fun pruneOrphanSnapshots(): Int

    @Query("SELECT * FROM app_snapshots WHERE sessionId = :sessionId AND kind = :kind ORDER BY capturedAt DESC, id DESC LIMIT 1")
    suspend fun latestSnapshot(sessionId: String, kind: AppSnapshotKind): AppSnapshot?

    @Query("SELECT * FROM app_snapshots ORDER BY capturedAt DESC, id DESC")
    suspend fun snapshots(): List<AppSnapshot>

    @Query("SELECT * FROM app_snapshot_uids WHERE snapshotId = :snapshotId ORDER BY uid")
    suspend fun snapshotUids(snapshotId: Long): List<AppSnapshotUid>

    @Query("DELETE FROM app_snapshots")
    suspend fun clearSnapshots()

    @Query("SELECT * FROM session_app_usage WHERE sessionId = :sessionId ORDER BY rank")
    fun sessionUsage(sessionId: String): Flow<List<SessionAppUsage>>

    /** Same session predicate as [SessionDao.sessionsBetween], for export. */
    @Query("SELECT u.* FROM session_app_usage u JOIN charge_sessions s ON s.sessionId = u.sessionId WHERE s.startTime <= :to AND COALESCE(s.endTime, s.lastSampleTime, s.startTime) >= :from ORDER BY s.startTime, u.sessionId, u.rank")
    suspend fun usageForSessionsBetween(from: Long, to: Long): List<SessionAppUsage>

    @Query("DELETE FROM session_app_usage WHERE sessionId = :sessionId")
    suspend fun deleteSessionUsage(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSessionUsage(rows: List<SessionAppUsage>)

    @Transaction
    suspend fun replaceSessionUsageRows(sessionId: String, rows: List<SessionAppUsage>) {
        require(rows.size <= SessionAppUsage.MAX_ROWS && rows.all { it.sessionId == sessionId }) { "Invalid app usage rows" }
        deleteSessionUsage(sessionId)
        insertSessionUsage(rows)
    }

    @Query("UPDATE charge_sessions SET appUsageStatus = :status, appUsageBasis = :basis WHERE sessionId = :sessionId")
    suspend fun setAppUsageStatus(sessionId: String, status: AppUsageStatus, basis: AppUsageBasis?): Int

    /**
     * Replaces the session's breakdown with [rows] in the given order (rank = index: top apps, waker candidates, then "others") and
     * marks the session READY with [basis], atomically. The session row must exist (FK).
     */
    @Transaction
    suspend fun replaceSessionUsage(sessionId: String, basis: AppUsageBasis, rows: List<AppUsageRow>) {
        replaceSessionUsageRows(sessionId, rows.mapIndexed { rank, row -> row.toSessionUsage(sessionId, rank, basis) })
        setAppUsageStatus(sessionId, AppUsageStatus.READY, basis)
    }

    companion object {
        const val SNAPSHOTS_KEPT = 3
    }
}

@Dao
interface InsightDao {
    @Query("SELECT * FROM insight_findings ORDER BY score DESC, key")
    fun findings(): Flow<List<InsightFindingEntity>>

    @Query("SELECT * FROM insight_findings ORDER BY score DESC, key")
    suspend fun findingsOnce(): List<InsightFindingEntity>

    @Upsert
    suspend fun upsertFindings(list: List<InsightFindingEntity>)

    @Query("UPDATE insight_findings SET status = :status WHERE `key` = :key")
    suspend fun setStatus(key: String, status: InsightFindingStatus)

    @Query("DELETE FROM insight_findings")
    suspend fun clearFindings()

    @Query("DELETE FROM insight_findings WHERE lastSeenAt < :ms")
    suspend fun purgeFindingsSeenBefore(ms: Long)

    @Query("SELECT * FROM insight_actions ORDER BY createdAt DESC, id DESC")
    fun actions(): Flow<List<InsightActionEntity>>

    @Query("SELECT * FROM insight_actions ORDER BY createdAt DESC, id DESC")
    suspend fun actionsOnce(): List<InsightActionEntity>

    @Query("SELECT * FROM insight_actions WHERE status IN (:statuses) ORDER BY createdAt DESC, id DESC")
    suspend fun actionsWithStatus(statuses: List<InsightActionStatus>): List<InsightActionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAction(entity: InsightActionEntity): Long

    /**
     * Purge expired terminal actions and uncertain force-stops, which have no Undo/readback authority.
     * Preserve reversible Undo/reconciliation authority and unrecognized future statuses.
     */
    @Query("DELETE FROM insight_actions WHERE (status IN ('REVERTED', 'FAILED', 'ONE_SHOT') OR (type = 'FORCE_STOP' AND status = 'UNKNOWN')) AND COALESCE(revertedAt, appliedAt, createdAt) < :ms")
    suspend fun purgeTerminalActionsBefore(ms: Long)

    @Update
    suspend fun updateAction(entity: InsightActionEntity)
}
