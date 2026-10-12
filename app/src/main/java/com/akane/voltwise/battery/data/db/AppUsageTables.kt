package com.akane.voltwise.battery.data.db

import androidx.room.*
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageRow
import kotlinx.serialization.Serializable

enum class AppSnapshotKind { BASELINE, END }

/**
 * Header of a stored `batterystats --charged` per-app snapshot: BASELINE after unplug (for the new discharge
 * session), END after plug-in (for the session that just ended). Transient: only the open session's baseline
 * and the last [AppUsageDao.SNAPSHOTS_KEPT] survive. `sessionId` is not a foreign key; retention removes
 * snapshots whose session is gone.
 */
@Entity(tableName = "app_snapshots", indices = [Index("sessionId")])
data class AppSnapshot(
    @field:PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String?,
    val kind: AppSnapshotKind,
    val capturedAt: Long,           // wall clock, ms
    val windowStartedAt: Long?,     // batterystats window start; a different window means the stats were reset
    val windowStartCount: Long?,
    val deepIdleMs: Long? = null,
    val deepIdleCount: Long? = null,
    val lightIdleMs: Long? = null,
    val lightIdleCount: Long? = null,
    val screenOffMs: Long? = null,
    val wakersComplete: Boolean? = null,
)

/** Raw per-uid totals only: no aggregated `isOthers` row or since-charge session tag hints. */
@Entity(
    tableName = "app_snapshot_uids",
    primaryKeys = ["snapshotId", "uid"],
    foreignKeys = [ForeignKey(entity = AppSnapshot::class, parentColumns = ["id"], childColumns = ["snapshotId"], onDelete = ForeignKey.CASCADE)],
)
data class AppSnapshotUid(
    val snapshotId: Long,
    val uid: Int,
    val packageName: String,
    val powerMah: Double,
    val cpuTimeMs: Long? = null,
    val foregroundTimeMs: Long? = null,
    val backgroundTimeMs: Long? = null,
    val wakelockTimeMs: Long? = null,
    val mobileBytes: Long? = null,
    val wifiBytes: Long? = null,
    val wakeupAlarms: Long? = null,
    val partialWakelockCount: Long? = null,
    val partialWakelockBgMs: Long? = null,
    val jobCount: Long? = null,
    val jobMs: Long? = null,
    val syncCount: Long? = null,
    val fgServiceMs: Long? = null,
    val topMs: Long? = null,
    val mobileActiveMs: Long? = null,
    val gpsMs: Long? = null,
    val sensorMs: Long? = null,
)

/**
 * A discharge session's per-app breakdown: ranked rows (0 = largest), the top 30 apps plus up to 10 waker candidates and at most one
 * `isOthers` row. Deleted with its session (FK cascade). Local evidence excluded from history export/import.
 */
