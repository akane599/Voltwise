package com.akane.voltwise.battery.data.sampling

import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.DailySummary
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DailySummaryReplayTest {
    private val day = LocalDate.of(2026, 9, 1)
    private val midnight = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun sample(offsetMs: Long, level: Int, charge: Long, screenOn: Boolean = true, status: Int = 3, plugged: Int = 0,
                       observation: String? = "run", boundaryReason: String? = null, source: String = "BatteryManager",
                       uptimeOffset: Long = offsetMs) = BatterySample(
        timestamp = midnight + offsetMs, levelPercent = level, status = status, plugged = plugged, currentNowUa = -300_000,
        chargeCounterUah = charge, voltageMv = 4000, temperatureDeciC = 280 + level % 3, health = 2, screenOn = screenOn,
        elapsedMs = 1_000_000 + offsetMs, uptimeMs = 900_000 + uptimeOffset, observationId = observation, source = source,
        boundaryReason = boundaryReason,
    )

    private fun replay(vararg samples: BatterySample): List<DailySummary> =
        DailySummaryReplay(ZoneOffset.UTC, updatedAt = 42).apply { samples.forEach(::add) }.result

    @Test fun screenOnAndOffDischargeIsRebuiltWithoutInventingGapsAtScreenChanges() {
        val rows = replay(
            sample(10 * 3_600_000L, 80, 4_000_000),
            sample(10 * 3_600_000L + 60_000, 80, 3_995_000),
            // Screen turned off: the stored row has no boundary label, the replay infers SCREEN.
            sample(10 * 3_600_000L + 120_000, 79, 3_990_000, screenOn = false),
            sample(10 * 3_600_000L + 420_000, 79, 3_980_000, screenOn = false, uptimeOffset = 10 * 3_600_000L + 130_000),
        )
        val row = rows.single()
        assertEquals(day.toEpochDay(), row.epochDay)
        assertEquals(120_000L, row.screenOnMs)
        assertEquals(10_000L, row.screenOnDischargeUah)
        assertEquals(300_000L, row.screenOffMs)
        assertEquals(10_000L, row.screenOffDischargeUah)
        assertEquals(290_000L, row.cpuSuspendMs)
        assertNull(row.dozeMs)
        assertNull(row.screenOffDozeMs)
        assertNull(row.screenOffSuspendMs)
        assertEquals(79, row.minLevel)
        assertEquals(80, row.maxLevel)
        assertEquals(282, row.peakTemperatureDeciC)
        assertEquals(42L, row.updatedAt)
    }

    @Test fun legacyImportedAndGapRowsAddNothing() {
        val rows = replay(
            sample(3_600_000, 90, 4_000_000, observation = null),
            sample(3_660_000, 90, 3_990_000, source = "import:BatteryManager"),
            sample(3_720_000, 90, 3_980_000),
            sample(3_780_000, 89, 3_970_000, boundaryReason = "Gap in observation"),
            sample(3_840_000, 89, 3_960_000, observation = "other"),
        )
        val row = rows.single()
        assertEquals(0L, row.screenOnMs)
        assertEquals(0L, row.screenOnDischargeUah)
        assertEquals(89, row.minLevel)
        assertEquals(90, row.maxLevel)
    }

    @Test fun chargingAcrossMidnightIsSplitByWallTime() {
        val rows = replay(
            sample(-60_000, 50, 2_000_000, status = 2, plugged = 1),
            sample(60_000, 52, 2_060_000, status = 2, plugged = 1),
        )
        assertEquals(listOf(day.toEpochDay() - 1, day.toEpochDay()), rows.map { it.epochDay })
        assertEquals(listOf(30_000L, 30_000L), rows.map { it.chargedUah })
        assertEquals(listOf(50, 52), rows.map { it.maxLevel })
    }

    @Test fun `unlabelled row with screen+power change is not a gap`() {
        val row = replay(sample(0, 80, 4_000_000),
            sample(60_000, 80, 3_990_000, screenOn = false, status = 2, plugged = 1)).single()
        assertEquals(60_000L, row.screenOnMs)
        assertEquals(0L, row.screenOnDischargeUah)
    }

    @Test fun labelledCompoundBoundaryRemainsAGap() {
        val row = replay(sample(0, 80, 4_000_000),
            sample(60_000, 80, 3_990_000, screenOn = false, status = 2, plugged = 1,
                boundaryReason = "Collection interrupted")).single()
        assertEquals(0L, row.screenOnMs)
    }

    @Test fun nothingToReplayGivesNoRows() {
        assertTrue(replay().isEmpty())
        assertTrue(replay(sample(0, 50, 1, observation = null)).isEmpty())
    }
}
