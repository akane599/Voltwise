package com.akane.voltwise.battery.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class InsightFindingStatus { ACTIVE, RESOLVED, DISMISSED }
enum class InsightActionStatus { PREPARED, APPLIED, FAILED, UNKNOWN, REVERTED, ONE_SHOT }

/** Kind is the parser's stable name (KERNEL_WAKELOCK or WAKEUP_REASON), not an engine dependency. */
@Entity(
    tableName = "snapshot_device_wakers",
    primaryKeys = ["snapshotId", "kind", "name"],
    foreignKeys = [ForeignKey(entity = AppSnapshot::class, parentColumns = ["id"], childColumns = ["snapshotId"], onDelete = ForeignKey.CASCADE)],
)
data class SnapshotDeviceWaker(
    val snapshotId: Long,
    val kind: String,
    val name: String,
    val count: Long,
    val totalMs: Long,
)

/**
 * Deltas keyed by kind and name. A missing baseline name counts as zero only when baseline waker
 * evidence is complete; otherwise the name must be present at both captures.
 * The writer ranks and bounds these rows.
 */
@Entity(
    tableName = "session_device_wakers",
    primaryKeys = ["sessionId", "kind", "name"],
    foreignKeys = [ForeignKey(entity = ChargeSession::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class SessionDeviceWaker(
    val sessionId: String,
    val kind: String,
    val name: String,
    val count: Long,
    val totalMs: Long,
    val rank: Int,
)

/**
 * Type, severity and confidence are stable names interpreted by the insight repository, not engine enums:
 * persistence must not depend on the engine model, and unknown names must remain readable after upgrades.
 * Only DB-owned lifecycle statuses use converters. Evidence is versioned and validated by its writer.
 */
@Entity(tableName = "insight_findings", indices = [Index("status")])
data class InsightFindingEntity(
    @field:PrimaryKey val key: String,
    val type: String,
    val uid: Int? = null,
    val packageName: String? = null,
    val severity: String,
    val confidence: String,
    val score: Double,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val status: InsightFindingStatus,
    @ColumnInfo(defaultValue = "1.0") val feedbackMultiplier: Double = 1.0,
    val evidenceVersion: Int,
    val evidenceJson: String,
)

const val MESSAGE_CHANGED_EXTERNALLY = "CHANGED_EXTERNALLY"

/**
 * Undo authority, deliberately independent of findings and history (no foreign key or history cascade).
 * Type is a stable action name interpreted by the action repository; unknown names are preserved.
 */
@Entity(tableName = "insight_actions", indices = [Index("status")])
data class InsightActionEntity(
    @field:PrimaryKey(autoGenerate = true) val id: Long = 0,
    val findingKey: String,
    val type: String,
    val packageName: String? = null,
    val uid: Int? = null,
    val userId: Int,
    val status: InsightActionStatus,
    val priorStateVersion: Int,
    val priorState: String? = null,
    val targetState: String? = null,
    val createdAt: Long,
    val appliedAt: Long? = null,
    val revertedAt: Long? = null,
    val message: String? = null,
    val metric: String? = null,
)
