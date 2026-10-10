package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.apps.*
import kotlinx.coroutines.flow.Flow

// Unused DAO operations fail loudly; the repository tests override only its actual read/write seam.
internal open class UnusedSessionDao : SessionDao {
    override suspend fun insert(session: ChargeSession): Unit = error("Unused DAO operation")
    override suspend fun update(session: ChargeSession): Int = error("Unused DAO operation")
    override suspend fun byId(id: String): ChargeSession? = error("Unused DAO operation")
    override suspend fun count(): Int = error("Unused DAO operation")
    override suspend fun boundStorage(limit: Int): Unit = error("Unused DAO operation")
    override suspend fun active(): ChargeSession? = error("Unused DAO operation")
    override fun activeFlow(): Flow<ChargeSession?> = error("Unused DAO operation")
    override suspend fun sessionsBetween(from: Long, to: Long): List<ChargeSession> = error("Unused DAO operation")
    override suspend fun closedSessionsBetween(from: Long, to: Long): List<ChargeSession> = error("Unused DAO operation")
    override suspend fun closedDischargeSessionsBetween(from: Long, to: Long): List<ChargeSession> = error("Unused DAO operation")
    override suspend fun closeInterrupted(reason: String): Unit = error("Unused DAO operation")
    override suspend fun purge(olderThan: Long): Unit = error("Unused DAO operation")
    override suspend fun clearAll(): Unit = error("Unused DAO operation")
    override fun filteredSessions(type: SessionType?, query: String, limit: Int): Flow<List<ChargeSession>> = error("Unused DAO operation")
    override fun session(id: String): Flow<ChargeSession?> = error("Unused DAO operation")
    override fun capacityEstimates(limit: Int): Flow<List<CapacityEstimateRow>> = error("Unused DAO operation")
    override suspend fun deleteSessionSamples(id: String): Int = error("Unused DAO operation")
    override suspend fun deleteSessionSnapshots(id: String): Int = error("Unused DAO operation")
    override suspend fun deleteSessionRow(id: String): Int = error("Unused DAO operation")
    override suspend fun complete(id: String, end: Long, endLevel: Int?, delta: Long?, avg: Long?, cap: Int?): Unit = error("Unused DAO operation")
}

internal open class UnusedDailySummaryDao : DailySummaryDao {
    override suspend fun upsert(summary: DailySummary): Unit = error("Unused DAO operation")
    override suspend fun upsertAll(summaries: List<DailySummary>): Unit = error("Unused DAO operation")
    override suspend fun byDay(epochDay: Long): DailySummary? = error("Unused DAO operation")
    override fun day(epochDay: Long): Flow<DailySummary?> = error("Unused DAO operation")
    override fun between(fromDay: Long, toDay: Long): Flow<List<DailySummary>> = error("Unused DAO operation")
    override suspend fun range(fromDay: Long, toDay: Long): List<DailySummary> = error("Unused DAO operation")
    override suspend fun count(): Int = error("Unused DAO operation")
    override suspend fun purgeBefore(epochDay: Long): Int = error("Unused DAO operation")
    override suspend fun clearAll(): Unit = error("Unused DAO operation")
}

internal open class UnusedAppUsageDao : AppUsageDao {
    override suspend fun insertSnapshotHeader(snapshot: AppSnapshot): Long = error("Unused DAO operation")
    override suspend fun insertSnapshotUids(rows: List<AppSnapshotUid>): Unit = error("Unused DAO operation")
    override suspend fun insertSnapshotWakers(rows: List<SnapshotDeviceWaker>): Unit = error("Unused DAO operation")
    override suspend fun snapshotWakers(snapshotId: Long): List<SnapshotDeviceWaker> = error("Unused DAO operation")
    override suspend fun sessionWakers(sessionIds: List<String>): List<SessionDeviceWaker> = error("Unused DAO operation")
    override suspend fun usageRowsForSessions(sessionIds: List<String>): List<SessionAppUsage> = error("Unused DAO operation")
    override suspend fun deleteSessionWakers(sessionId: String): Unit = error("Unused DAO operation")
    override suspend fun insertSessionWakerRows(rows: List<SessionDeviceWaker>): Unit = error("Unused DAO operation")
    override suspend fun pruneSnapshots(keepLatest: Int): Int = error("Unused DAO operation")
    override suspend fun pruneOrphanSnapshots(): Int = error("Unused DAO operation")
    override suspend fun latestSnapshot(sessionId: String, kind: AppSnapshotKind): AppSnapshot? = error("Unused DAO operation")
    override suspend fun snapshots(): List<AppSnapshot> = error("Unused DAO operation")
    override suspend fun snapshotUids(snapshotId: Long): List<AppSnapshotUid> = error("Unused DAO operation")
    override suspend fun clearSnapshots(): Unit = error("Unused DAO operation")
    override fun sessionUsage(sessionId: String): Flow<List<SessionAppUsage>> = error("Unused DAO operation")
    override suspend fun usageForSessionsBetween(from: Long, to: Long): List<SessionAppUsage> = error("Unused DAO operation")
    override suspend fun deleteSessionUsage(sessionId: String): Unit = error("Unused DAO operation")
    override suspend fun insertSessionUsage(rows: List<SessionAppUsage>): Unit = error("Unused DAO operation")
    override suspend fun setAppUsageStatus(sessionId: String, status: AppUsageStatus, basis: AppUsageBasis?): Int = error("Unused DAO operation")
}

internal open class UnusedInsightDao : InsightDao {
    override fun findings(): Flow<List<InsightFindingEntity>> = error("Unused DAO operation")
    override suspend fun findingsOnce(): List<InsightFindingEntity> = error("Unused DAO operation")
    override suspend fun upsertFindings(list: List<InsightFindingEntity>): Unit = error("Unused DAO operation")
    override suspend fun setStatus(key: String, status: InsightFindingStatus): Unit = error("Unused DAO operation")
    override suspend fun clearFindings(): Unit = error("Unused DAO operation")
    override suspend fun purgeFindingsSeenBefore(ms: Long): Unit = error("Unused DAO operation")
    override fun actions(): Flow<List<InsightActionEntity>> = error("Unused DAO operation")
    override suspend fun actionsOnce(): List<InsightActionEntity> = error("Unused DAO operation")
    override suspend fun actionsWithStatus(statuses: List<InsightActionStatus>): List<InsightActionEntity> = error("Unused DAO operation")
    override suspend fun insertAction(entity: InsightActionEntity): Long = error("Unused DAO operation")
    override suspend fun purgeTerminalActionsBefore(ms: Long): Unit = error("Unused DAO operation")
    override suspend fun updateAction(entity: InsightActionEntity): Unit = error("Unused DAO operation")
}

