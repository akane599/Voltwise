package com.akane.voltwise.battery.data.db

import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageRow
import org.junit.Assert.*
import org.junit.Test

class AppUsageTablesTest {
    private val row = AppUsageRow(
        uid = 10001, packageName = "example.app", powerMah = 1.5,
        cpuTimeMs = 1, foregroundTimeMs = 2, backgroundTimeMs = 3, wakelockTimeMs = 4,
        mobileBytes = 5, wifiBytes = 6, wakeupAlarms = 7, partialWakelockCount = 8,
        partialWakelockBgMs = 9, jobCount = 10, jobMs = 11, syncCount = 12, fgServiceMs = 13,
        topMs = 14, mobileActiveMs = 15, gpsMs = 16, sensorMs = 17,
        topWakelockTag = "lock", topAlarmTag = "alarm", topJobName = "job",
    )

    @Test fun snapshotRoundTripPreservesEveryMetricButNotSessionHints() {
        val stored = row.copy(isOthers = true).toSnapshotUid(42)
        assertEquals(42L, stored.snapshotId)
        assertEquals(row.copy(topWakelockTag = null, topAlarmTag = null, topJobName = null), stored.toRow())
    }

    @Test fun sessionRoundTripPreservesEveryMetricAndHint() {
        val input = row.copy(isOthers = true)
        val stored = input.toSessionUsage("session", 40, AppUsageBasis.DELTA)
        assertEquals("session", stored.sessionId)
        assertEquals(40, stored.rank)
        assertEquals(AppUsageBasis.DELTA, stored.basis)
        assertEquals(input, stored.toRow())
        assertEquals(41, SessionAppUsage.MAX_ROWS)
    }

    @Test fun oldCallersKeepUnknownMetricsNullAndMeasuredZeroIsNotUnknown() {
        val legacy = AppUsageRow(10001, "example.app", 1.5)
        assertEquals(legacy, legacy.toSnapshotUid(1).toRow())
        assertEquals(legacy, legacy.toSessionUsage("session", 0, AppUsageBasis.ABSOLUTE).toRow())
        assertNull(legacy.toSessionUsage("session", 0, AppUsageBasis.ABSOLUTE).wakeupAlarms)
        val measuredZero = legacy.copy(wakeupAlarms = 0, sensorMs = 0)
        assertEquals(measuredZero, measuredZero.toSnapshotUid(1).toRow())
        assertEquals(measuredZero, measuredZero.toSessionUsage("session", 0, AppUsageBasis.DELTA).toRow())
    }
}
