package com.akane.voltwise.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.db.InsightActionEntity
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.insights.*
import com.akane.voltwise.battery.util.ShellRunner
import com.akane.voltwise.battery.shizuku.ShizukuBridge
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import com.akane.voltwise.battery.insights.actions.*
import com.akane.voltwise.battery.insights.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeInsightsRepository()
    private val results = InsightApplyResults()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    private fun TestScope.start(saved: SavedStateHandle = SavedStateHandle()): InsightsViewModel {
        val vm = InsightsViewModel(source, backgroundScope, saved, results)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test fun defaultAdapterUsesSharedEligibilityAccessAndExplicitRefresh() = runTest {
        val now = com.akane.voltwise.battery.insights.NOW
        val local = testSession()
        val sessions = MutableStateFlow(listOf(local, local.copy(sessionId = "open", endTime = null),
            local.copy(sessionId = "import:one"), local.copy(sessionId = "short", appCaptureEndMs = local.appCaptureStartMs?.plus(1000))))
        val sessionDao = object : UnusedSessionDao() {
            override fun filteredSessions(type: SessionType?, query: String, limit: Int) = sessions
            override suspend fun closedSessionsBetween(from: Long, to: Long) = sessions.value.filter { it.endTime != null }
            override fun capacityEstimates(limit: Int) = flowOf(emptyList<CapacityEstimateRow>())
        }
        val rows = MutableStateFlow<List<InsightFindingEntity>>(emptyList())
        val dao = object : UnusedInsightDao() {
            override fun findings() = rows
            override suspend fun findingsOnce() = rows.value
            override fun actions() = source.actions
            override suspend fun actionsOnce() = source.actions.value
            override suspend fun upsertFindings(list: List<InsightFindingEntity>) { rows.value = list }
            override suspend fun setStatus(key: String, status: InsightFindingStatus) {
                rows.value = rows.value.map { if (it.key == key) it.copy(status = status) else it }
            }
        }
        val insights = InsightRepository(
            sessionDao,
            object : UnusedDailySummaryDao() { override suspend fun range(fromDay: Long, toDay: Long) = emptyList<DailySummary>() },
            object : UnusedAppUsageDao() {
                override suspend fun usageRowsForSessions(sessionIds: List<String>) = emptyList<SessionAppUsage>()
                override suspend fun sessionWakers(sessionIds: List<String>) = emptyList<SessionDeviceWaker>()
            },
            dao, backgroundScope, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
            { null }, FakeKeyValueStore(),
            maintenance = HistoryMaintenance(),
            ioDispatcher = dispatcher, analyzeDispatcher = dispatcher,
            analyze = { InsightReport(it.nowMs, listOf(insightFinding()), insightFinding()) },
        )
        val journal = InsightActionRepository(dao, { error("observation must never execute") }, object : TargetInspector {
            override val sdkInt = 37
            override fun installedUid(pkg: String, userId: Int): Int? = error("unexpected inspection")
            override fun packagesForUid(uid: Int): List<String> = error("unexpected inspection")
            override fun roleHolders(): Set<String> = error("unexpected inspection")
        }, { now }, {}, { true })
        var mode = ShellRunner.Mode.SHIZUKU
        var probes = 0
        val shell = ShellRunner({ probes++; mode }, { _, _, _ -> error("unexpected shell call") }, { false }, { 0L })
        val adapter = DefaultInsightsRepository(insights, journal, shell, sessionDao, { now })
        assertSame(insights.report, adapter.report)
        assertEquals(1, adapter.eligibleSessionCount.first())
        assertEquals("constructing the adapter must not probe", 0, probes)
        assertEquals("collecting privilege must discover cold-start Shizuku after unknown", listOf(null, true), adapter.privileged.take(2).toList())
        assertEquals(1, probes)
        assertEquals(true, adapter.privileged.first { it != null })
        assertEquals("a second collection must reuse cached detection", 1, probes)
        for (next in ShellRunner.Mode.entries) {
            mode = next
            shell.detectMode(forceRefresh = true)
            assertEquals(next == ShellRunner.Mode.ROOT || next == ShellRunner.Mode.SHIZUKU, adapter.privileged.first { it != null })
        }
        adapter.analyzeNow()
        assertEquals(now, adapter.lastAnalyzedAt.first())
        assertEquals("finding", adapter.report.value?.findings?.single()?.key)
        val manual = insightFinding().recommendations.last()
        assertTrue(adapter.apply(insightFinding(), manual) is ActionResult.OpenSettings)
        adapter.dismiss("finding")
        assertTrue(adapter.report.value?.findings?.isEmpty() == true)
    }

    @Test fun sameClockBackgroundSuccessRecoversThroughRealRepositoryAndAdapter() = runTest {
        assertClockIndependentRecovery(100)
    }

    @Test fun backwardClockBackgroundSuccessRecoversThroughRealRepositoryAndAdapter() = runTest {
        assertClockIndependentRecovery(99)
    }

    private suspend fun TestScope.assertClockIndependentRecovery(recoveryAt: Long) {
        var now = 100L
        var failAnalysis = false
        val sessions = object : UnusedSessionDao() {
            override fun filteredSessions(type: SessionType?, query: String, limit: Int) = flowOf(emptyList<ChargeSession>())
            override suspend fun closedSessionsBetween(from: Long, to: Long) = emptyList<ChargeSession>()
            override fun capacityEstimates(limit: Int) = flowOf(emptyList<CapacityEstimateRow>())
        }
        val rows = MutableStateFlow<List<InsightFindingEntity>>(emptyList())
        val dao = object : UnusedInsightDao() {
            override fun findings() = rows
            override suspend fun findingsOnce() = rows.value
            override fun actions() = flowOf(emptyList<InsightActionEntity>())
            override suspend fun actionsOnce() = emptyList<InsightActionEntity>()
            override suspend fun upsertFindings(list: List<InsightFindingEntity>) { rows.value = list }
        }
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant() = Instant.ofEpochMilli(now)
        }
        val insights = InsightRepository(
            sessions,
            object : UnusedDailySummaryDao() { override suspend fun range(fromDay: Long, toDay: Long) = emptyList<DailySummary>() },
            object : UnusedAppUsageDao() {
                override suspend fun usageRowsForSessions(sessionIds: List<String>) = emptyList<SessionAppUsage>()
                override suspend fun sessionWakers(sessionIds: List<String>) = emptyList<SessionDeviceWaker>()
            },
            dao, backgroundScope, clock, { null },
            FakeKeyValueStore(), maintenance = HistoryMaintenance(), ioDispatcher = dispatcher, analyzeDispatcher = dispatcher,
            analyze = {
                if (failAnalysis) error("analysis failed")
                InsightReport(it.nowMs, listOf(insightFinding()), insightFinding())
            },
        )
        val journal = InsightActionRepository(dao, { error("unexpected action") }, object : TargetInspector {
            override val sdkInt = 37
            override fun installedUid(pkg: String, userId: Int): Int? = error("unexpected inspection")
            override fun packagesForUid(uid: Int): List<String> = error("unexpected inspection")
            override fun roleHolders(): Set<String> = error("unexpected inspection")
        }, { now }, {}, { true })
        val shell = ShellRunner({ ShellRunner.Mode.NONE }, { _, _, _ -> error("unexpected shell call") }, { false }, { 0L })
        val adapter = DefaultInsightsRepository(insights, journal, shell, sessions, { now })
        insights.refresh()
        // Keep eligibility's unrelated Default-dispatcher work out of this recovery regression.
        val repository = object : InsightsRepository by adapter {
            override val eligibleSessionCount = flowOf(0)
        }
        val vm = InsightsViewModel(repository, backgroundScope, applyResults = results)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(100L, vm.state.value.lastAnalyzedAt)
        failAnalysis = true
        vm.onEvent(InsightsEvent.AnalyzeNow)
        // Access re-probing uses the real IO dispatcher; await the observed failure, not scheduler timing.
        vm.state.first { it.error == InsightMessageCode.ANALYSIS_FAILED }
        assertEquals(InsightMessageCode.ANALYSIS_FAILED, vm.state.value.error)
        assertEquals(InsightMessageCode.ANALYSIS_FAILED, results.latest.value?.code)

        failAnalysis = false
        now = recoveryAt
        insights.refresh()
        runCurrent()
        assertEquals(recoveryAt, vm.state.value.lastAnalyzedAt)
        assertEquals(recoveryAt, insights.report.value?.generatedAtMs)
        assertNull("successful background analysis at $recoveryAt must clear the failure", vm.state.value.error)
        assertNull("recovery must retire its held failure outcome", results.latest.value)
        assertNull(vm.state.value.apply.lastResult)
    }

    @Test fun mapsHeadlineKeyFindingsTrendsJournalEffectsAndLearningCount() = runTest {
        val key = insightFinding()
        val up = key.copy(key = "trend.up", type = FindingType.TREND, severity = Severity.INFO, direction = Direction.UP)
        val down = up.copy(key = "trend.down", direction = Direction.DOWN)
        // Informational without a direction: neither a key finding nor a change.
        val info = key.copy(key = "info", severity = Severity.INFO, direction = null)
        val effect = info.copy(key = "ACTION_EFFECT:example.app:POWER_MAH_PER_H:7", type = FindingType.ACTION_EFFECT, direction = Direction.UP)
        source.report.value = InsightReport(100, listOf(key, up, down, info, effect), key)
        source.actions.value = InsightActionStatus.entries.mapIndexed { index, status -> insightAction((index + 7).toLong(), status) }
        source.lastAnalyzedAt.value = 100
        source.eligibleSessionCount.value = 3
        val vm = start()
        assertEquals(key.key, vm.state.value.headline?.key)
        assertEquals(listOf(key.key), vm.state.value.keyFindings.map { it.key })
        assertEquals(listOf("trend.up", "trend.down"), vm.state.value.changes.map { it.key })
        assertEquals(listOf(InsightActionStatus.APPLIED, InsightActionStatus.UNKNOWN), vm.state.value.appliedActions.filter { it.undoable }.map { it.status })
        assertEquals(effect.key, vm.state.value.appliedActions.first().effect?.key)
        assertEquals(100L, vm.state.value.lastAnalyzedAt)
        assertFalse(vm.state.value.empty)
        assertTrue(vm.state.value.lowData)
        source.eligibleSessionCount.value = 5
        source.report.value = InsightReport(101, emptyList(), null)
        runCurrent()
        assertFalse(vm.state.value.lowData)
        assertTrue(vm.state.value.empty)
    }

    // R10-4: ChargingHealth's decline is INFO and DOWN, not a TREND; it must still reach "What changed".
    @Test fun anActiveHealthDeclineIsListedUnderChangesButAnActionEffectIsNot() = runTest {
        val decline = insightFinding().copy(
            key = "HEALTH_DECLINE:device", type = FindingType.HEALTH_DECLINE, severity = Severity.INFO,
            subject = Subject.Device, direction = Direction.DOWN, recommendations = emptyList(),
            evidence = listOf(Evidence(Metric.CAPACITY_CHANGE_PCT_PER_YEAR, -6.0, null, MetricUnit.PCT_PER_YEAR, 6)),
        )
        val effect = insightFinding().copy(
            key = "ACTION_EFFECT:example.app:POWER_MAH_PER_H:7", type = FindingType.ACTION_EFFECT,
            severity = Severity.INFO, direction = Direction.DOWN,
        )
        source.report.value = InsightReport(100, listOf(decline, effect), null)
        source.lastAnalyzedAt.value = 100
        source.eligibleSessionCount.value = 5
        val vm = start()
        assertEquals(listOf(decline.key), vm.state.value.changes.map { it.key })
        assertTrue("an informational finding is not a key finding", vm.state.value.keyFindings.isEmpty())
        // Negative control: the action effect stays out of Changes (Applied fixes shows it with its fix).
        source.report.value = InsightReport(101, listOf(effect), null)
        runCurrent()
        assertTrue(vm.state.value.changes.isEmpty())
    }

    // R9-5: app detectors baseline on the windows before the current one, so 4 eligible windows is still learning.
    @Test fun fourEligibleWindowsAreStillLowDataAndFiveAreNot() = runTest {
        source.report.value = InsightReport(100, emptyList(), null)
        source.lastAnalyzedAt.value = 100
        source.eligibleSessionCount.value = 4
        val vm = start()
        assertTrue("4 windows leave only 3 history windows for an app baseline", vm.state.value.lowData)
        source.eligibleSessionCount.value = 5
        runCurrent()
        assertFalse(vm.state.value.lowData)
    }

    @Test fun marksOnlyRevertedRowsSettledAsChangedExternally() = runTest {
        source.actions.value = listOf(
            insightAction(1, InsightActionStatus.REVERTED).copy(message = "CHANGED_EXTERNALLY"),
            insightAction(2, InsightActionStatus.REVERTED),
            insightAction(3, InsightActionStatus.APPLIED).copy(message = "CHANGED_EXTERNALLY"),
        )
        val vm = start()
        assertEquals(
            "only an undo that found the setting changed outside Voltwise is marked",
            listOf(1L to true, 2L to false, 3L to false),
            vm.state.value.appliedActions.map { it.id to it.changedExternally },
        )
    }

    @Test fun analyzeShowsBusyAndClearsItAfterCompletionAndFailure() = runTest {
        val vm = start()
        source.analyzeGate = CompletableDeferred()
        vm.onEvent(InsightsEvent.AnalyzeNow)
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        assertTrue(vm.state.value.analyzing)
        assertEquals(1, source.analyzeCalls)
        source.analyzeGate?.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.analyzing)
        source.analyzeFailure = true
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        assertFalse(vm.state.value.analyzing)
        assertEquals(InsightMessageCode.ANALYSIS_FAILED, vm.state.value.error)
    }

    @Test fun successfulBackgroundAnalysisClearsAnalysisFailure() = runTest {
        source.lastAnalyzedAt.value = 100
        val vm = start()
        source.analyzeFailure = true
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        assertEquals(InsightMessageCode.ANALYSIS_FAILED, vm.state.value.error)

        source.lastAnalyzedAt.value = 101
        source.successfulAnalysisRevision.value++
        runCurrent()
        assertEquals(101L, vm.state.value.lastAnalyzedAt)
        assertNull("a newer successful background analysis must clear analysis failure", vm.state.value.error)
        assertEquals("recovery must not retry analysis", 1, source.analyzeCalls)
    }

    @Test fun successfulBackgroundAnalysisAlsoRetiresHeldFailureResult() = runTest {
        source.lastAnalyzedAt.value = 100
        // No screen collector: a stopped activity cannot consume the failure result.
        val vm = InsightsViewModel(source, backgroundScope, applyResults = results)
        runCurrent()
        source.analyzeFailure = true
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        assertEquals(InsightMessageCode.ANALYSIS_FAILED, results.latest.value?.code)

        source.lastAnalyzedAt.value = 101
        source.successfulAnalysisRevision.value++
        runCurrent()
        assertNull("background recovery must retire the unconsumed analysis failure", results.latest.value)

        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertNull(vm.state.value.error)
        assertNull("a resumed screen must not receive a stale failure snackbar", vm.state.value.apply.lastResult)
        assertEquals("recovery must not retry analysis", 1, source.analyzeCalls)
    }

    @Test fun successfulBackgroundAnalysisKeepsNewerApplyResult() = runTest {
        source.lastAnalyzedAt.value = 100
        val vm = start()
        source.analyzeFailure = true
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        val failure = results.latest.value
        assertEquals(InsightMessageCode.ANALYSIS_FAILED, failure?.code)

        source.result = ActionResult.Reverted
        vm.onEvent(InsightsEvent.Undo(7))
        runCurrent()
        val newer = results.latest.value
        assertEquals(InsightMessageCode.REVERTED, newer?.code)
        assertTrue(newer!!.seq > failure!!.seq)

        source.lastAnalyzedAt.value = 101
        source.successfulAnalysisRevision.value++
        runCurrent()
        assertNull(vm.state.value.error)
        assertSame("recovery must not consume a newer apply outcome", newer, results.latest.value)
        assertSame(newer, vm.state.value.apply.lastResult)
    }

    // R10-5: the failure's notice (error) dies with the VM, so its held snackbar must not greet the next VM.
    @Test fun clearingTheViewModelRetiresItsHeldAnalysisFailure() = runTest {
        source.lastAnalyzedAt.value = 100
        val vm1 = start()
        val store = ViewModelStore().apply { put("insights", vm1) }
        source.analyzeFailure = true
        vm1.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        assertEquals(InsightMessageCode.ANALYSIS_FAILED, results.latest.value?.code)

        store.clear()
        source.analyzeFailure = false
        source.lastAnalyzedAt.value = 101
        val vm2 = start()
        assertNull(vm2.state.value.error)
        assertNull("a new screen must not show 'Couldn't analyze' under a fresh analysis", vm2.state.value.apply.lastResult)
    }

    @Test fun analysisFailureAfterClearingTheViewModelIsNotPublished() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = object : InsightsRepository by source {
            override suspend fun analyzeNow() {
                source.analyzeCalls++
                withContext(NonCancellable) {
                    gate.await()
                    throw IOException("late analysis failure")
                }
            }
        }
        val vm = InsightsViewModel(repository, backgroundScope, applyResults = results)
        val store = ViewModelStore().apply { put("insights", vm) }
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        assertEquals("analysis must start before the VM is cleared", 1, source.analyzeCalls)

        store.clear()
        assertNull(results.latest.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull("a failure after clearing must not publish a held snackbar", results.latest.value)

        val nextVm = start()
        assertNull(nextVm.state.value.error)
        assertNull("the next VM must not receive the late analysis failure", nextVm.state.value.apply.lastResult)
    }

    @Test fun clearingTheViewModelKeepsANewerApplyResult() = runTest {
        source.lastAnalyzedAt.value = 100
        val vm1 = start()
        val store = ViewModelStore().apply { put("insights", vm1) }
        source.analyzeFailure = true
        vm1.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        val failure = checkNotNull(results.latest.value)
        source.result = ActionResult.Reverted
        vm1.onEvent(InsightsEvent.Undo(7))
        runCurrent()
        val newer = checkNotNull(results.latest.value)
        assertTrue(newer.seq > failure.seq)

        store.clear()
        val vm2 = start()
        assertSame("clearing must consume only its own failure, never a newer outcome", newer, results.latest.value)
        assertSame(newer, vm2.state.value.apply.lastResult)
    }

    @Test fun timestampEmissionsWithoutSuccessfulRevisionKeepAnalysisFailure() = runTest {
        val timestamps = kotlinx.coroutines.flow.MutableSharedFlow<Long?>(replay = 1)
        timestamps.emit(100)
        val repository = object : InsightsRepository by source {
            override val lastAnalyzedAt = timestamps
        }
        val vm = InsightsViewModel(repository, backgroundScope, applyResults = results)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        source.analyzeFailure = true
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        for (at in listOf(100L, 99L, 100L, 101L)) {
            timestamps.emit(at)
            runCurrent()
            assertEquals("timestamp $at without a completed analysis must keep the failure",
                InsightMessageCode.ANALYSIS_FAILED, vm.state.value.error)
        }
        source.successfulAnalysisRevision.value++
        runCurrent()
        assertNull("a completed analysis clears the failure independently of timestamp emissions", vm.state.value.error)
    }

    @Test fun firstAnalysisTimestampEmissionDoesNotClearAnalysisFailure() = runTest {
        val timestamps = kotlinx.coroutines.flow.MutableSharedFlow<Long?>(replay = 1)
        val repository = object : InsightsRepository by source {
            override val lastAnalyzedAt = timestamps
        }
        val vm = InsightsViewModel(repository, backgroundScope, applyResults = results)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        source.analyzeFailure = true
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        timestamps.emit(100)
        runCurrent()
        assertEquals("initial timestamp loading is not a successful refresh",
            InsightMessageCode.ANALYSIS_FAILED, vm.state.value.error)
        timestamps.emit(100)
        runCurrent()
        assertEquals("re-emitting the initial timestamp must keep the failure",
            InsightMessageCode.ANALYSIS_FAILED, vm.state.value.error)
        timestamps.emit(101)
        runCurrent()
        assertEquals("even a newer loaded timestamp is not a successful refresh",
            InsightMessageCode.ANALYSIS_FAILED, vm.state.value.error)
        source.successfulAnalysisRevision.value++
        runCurrent()
        assertNull("a completed analysis proves recovery", vm.state.value.error)
    }

    @Test fun firstSuccessfulBackgroundAnalysisClearsFailureWithoutAPreviousTimestamp() = runTest {
        val vm = start()
        assertNull(vm.state.value.lastAnalyzedAt)
        source.analyzeFailure = true
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        assertEquals(InsightMessageCode.ANALYSIS_FAILED, vm.state.value.error)
        source.lastAnalyzedAt.value = 100
        source.successfulAnalysisRevision.value++
        runCurrent()
        assertNull("a first successful analysis after the initial null must clear failure", vm.state.value.error)
    }

    @Test fun successfulBackgroundAnalysisKeepsFeedbackFailure() = runTest {
        source.lastAnalyzedAt.value = 100
        val repository = object : InsightsRepository by source {
            override suspend fun dismiss(key: String) { error("feedback test failure") }
        }
        val vm = InsightsViewModel(repository, backgroundScope, applyResults = results)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.onEvent(InsightsEvent.Dismiss("finding"))
        runCurrent()
        assertEquals(InsightMessageCode.FEEDBACK_FAILED, vm.state.value.error)
        source.lastAnalyzedAt.value = 101
        source.successfulAnalysisRevision.value++
        runCurrent()
        assertEquals(101L, vm.state.value.lastAnalyzedAt)
        assertEquals("analysis success must not erase feedback failure",
            InsightMessageCode.FEEDBACK_FAILED, vm.state.value.error)
    }

    @Test fun failureCapturesCurrentRevisionBeforeItsObserverHandlesIt() = runTest {
        source.successfulAnalysisRevision.value = 1
        val repository = object : InsightsRepository by source {
            override suspend fun analyzeNow() {
                source.successfulAnalysisRevision.value = 2
                error("failure after an earlier completion")
            }
        }
        val vm = InsightsViewModel(repository, backgroundScope, applyResults = results)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        assertEquals("a completion before the failure must not clear the new failure",
            InsightMessageCode.ANALYSIS_FAILED, vm.state.value.error)
        assertEquals(InsightMessageCode.ANALYSIS_FAILED, results.latest.value?.code)
        source.successfulAnalysisRevision.value = 3
        runCurrent()
        assertNull(vm.state.value.error)
        assertNull(results.latest.value)
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    @Test fun firstObservedRevisionCanRecoverIfSuccessHappenedAfterFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        val revisions = source.successfulAnalysisRevision
        val repository = object : InsightsRepository by source {
            override val successfulAnalysisRevision = object : kotlinx.coroutines.flow.StateFlow<Long> by revisions {
                override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Long>): Nothing {
                    gate.await()
                    revisions.collect(collector)
                }
            }
        }
        val vm = InsightsViewModel(repository, backgroundScope, applyResults = results)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        source.analyzeFailure = true
        vm.onEvent(InsightsEvent.AnalyzeNow)
        runCurrent()
        assertEquals(InsightMessageCode.ANALYSIS_FAILED, vm.state.value.error)
        revisions.value = 1
        gate.complete(Unit)
        runCurrent()
        assertNull("the first observed revision still proves success after the failure", vm.state.value.error)
        assertNull(results.latest.value)
    }

    @Test fun requestApplyRequiresExplicitConfirmAndCannotReplay() = runTest {
        val vm = start()
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        runCurrent()
        assertEquals("RequestApply must never call apply", 0, source.applied.size)
        assertEquals(PendingInsightApply("finding", ActionType.RESTRICT_BACKGROUND), vm.state.value.apply.pending)
        vm.onEvent(InsightsEvent.ConfirmApply)
        vm.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals(1, source.applied.size)
        assertNull(vm.state.value.apply.pending)
        assertEquals(InsightMessageCode.APPLIED, vm.state.value.apply.lastResult?.code)
    }

    @Test fun wrongTypePendingActionIsIgnoredWithoutApplying() = runTest {
        val restored = runCatching { start(SavedStateHandle(mapOf(
            "insights.pending.key" to "finding",
            "insights.pending.action" to 1,
        ))) }
        assertTrue("a non-String action must not crash construction: ${restored.exceptionOrNull()}", restored.isSuccess)
        assertNull("a non-String action must not restore a pending request", restored.getOrThrow().state.value.apply.pending)
        assertTrue("malformed saved state must never apply", source.applied.isEmpty())
    }

    @Test fun wrongTypePendingKeyIsIgnoredWithoutApplying() = runTest {
        val restored = runCatching { start(SavedStateHandle(mapOf(
            "insights.pending.key" to 1,
            "insights.pending.action" to ActionType.RESTRICT_BACKGROUND.name,
        ))) }
        assertTrue("a non-String finding key must not crash construction: ${restored.exceptionOrNull()}", restored.isSuccess)
        assertNull("a non-String finding key must not restore a pending request", restored.getOrThrow().state.value.apply.pending)
        assertTrue("malformed saved state must never apply", source.applied.isEmpty())
    }

    @Test fun wrongTypeRouteKeyIsIgnoredWithoutApplying() = runTest {
        val restored = runCatching { start(SavedStateHandle(mapOf("key" to 1))) }
        assertTrue("a non-String route key must not crash construction: ${restored.exceptionOrNull()}", restored.isSuccess)
        assertNull("the route key is not apply state", restored.getOrThrow().state.value.apply.pending)
        assertTrue("a route key must never apply", source.applied.isEmpty())
    }

    @Test fun processDeathRestoresDialogWithoutApplyingOrOverwritingRouteKey() = runTest {
        val saved = SavedStateHandle(mapOf("key" to "details-route"))
        val original = start(saved)
        original.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        runCurrent()
        val restored = start(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(original.state.value.apply.pending, restored.state.value.apply.pending)
        assertEquals("RequestApply must preserve the details route argument", "details-route", saved.get<String>("key"))
        assertEquals(0, source.applied.size)
        restored.onEvent(InsightsEvent.CancelApply)
        restored.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals(0, source.applied.size)
        assertNull(restored.state.value.apply.pending)
    }

    @Test fun restoredPendingSurvivesUnknownPrivilegeUntilDetectionCompletes() = runTest {
        source.report.value = null
        source.privileged.value = null
        val request = PendingInsightApply("finding", ActionType.RESTRICT_BACKGROUND)
        val saved = SavedStateHandle(mapOf(
            "insights.pending.key" to request.key,
            "insights.pending.action" to request.action.name,
        ))
        val vm = start(saved)
        source.report.value = InsightReport(2, listOf(insightFinding()), insightFinding())
        runCurrent()
        assertFalse("unknown access is not visually privileged", vm.state.value.privileged)
        assertEquals("unknown access must not invalidate a restored dialog", request, vm.state.value.apply.pending)
        source.privileged.value = true
        runCurrent()
        assertTrue(vm.state.value.privileged)
        assertEquals("successful detection must preserve the restored request", request, vm.state.value.apply.pending)
        assertEquals(request.key, saved.get<String>("insights.pending.key"))
        assertTrue("restoration and detection must never apply", source.applied.isEmpty())
        source.privileged.value = false
        runCurrent()
        assertNull("confirmed access loss must still invalidate pending", vm.state.value.apply.pending)
        assertNull(saved.get<String>("insights.pending.key"))
    }

    @Test fun dismissalFeedbackAndNavigationUseTheirOwnPaths() = runTest {
        val saved = SavedStateHandle(mapOf("key" to "details-route"))
        val vm = start(saved)
        val effects = mutableListOf<InsightUiEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.onEvent(InsightsEvent.Dismiss("one"))
        vm.onEvent(InsightsEvent.NotAProblem("two"))
        vm.onEvent(InsightsEvent.OpenFinding("three"))
        runCurrent()
        assertEquals(listOf("one"), source.dismissed)
        assertEquals(listOf("two"), source.feedback)
        assertEquals(listOf(InsightUiEffect.OpenFinding("three")), effects)
        assertEquals("OpenFinding must preserve the details route argument", "details-route", saved.get<String>("key"))
        assertTrue(source.applied.isEmpty())
    }

    @Test fun highBatteryAlertMessageCarriesNotificationsBlockedInStateAndEffect() = runTest {
        val finding = insightFinding().copy(
            subject = Subject.Device,
            recommendations = listOf(Recommendation(ActionType.ENABLE_HIGH_BATTERY_ALERT, false, false)),
        )
        source.report.value = InsightReport(1, listOf(finding), finding)
        val vm = start()
        val effects = mutableListOf<InsightUiEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        for (blocked in listOf(true, false)) {
            source.result = ActionResult.OneShot(19, notificationsBlocked = blocked)
            vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.ENABLE_HIGH_BATTERY_ALERT))
            vm.onEvent(InsightsEvent.ConfirmApply)
            runCurrent()
            val message = checkNotNull(vm.state.value.apply.lastResult)
            assertEquals(InsightMessageCode.ONE_SHOT, message.code)
            assertEquals(19L, message.actionId)
            assertEquals("The message must preserve the notification postability outcome", blocked, message.notificationsBlocked)
            assertEquals(message, (effects.last() as InsightUiEffect.Message).result)
            assertEquals(message, results.latest.value)
        }
        assertEquals(2, source.applied.size)
    }

    @Test fun everyActionResultAndRefusalGetsItsFixedCodeOrSettingsEvent() = runTest {
        val vm = start()
        val effects = mutableListOf<InsightUiEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        val cases = listOf(
            ActionResult.Applied(1) to InsightMessageCode.APPLIED,
            ActionResult.AppliedWithFallback(2, FallbackCode.RESTRICTED_TO_RARE) to InsightMessageCode.RESTRICTED_TO_RARE,
            ActionResult.Unknown to InsightMessageCode.UNKNOWN,
            ActionResult.Reverted to InsightMessageCode.REVERTED,
            ActionResult.ChangedExternally("private shell text") to InsightMessageCode.CHANGED_EXTERNALLY,
            ActionResult.ChangedExternally(null) to InsightMessageCode.CHANGED_EXTERNALLY,
            ActionResult.OneShot(3) to InsightMessageCode.ONE_SHOT,
        ) + FailureCode.entries.map { ActionResult.Failed(it) to InsightMessageCode.valueOf(it.name) } +
            RefusalCode.entries.map { ActionResult.Refused(it) to InsightMessageCode.valueOf(it.name) }
        for ((result, expected) in cases) {
            source.result = result
            vm.onEvent(InsightsEvent.Undo(42))
            runCurrent()
            assertEquals(result.toString(), expected, vm.state.value.apply.lastResult?.code)
            assertEquals(expected, (effects.last() as InsightUiEffect.Message).result.code)
        }
        val spec = IntentSpec("android.settings.APPLICATION_DETAILS_SETTINGS", "example.app")
        source.result = ActionResult.OpenSettings(spec)
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.OPEN_APP_SETTINGS))
        vm.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals(InsightUiEffect.OpenSettings(spec), effects.last())
        assertNull(vm.state.value.apply.lastResult)
        assertEquals(cases.size, source.undone.size)
        assertTrue(source.undone.all { it == 42L })
    }

    @Test fun clearingScreenDoesNotCancelConfirmedApplyOrUndo() = runTest {
        for (undo in listOf(false, true)) {
            val vm = start()
            val store = ViewModelStore().apply { put("vm", vm) }
            source.actionGate = CompletableDeferred()
            if (undo) vm.onEvent(InsightsEvent.Undo(9)) else {
                vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
                vm.onEvent(InsightsEvent.ConfirmApply)
            }
            runCurrent()
            assertTrue(vm.state.value.apply.working)
            store.clear()
            source.actionGate?.complete(Unit)
            runCurrent()
            assertEquals(if (undo) 2 else 1, source.completedActions)
        }
    }

    @Test fun disappearingFindingClearsSavedPendingAndDoesNotResurface() = runTest {
        val saved = SavedStateHandle()
        val vm = start(saved)
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        source.report.value = null
        runCurrent()
        assertNotNull("an unloaded report must not invalidate a restored dialog", vm.state.value.apply.pending)
        source.report.value = InsightReport(2, emptyList(), null)
        runCurrent()
        assertNull("missing finding must clear pending", vm.state.value.apply.pending)
        assertNull(saved.get<String>("insights.pending.key"))
        assertNull(saved.get<String>("insights.pending.action"))
        source.report.value = InsightReport(3, listOf(insightFinding()), insightFinding())
        runCurrent()
        assertNull("returning finding must not resurrect pending", vm.state.value.apply.pending)
        assertNull(start(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })).state.value.apply.pending)
        assertTrue(source.applied.isEmpty())
    }

    @Test fun pendingIsInvalidatedWithoutAScreenCollector() = runTest {
        val saved = SavedStateHandle()
        val vm = InsightsViewModel(source, backgroundScope, saved, results)
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        runCurrent()
        assertNotNull(saved.get<String>("insights.pending.key"))
        source.report.value = InsightReport(2, emptyList(), null)
        runCurrent()
        assertNull("saved pending must clear without a UI subscriber", saved.get<String>("insights.pending.key"))
        source.report.value = InsightReport(3, listOf(insightFinding()), null)
        assertNull(start(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })).state.value.apply.pending)
    }

    @Test fun dozeRemovalAfterReportDisablesCardsAndClearsSavedPending() = runTest {
        val finding = insightFinding().copy(
            type = FindingType.DOZE_WHITELISTED_DRAINER,
            recommendations = listOf(Recommendation(ActionType.REMOVE_DOZE_WHITELIST, true, true)),
        )
        val report = InsightReport(10, listOf(finding), finding)
        source.report.value = report
        val saved = SavedStateHandle(mapOf(
            "insights.pending.key" to finding.key,
            "insights.pending.action" to ActionType.REMOVE_DOZE_WHITELIST.name,
        ))
        val vm = start(saved)
        assertNotNull(vm.state.value.apply.pending)
        source.actions.value = listOf(insightAction().copy(
            type = ActionType.REMOVE_DOZE_WHITELIST.name, createdAt = 11, appliedAt = 12,
        ))
        runCurrent()

        assertSame("applying must not require a refreshed report", report, source.report.value)
        for (card in listOf(vm.state.value.headline, vm.state.value.keyFindings.single())) {
            val recommendation = checkNotNull(card).recommendations.single()
            assertTrue("stale report cards must show removal as applied", recommendation.alreadyApplied)
            assertFalse("stale report cards must disable removal", recommendation.available)
        }
        assertTrue(vm.state.value.appliedActions.single().undoable)
        assertNull("a post-report removal must invalidate pending", vm.state.value.apply.pending)
        assertNull(saved.get<String>("insights.pending.key"))
        assertNull(saved.get<String>("insights.pending.action"))
        assertTrue("invalidating a dialog must not execute an action", source.applied.isEmpty())

        source.report.value = report.copy(generatedAtMs = 13)
        runCurrent()
        assertTrue("a newer live whitelist finding permits removal again", vm.state.value.keyFindings.single().recommendations.single().available)
        assertNull("a new report must not resurrect the discarded dialog", vm.state.value.apply.pending)
    }

    @Test fun unavailableRecommendationClearsPending() = runTest {
        val vm = start()
        for (reason in listOf("removed", "privilege", "applied")) {
            source.report.value = InsightReport(1, listOf(insightFinding()), insightFinding())
            source.privileged.value = true
            source.actions.value = emptyList()
            runCurrent()
            vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
            runCurrent()
            assertNotNull(vm.state.value.apply.pending)
            when (reason) {
                "removed" -> source.report.value = InsightReport(2, listOf(insightFinding().copy(recommendations = emptyList())), null)
                "privilege" -> source.privileged.value = false
                "applied" -> source.actions.value = listOf(insightAction())
            }
            runCurrent()
            assertNull("$reason recommendation must clear pending", vm.state.value.apply.pending)
        }
        assertTrue(source.applied.isEmpty())
    }

    @Test fun openSettingsDoesNotConsumeAnotherFlowsAppliedOutcome() = runTest {
        for (previous in listOf(null, ActionResult.Refused(RefusalCode.PROTECTED))) {
            val sharedResults = InsightApplyResults()
            val applyingSource = FakeInsightsRepository()
            val settingsSource = FakeInsightsRepository()
            val applying = InsightApplyFlow(applyingSource, backgroundScope, SavedStateHandle(), backgroundScope, sharedResults)
            val settings = InsightApplyFlow(settingsSource, backgroundScope, SavedStateHandle(), backgroundScope, sharedResults)
            val effects = mutableListOf<InsightUiEffect>()
            backgroundScope.launch { settings.effects.collect { effects += it } }
            if (previous != null) {
                settingsSource.result = previous
                settings.onEvent(InsightsEvent.Undo(9))
                runCurrent()
            }
            applying.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
            applying.onEvent(InsightsEvent.ConfirmApply)
            runCurrent()
            val expected = checkNotNull(sharedResults.latest.value)
            assertEquals(InsightActionMessage(InsightMessageCode.APPLIED, 7), expected.copy(seq = 0))
            assertEquals(expected, settings.state.value.lastResult)

            val spec = IntentSpec("android.settings.APPLICATION_DETAILS_SETTINGS", "example.app")
            settingsSource.result = ActionResult.OpenSettings(spec)
            settings.onEvent(InsightsEvent.RequestApply("finding", ActionType.OPEN_APP_SETTINGS))
            settings.onEvent(InsightsEvent.ConfirmApply)
            runCurrent()

            assertEquals(InsightUiEffect.OpenSettings(spec), effects.last())
            assertEquals("opening settings must preserve another flow's unconsumed APPLIED outcome", expected, sharedResults.latest.value)
            assertEquals(expected, applying.state.value.lastResult)
            assertEquals(expected, settings.state.value.lastResult)
        }
    }

    @Test fun openSettingsDoesNotConsumeAnotherFlowsEqualOutcome() = runTest {
        val sharedResults = InsightApplyResults()
        val detailsSource = FakeInsightsRepository()
        val insightsSource = FakeInsightsRepository()
        val details = InsightApplyFlow(detailsSource, backgroundScope, SavedStateHandle(), backgroundScope, sharedResults)
        val insights = InsightApplyFlow(insightsSource, backgroundScope, SavedStateHandle(), backgroundScope, sharedResults)
        detailsSource.result = ActionResult.Failed(FailureCode.EXECUTION_FAILED)
        details.onEvent(InsightsEvent.Undo(1))
        runCurrent()
        details.onEvent(InsightsEvent.ResultShown(checkNotNull(details.state.value.lastResult)))
        runCurrent()
        assertNull("details showed and consumed its own failure", sharedResults.latest.value)

        insightsSource.result = ActionResult.Failed(FailureCode.EXECUTION_FAILED)
        insights.onEvent(InsightsEvent.Undo(2))
        runCurrent()
        val insightsOutcome = checkNotNull(sharedResults.latest.value)
        assertEquals(InsightMessageCode.EXECUTION_FAILED, insightsOutcome.code)

        detailsSource.result = ActionResult.OpenSettings(IntentSpec("android.settings.APPLICATION_DETAILS_SETTINGS", "example.app"))
        details.onEvent(InsightsEvent.Undo(3))
        runCurrent()
        assertSame("opening settings must not consume another flow's equal, unseen outcome",
            insightsOutcome, sharedResults.latest.value)
        assertSame(insightsOutcome, insights.state.value.lastResult)
    }

    @Test fun resultShownConsumesOnlyTheResultThatWasShown() = runTest {
        val pairs = listOf(
            ActionResult.Refused(RefusalCode.PROTECTED) to ActionResult.Refused(RefusalCode.ROLE_HOLDER),
            ActionResult.Failed(FailureCode.EXECUTION_FAILED) to ActionResult.Failed(FailureCode.EXECUTION_FAILED),
        )
        for ((first, second) in pairs) {
            val sharedResults = InsightApplyResults()
            val firstSource = FakeInsightsRepository().apply { result = first }
            val secondSource = FakeInsightsRepository().apply { result = second }
            val shower = InsightApplyFlow(firstSource, backgroundScope, SavedStateHandle(), backgroundScope, sharedResults)
            val other = InsightApplyFlow(secondSource, backgroundScope, SavedStateHandle(), backgroundScope, sharedResults)
            shower.onEvent(InsightsEvent.Undo(1))
            runCurrent()
            val shown = checkNotNull(shower.state.value.lastResult)
            other.onEvent(InsightsEvent.Undo(2))
            runCurrent()
            val unseen = checkNotNull(sharedResults.latest.value)
            assertSame("the observer moved to the newer outcome", unseen, shower.state.value.lastResult)

            // The snackbar for the older result ends one frame after lastResult moved on.
            shower.onEvent(InsightsEvent.ResultShown(shown))
            runCurrent()
            assertSame("$first then $second: showing the older result must not consume the newer one",
                unseen, sharedResults.latest.value)

            shower.onEvent(InsightsEvent.ResultShown(unseen))
            runCurrent()
            assertNull("showing the current result consumes it", sharedResults.latest.value)
            assertNull(other.state.value.lastResult)
        }
    }

    @Test fun consumingAnOlderObservedResultDoesNotEraseTheLatestOutcome() {
        val older = results.publish(InsightActionMessage(InsightMessageCode.PROTECTED))
        val latest = results.publish(InsightActionMessage(InsightMessageCode.ROLE_HOLDER))
        results.consume(older)
        assertEquals("a stale screen receipt must not erase a newer outcome", latest, results.latest.value)
        results.consume(InsightActionMessage(InsightMessageCode.ROLE_HOLDER))
        assertEquals("an equal but unpublished message must not consume the outcome", latest, results.latest.value)
        results.consume(latest)
        assertNull(results.latest.value)
        results.consume(latest)
        assertNull("repeated consumption must not restore a result", results.latest.value)
    }

    @Test fun latestUnconsumedResultSurvivesRecreationAndConsumptionIsShared() = runTest {
        val saved = SavedStateHandle()
        val vm = start(saved)
        vm.onEvent(InsightsEvent.Undo(7))
        runCurrent()
        source.result = ActionResult.OneShot(19)
        vm.onEvent(InsightsEvent.Undo(8))
        runCurrent()
        val expected = checkNotNull(vm.state.value.apply.lastResult)
        assertEquals(InsightActionMessage(InsightMessageCode.ONE_SHOT, 19), expected.copy(seq = 0))
        val restoredSaved = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val restored = start(restoredSaved)
        assertEquals("latest unconsumed result must survive recreation", expected, restored.state.value.apply.lastResult)
        restored.onEvent(InsightsEvent.ResultShown(checkNotNull(restored.state.value.apply.lastResult)))
        runCurrent()
        assertNull(restored.state.value.apply.lastResult)
        assertNull("consumption must clear the original observer too", vm.state.value.apply.lastResult)
        assertFalse("results must not be duplicated in saved state", saved.keys().any { it.startsWith("insights.result.") })
        assertNull(start(SavedStateHandle(restoredSaved.keys().associateWith { restoredSaved.get<Any?>(it) })).state.value.apply.lastResult)
        assertEquals("restoring or consuming must not replay actions", listOf(7L, 8L), source.undone)
    }

    @Test fun loadedWaitsForNonNullReportAndStatus() = runTest {
        source.report.value = null
        val actions = kotlinx.coroutines.flow.MutableSharedFlow<List<InsightActionEntity>>(replay = 1)
        val count = kotlinx.coroutines.flow.MutableSharedFlow<Int>(replay = 1)
        val delayed = object : InsightsRepository by source {
            override val actions = actions
            override val eligibleSessionCount = count
        }
        val vm = InsightsViewModel(delayed, backgroundScope, applyResults = results)
        assertFalse(vm.state.value.loaded)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertFalse(vm.state.value.loaded)
        actions.emit(emptyList())
        runCurrent()
        assertFalse("status has not emitted yet", vm.state.value.loaded)
        count.emit(0)
        runCurrent()
        assertFalse("a null report remains loading after other sources emit", vm.state.value.loaded)
        assertTrue(vm.state.value.lowData)
        source.report.value = InsightReport(2, emptyList(), null)
        runCurrent()
        assertTrue("a published empty report is loaded", vm.state.value.loaded)
        assertTrue(vm.state.value.empty)
    }

    @Test fun confirmingStaleOrUnsupportedRecommendationNeverApplies() = runTest {
        val vm = start()
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        source.report.value = InsightReport(2, emptyList(), null)
        vm.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals(InsightMessageCode.FINDING_UNAVAILABLE, vm.state.value.apply.lastResult?.code)
        source.report.value = InsightReport(3, listOf(insightFinding()), null)
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.FORCE_STOP))
        vm.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals(InsightMessageCode.RECOMMENDATION_UNAVAILABLE, vm.state.value.apply.lastResult?.code)
        assertTrue(source.applied.isEmpty())
    }
}

