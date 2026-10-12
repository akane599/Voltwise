package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.data.db.BatteryDao
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.SessionChartReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSamplesTest {
    @Test
    fun allExportAfterFullImportAndLiveSamplingKeepsNewestCapWithoutDeletingHistory() = runTest {
        val count = HistoryLimits.MAX_SAMPLES + HistoryLimits.CLEANUP_SAMPLE_INTERVAL
        val dao = SamplesDao(samples(count))

        val exported = dao.exportSamples(0, count.toLong())

        assertEquals("ALL export must remain within its sample cap before cleanup", HistoryLimits.MAX_SAMPLES, exported.size)
        assertEquals(201L, exported.first().timestamp)
        assertEquals(count.toLong(), exported.last().timestamp)
        assertTrue(exported.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp })
        assertEquals("Export must not trim stored history", count, dao.count())
    }

    @Test
    fun allExportKeepsFullBackupAtCapAndHonorsEndTime() = runTest {
        val dao = SamplesDao(samples(HistoryLimits.MAX_SAMPLES + 1))

        val exported = dao.exportSamples(0, HistoryLimits.MAX_SAMPLES.toLong())

        assertEquals(HistoryLimits.MAX_SAMPLES, exported.size)
        assertEquals(1L, exported.first().timestamp)
        assertEquals(HistoryLimits.MAX_SAMPLES.toLong(), exported.last().timestamp)
        assertTrue(HistoryLimits.importWithinLimit(0, exported.size, HistoryLimits.MAX_SAMPLES))
    }

    @Test
    fun explicitRangeDoesNotSilentlyTruncateExcessSamples() = runTest {
        val count = HistoryLimits.MAX_SAMPLES + 1
        val dao = SamplesDao(samples(count))

        val exported = dao.exportSamples(1, count.toLong())

        // The snapshot's existing cap validation must still reject oversized explicit ranges.
        assertEquals(count, exported.size)
    }

    private fun samples(count: Int): List<BatterySample> = (1..count).map { index ->
        BatterySample(
            id = index.toLong(), timestamp = index.toLong(), levelPercent = 50, status = 3,
            plugged = null, currentNowUa = null, chargeCounterUah = null, voltageMv = null,
            temperatureDeciC = null, health = null, screenOn = false,
        )
    }

    private class SamplesDao(private val stored: List<BatterySample>) : BatteryDao {
        override fun samplesBetween(from: Long, to: Long): Flow<List<BatterySample>> =
            flowOf(stored.filter { it.timestamp in from..to }.sortedBy { it.timestamp })
        override suspend fun latestSamplesBetween(from: Long, to: Long, limit: Int): List<BatterySample> =
            stored.filter { it.timestamp in from..to }
                .sortedWith(compareBy<BatterySample> { it.timestamp }.thenBy { it.id })
                .takeLast(limit)
        override suspend fun count(): Int = stored.size
        override suspend fun insertSample(sample: BatterySample): Long = error("Not used")
        override suspend fun byId(id: Long): BatterySample? = error("Not used")
        override suspend fun atTimestamp(timestamp: Long): List<BatterySample> = error("Not used")
        override suspend fun observedPoint(observationId: String, elapsedMs: Long): BatterySample? = error("Not used")
        override suspend fun lastSample(): BatterySample? = error("Not used")
        override suspend fun lastLocalSample(): BatterySample? = error("Not used")
        override fun chartSamples(from: Long, to: Long, bucketMs: Long): Flow<List<BatterySample>> = error("Not used")
        override fun samplesForSession(sessionId: String): Flow<List<BatterySample>> = error("Not used")
        override suspend fun sessionChartSamples(sessionId: String, from: Long, to: Long, bucketMs: Long): List<SessionChartReading> = error("Not used")
        override suspend fun boundStorage(limit: Int) = error("Export must not trim history")
        override suspend fun clearAll() = error("Export must not clear history")
        override suspend fun purge(olderThan: Long) = error("Export must not purge history")
    }
}
