package com.akane.voltwise.battery.data.db

import androidx.room.*
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageStatus
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "battery_samples",
    indices = [Index("timestamp"), Index("sessionId"), Index(value = ["observationId", "elapsedMs"], unique = true)]
)
data class BatterySample(
    @field:PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val levelPercent: Int?,                  // 0..100
    val status: Int,                        // BatteryManager status
    val plugged: Int?,                       // BatteryManager EXTRA_PLUGGED
    val currentNowUa: Long?,                // microAmps (negative while discharging)
    val chargeCounterUah: Long?,            // microAh
    val voltageMv: Int?,                    // mV
    val temperatureDeciC: Int?,             // tenths of °C
    val health: Int?,                       // BatteryManager EXTRA_HEALTH
    val screenOn: Boolean,
    val elapsedMs: Long? = null,
    val uptimeMs: Long? = null,
    val observationId: String? = null,
    val sessionId: String? = null,
    val currentAverageUa: Long? = null,
    val energyNwh: Long? = null,
    val cycleCount: Int? = null,
    val etaMs: Long? = null,
    val etaBasis: String? = null,
    @ColumnInfo(defaultValue = "'legacy'") val source: String = "legacy",
    val boundaryReason: String? = null
)

@Serializable
@Entity(
    tableName = "charge_sessions",
    indices = [Index("startTime"), Index("type"), Index(value = ["activeKey"], unique = true)]
)
data class ChargeSession(
    @Contextual
    @field:PrimaryKey val sessionId: String,
    val type: SessionType,
    val startTime: Long,
    val endTime: Long?,             // null while active
    val startLevel: Int?,
    val endLevel: Int?,
    val deltaUah: Long?,            // integrated charge delta
    val avgCurrentUa: Long?,        // session average
    val estCapacityMah: Int?,       // legacy session estimate; new sessions do not guess capacity
    val activeKey: Int? = if (endTime == null) 1 else null,
    val observationId: String? = null,
    val lastSampleTime: Long? = null,
    @ColumnInfo(defaultValue = "0") val observedMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val counterCoveredMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val screenOnMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val screenOffMs: Long = 0,
    val screenOnUah: Long? = null,
    val screenOffUah: Long? = null,
    val cpuSuspendMs: Long? = null,
    val closeReason: String? = null,
    @ColumnInfo(defaultValue = "'legacy'") val source: String = "legacy",
    // v5 — all nullable: legacy and imported v4 rows leave them null.
    val chargerType: String? = null,         // charging only: AC / USB / WIRELESS / DOCK (enum name)
    val energyNwh: Long? = null,             // nWh, ≥ 0; same sign rule as deltaUah (gained for CHARGE, consumed for DISCHARGE)
    val peakPowerMw: Long? = null,           // mW magnitude, ≥ 0
    val peakTemperatureDeciC: Int? = null,   // tenths of °C
    val screenOffSuspendMs: Long? = null,    // CPU suspend (deep sleep) while the screen was off
    val capacityEstimateMah: Int? = null,    // full-capacity estimate from this session's counter span
    val capacityConfidence: String? = null,  // measurement confidence enum name
    val capacityBasis: String? = null,       // measurement basis enum name
    val appUsageStatus: AppUsageStatus? = null,
    val appUsageBasis: AppUsageBasis? = null,
    // v6: null means legacy coverage is unknown.
    val screenOnCoveredMs: Long? = null,
    val screenOffCoveredMs: Long? = null,
    val dozeMs: Long? = null,
    val screenOffDozeMs: Long? = null,
    val appCaptureStartMs: Long? = null,
    val appCaptureEndMs: Long? = null,
)

enum class SessionType { CHARGE, DISCHARGE, PLUGGED, UNKNOWN }

/** A session's stored capacity estimate with what the Health trend shows next to it ([SessionDao.capacityEstimates]). */
data class CapacityEstimateRow(
    val sessionId: String,
    val type: SessionType,
    val startTime: Long,
    val endTime: Long?,
    val lastSampleTime: Long?,
    val startLevel: Int?,
    val endLevel: Int?,
    val capacityEstimateMah: Int,
    val capacityConfidence: String?,
    val capacityBasis: String?,
    val source: String = "legacy",
)

/** Bounded representative chart rows; bucket discontinuities must remain visible. */
data class SessionChartReading(
    val timestamp: Long,
    val currentNowUa: Long?,
    val voltageMv: Int?,
    val temperatureDeciC: Int?,
    val observationId: String?,
    val source: String,
    val discontinuity: Boolean
)