internal class FakeInsightsRepository : InsightsRepository {
    override val report = MutableStateFlow<InsightReport?>(InsightReport(1, listOf(insightFinding()), insightFinding()))
    override val actions = MutableStateFlow<List<InsightActionEntity>>(emptyList())
    override val privileged = MutableStateFlow<Boolean?>(true)
    override val lastAnalyzedAt = MutableStateFlow<Long?>(null)
    override val successfulAnalysisRevision = MutableStateFlow(0L)
    override val eligibleSessionCount = MutableStateFlow(0)
    val applied = mutableListOf<Pair<Finding, Recommendation>>()
    val undone = mutableListOf<Long>()
    val dismissed = mutableListOf<String>()
    val feedback = mutableListOf<String>()
    var result: ActionResult = ActionResult.Applied(7)
    var analyzeCalls = 0
    var analyzeGate: CompletableDeferred<Unit>? = null
    var analyzeFailure = false
    var actionGate: CompletableDeferred<Unit>? = null
    var completedActions = 0
    override suspend fun analyzeNow() {
        analyzeCalls++
        analyzeGate?.await()
        if (analyzeFailure) error("analysis test failure")
        successfulAnalysisRevision.value++
    }
    override suspend fun dismiss(key: String) { dismissed += key }
    override suspend fun notAProblem(key: String) { feedback += key }
    override suspend fun apply(finding: Finding, recommendation: Recommendation): ActionResult {
        applied += finding to recommendation
        actionGate?.await()
        completedActions++
        return result
    }
    override suspend fun undo(actionId: Long): ActionResult {
        undone += actionId
        actionGate?.await()
        completedActions++
        return result
    }
}

internal fun insightFinding() = Finding(
    "finding", FindingType.APP_DRAIN_ANOMALY, Severity.HIGH, Confidence.HIGH, 1.0,
    Subject.App(10042, "example.app"), Direction.UP,
    listOf(Evidence(Metric.POWER_MAH_PER_H, 10.0, 2.0, MetricUnit.MAH_PER_H, 4)),
    listOf(SeriesPoint(10, 10.0, 1.0, 3.0)),
    listOf(Recommendation(ActionType.RESTRICT_BACKGROUND, true, true), Recommendation(ActionType.OPEN_APP_SETTINGS, false, false)),
)

internal fun insightAction(id: Long = 7, status: InsightActionStatus = InsightActionStatus.APPLIED) = InsightActionEntity(
    id = id, findingKey = "finding", type = ActionType.RESTRICT_BACKGROUND.name,
    packageName = "example.app", uid = 10042, userId = 0, status = status,
    priorStateVersion = 1, createdAt = 1, appliedAt = 2,
)
