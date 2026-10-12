package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.db.CapacityEstimateRow
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.DailySummary
import com.akane.voltwise.battery.data.db.InsightActionEntity
import com.akane.voltwise.battery.data.db.InsightFindingEntity
import com.akane.voltwise.battery.data.db.InsightFindingStatus
import com.akane.voltwise.battery.data.db.SessionAppUsage
import com.akane.voltwise.battery.data.db.SessionDeviceWaker
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.insights.engine.InsightEngine
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFindingExpiryRepositoryTest {
    @Test fun screenOffDrainResolvesAfterSevenDaysWithHistoryPreserved() = runTest {
        assertExpiry(FindingType.SCREEN_OFF_DRAIN_HIGH)
    }

    @Test fun dozeBlockedResolvesAfterSevenDaysWithHistoryPreserved() = runTest {
        assertExpiry(FindingType.DOZE_BLOCKED)
    }

    private suspend fun TestScope.assertExpiry(type: FindingType) {
        var now = NOW
        val doze = type == FindingType.DOZE_BLOCKED
        val sessions = (0..4).map { index ->
            val end = NOW - (4 - index) * 24 * HOUR - HOUR
            testSession("device$index").copy(
                startTime = end - 2 * HOUR,
                endTime = end,
                observedMs = 2 * HOUR,
                screenOffMs = 2 * HOUR,
                screenOffCoveredMs = 2 * HOUR,
                screenOffUah = if (doze || index < 4) 80_000 else 320_000,
                screenOffDozeMs = if (!doze) null else if (index < 4) HOUR * 16 / 10 else HOUR / 10,
                screenOffSuspendMs = if (!doze) null else if (index < 4) HOUR * 18 / 10 else HOUR / 10,
                appUsageStatus = null,
                appUsageBasis = null,
                appCaptureStartMs = null,
                appCaptureEndMs = null,
            )
        }
        val seen = mutableListOf<InsightInputs>()
        val rows = MutableStateFlow(emptyList<InsightFindingEntity>())
        val insights = object : UnusedInsightDao() {
            override fun findings() = rows
            override suspend fun findingsOnce() = rows.value
            override suspend fun actionsOnce() = emptyList<InsightActionEntity>()
            override suspend fun upsertFindings(list: List<InsightFindingEntity>) {
                rows.value = (rows.value.associateBy { it.key } + list.associateBy { it.key }).values.toList()
            }
        }
        val sessionDao = object : UnusedSessionDao() {
            override suspend fun closedSessionsBetween(from: Long, to: Long): List<ChargeSession> =
                sessions.filter { it.endTime!! in from..to }
            override fun capacityEstimates(limit: Int) = flowOf(emptyList<CapacityEstimateRow>())
        }
        val daily = object : UnusedDailySummaryDao() {
            override suspend fun range(fromDay: Long, toDay: Long) = emptyList<DailySummary>()
        }
        val apps = object : UnusedAppUsageDao() {
            override suspend fun usageRowsForSessions(sessionIds: List<String>) =
                emptyList<SessionAppUsage>()
            override suspend fun sessionWakers(sessionIds: List<String>) =
                emptyList<SessionDeviceWaker>()
        }
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = InsightRepository(sessionDao, daily, apps, insights, backgroundScope, clock,
            { emptySet() }, FakeKeyValueStore(), HistoryMaintenance(),
            capacityReading = { 2_000_000L to 50 }, ioDispatcher = dispatcher, analyzeDispatcher = dispatcher,
            analyze = { seen += it; InsightEngine.analyze(it, 28) })

        repository.refresh()
        val finding = repository.report.value!!.findings.single()
        assertEquals(type, finding.type)
        assertEquals(InsightFindingStatus.ACTIVE, rows.value.single().status)
        assertEquals(4, finding.evidence.first().sessions)

        now = sessions.last().endTime!! + 7 * 24 * HOUR
        repository.refresh()
        assertEquals(InsightFindingStatus.ACTIVE, rows.value.single().status)
        assertEquals(type, repository.report.value!!.findings.single().type)
        now++
        repository.refresh()
        assertEquals(InsightFindingStatus.RESOLVED, rows.value.single().status)
        assertTrue("Expired device findings leave the published report", repository.report.value!!.findings.isEmpty())
        assertEquals("All historical sessions remain available after device expiry", 5, seen.last().sessions.size)
        assertEquals(sessions.map { it.sessionId }, seen.last().sessions.map { it.id })
    }
}
