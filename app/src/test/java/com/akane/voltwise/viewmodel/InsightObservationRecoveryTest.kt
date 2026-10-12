package com.akane.voltwise.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.insights.*
import com.akane.voltwise.battery.insights.actions.InsightActionRepository
import com.akane.voltwise.battery.insights.actions.TargetInspector
import com.akane.voltwise.battery.insights.model.*
import com.akane.voltwise.battery.util.ShellRunner
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InsightObservationRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun findingsQueryRecoversAndPublishesClearWithoutAnotherAnalysis() = runTest {
        assertClearRecovers(publicationFailure = false)
    }

    @Test fun publicationReadRecoversAndPublishesClearWithoutAnotherAnalysis() = runTest {
        assertClearRecovers(publicationFailure = true)
    }

    private suspend fun TestScope.assertClearRecovers(publicationFailure: Boolean) {
        val fixture = InsightObservationFixture(this)
        fixture.dao.findingRows.value = listOf(FindingCodec.encode(insightFinding(), 1))
        runCurrent()
        assertEquals(listOf(insightFinding()), fixture.insights.report.value!!.findings)
        if (publicationFailure) fixture.dao.readFailure = IOException("publication read")
        else fixture.dao.findingFailure = IOException("observation query")
        fixture.dao.findingRows.value = fixture.dao.findingRows.value.map { it.copy(lastSeenAt = 2) }
        runCurrent()
        assertEquals(1, fixture.findingFailures)
        fixture.dao.findingFailure = null
        fixture.dao.readFailure = null
        fixture.maintenance.clear({}, { fixture.dao.clearFindings() })
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue("recovered observer must publish a clear without refresh", fixture.insights.report.value!!.findings.isEmpty())
        assertNull(fixture.insights.report.value!!.headline)
        assertEquals("observation is not analysis", 0L, fixture.insights.successfulAnalysisRevision.value)
        assertEquals(2, fixture.dao.findingCollections)
    }

    @Test fun findingsCancellationDoesNotRestartObservation() = runTest {
        val fixture = InsightObservationFixture(this)
        fixture.dao.findingFailure = CancellationException("stop")
        runCurrent()
        fixture.dao.findingFailure = null
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, fixture.dao.findingCollections)
    }

    @Test fun actionsRetryWithoutInventingAnEmptyJournal() = runTest {
        val fixture = InsightObservationFixture(this)
        val journal = listOf(insightAction(status = InsightActionStatus.UNKNOWN))
        fixture.dao.actionRows.value = journal
        val emissions = mutableListOf<List<InsightActionEntity>>()
        val collection = backgroundScope.launch { fixture.actions.actions.toList(emissions) }
        runCurrent()
        fixture.dao.actionFailure = IOException("actions query")
        fixture.dao.actionRows.value = listOf(journal.single().copy(id = 8))
        runCurrent()
        assertEquals(listOf(journal), emissions)
        assertEquals(1, fixture.actionFailures)
        fixture.dao.actionFailure = null
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(journal, fixture.dao.actionRows.value), emissions)
        assertEquals(2, fixture.dao.actionCollections)
        collection.cancel()
    }

    @Test fun actionsCancellationAndCancelledBackoffDoNotRetry() = runTest {
        for (failure in listOf(CancellationException("stop"), IOException("retry"))) {
            val fixture = InsightObservationFixture(this)
            fixture.dao.actionFailure = failure
            val collection = backgroundScope.launch { fixture.actions.actions.collect() }
            runCurrent()
            collection.cancel()
            fixture.dao.actionFailure = null
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, fixture.dao.actionCollections)
        }
    }

    @Test fun eligibilityRetriesInitialAndLaterFailuresWithoutInventingEvidence() = runTest {
        val fixture = InsightObservationFixture(this)
        fixture.windows(fixture.now)
        fixture.sessionFailure = IOException("initial session query")
        val counts = mutableListOf<Int>()
        val collection = backgroundScope.launch { fixture.adapter.eligibleSessionCount.toList(counts) }
        runCurrent()
        assertTrue(counts.isEmpty())
        assertEquals(1, fixture.eligibilityFailures)
        fixture.sessionFailure = null
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(5), counts)
        fixture.sessionFailure = IOException("session invalidation")
        fixture.sessionRows.value = emptyList()
        runCurrent()
        assertEquals("a failed query must not emit a fabricated zero", listOf(5), counts)
        assertEquals(2, fixture.eligibilityFailures)
        fixture.sessionFailure = null
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(5, 0), counts)
        assertEquals(3, fixture.sessionCollections)
        collection.cancel()
    }

    @Test fun eligibilityCancellationAndCancelledBackoffDoNotRetryOrLogCancellation() = runTest {
        for (failure in listOf(CancellationException("stop"), IOException("retry"))) {
            val fixture = InsightObservationFixture(this)
            fixture.sessionFailure = failure
            val collection = backgroundScope.launch { fixture.adapter.eligibleSessionCount.collect() }
            runCurrent()
            assertEquals(if (failure is CancellationException) 0 else 1, fixture.eligibilityFailures)
            collection.cancel()
            fixture.sessionFailure = null
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, fixture.sessionCollections)
        }
    }

    @Test fun bothDestinationsRecoverWithAndWithoutUiCollectorsAndKeepPendingSafety() = runTest {
        for (subscribed in listOf(false, true)) {
            val fixture = InsightObservationFixture(this)
            fixture.dao.findingRows.value = listOf(FindingCodec.encode(insightFinding(), 1))
            fixture.dao.actionFailure = IOException("initial actions query")
            val source = object : InsightsRepository by fixture.adapter {
                override val privileged = flowOf(true)
                override val eligibleSessionCount = flowOf(0)
            }
            fun saved() = SavedStateHandle(mapOf(
                "key" to "finding", "insights.pending.key" to "finding",
                "insights.pending.action" to ActionType.RESTRICT_BACKGROUND.name,
            ))
            val listSaved = saved()
            val detailsSaved = saved()
            val results = InsightApplyResults()
            val list = InsightsViewModel(source, backgroundScope, listSaved, results)
            val details = FindingDetailsViewModel(source, backgroundScope, detailsSaved, results)
            val collectors = if (subscribed) listOf(
                backgroundScope.launch { list.state.collect() },
                backgroundScope.launch { details.state.collect() },
            ) else emptyList()
            try {
                runCurrent()
                assertEquals("finding", listSaved.get<String>("insights.pending.key"))
                assertEquals("finding", detailsSaved.get<String>("insights.pending.key"))
                fixture.dao.actionRows.value = listOf(insightAction(status = InsightActionStatus.PREPARED))
                fixture.dao.actionFailure = null
                advanceTimeBy(60_000)
                runCurrent()
                assertNull("journal must invalidate an already-applied pending request", listSaved.get<String>("insights.pending.key"))
                assertNull(detailsSaved.get<String>("insights.pending.key"))
                if (subscribed) {
                    assertTrue(list.state.value.loaded)
                    assertTrue(details.state.value.loaded)
                    assertTrue(list.state.value.appliedActions.single().undoable)
                    assertFalse(details.state.value.finding!!.recommendations.first().available)
                }
            } finally {
                collectors.forEach { it.cancel() }
                list.viewModelScope.cancel()
                details.viewModelScope.cancel()
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class InsightObservationFixture(scope: TestScope) {
    var now = NOW
    var findingFailures = 0
    var actionFailures = 0
    var eligibilityFailures = 0
    var sessionFailure: Exception? = null
    var sessionCollections = 0
    val maintenance = HistoryMaintenance()
    val dao = Dao()
    class Dao : UnusedInsightDao() {
        val findingRows = MutableStateFlow<List<InsightFindingEntity>>(emptyList())
        val actionRows = MutableStateFlow<List<InsightActionEntity>>(emptyList())
        var findingFailure: Exception? = null
        var readFailure: Exception? = null
        var actionFailure: Exception? = null
        var findingCollections = 0
        var actionCollections = 0
        override fun findings() = flow {
            findingCollections++
            findingRows.collect { rows -> findingFailure?.let { throw it }; emit(rows) }
        }
        override suspend fun findingsOnce(): List<InsightFindingEntity> {
            readFailure?.let { throw it }
            return findingRows.value
        }
        override suspend fun clearFindings() { findingRows.value = emptyList() }
        override fun actions() = flow {
            actionCollections++
            actionRows.collect { rows -> actionFailure?.let { throw it }; emit(rows) }
        }
        override suspend fun actionsOnce() = actionRows.value
        override suspend fun upsertFindings(list: List<InsightFindingEntity>) { findingRows.value = list }
    }
    val sessionRows = MutableStateFlow<List<ChargeSession>>(emptyList())
    val sessions = object : UnusedSessionDao() {
        override fun filteredSessions(type: SessionType?, query: String, limit: Int) = flow {
            sessionCollections++
            sessionRows.collect { rows -> sessionFailure?.let { throw it }; emit(rows) }
        }
        override suspend fun closedSessionsBetween(from: Long, to: Long) = sessionRows.value
        override fun capacityEstimates(limit: Int) = flowOf(emptyList<CapacityEstimateRow>())
    }
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val insights = com.akane.voltwise.battery.insights.InsightRepository(
        sessions, object : UnusedDailySummaryDao() {
            override suspend fun range(fromDay: Long, toDay: Long) = emptyList<DailySummary>()
        }, object : UnusedAppUsageDao() {
            override suspend fun usageRowsForSessions(sessionIds: List<String>) = emptyList<SessionAppUsage>()
            override suspend fun sessionWakers(sessionIds: List<String>) = emptyList<SessionDeviceWaker>()
        }, dao, scope.backgroundScope, object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = Instant.ofEpochMilli(now)
        }, { emptySet() }, FakeKeyValueStore(), maintenance,
        ioDispatcher = dispatcher, analyzeDispatcher = dispatcher,
        analyze = { InsightReport(it.nowMs, emptyList(), null) },
        onObservationFailure = { findingFailures++ },
    )
    val actions = InsightActionRepository(dao, { error("observation must not execute") }, object : TargetInspector {
        override val sdkInt = 37
        override fun installedUid(pkg: String, userId: Int): Int? = error("unexpected inspection")
        override fun packagesForUid(uid: Int): List<String> = error("unexpected inspection")
        override fun roleHolders(): Set<String> = error("unexpected inspection")
    }, { now }, {}, { true }, onObservationFailure = { actionFailures++ })
    val adapter = DefaultInsightsRepository(insights, actions, ShellRunner(
        probeMode = { ShellRunner.Mode.ROOT }, runShizuku = { _, _, _ -> error("unexpected shell") },
        shizukuRunning = { false }, elapsedMs = { 0L },
    ), sessions, clock = { now }, onObservationFailure = { eligibilityFailures++ }, computeDispatcher = dispatcher)

    fun windows(newestEnd: Long) {
        sessionRows.value = (0..4).map { index ->
            val end = newestEnd - index * 24 * HOUR
            testSession("window$index").copy(startTime = end - HOUR, endTime = end,
                appCaptureStartMs = end - HOUR, appCaptureEndMs = end)
        }
    }
}