@Serializable
@Entity(
    tableName = "session_app_usage",
    primaryKeys = ["sessionId", "rank"],
    foreignKeys = [ForeignKey(entity = ChargeSession::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class SessionAppUsage(
    val sessionId: String,
    val rank: Int,
    val uid: Int,
    val packageName: String,
    val powerMah: Double,           // mAh attributed by Android batterystats
    val cpuTimeMs: Long? = null,
    val foregroundTimeMs: Long? = null,
    val backgroundTimeMs: Long? = null,
    val wakelockTimeMs: Long? = null,
    val mobileBytes: Long? = null,
    val wifiBytes: Long? = null,
    val isOthers: Boolean = false,
    val basis: AppUsageBasis,
    val wakeupAlarms: Long? = null,
    val partialWakelockCount: Long? = null,
    val partialWakelockBgMs: Long? = null,
    val jobCount: Long? = null,
    val jobMs: Long? = null,
    val syncCount: Long? = null,
    val fgServiceMs: Long? = null,
    val topMs: Long? = null,
    val mobileActiveMs: Long? = null,
    val gpsMs: Long? = null,
    val sensorMs: Long? = null,
    val topWakelockTag: String? = null,
    val topAlarmTag: String? = null,
    val topJobName: String? = null,
) {
    companion object {
        /** Top 30 apps, up to 10 additional waker candidates, plus the "others" row. */
        const val MAX_ROWS = 41
    }
}

fun AppUsageRow.toSnapshotUid(snapshotId: Long) = AppSnapshotUid(
    snapshotId = snapshotId,
    uid = uid,
    packageName = packageName,
    powerMah = powerMah,
    cpuTimeMs = cpuTimeMs,
    foregroundTimeMs = foregroundTimeMs,
    backgroundTimeMs = backgroundTimeMs,
    wakelockTimeMs = wakelockTimeMs,
    mobileBytes = mobileBytes,
    wifiBytes = wifiBytes,
    wakeupAlarms = wakeupAlarms,
    partialWakelockCount = partialWakelockCount,
    partialWakelockBgMs = partialWakelockBgMs,
    jobCount = jobCount,
    jobMs = jobMs,
    syncCount = syncCount,
    fgServiceMs = fgServiceMs,
    topMs = topMs,
    mobileActiveMs = mobileActiveMs,
    gpsMs = gpsMs,
    sensorMs = sensorMs,
)

fun AppSnapshotUid.toRow() = AppUsageRow(
    uid = uid,
    packageName = packageName,
    powerMah = powerMah,
    cpuTimeMs = cpuTimeMs,
    foregroundTimeMs = foregroundTimeMs,
    backgroundTimeMs = backgroundTimeMs,
    wakelockTimeMs = wakelockTimeMs,
    mobileBytes = mobileBytes,
    wifiBytes = wifiBytes,
    wakeupAlarms = wakeupAlarms,
    partialWakelockCount = partialWakelockCount,
    partialWakelockBgMs = partialWakelockBgMs,
    jobCount = jobCount,
    jobMs = jobMs,
    syncCount = syncCount,
    fgServiceMs = fgServiceMs,
    topMs = topMs,
    mobileActiveMs = mobileActiveMs,
    gpsMs = gpsMs,
    sensorMs = sensorMs,
)

fun AppUsageRow.toSessionUsage(sessionId: String, rank: Int, basis: AppUsageBasis) = SessionAppUsage(
    sessionId = sessionId,
    rank = rank,
    uid = uid,
    packageName = packageName,
    powerMah = powerMah,
    cpuTimeMs = cpuTimeMs,
    foregroundTimeMs = foregroundTimeMs,
    backgroundTimeMs = backgroundTimeMs,
    wakelockTimeMs = wakelockTimeMs,
    mobileBytes = mobileBytes,
    wifiBytes = wifiBytes,
    isOthers = isOthers,
    basis = basis,
    wakeupAlarms = wakeupAlarms,
    partialWakelockCount = partialWakelockCount,
    partialWakelockBgMs = partialWakelockBgMs,
    jobCount = jobCount,
    jobMs = jobMs,
    syncCount = syncCount,
    fgServiceMs = fgServiceMs,
    topMs = topMs,
    mobileActiveMs = mobileActiveMs,
    gpsMs = gpsMs,
    sensorMs = sensorMs,
    topWakelockTag = topWakelockTag,
    topAlarmTag = topAlarmTag,
    topJobName = topJobName,
)

fun SessionAppUsage.toRow() = AppUsageRow(
    uid = uid,
    packageName = packageName,
    powerMah = powerMah,
    cpuTimeMs = cpuTimeMs,
    foregroundTimeMs = foregroundTimeMs,
    backgroundTimeMs = backgroundTimeMs,
    wakelockTimeMs = wakelockTimeMs,
    mobileBytes = mobileBytes,
    wifiBytes = wifiBytes,
    isOthers = isOthers,
    wakeupAlarms = wakeupAlarms,
    partialWakelockCount = partialWakelockCount,
    partialWakelockBgMs = partialWakelockBgMs,
    jobCount = jobCount,
    jobMs = jobMs,
    syncCount = syncCount,
    fgServiceMs = fgServiceMs,
    topMs = topMs,
    mobileActiveMs = mobileActiveMs,
    gpsMs = gpsMs,
    sensorMs = sensorMs,
    topWakelockTag = topWakelockTag,
    topAlarmTag = topAlarmTag,
    topJobName = topJobName,
)
