package com.akane.voltwise.battery.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One row per local calendar day, updated in the same transaction as each persisted sample.
 * Charge amounts are raw counter deltas (µAh, always ≥ 0); durations are observed wall time.
 */
@Serializable
@Entity(tableName = "daily_summaries")
data class DailySummary(
    @field:PrimaryKey val epochDay: Long,  // LocalDate.toEpochDay() in the device zone at write time
    val screenOnMs: Long = 0,
    val screenOffMs: Long = 0,
    val screenOnDischargeUah: Long = 0,
    val screenOffDischargeUah: Long = 0,
    val chargedUah: Long = 0,
    val cpuSuspendMs: Long? = null,        // deep sleep while discharging, when measurable
    val minLevel: Int? = null,
    val maxLevel: Int? = null,
    val peakTemperatureDeciC: Int? = null,
    val updatedAt: Long = 0,
    // v6: null means legacy coverage is unknown; zero means no measured counter interval.
    val screenOnCoveredMs: Long? = null,
    val screenOffCoveredMs: Long? = null,
    val dozeMs: Long? = null,
    val screenOffDozeMs: Long? = null,
    val screenOffSuspendMs: Long? = null,
)
