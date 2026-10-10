package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.reconcileAndCatchUpInsights
import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.data.sampling.KeyValueStore
import com.akane.voltwise.battery.insights.model.*
import com.akane.voltwise.battery.insights.engine.InsightEngine
import com.akane.voltwise.battery.insights.engine.Trends
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class InsightRepositoryTest {
    private class MemoryInsights(initial: List<InsightFindingEntity> = emptyList()) : UnusedInsightDao() {
        val rows = MutableStateFlow(initial)
        var actionRows = emptyList<InsightActionEntity>()
        var afterFindingsRead: suspend () -> Unit = {}
        var afterFindingsWrite: suspend () -> Unit = {}
        var feedbackWrites = 0
        override fun findings() = rows
        override suspend fun findingsOnce(): List<InsightFindingEntity> {
            val snapshot = rows.value
            val callback = afterFindingsRead
            afterFindingsRead = {}
            callback()
            return snapshot
        }
        override suspend fun actionsOnce() = actionRows
        override suspend fun upsertFindings(list: List<InsightFindingEntity>) {
            feedbackWrites++
            val merged = rows.value.associateBy { it.key }.toMutableMap()
            list.forEach { merged[it.key] = it }
            rows.value = merged.values.toList()
            afterFindingsWrite()
        }
        override suspend fun setStatus(key: String, status: InsightFindingStatus) {
            feedbackWrites++
            rows.value = rows.value.map { if (it.key == key) it.copy(status = status) else it }
        }
        override suspend fun clearFindings() { rows.value = emptyList() }
        fun row(key: String = testFinding().key) = rows.value.single { it.key == key }
    }

    private class CountingStore : KeyValueStore {
        private val delegate = FakeKeyValueStore()
        var reads = 0
        override fun getString(key: String): String? {
            reads++
            return delegate.getString(key)
        }
        override fun edit(values: Map<String, String?>) = delegate.edit(values)
    }

    private class Fixture(private val clockZone: ZoneId = ZoneOffset.UTC) {
        var now = NOW
        var sessions = listOf(testSession())
        var output = listOf(testFinding())
        val seen = mutableListOf<InsightInputs>()
        val events = mutableListOf<String>()
        val rowChunks = mutableListOf<List<String>>()
        val wakerChunks = mutableListOf<List<String>>()
        var window: Pair<Long, Long>? = null
        var dayWindow: Pair<Long, Long>? = null
        var dailyRows = emptyList<DailySummary>()
        var currentZone: () -> ZoneId = { clockZone }
        var beforeSessions: suspend () -> Unit = {}
        var duringAnalysis: () -> Unit = {}
        var analyze: ((InsightInputs) -> InsightReport)? = null
        var appRows: List<SessionAppUsage>? = null
        val maintenance = HistoryMaintenance()
        var whitelist: Set<String>? = setOf("old.whitelist")
        var highBatteryAlertEnabled = false
        val store = CountingStore()
        val insights = MemoryInsights()
        val clock = object : Clock() {
            override fun getZone(): ZoneId = clockZone
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
        val sessionDao = object : UnusedSessionDao() {
            override suspend fun closedSessionsBetween(from: Long, to: Long): List<ChargeSession> {
                events += "sessions"
                beforeSessions()
                window = from to to
                return sessions
            }
            override fun capacityEstimates(limit: Int) = flowOf(emptyList<CapacityEstimateRow>())
        }
        val daily = object : UnusedDailySummaryDao() {
            override suspend fun range(fromDay: Long, toDay: Long): List<DailySummary> {
                dayWindow = fromDay to toDay
                return dailyRows.filter { it.epochDay in fromDay..toDay }
            }
        }
        val apps = object : UnusedAppUsageDao() {
            override suspend fun usageRowsForSessions(sessionIds: List<String>): List<SessionAppUsage> {
                rowChunks += sessionIds
                return appRows?.filter { it.sessionId in sessionIds } ?: sessionIds.map { testAppRow(it) }
            }
            override suspend fun sessionWakers(sessionIds: List<String>): List<SessionDeviceWaker> {
                wakerChunks += sessionIds
                return emptyList()
            }
        }
        fun repository(scope: TestScope) = InsightRepository(sessionDao, daily, apps, insights, scope.backgroundScope,
            clock, { events += "whitelist"; whitelist }, store,
            maintenance = maintenance,
            capacityReading = { 2_000_000L to 50 },
            highBatteryAlertEnabled = { highBatteryAlertEnabled },
            currentZone = { currentZone() },
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
            analyzeDispatcher = StandardTestDispatcher(scope.testScheduler),
            analyze = {
                seen += it
                events += "analyze"
                duringAnalysis()
                analyze?.invoke(it) ?: InsightReport(it.nowMs, output, output.firstOrNull())
            })
    }

    @Test fun retainedRepositoryUsesNewZoneForCompletedDaysAndAppMidnights() = runTest {
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val tokyo = ZoneId.of("Asia/Tokyo")
        val fixture = Fixture(losAngeles)
        fixture.now = 1_791_594_000_000L // 2026-10-10T01:00Z: Oct 9 in LA, Oct 10 in Tokyo.
        var zone = losAngeles
        fixture.currentZone = { zone }
        fixture.dailyRows = trendDays()
        val dates = listOf("2026-09-20", "2026-09-21", "2026-09-22", "2026-09-23",
            "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09")
        fixture.sessions = dates.map { date ->
            val start = LocalDate.parse(date).atTime(17, 0).atZone(tokyo).toInstant().toEpochMilli()
            testSession(date).copy(startTime = start, endTime = start + HOUR,
                appCaptureStartMs = start, appCaptureEndMs = start + HOUR)
        }
        fixture.appRows = fixture.sessions.mapIndexed { index, session ->
            testAppRow(session.sessionId).copy(powerMah = if (index < 4) 5.0 else 10.0)
        }
        fixture.analyze = { InsightReport(it.nowMs, Trends.detect(it), null) }
        val repo = fixture.repository(this)
        repo.refresh()
        assertEquals(20_735L, fixture.seen.last().todayEpochDay)
        assertEquals(losAngeles, fixture.seen.last().zone)
        assertTrue("LA Oct 9 is incomplete and leaves only three recent points", repo.report.value!!.findings.isEmpty())

        zone = tokyo
        repo.refresh()
        val inputs = fixture.seen.last()
        assertEquals(20_736L, inputs.todayEpochDay)
        assertEquals(tokyo, inputs.zone)
        assertEquals(20_646L to 20_736L, fixture.dayWindow)
        val findings = repo.report.value!!.findings
        val device = findings.single { it.subject == Subject.Device }
        assertEquals(8, device.evidence.single().sessions)
        assertEquals(20.0, device.evidence.single().observed, 0.0)
        assertEquals("Oct 9 local midnight in Tokyo", 1_791_471_600_000L, device.series.last().atMs)
        val app = findings.single { it.subject is Subject.App }
        assertEquals(8, app.evidence.single().sessions)
        assertEquals(10.0, app.evidence.single().observed, 0.0)
        assertEquals("Yesterday evening remains inside the completed-day app window",
            fixture.sessions.last().appCaptureEndMs, app.series.last().atMs)
    }

    @Test fun retainedRepositoryExcludesNewCurrentDayAfterReverseZoneChange() = runTest {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val fixture = Fixture(tokyo)
        fixture.now = 1_791_594_000_000L
        var zone = tokyo
        fixture.currentZone = { zone }
        fixture.dailyRows = trendDays()
        fixture.sessions = emptyList()
        fixture.analyze = { InsightReport(it.nowMs, Trends.detect(it), null) }
        val repo = fixture.repository(this)
        repo.refresh()
        assertEquals(1, repo.report.value!!.findings.size)

        zone = losAngeles
        repo.refresh()
        assertEquals(20_735L, fixture.seen.last().todayEpochDay)
        assertEquals(losAngeles, fixture.seen.last().zone)
        assertEquals(20_645L to 20_735L, fixture.dayWindow)
        assertTrue("Oct 9 is now incomplete, leaving only three completed recent days",
            repo.report.value!!.findings.isEmpty())
    }

    @Test fun refreshReadsOneZoneForDailyQueryAndAnalyzerInputs() = runTest {
        val fixture = Fixture(ZoneId.of("America/Los_Angeles"))
        fixture.now = 1_791_594_000_000L
        var reads = 0
        fixture.currentZone = {
            reads++
            if (reads == 1) ZoneId.of("Asia/Tokyo") else ZoneId.of("America/Los_Angeles")
        }
        val repo = fixture.repository(this)
        repo.refresh()
        assertEquals(1, reads)
        assertEquals(20_736L, fixture.seen.last().todayEpochDay)
        assertEquals(ZoneId.of("Asia/Tokyo"), fixture.seen.last().zone)
        assertEquals(20_646L to 20_736L, fixture.dayWindow)
        repo.refresh()
        assertEquals(2, reads)
        assertEquals(20_735L, fixture.seen.last().todayEpochDay)
        assertEquals(ZoneId.of("America/Los_Angeles"), fixture.seen.last().zone)
        assertEquals(20_645L to 20_735L, fixture.dayWindow)
    }

    private fun trendDays(): List<DailySummary> =
        listOf(20_716L, 20_717L, 20_718L, 20_719L, 20_732L, 20_733L, 20_734L, 20_735L)
            .mapIndexed { index, day ->
                DailySummary(epochDay = day, screenOffMs = HOUR, screenOffCoveredMs = HOUR,
                    screenOffDischargeUah = if (index < 4) 400_000 else 800_000)
            }

    @Test fun completedRevisionsAdvanceAfterPublicationWithRepeatedAndBackwardClock() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        assertEquals(0L, repo.successfulAnalysisRevision.value)
        for ((now, revision) in listOf(NOW to 1L, NOW to 2L, (NOW - 1) to 3L)) {
            fixture.now = now
            repo.refresh()
            assertEquals(revision, repo.successfulAnalysisRevision.value)
            assertEquals(now, repo.lastAnalyzedAt.value)
            assertEquals(now.toString(), fixture.store.getString(InsightRepository.LAST_ANALYZED_AT))
            assertEquals(now, repo.report.value!!.generatedAtMs)
            assertEquals(fixture.output, repo.report.value!!.findings)
        }
    }

    @Test fun successfulRevisionWaitsForReportPublication() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        runCurrent()
        fixture.now = NOW + 1
        val gate = CompletableDeferred<Unit>()
        fixture.insights.afterFindingsWrite = {
            fixture.insights.afterFindingsRead = { gate.await() }
        }
        val refresh = async { repo.refresh() }
        try {
            runCurrent()
            assertFalse("publication must still be suspended", refresh.isCompleted)
            assertEquals(NOW + 1, repo.lastAnalyzedAt.value)
            assertEquals(NOW, repo.report.value!!.generatedAtMs)
            assertEquals("persisting a timestamp alone does not complete publication", 1L, repo.successfulAnalysisRevision.value)
        } finally {
            gate.complete(Unit)
        }
        refresh.await()
        assertEquals(NOW + 1, repo.report.value!!.generatedAtMs)
        assertEquals(2L, repo.successfulAnalysisRevision.value)
    }

    @Test fun failedAnalysisAndFeedbackDoNotAdvanceCompletedRevision() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        fixture.analyze = { error("analysis failed") }
        try { repo.refresh(); fail("Expected analysis failure") } catch (_: IllegalStateException) { }
        assertEquals(1L, repo.successfulAnalysisRevision.value)
        repo.notAProblem(testFinding().key)
        repo.dismiss(testFinding().key)
        runCurrent()
        assertTrue(repo.report.value!!.findings.isEmpty())
        assertEquals("feedback publication is not a completed analysis", 1L, repo.successfulAnalysisRevision.value)
    }

    @Test fun refreshSkippedDuringClearDoesNotAdvanceCompletedRevision() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        val gate = CompletableDeferred<Unit>()
        val clear = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.maintenance.clear({}, { gate.await(); fixture.insights.clearFindings() })
        }
        try {
            assertTrue(fixture.maintenance.isClearing)
            repo.refresh()
            assertEquals("a maintenance no-op must not signal success", 1L, repo.successfulAnalysisRevision.value)
            assertEquals(1, fixture.seen.size)
        } finally {
            gate.complete(Unit)
        }
        clear.await()
        runCurrent()
        assertTrue(repo.report.value!!.findings.isEmpty())
        assertEquals("clear publication is not a completed analysis", 1L, repo.successfulAnalysisRevision.value)
    }

    @Test fun refreshReadsLiveHighBatteryAlertSettingForEachAnalysis() = runTest {
        val fixture = Fixture()
        fixture.highBatteryAlertEnabled = true
        val repo = fixture.repository(this)

        repo.refresh()
        assertTrue("Enabled setting must reach the analyzer", fixture.seen.single().highBatteryAlertEnabled)

        fixture.highBatteryAlertEnabled = false
        repo.refresh()
        assertEquals(2, fixture.seen.size)
        assertFalse("Disabling the alert must reach the next analysis", fixture.seen.last().highBatteryAlertEnabled)
    }

    @Test fun refreshKeepsWhitelistFindingWhenMembershipUnknownAndResolvesKnownRemoval() = runTest {
        val fixture = Fixture()
        fixture.whitelist = setOf("example.app0")
        fixture.appRows = listOf(testAppRow().copy(backgroundTimeMs = 60_000))
        fixture.analyze = { InsightEngine.analyze(it, 28) }
        val repo = fixture.repository(this)
        repo.refresh()
        val finding = repo.report.value!!.findings.single()
        assertEquals(FindingType.DOZE_WHITELISTED_DRAINER, finding.type)
        val active = fixture.insights.row(finding.key)
        assertEquals(InsightFindingStatus.ACTIVE, active.status)

        fixture.now += 100
        fixture.whitelist = null
        repo.refresh()
        assertNull(fixture.seen.last().dozeUserWhitelist)
        assertEquals("Unknown membership must preserve the last known active row", active,
            fixture.insights.row(finding.key))
        assertEquals(listOf(finding), repo.report.value!!.findings)

        fixture.now += 100
        fixture.whitelist = emptySet()
        repo.refresh()
        assertEquals("Known removal must resolve the whitelist finding", InsightFindingStatus.RESOLVED,
            fixture.insights.row(finding.key).status)
        assertTrue(repo.report.value!!.findings.isEmpty())
    }

    @Test fun unknownWhitelistDoesNotPreventResolvingOtherFindingTypes() = runTest {
        val fixture = Fixture()
        fixture.whitelist = null
        val repo = fixture.repository(this)
        repo.refresh()
        assertEquals(InsightFindingStatus.ACTIVE, fixture.insights.row().status)

        fixture.output = emptyList()
        repo.refresh()
        assertEquals(InsightFindingStatus.RESOLVED, fixture.insights.row().status)
        assertTrue(repo.report.value!!.findings.isEmpty())
    }

    @Test fun refreshResolvesExpiredWhitelistFindingAndRemovesItsOffer() = runTest {
        val fixture = Fixture()
        fixture.whitelist = setOf("example.app0")
        fixture.appRows = listOf(testAppRow().copy(backgroundTimeMs = 60_000))
        fixture.analyze = { InsightEngine.analyze(it, 28) }
        val repo = fixture.repository(this)
        repo.refresh()
        val finding = repo.report.value!!.findings.single()
        assertEquals(FindingType.DOZE_WHITELISTED_DRAINER, finding.type)
        assertTrue(finding.recommendations.any { it.action == ActionType.REMOVE_DOZE_WHITELIST })
        assertEquals(InsightFindingStatus.ACTIVE, fixture.insights.row(finding.key).status)

        fixture.now = fixture.sessions.single().appCaptureEndMs!! + 7 * 24 * HOUR
        repo.refresh()
        assertEquals(InsightFindingStatus.ACTIVE, fixture.insights.row(finding.key).status)
        fixture.now++
        repo.refresh()
        assertEquals(InsightFindingStatus.RESOLVED, fixture.insights.row(finding.key).status)
        assertTrue("Expired findings and their action offers leave the published report", repo.report.value!!.findings.isEmpty())
        assertEquals("History remains available to the analyzer", 1, fixture.seen.last().sessions.size)
    }

    @Test fun constructorDefersPreferenceReadUntilIoInitialization() = runTest {
        val fixture = Fixture()
        fixture.store.edit(mapOf(InsightRepository.LAST_ANALYZED_AT to "123"))
        val repo = fixture.repository(this)
        assertEquals("Constructing the repository must not read preferences", 0, fixture.store.reads)
        assertNull(repo.lastAnalyzedAt.value)
        runCurrent()
        assertEquals(1, fixture.store.reads)
        assertEquals(123L, repo.lastAnalyzedAt.value)
        assertEquals(123L, repo.report.value!!.generatedAtMs)
        assertEquals(0L, repo.successfulAnalysisRevision.value)
    }

    @Test fun notAProblemCannotResurrectFindingClearedAfterRead() = runTest {
        val fixture = Fixture()
        fixture.insights.rows.value = listOf(FindingCodec.encode(testFinding(), 1))
        val repo = fixture.repository(this)
        runCurrent()
        var clear: Deferred<Unit>? = null
        fixture.insights.afterFindingsRead = {
            clear = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.maintenance.clear({}, { fixture.insights.clearFindings() })
            }
        }
        repo.notAProblem(testFinding().key)
        clear!!.await()
        assertTrue("Feedback must not reinsert a cleared finding", fixture.insights.findingsOnce().isEmpty())
        assertEquals("Feedback must skip a clear started during its read", 0, fixture.insights.feedbackWrites)
        runCurrent()
        assertTrue(repo.report.value!!.findings.isEmpty())
    }

    @Test fun feedbackSkipsWhileClearHoldsMutationLock() = runTest {
        val fixture = Fixture()
        fixture.insights.rows.value = listOf(FindingCodec.encode(testFinding(), 1))
        val repo = fixture.repository(this)
        runCurrent()
        val deleteGate = CompletableDeferred<Unit>()
        val clear = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.maintenance.clear({}, {
                deleteGate.await()
                fixture.insights.clearFindings()
            })
        }
        val feedback = async { repo.notAProblem(testFinding().key) }
        val dismiss = async { repo.dismiss(testFinding().key) }
        try {
            runCurrent()
            assertEquals("Neither feedback path may write during a clear", 0, fixture.insights.feedbackWrites)
        } finally {
            deleteGate.complete(Unit)
        }
        clear.await()
        feedback.await()
        dismiss.await()
        assertTrue(fixture.insights.findingsOnce().isEmpty())
        assertEquals(0, fixture.insights.feedbackWrites)
    }

    @Test fun bothFeedbackPathsWaitForHistoryMutations() = runTest {
        for (notAProblem in listOf(false, true)) {
            val fixture = Fixture()
            fixture.insights.rows.value = listOf(FindingCodec.encode(testFinding(), 1))
            val repo = fixture.repository(this)
            runCurrent()
            fixture.maintenance.mutations.lock()
            val feedback = async {
                if (notAProblem) repo.notAProblem(testFinding().key) else repo.dismiss(testFinding().key)
            }
            try {
                runCurrent()
                assertFalse("Feedback must acquire the history mutation lock", feedback.isCompleted)
                assertEquals(0, fixture.insights.feedbackWrites)
            } finally {
                fixture.maintenance.mutations.unlock()
            }
            feedback.await()
            assertEquals(InsightFindingStatus.DISMISSED, fixture.insights.row().status)
            assertEquals(if (notAProblem) 1.5 else 1.0, fixture.insights.row().feedbackMultiplier, 0.0)
        }
    }

    @Test fun catchUpWaitsForStoredTimestampRatherThanInitialNull() = runTest {
        val fixture = Fixture()
        fixture.store.edit(mapOf(InsightRepository.LAST_ANALYZED_AT to NOW.toString()))
        val repo = fixture.repository(this)
        var refreshes = 0
        var reconciled = false
        val catchUp = async(start = CoroutineStart.UNDISPATCHED) {
            reconcileAndCatchUpInsights(
                reconcile = { reconciled = true },
                lastAnalyzedAt = { repo.awaitLastAnalyzedAt() },
                refresh = { refreshes++ },
                clock = { NOW + 1_000 },
            )
        }
        assertTrue(reconciled)
        assertFalse("Catch-up must wait for IO initialization", catchUp.isCompleted)
        assertEquals(0, fixture.store.reads)
        runCurrent()
        catchUp.await()
        assertEquals(NOW, repo.lastAnalyzedAt.value)
        assertEquals("A recent stored analysis must not trigger catch-up", 0, refreshes)
        repo.refresh()
        assertEquals("Awaited accessor must return the latest value", repo.lastAnalyzedAt.value, repo.awaitLastAnalyzedAt())
    }

    @Test fun catchUpWithNoStoredTimestampCompletesLoadingAndRefreshes() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        var refreshes = 0
        reconcileAndCatchUpInsights(
            reconcile = {},
            lastAnalyzedAt = { repo.awaitLastAnalyzedAt() },
            refresh = { refreshes++ },
            clock = { NOW },
        )
        assertEquals(1, fixture.store.reads)
        assertNull(repo.lastAnalyzedAt.value)
        assertEquals(1, refreshes)
    }

    @Test fun startupLoadsOnlyActiveSupportedFindingsAndPersistedAnalysisTime() = runTest {
        val fixture = Fixture()
        fixture.store.edit(mapOf(InsightRepository.LAST_ANALYZED_AT to "123"))
        fixture.insights.rows.value = listOf(FindingCodec.encode(testFinding(), 1),
            FindingCodec.encode(testFinding("dismissed"), 1, status = InsightFindingStatus.DISMISSED),
            FindingCodec.encode(testFinding("resolved"), 1, status = InsightFindingStatus.RESOLVED),
            FindingCodec.encode(testFinding("bad"), 1).copy(evidenceJson = "bad"))
        val repo = fixture.repository(this)
        assertNull(repo.lastAnalyzedAt.value)
        assertNull(repo.report.value)
        runCurrent()
        assertEquals(123L, repo.lastAnalyzedAt.value)
        assertEquals(listOf(testFinding()), repo.report.value!!.findings)
        assertEquals(123L, repo.report.value!!.generatedAtMs)
        fixture.insights.rows.value = emptyList() // Clear history propagates without running analysis.
        runCurrent()
        assertTrue(repo.report.value!!.findings.isEmpty())
    }

    @Test fun mergeUpdatesActiveKeepsDismissedUnlessSeverityRisesAndResolvesMissing() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        assertEquals(InsightFindingStatus.ACTIVE, fixture.insights.row().status)
        assertEquals(NOW, fixture.insights.row().firstSeenAt)
        fixture.now += 100
        fixture.output = listOf(testFinding().copy(score = 80.0))
        repo.refresh()
        assertEquals(80.0, fixture.insights.row().score, 0.0)
        assertEquals(NOW, fixture.insights.row().firstSeenAt)
        assertEquals(NOW + 100, fixture.insights.row().lastSeenAt)
        repo.dismiss(testFinding().key)
        assertTrue(repo.report.value!!.findings.isEmpty())
        fixture.now += 100
        repo.refresh()
        assertEquals(InsightFindingStatus.DISMISSED, fixture.insights.row().status)
        assertEquals(NOW + 200, fixture.insights.row().lastSeenAt)
        fixture.output = listOf(testFinding(severity = Severity.HIGH))
        repo.refresh()
        assertEquals(InsightFindingStatus.ACTIVE, fixture.insights.row().status)
        fixture.output = emptyList()
        repo.refresh()
        assertEquals(InsightFindingStatus.RESOLVED, fixture.insights.row().status)
        assertTrue(repo.report.value!!.findings.isEmpty())
        fixture.output = listOf(testFinding())
        repo.refresh()
        assertEquals(InsightFindingStatus.ACTIVE, fixture.insights.row().status)
        assertEquals(NOW, fixture.insights.row().firstSeenAt)
    }

    @Test fun dismissedHighFindingStaysDismissedAfterMediumThenHigh() = runTest {
        assertDismissedSeveritySurvivesImprovement(notAProblem = false)
    }

    @Test fun notAProblemHighFindingStaysDismissedAfterMediumThenHigh() = runTest {
        assertDismissedSeveritySurvivesImprovement(notAProblem = true)
    }

    private suspend fun TestScope.assertDismissedSeveritySurvivesImprovement(notAProblem: Boolean) {
        val fixture = Fixture()
        fixture.output = listOf(testFinding(severity = Severity.HIGH))
        val repo = fixture.repository(this)
        repo.refresh()
        if (notAProblem) repo.notAProblem(testFinding().key) else repo.dismiss(testFinding().key)
        fixture.output = listOf(testFinding(severity = Severity.MEDIUM))
        repo.refresh()
        fixture.output = listOf(testFinding(severity = Severity.HIGH))
        repo.refresh()
        assertEquals("HIGH must not reactivate a finding dismissed at HIGH",
            InsightFindingStatus.DISMISSED, fixture.insights.row().status)
        assertEquals(Severity.HIGH.name, fixture.insights.row().severity)
        assertEquals(if (notAProblem) 1.5 else 1.0, fixture.insights.row().feedbackMultiplier, 0.0)
        assertTrue(repo.report.value!!.findings.isEmpty())
    }

    @Test fun completedClearPublishesEmptyReportBeforeSuspendedRefreshReturns() = runTest {
        val fixture = Fixture()
        fixture.insights.rows.value = listOf(FindingCodec.encode(testFinding(), 1))
        val repo = fixture.repository(this)
        runCurrent()
        assertEquals(listOf(testFinding()), repo.report.value!!.findings)
        val inputGate = CompletableDeferred<Unit>()
        fixture.beforeSessions = { inputGate.await() }
        val refresh = async { repo.refresh() }
        try {
            runCurrent()
            assertEquals(listOf("sessions"), fixture.events)
            fixture.maintenance.clear({}, { fixture.insights.clearFindings() })
            runCurrent()
            assertFalse("Refresh must still be suspended", refresh.isCompleted)
            assertTrue("Clear must remove published findings before refresh returns",
                repo.report.value!!.findings.isEmpty())
        } finally {
            inputGate.complete(Unit)
        }
        refresh.await()
        assertTrue("Stale refresh must not repopulate cleared findings", fixture.insights.rows.value.isEmpty())
        assertNull(repo.lastAnalyzedAt.value)
        assertEquals(0L, repo.successfulAnalysisRevision.value)
    }

    @Test fun startupPublishesStoredReportBeforeSuspendedRefreshReturns() = runTest {
        val fixture = Fixture()
        fixture.store.edit(mapOf(InsightRepository.LAST_ANALYZED_AT to "123"))
        fixture.insights.rows.value = listOf(FindingCodec.encode(testFinding(), 1))
        val inputGate = CompletableDeferred<Unit>()
        fixture.beforeSessions = { inputGate.await() }
        val repo = fixture.repository(this)
        assertNull(repo.report.value)
        // Acquire the analysis mutex before initialization or the first Room emission runs.
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { repo.refresh() }
        try {
            runCurrent()
            assertEquals(listOf("sessions"), fixture.events)
            assertFalse("Refresh must still be suspended", refresh.isCompleted)
            assertNotNull("Room must publish a report before refresh returns", repo.report.value)
            assertEquals(listOf(testFinding()), repo.report.value!!.findings)
            assertEquals("Initialization must load without waiting for analysis", 123L, repo.lastAnalyzedAt.value)
            assertEquals(123L, repo.report.value!!.generatedAtMs)
        } finally {
            inputGate.complete(Unit)
        }
        refresh.await()
    }

    @Test fun completedClearDuringAnalysisDiscardsStaleFindingsAndTimestamp() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        var clear: Deferred<Unit>? = null
        fixture.duringAnalysis = {
            clear = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.maintenance.clear({}, {
                    fixture.sessions = emptyList()
                    fixture.insights.rows.value = emptyList()
                })
            }
            assertTrue("Slow analysis must not hold the history mutation lock", clear!!.isCompleted)
            assertFalse(fixture.maintenance.isClearing)
        }
        repo.refresh()
        clear!!.await()
        assertEquals(1, fixture.seen.single().sessions.size)
        assertTrue("Completed clear must not be repopulated by stale analysis", fixture.insights.rows.value.isEmpty())
        assertNull(fixture.store.getString(InsightRepository.LAST_ANALYZED_AT))
        assertNull(repo.lastAnalyzedAt.value)
        runCurrent()
        assertTrue(repo.report.value!!.findings.isEmpty())
        assertEquals("discarded stale analysis is not a success", 0L, repo.successfulAnalysisRevision.value)
        fixture.duringAnalysis = {}
        fixture.output = emptyList()
        repo.refresh()
        assertTrue(fixture.seen.last().sessions.isEmpty())
        assertEquals(NOW, repo.lastAnalyzedAt.value)
        assertEquals(1L, repo.successfulAnalysisRevision.value)
    }

    @Test fun clearHoldingMutationLockPreventsRefreshFromWriting() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        val deleteGate = CompletableDeferred<Unit>()
        var clear: Deferred<Unit>? = null
        fixture.duringAnalysis = {
            clear = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.maintenance.clear({}, {
                    deleteGate.await()
                    fixture.sessions = emptyList()
                    fixture.insights.rows.value = emptyList()
                })
            }
        }
        val refresh = async { repo.refresh() }
        try {
            runCurrent()
            assertTrue(fixture.maintenance.isClearing)
            assertTrue("No write may race a pending clear", fixture.insights.rows.value.isEmpty())
            assertNull(repo.lastAnalyzedAt.value)
        } finally {
            // Clear is non-cancellable, so release its fake deletion even when a regression fails.
            deleteGate.complete(Unit)
        }
        clear!!.await()
        refresh.await()
        runCurrent()
        assertTrue(fixture.insights.rows.value.isEmpty())
        assertTrue(repo.report.value!!.findings.isEmpty())
        assertNull(repo.lastAnalyzedAt.value)
        assertEquals(0L, repo.successfulAnalysisRevision.value)
    }

    @Test fun repeatedNotAProblemOnlyMultipliesActiveFindingOnce() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        assertEquals(InsightFindingStatus.ACTIVE, fixture.insights.row().status)

        repo.notAProblem(testFinding().key)
        repo.notAProblem(testFinding().key)

        assertEquals(1.5, fixture.insights.row().feedbackMultiplier, 0.0)
        assertEquals(InsightFindingStatus.DISMISSED, fixture.insights.row().status)
    }

    @Test fun singleNotAProblemMultipliesAndDismissesActiveFinding() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        assertEquals(InsightFindingStatus.ACTIVE, fixture.insights.row().status)

        repo.notAProblem(testFinding().key)

        assertEquals(1.5, fixture.insights.row().feedbackMultiplier, 0.0)
        assertEquals(InsightFindingStatus.DISMISSED, fixture.insights.row().status)
    }

    @Test fun notAProblemCapsActiveFindingFeedbackMultiplier() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        fixture.insights.rows.value = listOf(fixture.insights.row().copy(feedbackMultiplier = 3.375))

        repo.notAProblem(testFinding().key)

        assertEquals(4.0, fixture.insights.row().feedbackMultiplier, 0.0)
        assertEquals(InsightFindingStatus.DISMISSED, fixture.insights.row().status)
    }

    @Test fun missingDismissedFindingRemainsDismissedAndFeedbackFlowsIntoNextAnalysis() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        repo.notAProblem(testFinding().key)
        assertEquals(1.5, fixture.insights.row().feedbackMultiplier, 0.0)
        fixture.output = emptyList()
        repo.refresh()
        assertEquals(1.5, fixture.seen.last().feedback[testFinding().key]!!, 0.0)
        assertEquals(InsightFindingStatus.DISMISSED, fixture.insights.row().status)
        repeat(5) { repo.notAProblem(testFinding().key) }
        repo.refresh()
        assertEquals(1.5, fixture.seen.last().feedback[testFinding().key]!!, 0.0)
        repo.notAProblem("missing")
        repo.dismiss("missing")
        assertEquals(1, fixture.insights.rows.value.size)
    }

    @Test fun refreshBuildsInputsOnlyFromRecordedHistoryAndLiveAnalysisSettings() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        assertEquals(listOf("sessions", "whitelist", "analyze"), fixture.events)
        assertEquals(setOf("old.whitelist"), fixture.seen.single().dozeUserWhitelist)
        assertEquals(4_000_000L, fixture.seen.single().fullUah)
        assertEquals(NOW - InsightInputsBuilder.HISTORY_MS to NOW, fixture.window)
        assertEquals(10L to 100L, fixture.dayWindow)
        // Snapshot/baseline writes on the fake throw; analysis called none.
    }

    @Test fun refreshesAndFeedbackAreSerializedOnlyWhileAnalysisIsInProgress() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeSessions = { gate.await() }
        val repo = fixture.repository(this)
        val first = async { repo.refresh() }
        runCurrent()
        val second = async { repo.refresh() }
        val feedback = async { repo.notAProblem(testFinding().key) }
        runCurrent()
        assertEquals(listOf("sessions"), fixture.events)
        assertFalse(second.isCompleted)
        assertFalse(feedback.isCompleted)
        gate.complete(Unit)
        first.await()
        second.await()
        feedback.await()
        assertEquals(2, fixture.seen.size)
        assertEquals(1.5, fixture.insights.row().feedbackMultiplier, 0.0)
    }

    @Test fun cancellationAfterFindingsWriteStillAdvancesStoredAndPublishedAnalysisTime() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        fixture.now += 500
        fixture.output = listOf(testFinding().copy(score = 80.0))
        lateinit var refresh: Deferred<Unit>
        fixture.insights.afterFindingsWrite = {
            // Room can commit rows before its suspending write returns to a cancelled caller.
            refresh.cancel()
            yield()
        }
        refresh = async { repo.refresh() }
        refresh.join()
        runCurrent()
        assertTrue("The refresh caller must have been cancelled", refresh.isCancelled)
        assertEquals(NOW + 500, fixture.insights.row().lastSeenAt)
        assertEquals(80.0, fixture.insights.row().score, 0.0)
        assertEquals("Committed findings must advance the stored analysis time",
            (NOW + 500).toString(), fixture.store.getString(InsightRepository.LAST_ANALYZED_AT))
        assertEquals("Committed findings must advance the timestamp StateFlow", NOW + 500, repo.lastAnalyzedAt.value)
        assertEquals("Republished findings must use the new analysis time", NOW + 500, repo.report.value!!.generatedAtMs)
        assertEquals(fixture.output, repo.report.value!!.findings)
        assertEquals(2L, repo.successfulAnalysisRevision.value)
    }

    @Test fun cancellationBeforeWriteLeavesFindingsAndAnalysisTimeUnchanged() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        val previousRows = fixture.insights.rows.value
        val previousReport = repo.report.value
        fixture.now += 500
        fixture.output = listOf(testFinding().copy(score = 80.0))
        fixture.maintenance.mutations.lock()
        val refresh = async { repo.refresh() }
        try {
            runCurrent()
            assertEquals("Analysis must finish before waiting for the write lock", 2, fixture.seen.size)
            assertFalse("Refresh must still be waiting to write", refresh.isCompleted)
            refresh.cancel()
            refresh.join()
        } finally {
            fixture.maintenance.mutations.unlock()
        }
        runCurrent()
        assertTrue(refresh.isCancelled)
        assertEquals("Cancellation before writing must not change findings", previousRows, fixture.insights.rows.value)
        assertEquals(1, fixture.insights.feedbackWrites)
        assertEquals(NOW.toString(), fixture.store.getString(InsightRepository.LAST_ANALYZED_AT))
        assertEquals(NOW, repo.lastAnalyzedAt.value)
        assertEquals(previousReport, repo.report.value)
        assertEquals(1L, repo.successfulAnalysisRevision.value)
    }

    @Test fun cancellationDuringPreCommitReadLeavesFindingsAndAnalysisTimeUnchanged() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        val previousRows = fixture.insights.rows.value
        val previousReport = repo.report.value
        fixture.now += 500
        fixture.output = listOf(testFinding().copy(score = 80.0))
        lateinit var refresh: Deferred<Unit>
        fixture.duringAnalysis = {
            // Cancel in the final read, without a further suspension before the commit boundary.
            fixture.insights.afterFindingsRead = { refresh.cancel() }
        }
        refresh = async { repo.refresh() }
        refresh.join()
        runCurrent()
        assertTrue(refresh.isCancelled)
        assertEquals("Cancellation at the commit boundary must not change findings", previousRows, fixture.insights.rows.value)
        assertEquals(1, fixture.insights.feedbackWrites)
        assertEquals(NOW.toString(), fixture.store.getString(InsightRepository.LAST_ANALYZED_AT))
        assertEquals(NOW, repo.lastAnalyzedAt.value)
        assertEquals(previousReport, repo.report.value)
        assertEquals(1L, repo.successfulAnalysisRevision.value)
    }

    @Test fun successfulRefreshPersistsLastAnalyzedAtAndFailureDoesNotAdvanceIt() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository(this)
        repo.refresh()
        assertEquals(NOW, repo.lastAnalyzedAt.value)
        assertEquals(NOW.toString(), fixture.store.getString(InsightRepository.LAST_ANALYZED_AT))
        assertEquals(NOW, fixture.repository(this).awaitLastAnalyzedAt())
        fixture.now += 500
        fixture.beforeSessions = { error("history read failed") }
        try { repo.refresh(); fail("Expected history read failure") } catch (_: IllegalStateException) { }
        assertEquals(NOW, repo.lastAnalyzedAt.value)
        assertEquals(1, fixture.seen.size)
        assertEquals(1L, repo.successfulAnalysisRevision.value)
    }

    @Test fun chunksDaoIdsBelowSqliteLimitAndOmitsOpenSession() = runTest {
        val fixture = Fixture()
        fixture.sessions = (0..1_800).map { testSession("s$it") } + testSession("open").copy(endTime = null)
        fixture.repository(this).refresh()
        assertEquals(listOf(900, 900, 1), fixture.rowChunks.map { it.size })
        assertEquals(fixture.rowChunks, fixture.wakerChunks)
        assertEquals(1_801, fixture.seen.single().sessions.size)
        assertFalse(fixture.rowChunks.flatten().contains("open"))
    }

    @Test fun realEngineDetectsHotChargingAndPersistsItsEvidence() = runTest {
        val fixture = Fixture()
        fixture.sessions = (1..3).map { index -> testSession("charge$index").copy(
            type = SessionType.CHARGE, startTime = NOW - (index + 1) * HOUR,
            endTime = NOW - index * HOUR, peakTemperatureDeciC = 430,
        ) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = InsightRepository(fixture.sessionDao, fixture.daily, fixture.apps, fixture.insights,
            backgroundScope, fixture.clock, { emptySet() }, fixture.store,
            maintenance = fixture.maintenance,
            ioDispatcher = dispatcher, analyzeDispatcher = dispatcher)
        repo.refresh()
        assertNotNull(repo.report.value)
        val hot = repo.report.value!!.findings.single { it.type == FindingType.HOT_CHARGING }
        assertEquals(43.0, hot.evidence.single().observed, 0.0)
        assertEquals(3, hot.evidence.single().sessions)
        assertEquals(hot, FindingCodec.decode(fixture.insights.row(hot.key)))
        assertEquals(hot, repo.report.value!!.headline)
        assertEquals(NOW, repo.report.value!!.generatedAtMs)
        assertEquals(NOW, repo.lastAnalyzedAt.value)
    }
}
