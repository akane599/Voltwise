package com.akane.voltwise.viewmodel

import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.InsightActionEntity
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.data.db.InsightFindingEntity
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.insights.InsightRepository
import com.akane.voltwise.battery.insights.UnusedAppUsageDao
import com.akane.voltwise.battery.insights.UnusedDailySummaryDao
import com.akane.voltwise.battery.insights.UnusedInsightDao
import com.akane.voltwise.battery.insights.UnusedSessionDao
import com.akane.voltwise.battery.insights.actions.InsightActionRepository
import com.akane.voltwise.battery.insights.actions.TargetInspector
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightReport
import com.akane.voltwise.battery.insights.model.Recommendation
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.battery.util.ShellRunner
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultInsightsRepositoryTest {
    @Test fun actionHistoryPreservesActualJournalTargetWithoutChangingRequestedType() {
        for (target in listOf("RARE", "RESTRICTED", "FUTURE_BUCKET", null)) {
            val row = insightAction().copy(type = ActionType.STANDBY_BUCKET_RESTRICTED.name, targetState = target)
            val history = actionStates(listOf(row), emptyList()).single()
            assertEquals("stored target $target", target, history.targetState)
            assertEquals(ActionType.STANDBY_BUCKET_RESTRICTED, history.action)
        }
    }

    @Test fun highBatteryAlertEnabledAtOrAfterReportIsAppliedAndUnavailableWithoutUndo() {
        val finding = insightFinding().copy(
            type = FindingType.CHARGING_AT_FULL,
            subject = Subject.Device,
            recommendations = listOf(Recommendation(ActionType.ENABLE_HIGH_BATTERY_ALERT, false, false)),
        )
        for (appliedAt in listOf(10L, 11L)) {
            val row = insightAction(status = InsightActionStatus.ONE_SHOT).copy(
                type = ActionType.ENABLE_HIGH_BATTERY_ALERT.name,
                packageName = null,
                uid = null,
                appliedAt = appliedAt,
            )
            val state = finding.toInsightState(privileged = false, listOf(row), generatedAtMs = 10)
            val recommendation = state.recommendations.single()
            assertTrue("alert enabled at $appliedAt must count as applied", recommendation.alreadyApplied)
            assertFalse("enabled alert must not be offered again", recommendation.available)
            assertFalse("one-shot alert must not gain Undo", actionStates(listOf(row), listOf(state)).single().undoable)
        }
    }

    @Test fun highBatteryAlertEnabledBeforeNewReportRemainsAvailable() {
        val finding = insightFinding().copy(
            type = FindingType.CHARGING_AT_FULL,
            subject = Subject.Device,
            recommendations = listOf(Recommendation(ActionType.ENABLE_HIGH_BATTERY_ALERT, false, false)),
        )
        val row = insightAction(status = InsightActionStatus.ONE_SHOT).copy(
            type = ActionType.ENABLE_HIGH_BATTERY_ALERT.name,
            packageName = null,
            uid = null,
            createdAt = 11,
            appliedAt = 9,
        )
        val hotCharging = finding.copy(key = "hot-charging", type = FindingType.HOT_CHARGING)
        for (deviceFinding in listOf(finding, hotCharging)) {
            val recommendation = deviceFinding.toInsightState(false, listOf(row), generatedAtMs = 10).recommendations.single()
            assertFalse("old alert row must not block ${deviceFinding.type}", recommendation.alreadyApplied)
            assertTrue("new report may offer enabling the alert again for ${deviceFinding.type}", recommendation.available)
        }
    }

    @Test fun oneShotHighBatteryAlertUsesCreatedAtWhenAppliedAtIsMissing() {
        val finding = insightFinding().copy(
            key = "hot-charging",
            type = FindingType.HOT_CHARGING,
            subject = Subject.Device,
            recommendations = listOf(Recommendation(ActionType.ENABLE_HIGH_BATTERY_ALERT, false, false)),
        )
        for (createdAt in listOf(9L, 10L, 11L)) {
            val row = insightAction(status = InsightActionStatus.ONE_SHOT).copy(
                type = ActionType.ENABLE_HIGH_BATTERY_ALERT.name,
                packageName = null,
                uid = null,
                createdAt = createdAt,
                appliedAt = null,
            )
            val recommendation = finding.toInsightState(false, listOf(row), generatedAtMs = 10).recommendations.single()
            assertEquals("one-shot alert uses creation time $createdAt", createdAt >= 10, recommendation.alreadyApplied)
            assertEquals("old alert creation time must not block a newer report", createdAt < 10, recommendation.available)
        }
    }

    @Test fun oneShotHighBatteryAlertAppliesToEveryDeviceFindingInReport() {
        val chargingAtFull = insightFinding().copy(
            type = FindingType.CHARGING_AT_FULL,
            subject = Subject.Device,
            recommendations = listOf(Recommendation(ActionType.ENABLE_HIGH_BATTERY_ALERT, false, false)),
        )
        val hotCharging = chargingAtFull.copy(key = "hot-charging", type = FindingType.HOT_CHARGING)
        val report = InsightReport(10, listOf(chargingAtFull, hotCharging), chargingAtFull)
        for (appliedAt in listOf(10L, 11L)) {
            val row = insightAction(status = InsightActionStatus.ONE_SHOT).copy(
                findingKey = chargingAtFull.key,
                type = ActionType.ENABLE_HIGH_BATTERY_ALERT.name,
                packageName = null,
                uid = null,
                appliedAt = appliedAt,
            )
            for (finding in report.findings) {
                val recommendation = finding.toInsightState(false, listOf(row), report.generatedAtMs).recommendations.single()
                assertTrue("alert enabled at $appliedAt must count as applied on ${finding.type}", recommendation.alreadyApplied)
                assertFalse("enabled alert must not be offered again on ${finding.type}", recommendation.available)
            }
        }
    }

    @Test fun oneShotHighBatteryAlertDoesNotApplyToAppFinding() {
        val finding = insightFinding().copy(
            recommendations = listOf(Recommendation(ActionType.ENABLE_HIGH_BATTERY_ALERT, false, false)),
        )
        val row = insightAction(status = InsightActionStatus.ONE_SHOT).copy(
            type = ActionType.ENABLE_HIGH_BATTERY_ALERT.name,
            appliedAt = 11,
        )
        val recommendation = finding.toInsightState(false, listOf(row), generatedAtMs = 10).recommendations.single()
        assertFalse("one-shot alert must not count as applied on an App finding", recommendation.alreadyApplied)
        assertTrue("one-shot alert must not block an App recommendation", recommendation.available)
    }

    @Test fun oneShotForceStopRemainsRepeatableAndUnavailableForUndo() {
        val finding = insightFinding().copy(
            recommendations = listOf(Recommendation(ActionType.FORCE_STOP, false, true)),
        )
        val row = insightAction(status = InsightActionStatus.ONE_SHOT).copy(
            type = ActionType.FORCE_STOP.name,
            appliedAt = 11,
        )
        val state = finding.toInsightState(true, listOf(row), generatedAtMs = 10)
        val recommendation = state.recommendations.single()
        assertFalse("one-shot force-stop must not count as a lasting fix", recommendation.alreadyApplied)
        assertTrue("force-stop must remain repeatable", recommendation.available)
        assertFalse("one-shot force-stop must not gain Undo", actionStates(listOf(row), listOf(state)).single().undoable)
    }

    @Test fun uncertainForceStopHasNoUndoOrAppliedBadgeAndAllowsDeliberateRetry() {
        val finding = insightFinding().copy(recommendations = listOf(Recommendation(ActionType.FORCE_STOP, false, true)))
        val row = insightAction(status = InsightActionStatus.UNKNOWN).copy(
            type = ActionType.FORCE_STOP.name, appliedAt = null, priorState = null, targetState = null,
        )
        val state = finding.toInsightState(true, listOf(row), generatedAtMs = 10)
        assertFalse(state.recommendations.single().alreadyApplied)
        assertTrue(state.recommendations.single().available)
        assertFalse(actionStates(listOf(row), listOf(state)).single().undoable)
    }

    @Test fun dozeRemovalAfterStoredReportIsAppliedAndUnavailable() {
        val finding = insightFinding().copy(
            type = FindingType.DOZE_WHITELISTED_DRAINER,
            recommendations = listOf(Recommendation(ActionType.REMOVE_DOZE_WHITELIST, true, true)),
        )
        val report = InsightReport(10, listOf(finding), finding)
        val row = insightAction().copy(
            type = ActionType.REMOVE_DOZE_WHITELIST.name,
            priorState = "PRESENT",
            targetState = "ABSENT",
            createdAt = report.generatedAtMs + 1,
            appliedAt = report.generatedAtMs + 2,
        )

        val recommendation = report.findings.single().toInsightState(privileged = true, listOf(row), report.generatedAtMs).recommendations.single()
        assertTrue("removal after the stored report must count as applied", recommendation.alreadyApplied)
        assertFalse("removal after the stored report must not be offered again", recommendation.available)
    }

    @Test fun unknownDozeRemovalUsesCreatedAtWhenAppliedAtIsMissing() {
        val finding = insightFinding().copy(
            type = FindingType.DOZE_WHITELISTED_DRAINER,
            recommendations = listOf(Recommendation(ActionType.REMOVE_DOZE_WHITELIST, true, true)),
        )
        val report = InsightReport(10, listOf(finding), finding)
        for (createdAt in listOf(9L, 10L, 11L)) {
            val row = insightAction(status = InsightActionStatus.UNKNOWN).copy(
                type = ActionType.REMOVE_DOZE_WHITELIST.name,
                createdAt = createdAt,
                appliedAt = null,
            )
            val recommendation = finding.toInsightState(true, listOf(row), report.generatedAtMs).recommendations.single()
            assertEquals("UNKNOWN removal uses creation time $createdAt", createdAt >= report.generatedAtMs, recommendation.alreadyApplied)
            assertEquals("only a newer report may offer removal again", createdAt < report.generatedAtMs, recommendation.available)
        }
    }

    @Test fun dozeRemovalAtReportTimeStillCountsAsApplied() {
        val finding = insightFinding().copy(
            type = FindingType.DOZE_WHITELISTED_DRAINER,
            recommendations = listOf(Recommendation(ActionType.REMOVE_DOZE_WHITELIST, true, true)),
        )
        val row = insightAction().copy(type = ActionType.REMOVE_DOZE_WHITELIST.name, appliedAt = 10)
        val recommendation = finding.toInsightState(true, listOf(row), generatedAtMs = 10).recommendations.single()
        assertTrue("equal timestamps do not prove removal stopped holding", recommendation.alreadyApplied)
        assertFalse(recommendation.available)
    }

    @Test fun liveDozeWhitelistFindingAllowsRemovalAgainDespiteUndoableRow() {
        val finding = insightFinding().copy(
            type = FindingType.DOZE_WHITELISTED_DRAINER,
            recommendations = listOf(Recommendation(ActionType.REMOVE_DOZE_WHITELIST, true, true)),
        )
        for (status in listOf(InsightActionStatus.APPLIED, InsightActionStatus.UNKNOWN)) {
            val row = insightAction(status = status).copy(
                type = ActionType.REMOVE_DOZE_WHITELIST.name,
                priorState = "PRESENT",
                targetState = "ABSENT",
            )

            val recommendation = finding.toInsightState(privileged = true, listOf(row), generatedAtMs = 10).recommendations.single()
            assertTrue("live whitelist must keep removal available despite $status", recommendation.available)
            assertFalse("live whitelist proves $status removal no longer holds", recommendation.alreadyApplied)

            val withoutPrivilege = finding.toInsightState(privileged = false, listOf(row), generatedAtMs = 10).recommendations.single()
            assertFalse("removal still requires privileged access", withoutPrivilege.available)
            assertFalse("lack of privilege must not claim removal is applied", withoutPrivilege.alreadyApplied)
        }
    }

    @Test fun ordinaryFindingsReofferDozeRemovalOnlyAfterOlderMatchingJournalWrites() {
        for (type in listOf(FindingType.APP_DRAIN_ANOMALY, FindingType.WAKEUP_STORM)) {
            val finding = insightFinding().copy(
                type = type,
                recommendations = listOf(Recommendation(ActionType.REMOVE_DOZE_WHITELIST, true, true)),
            )
            for (status in listOf(InsightActionStatus.APPLIED, InsightActionStatus.UNKNOWN)) {
                for (timestamp in listOf(9L, 10L, 11L)) {
                    for (appliedAt in listOf(timestamp, null)) {
                        val row = insightAction(status = status).copy(
                            type = ActionType.REMOVE_DOZE_WHITELIST.name,
                            createdAt = timestamp, appliedAt = appliedAt,
                        )
                        val recommendation = finding.toInsightState(true, listOf(row), 10).recommendations.single()
                        assertEquals("$type $status write at $timestamp/$appliedAt", timestamp < 10, recommendation.available)
                        assertEquals(timestamp >= 10, recommendation.alreadyApplied)
                        assertFalse("Privilege is still required", finding.toInsightState(false, listOf(row), 10)
                            .recommendations.single().available)
                    }
                }
            }
        }
    }

    @Test fun ordinaryDozeRemovalDoesNotSuppressAnotherPackageOrProfile() {
        for (type in listOf(FindingType.APP_DRAIN_ANOMALY, FindingType.WAKEUP_STORM)) {
            val finding = insightFinding().copy(
                type = type,
                recommendations = listOf(Recommendation(ActionType.REMOVE_DOZE_WHITELIST, true, true)),
            )
            val row = insightAction().copy(type = ActionType.REMOVE_DOZE_WHITELIST.name, appliedAt = 11)
            for (other in listOf(row.copy(packageName = "other.app"), row.copy(uid = row.uid!! + 100_000))) {
                val recommendation = finding.toInsightState(true, listOf(other), 10).recommendations.single()
                assertTrue("Other identity must not suppress $type", recommendation.available)
                assertFalse(recommendation.alreadyApplied)
            }
        }
    }

    @Test fun appliedBackgroundRestrictionRemainsAppliedAndUnavailable() {
        val finding = insightFinding().copy(
            recommendations = listOf(Recommendation(ActionType.RESTRICT_BACKGROUND, true, true)),
        )

        for (generatedAtMs in listOf(1L, 2L, 10L)) {
            val recommendation = finding.toInsightState(privileged = true, listOf(insightAction()), generatedAtMs).recommendations.single()
            assertFalse("applied background restriction must remain unavailable", recommendation.available)
            assertTrue("background restriction must retain applied state", recommendation.alreadyApplied)
        }
    }

    @Test fun establishedPrivilegeIsImmediateOnEveryCollectionWithoutWaitingForStaleProbe() = runTest {
        for (mode in listOf(ShellRunner.Mode.SHIZUKU, ShellRunner.Mode.ROOT)) {
            val probe = FakeProbe(mode)
            probe.shell.detectMode()
            probe.elapsedMs = 10_001L
            probe.pending = CompletableDeferred()
            val repository = repository(probe.shell)

            repeat(2) {
                assertEquals("established $mode must emit true first, never unknown", true, repository.privileged.first())
            }
            assertEquals("resubscription must not re-probe established access", 1, probe.calls)
            assertFalse("the suspended replacement probe was not needed", probe.started.isCompleted)
        }
    }

    @Test fun coldAccessStaysUnknownUntilProbeCompletes() = runTest {
        val probe = FakeProbe(ShellRunner.Mode.SHIZUKU).apply { pending = CompletableDeferred() }
        val repository = repository(probe.shell)
        val emissions = mutableListOf<Boolean?>()
        val collection = async(start = CoroutineStart.UNDISPATCHED) {
            repository.privileged.take(2).toList(emissions)
        }
        probe.started.await()
        assertEquals("pending detection must remain unknown, not false", listOf<Boolean?>(null), emissions)
        probe.pending?.complete(ShellRunner.Mode.SHIZUKU)
        assertEquals(listOf(null, true), collection.await())
        assertEquals(1, probe.calls)
    }

    @Test fun establishedAdbAccessIsImmediatelyUnprivileged() = runTest {
        val probe = FakeProbe(ShellRunner.Mode.ADB)
        probe.shell.detectMode()
        probe.elapsedMs = 10_001L
        probe.pending = CompletableDeferred()

        assertEquals("ADB is known but cannot execute privileged fixes", false, repository(probe.shell).privileged.first())
        assertEquals(1, probe.calls)
    }

    @Test fun establishedAccessKeepsFollowingRevocationWithoutReturningToUnknown() = runTest {
        val probe = FakeProbe(ShellRunner.Mode.SHIZUKU)
        probe.shell.detectMode()
        val repository = repository(probe.shell)
        val collection = async(start = CoroutineStart.UNDISPATCHED) {
            repository.privileged.take(2).toList()
        }

        probe.mode = ShellRunner.Mode.NONE
        probe.shell.detectMode(forceRefresh = true)
        assertEquals("revocation must publish false, not another loading state", listOf(true, false), collection.await())
    }

    @Test fun analyzeNowReprobesWithoutDumpAndUsesTheSameRecordedInputsAsRefresh() = runTest {
        val fixture = AnalysisFixture(this)
        fixture.output = emptyList()
        fixture.shell.detectMode()
        fixture.insights.refresh()
        fixture.adapter.analyzeNow()
        assertTrue("Analyze now must not issue BatteryStatsBinaryOutput or refresh the Apps cache", fixture.commands.isEmpty())
        assertEquals("Analyze now must force a fresh probe even within the mode-cache TTL", 2, fixture.probes)
        assertEquals("Analyze now must use the same InputsBuilder history, capacity, whitelist and settings", fixture.seen[0], fixture.seen[1])
        assertEquals("local", fixture.seen[1].sessions.single().id)
        assertEquals(10.0, fixture.seen[1].appSessions.single().powerMah!!, 0.0)
        assertEquals(setOf("com.example.app"), fixture.seen[1].dozeUserWhitelist)
        assertEquals(4_000_000L, fixture.seen[1].fullUah)
        assertTrue(fixture.seen[1].highBatteryAlertEnabled)
        assertTrue(fixture.seen[1].privileged)
    }

    @Test fun feedbackDoesNotWaitForAnalyzeNowAccessProbe() = runTest {
        val fixture = AnalysisFixture(this)
        fixture.insights.refresh()
        fixture.probeGate = CompletableDeferred()
        val refresh = async { fixture.adapter.analyzeNow() }
        fixture.probeStarted.await()
        val feedback = async { fixture.adapter.notAProblem(insightFinding().key) }
        runCurrent()
        try {
            assertTrue("Feedback must not wait for an access probe outside analysis", feedback.isCompleted)
            assertFalse(refresh.isCompleted)
            assertEquals(1.5, fixture.rows.value.single().feedbackMultiplier, 0.0)
        } finally {
            fixture.probeGate!!.complete(Unit)
        }
        feedback.await()
        refresh.await()
    }

    private class AnalysisFixture(scope: TestScope) {
        val commands = mutableListOf<String>()
        var probes = 0
        var probeGate: CompletableDeferred<Unit>? = null
        val probeStarted = CompletableDeferred<Unit>()
        val shell = ShellRunner(
            probeMode = {
                probes++
                probeGate?.let { probeStarted.complete(Unit); it.await() }
                ShellRunner.Mode.SHIZUKU
            },
            runShizuku = { command, _, _ ->
                commands += command
                com.akane.voltwise.battery.shizuku.ShizukuBridge.RunResult.Success("garbage")
            }, shizukuRunning = { true }, elapsedMs = { 0L },
        )
        val rows = kotlinx.coroutines.flow.MutableStateFlow<List<InsightFindingEntity>>(emptyList())
        var output = listOf(insightFinding())
        val seen = mutableListOf<com.akane.voltwise.battery.insights.model.InsightInputs>()
        private val dao = object : UnusedInsightDao() {
            override fun findings() = rows
            override suspend fun findingsOnce() = rows.value
            override suspend fun upsertFindings(list: List<InsightFindingEntity>) { rows.value = list }
            override fun actions() = flowOf(emptyList<InsightActionEntity>())
            override suspend fun actionsOnce() = emptyList<InsightActionEntity>()
        }
        private val sessions = object : UnusedSessionDao() {
            override fun filteredSessions(type: SessionType?, query: String, limit: Int) = flowOf(emptyList<ChargeSession>())
            override suspend fun closedSessionsBetween(from: Long, to: Long) = listOf(com.akane.voltwise.battery.insights.testSession())
            override fun capacityEstimates(limit: Int) = flowOf(emptyList<com.akane.voltwise.battery.data.db.CapacityEstimateRow>())
        }
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val insights = InsightRepository(
            sessions, object : UnusedDailySummaryDao() {
                override suspend fun range(fromDay: Long, toDay: Long) = emptyList<com.akane.voltwise.battery.data.db.DailySummary>()
            }, object : UnusedAppUsageDao() {
                override suspend fun usageRowsForSessions(sessionIds: List<String>) = listOf(com.akane.voltwise.battery.insights.testAppRow())
                override suspend fun sessionWakers(sessionIds: List<String>) = emptyList<com.akane.voltwise.battery.data.db.SessionDeviceWaker>()
            }, dao, scope.backgroundScope,
            Clock.fixed(java.time.Instant.ofEpochMilli(com.akane.voltwise.battery.insights.NOW), java.time.ZoneOffset.UTC),
            { shell.detectMode(); setOf("com.example.app") },
            { shell.access.value == ShellRunner.Mode.SHIZUKU }, FakeKeyValueStore(),
            maintenance = HistoryMaintenance(), capacityReading = { 2_000_000L to 50 },
            ioDispatcher = dispatcher, analyzeDispatcher = dispatcher,
            highBatteryAlertEnabled = { true },
            analyze = { seen += it; InsightReport(it.nowMs, output, output.firstOrNull()) },
        )
        private val actions = InsightActionRepository(dao, { error("unexpected action") }, object : TargetInspector {
            override val sdkInt = 37
            override fun installedUid(pkg: String, userId: Int): Int? = error("unexpected inspection")
            override fun packagesForUid(uid: Int): List<String> = error("unexpected inspection")
            override fun roleHolders(): Set<String> = error("unexpected inspection")
        }, { 100L }, {})
        val adapter = DefaultInsightsRepository(insights, actions, shell, sessions)
    }

    private fun TestScope.repository(shell: ShellRunner): DefaultInsightsRepository {
        val sessions = object : UnusedSessionDao() {
            override fun filteredSessions(type: SessionType?, query: String, limit: Int) = flowOf(emptyList<ChargeSession>())
        }
        val dao = object : UnusedInsightDao() {
            override fun findings() = flowOf(emptyList<InsightFindingEntity>())
            override suspend fun findingsOnce() = emptyList<InsightFindingEntity>()
            override fun actions() = flowOf(emptyList<InsightActionEntity>())
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val insights = InsightRepository(
            sessions, UnusedDailySummaryDao(), UnusedAppUsageDao(), dao,
            backgroundScope, Clock.systemUTC(), { null }, { false }, FakeKeyValueStore(),
            maintenance = HistoryMaintenance(), ioDispatcher = dispatcher, analyzeDispatcher = dispatcher,
        )
        val actions = InsightActionRepository(dao, { error("observation must not execute") }, object : TargetInspector {
            override val sdkInt = 37
            override fun installedUid(pkg: String, userId: Int): Int? = error("unexpected inspection")
            override fun packagesForUid(uid: Int): List<String> = error("unexpected inspection")
            override fun roleHolders(): Set<String> = error("unexpected inspection")
        }, { 0L }, {})
        return DefaultInsightsRepository(insights, actions, shell, sessions)
    }

    private class FakeProbe(var mode: ShellRunner.Mode) {
        var elapsedMs = 0L
        var calls = 0
        var pending: CompletableDeferred<ShellRunner.Mode>? = null
        val started = CompletableDeferred<Unit>()
        val shell = ShellRunner(
            probeMode = {
                calls++
                pending?.let {
                    started.complete(Unit)
                    it.await()
                } ?: mode
            },
            runShizuku = { _, _, _ -> error("unexpected shell call") },
            shizukuRunning = { false },
            elapsedMs = { elapsedMs },
        )
    }
}
