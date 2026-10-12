package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.insights.UnusedSessionDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryLimitsTest {
    @Test fun samplesRemainExportableAndImportableUntilTheNextTrim() {
        assertEquals("Sample trim reserves exactly one cleanup interval", HistoryLimits.CLEANUP_SAMPLE_INTERVAL,
            HistoryLimits.MAX_SAMPLES - HistoryLimits.SAMPLE_TRIM_TARGET)
        assertTrimHeadroom(HistoryLimits.SAMPLE_TRIM_TARGET, HistoryLimits.MAX_SAMPLES)
    }

    @Test fun fullSampleImportFollowedByNativeWriteCannotGrowThroughAnotherImport() {
        val afterNativeWrite = HistoryLimits.MAX_SAMPLES + 1
        assertEquals("A full import plus one native write needs exactly interval + 1 deletions",
            HistoryLimits.CLEANUP_SAMPLE_INTERVAL + 1, afterNativeWrite - HistoryLimits.SAMPLE_TRIM_TARGET)
        assertTrue(HistoryLimits.importWithinLimit(afterNativeWrite, afterNativeWrite, HistoryLimits.MAX_SAMPLES))
        assertFalse(HistoryLimits.importWithinLimit(afterNativeWrite, afterNativeWrite + 1, HistoryLimits.MAX_SAMPLES))
    }

    @Test fun sessionsRemainExportableAndImportableUntilTheNextTrim() {
        // A persisted sample can open one new session at a power/gap boundary.
        assertTrimHeadroom(HistoryLimits.SESSION_TRIM_TARGET, HistoryLimits.MAX_SESSIONS)
    }

    @Test fun duplicateImportIsAllowedWithExistingExcessSamples() {
        val existing = HistoryLimits.MAX_SAMPLES + 199
        assertTrue("Unchanged excess samples must not block an import", HistoryLimits.importWithinLimit(existing, existing, HistoryLimits.MAX_SAMPLES))
    }

    @Test fun sampleOnlyImportIsAllowedWithExistingExcessSessions() {
        val existing = HistoryLimits.MAX_SESSIONS + 199
        assertTrue("Unchanged excess sessions must not block an import", HistoryLimits.importWithinLimit(existing, existing, HistoryLimits.MAX_SESSIONS))
    }

    @Test fun importsMayFillEitherStoreExactlyToItsCap() {
        for (limit in listOf(HistoryLimits.MAX_SAMPLES, HistoryLimits.MAX_SESSIONS)) {
            assertTrue(HistoryLimits.importWithinLimit(limit - 1, limit, limit))
        }
    }

    @Test fun importsCannotGrowEitherStorePastItsCap() {
        for (limit in listOf(HistoryLimits.MAX_SAMPLES, HistoryLimits.MAX_SESSIONS)) {
            assertFalse(HistoryLimits.importWithinLimit(limit, limit + 1, limit))
            assertFalse(HistoryLimits.importWithinLimit(limit + 199, limit + 200, limit))
        }
    }

    @Test fun reducingExistingExcessDoesNotBlockAnImport() {
        assertTrue(HistoryLimits.importWithinLimit(100_199, 100_198, HistoryLimits.MAX_SAMPLES))
    }

    @Test fun allSessionExportAfterFullImportAndBoundaryKeepsNewestCapWithoutDeletingHistory() = runTest {
        val count = HistoryLimits.MAX_SESSIONS + 1
        val stored = (1..count).map { index ->
            exportSession("s%05d".format(index), maxOf(2, index).toLong())
        }.reversed()
        val dao = ExportSessionDao(stored)
        val exported = dao.exportSessions(0, count.toLong())
        assertEquals("ALL export must remain within its session cap before cleanup", HistoryLimits.MAX_SESSIONS, exported.size)
        assertEquals(stored.asReversed().drop(1), exported)
        assertEquals("Equal-time cutoff must retain the greater sessionId", "s00002", exported.first().sessionId)
        assertEquals("Export must not trim stored sessions", count, dao.count())
    }

    @Test fun explicitSessionExportDoesNotSilentlyTruncateOverflow() = runTest {
        val count = HistoryLimits.MAX_SESSIONS + 1
        val dao = ExportSessionDao((1..count).map { exportSession("s$it", it.toLong()) })
        // snapshot's existing cap check must reject this untruncated explicit selection.
        assertEquals(count, dao.exportSessions(1, count.toLong()).size)
        assertEquals(count, dao.count())
    }

    @Test fun sessionExportPreservesInclusiveOverlapAndChronologicalTies() = runTest {
        val stored = listOf(
            exportSession("later", 201), exportSession("b", 200), exportSession("a", 200),
            exportSession("ends-at-from", 50).copy(endTime = 100),
            exportSession("last-sample-at-from", 60).copy(endTime = null, lastSampleTime = 100),
            exportSession("before", 10).copy(endTime = 99),
            exportSession("no-end", 150).copy(endTime = null),
        )
        val dao = ExportSessionDao(stored)
        assertEquals(listOf("ends-at-from", "last-sample-at-from", "no-end", "a", "b"),
            dao.exportSessions(100, 200).map { it.sessionId })
        assertEquals(listOf("before", "ends-at-from", "last-sample-at-from", "no-end", "a", "b"),
            dao.exportSessions(0, 200).map { it.sessionId })
        assertEquals(stored.size, dao.count())
    }

    private fun exportSession(id: String, start: Long) =
        ChargeSession(id, SessionType.DISCHARGE, start, start + 1, null, null, null, null, null, activeKey = null)

    private class ExportSessionDao(private val stored: List<ChargeSession>) : UnusedSessionDao() {
        override suspend fun count(): Int = stored.size
        override suspend fun sessionsBetween(from: Long, to: Long): List<ChargeSession> =
            stored.filter { it.startTime <= to && (it.endTime ?: it.lastSampleTime ?: it.startTime) >= from }
                .sortedWith(compareBy<ChargeSession> { it.startTime }.thenBy { it.sessionId })
        override suspend fun latestSessionsBetween(from: Long, to: Long, limit: Int): List<ChargeSession> =
            sessionsBetween(from, to).takeLast(limit)
    }

    private fun assertTrimHeadroom(target: Int, limit: Int) {
        for (added in 0..HistoryLimits.CLEANUP_SAMPLE_INTERVAL) {
            val stored = target + added
            assertTrue("Post-trim count $target plus $added rows must remain exportable (cap $limit)", stored <= limit)
            assertTrue("Post-trim history must remain importable", HistoryLimits.importWithinLimit(target, stored, limit))
        }
    }
}
