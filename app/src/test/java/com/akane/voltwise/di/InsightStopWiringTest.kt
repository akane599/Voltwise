package com.akane.voltwise.di

import com.akane.voltwise.battery.apps.AppStatsResult
import com.akane.voltwise.battery.apps.AppStatsSource
import com.akane.voltwise.battery.apps.AppUsageDeltaResult
import com.akane.voltwise.battery.apps.AppUsageSnapshot
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.apps.OpenSession
import com.akane.voltwise.battery.apps.SessionSnapshotCollector
import com.akane.voltwise.battery.apps.SessionSnapshotStore
import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.HistoryWriter
import com.akane.voltwise.battery.data.PowerTransition
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.InsightFindingEntity
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.insights.InsightRepository
import com.akane.voltwise.battery.insights.UnusedAppUsageDao
import com.akane.voltwise.battery.insights.UnusedDailySummaryDao
import com.akane.voltwise.battery.insights.UnusedInsightDao
import com.akane.voltwise.battery.insights.UnusedSessionDao
import com.akane.voltwise.battery.insights.engine.InsightEngine
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.startInsightSessionRefresh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class InsightStopWiringTest {
    @Test fun delayedDischargeStopCommitRefreshesDeviceReportAfterSnapshotCollectorIsCancelled() = runTest {
        val hour = 3_600_000L
        val now = 20 * hour
        fun measurement(id: String, end: Long, drainUah: Long) = ChargeSession(
            sessionId = id, type = SessionType.DISCHARGE, startTime = end - hour, endTime = end,
            startLevel = 80, endLevel = 75, deltaUah = drainUah, avgCurrentUa = null,
            estCapacityMah = null, observationId = id, lastSampleTime = end, source = "local",
            observedMs = hour, screenOffMs = hour, screenOffCoveredMs = hour,
            screenOffUah = drainUah, appUsageStatus = AppUsageStatus.PENDING,
        )
        val rows = (1..4).associate { i ->
            val row = measurement("history-$i", i * 2 * hour, 20_000)
            row.sessionId to row
        }.toMutableMap()
        val openRow = measurement("stopped", now - hour, 200_000).copy(endTime = null, activeKey = 1)
        rows[openRow.sessionId] = openRow
        val open = MutableStateFlow<OpenSession?>(OpenSession(openRow.sessionId, SessionType.DISCHARGE))
        val commit = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        var historyReads = 0
        val sessions = object : UnusedSessionDao() {
            override suspend fun update(session: ChargeSession): Int {
                writeStarted.complete(Unit)
                commit.await()
                rows[session.sessionId] = session
                open.value = null
                return 1
            }
            override suspend fun closedSessionsBetween(from: Long, to: Long): List<ChargeSession> {
                historyReads++
                return rows.values.filter { it.endTime?.let { end -> end in from..to } == true }
            }
            override fun capacityEstimates(limit: Int) = flowOf(emptyList<com.akane.voltwise.battery.data.db.CapacityEstimateRow>())
        }
        val findings = MutableStateFlow(emptyList<InsightFindingEntity>())
        val insightDao = object : UnusedInsightDao() {
            override fun findings() = findings
            override suspend fun findingsOnce() = findings.value
            override suspend fun actionsOnce() = emptyList<com.akane.voltwise.battery.data.db.InsightActionEntity>()
            override suspend fun upsertFindings(list: List<InsightFindingEntity>) { findings.value = list }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val maintenance = HistoryMaintenance()
        val insights = InsightRepository(
            sessions,
            object : UnusedDailySummaryDao() {
                override suspend fun range(fromDay: Long, toDay: Long) = emptyList<com.akane.voltwise.battery.data.db.DailySummary>()
            },
            object : UnusedAppUsageDao() {
                override suspend fun usageRowsForSessions(sessionIds: List<String>) = emptyList<com.akane.voltwise.battery.data.db.SessionAppUsage>()
                override suspend fun sessionWakers(sessionIds: List<String>) = emptyList<com.akane.voltwise.battery.data.db.SessionDeviceWaker>()
            },
            insightDao, backgroundScope, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
            { emptySet() },
            FakeKeyValueStore(), maintenance, capacityReading = { 2_000_000L to 50 },
            ioDispatcher = dispatcher, analyzeDispatcher = dispatcher,
            analyze = { InsightEngine.analyze(it, 35) },
        )
        val snapshotStore = object : SessionSnapshotStore {
            override fun openSession() = open
            override suspend fun hasBaseline(sessionId: String) = true
            override suspend fun baseline(sessionId: String): AppUsageSnapshot? = error("Unexpected baseline")
            override suspend fun saveBaseline(sessionId: String, snapshot: AppUsageSnapshot): Boolean = error("Unexpected baseline")
            override suspend fun saveEnd(sessionId: String, end: AppUsageSnapshot, result: AppUsageDeltaResult): Boolean = error("Unexpected end")
            override suspend fun setStatus(sessionId: String, status: AppUsageStatus): Boolean {
                rows[sessionId] = rows.getValue(sessionId).copy(appUsageStatus = status)
                return true
            }
            override suspend fun pendingClosedDischarges() = rows.values.filter {
                it.endTime != null && it.appUsageStatus == AppUsageStatus.PENDING
            }.map { it.sessionId }
        }
        val snapshots = SessionSnapshotCollector(
            object : AppStatsSource {
                override suspend fun snapshot(force: Boolean): AppStatsResult = error("Unexpected dump")
            }, snapshotStore, MutableSharedFlow<PowerTransition>(), log = {}, warn = { fail(it) },
        )
        val closed = MutableSharedFlow<ChargeSession>()
        val ready = CompletableDeferred<Unit>()
        val appCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            startInsightSessionRefresh(ready, { snapshots.finalizedSessions }, { closed },
                { fail("Unexpected refresh failure") }) { insights.refresh() }
        }
        assertTrue("Application listener must be ready before the service starts", ready.isCompleted)
        val serviceSnapshots = backgroundScope.launch { snapshots.run() }
        runCurrent()
        assertTrue(serviceSnapshots.isActive)
        assertEquals(1, open.subscriptionCount.value)
        assertEquals(0, historyReads)
        assertTrue(insights.report.value!!.findings.isEmpty())

        // Android-free storage callback follows BatteryRepository.finishSession: upsert before emit.
        val writer = HistoryWriter<Unit>(backgroundScope, dispatcher, maintenance,
            openSessionId = { open.value?.sessionId }, deleteRow = { false },
            handleEvent = {
                val stopped = openRow.copy(endTime = openRow.lastSampleTime, activeKey = null,
                    closeReason = "Monitoring stopped")
                sessions.upsert(stopped)
                closed.emit(stopped)
            }, onFailure = { fail("Unexpected writer failure: $it") },
        )
        // onDestroy queues Stop on the application writer, then cancels the service snapshots.
        assertTrue(writer.trySend(Unit).isSuccess)
        runCurrent()
        assertTrue(writeStarted.isCompleted)
        serviceSnapshots.cancelAndJoin()
        assertFalse(serviceSnapshots.isActive)
        assertEquals(0, open.subscriptionCount.value)
        assertNull(rows.getValue("stopped").endTime)
        assertEquals(0, historyReads)

        commit.complete(Unit)
        runCurrent()
        assertEquals(now - hour, rows.getValue("stopped").endTime)
        assertEquals(AppUsageStatus.PENDING, rows.getValue("stopped").appUsageStatus)
        assertEquals("One committed Stop closure must trigger one actual history refresh", 1, historyReads)
        val finding = insights.report.value!!.findings.single { it.type == FindingType.SCREEN_OFF_DRAIN_HIGH }
        assertEquals(5.0, finding.evidence.single().observed, 0.0)
        assertEquals(0.5, finding.evidence.single().baseline!!, 0.0)
        assertEquals(now, insights.report.value!!.generatedAtMs)
        // A cancelled service cannot later sweep this pending row and emit its finalization.
        advanceTimeBy(SessionSnapshotCollector.STARTUP_SWEEP_DELAY_MS + 1)
        runCurrent()
        assertEquals(AppUsageStatus.PENDING, rows.getValue("stopped").appUsageStatus)
        assertEquals(1, historyReads)
        assertTrue(appCollector.isActive)
        appCollector.cancelAndJoin()
    }
}
