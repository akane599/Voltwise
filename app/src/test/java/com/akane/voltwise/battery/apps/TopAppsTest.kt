package com.akane.voltwise.battery.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopAppsTest {
    private fun row(uid: Int, mah: Double) = AppUsageRow(uid = uid, packageName = "app.$uid", powerMah = mah)
    private fun dump(at: Long, vararg rows: AppUsageRow) = AppUsageSnapshot(100, 1, at, rows.toList())

    @Test fun sinceTheBaselineTheTopThreeWithSharesOfEveryApp() {
        val usage = dump(2_000, row(1, 130.0), row(2, 60.0), row(3, 8.0), row(4, 2.0))
        val top = TopApps.of(usage, baseline = dump(1_000, row(1, 6.0), row(2, 0.0)))!!
        assertEquals(AppUsageBasis.DELTA, top.basis)
        assertEquals(2_000L, top.capturedAt)
        assertEquals(listOf(1, 2, 3), top.rows.map { it.uid })
        assertEquals(listOf(124.0, 60.0, 8.0), top.rows.map { it.powerMah })
        assertEquals((124.0 / 194.0).toFloat(), top.rows.first().share, 1e-6f)
    }

    @Test fun aBaselineNewerThanTheDumpIsIgnored() {
        val usage = dump(2_000, row(1, 130.0))
        val top = TopApps.of(usage, baseline = dump(3_000, row(1, 100.0)))!!
        assertEquals(AppUsageBasis.ABSOLUTE, top.basis)
        assertEquals(130.0, top.rows.single().powerMah, 1e-9)
    }

    @Test fun theFoldedTailCountsTowardTheTotalButIsNeverARow() {
        val usage = dump(2_000, *Array(40) { row(it + 1, 40.0 - it) })
        val top = TopApps.of(usage, baseline = null, count = 3)!!
        assertEquals(listOf(1, 2, 3), top.rows.map { it.uid })
        val total = (1..40).sumOf { it.toDouble() }
        assertEquals((40.0 / total).toFloat(), top.rows.first().share, 1e-6f)
    }

    @Test fun nothingUsedPowerMeansNoTopApps() {
        assertNull(TopApps.of(dump(2_000), baseline = null))
        assertNull(TopApps.of(dump(2_000, row(1, 0.0)), baseline = null))
    }

    @Test fun overflowingTailDeltasStayUnknownWithoutChangingSelectionOrCaptureMetadata() {
        val result = tailDelta(Long.MAX_VALUE, 1L)

        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertEquals(1_000L, result.captureStartMs)
        assertEquals(2_000L, result.captureEndMs)
        assertTailCounters(result.rows, null)
    }

    @Test fun exactLongMaxTailDeltasRemainKnown() {
        assertTailCounters(tailDelta(Long.MAX_VALUE, 0L).rows, Long.MAX_VALUE)
    }

    @Test fun anUnknownTailContributorKeepsItsAggregateUnknown() {
        assertTailCounters(TopApps.selectSessionRows(tailRows(Long.MAX_VALUE, null), 30), null)
    }

    @Test fun aNegativeTailContributorCannotProduceAnObservedAggregate() {
        assertTailCounters(TopApps.selectSessionRows(tailRows(Long.MAX_VALUE, -1L), 30), null)
    }

    @Test fun overflowingCountersInTheUnselectedWakerTailStayUnknown() {
        val leaders = (1..30).map { countersRow(it, 100.0 - it, 0L) }
        // Ten MAX-count wakers win the extra slots; the last MAX and 1 still reach Others.
        val wakers = (31..42).map { uid ->
            val value = if (uid == 42) 1L else Long.MAX_VALUE
            countersRow(uid, if (uid == 42) 1.0 else 2.0, 0L).copy(
                wakeupAlarms = value,
                partialWakelockBgMs = value,
            )
        }
        val selected = TopApps.selectSessionRows(leaders + wakers, 30)

        assertEquals((1..40).toList(), selected.dropLast(1).map { it.uid })
        val others = selected.last()
        assertTrue(others.isOthers)
        assertEquals(3.0, others.powerMah, 0.0)
        assertNull(others.wakeupAlarms)
        assertNull(others.partialWakelockBgMs)
        assertEquals(0L, others.mobileBytes)
    }

    private fun tailDelta(first: Long, second: Long): AppUsageDeltaResult {
        val baseline = dump(1_000, *(1..32).map { countersRow(it, 0.0, 0L) }.toTypedArray())
        return AppUsageDelta.compute(baseline, dump(2_000, *tailRows(first, second).toTypedArray()))
    }

    private fun tailRows(first: Long?, second: Long?): List<AppUsageRow> = (1..32).map { uid ->
        countersRow(uid, 33.0 - uid, when (uid) {
            31 -> first
            32 -> second
            else -> 0L
        })
    }

    private fun countersRow(uid: Int, power: Double, value: Long?) = row(uid, power).copy(
        cpuTimeMs = value,
        foregroundTimeMs = value,
        backgroundTimeMs = value,
        wakelockTimeMs = value,
        mobileBytes = value,
        wifiBytes = value,
        wakeupAlarms = 0L,
        partialWakelockCount = value,
        partialWakelockBgMs = 0L,
        jobCount = value,
        jobMs = value,
        syncCount = value,
        fgServiceMs = value,
        topMs = value,
        mobileActiveMs = value,
        gpsMs = value,
        sensorMs = value,
    )

    private fun assertTailCounters(selected: List<AppUsageRow>, expected: Long?) {
        assertEquals((1..30).toList(), selected.dropLast(1).map { it.uid })
        assertEquals((32 downTo 3).map { it.toDouble() }, selected.dropLast(1).map { it.powerMah })
        assertEquals((1..30).map { "app.$it" }, selected.dropLast(1).map { it.packageName })
        val others = selected.last()
        assertEquals(-1, others.uid)
        assertEquals("", others.packageName)
        assertTrue(others.isOthers)
        assertEquals(3.0, others.powerMah, 0.0)
        assertEquals(0L, others.wakeupAlarms)
        assertEquals(0L, others.partialWakelockBgMs)
        val counters = mapOf(
            "cpuTimeMs" to others.cpuTimeMs,
            "foregroundTimeMs" to others.foregroundTimeMs,
            "backgroundTimeMs" to others.backgroundTimeMs,
            "wakelockTimeMs" to others.wakelockTimeMs,
            "mobileBytes" to others.mobileBytes,
            "wifiBytes" to others.wifiBytes,
            "partialWakelockCount" to others.partialWakelockCount,
            "jobCount" to others.jobCount,
            "jobMs" to others.jobMs,
            "syncCount" to others.syncCount,
            "fgServiceMs" to others.fgServiceMs,
            "topMs" to others.topMs,
            "mobileActiveMs" to others.mobileActiveMs,
            "gpsMs" to others.gpsMs,
            "sensorMs" to others.sensorMs,
        )
        counters.forEach { (name, value) -> assertEquals(name, expected, value) }
    }
}
