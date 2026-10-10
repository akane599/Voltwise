package com.akane.voltwise.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.insights.actions.ActionResult
import com.akane.voltwise.battery.insights.actions.RefusalCode
import com.akane.voltwise.battery.insights.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FindingDetailsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeInsightsRepository()
    private val results = InsightApplyResults()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    private fun TestScope.start(saved: SavedStateHandle = SavedStateHandle(mapOf("key" to "finding"))): FindingDetailsViewModel {
        val vm = FindingDetailsViewModel(source, backgroundScope, saved, results)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test fun mapsEvidenceSeriesBaselineAndPackageRelatedActions() = runTest {
        val finding = insightFinding()
        source.actions.value = listOf(
            insightAction(), insightAction(8, InsightActionStatus.UNKNOWN).copy(findingKey = "another.finding"),
            insightAction(9).copy(packageName = "other.app"),
            insightAction(10, InsightActionStatus.ONE_SHOT).copy(type = ActionType.FORCE_STOP.name),
        )
        val vm = start()
        val state = vm.state.value
        assertEquals(finding.evidence, state.finding?.evidence)
        assertEquals(finding.series, state.finding?.series)
        assertEquals(1.0, state.finding?.series?.single()?.baselineLow)
        assertEquals(3.0, state.finding?.series?.single()?.baselineHigh)
        assertEquals(listOf(7L, 8L, 10L), state.relatedActions.map { it.id })
        assertEquals(listOf(true, true, false), state.relatedActions.map { it.undoable })
        val restriction = state.finding?.recommendations?.first()
        assertEquals(true, restriction?.alreadyApplied)
        assertEquals(true, restriction?.reversible)
        assertEquals(false, restriction?.available)
    }

    @Test fun dozeRemovalAfterReportDisablesFixUntilNewerAnalysis() = runTest {
        val finding = insightFinding().copy(
            type = FindingType.DOZE_WHITELISTED_DRAINER,
            recommendations = listOf(Recommendation(ActionType.REMOVE_DOZE_WHITELIST, true, true)),
        )
        val report = InsightReport(10, listOf(finding), finding)
        source.report.value = report
        val vm = start()
        assertTrue(checkNotNull(vm.state.value.finding).recommendations.single().available)
        source.actions.value = listOf(insightAction().copy(
            type = ActionType.REMOVE_DOZE_WHITELIST.name, createdAt = 11, appliedAt = 12,
        ))
        runCurrent()

        val recommendation = checkNotNull(vm.state.value.finding).recommendations.single()
        assertTrue("details must show a post-report removal as applied", recommendation.alreadyApplied)
        assertFalse("details must not offer the same removal again", recommendation.available)
        assertTrue("the removal remains available to undo", vm.state.value.relatedActions.single().undoable)
        assertSame(report, source.report.value)

        source.report.value = report.copy(generatedAtMs = 13)
        runCurrent()
        val refreshed = checkNotNull(vm.state.value.finding).recommendations.single()
        assertTrue("a newer live whitelist finding permits removal again", refreshed.available)
        assertFalse(refreshed.alreadyApplied)
    }

    @Test fun relatedActionsMatchBothUidAndPackageAcrossProfiles() = runTest {
        val personal = insightAction()
        val work = insightAction(8).copy(uid = 1_010_042, userId = 10)
        val sameUidOtherPackage = insightAction(9).copy(packageName = "other.app")
        val unknownUid = insightAction(10).copy(uid = null)
        source.actions.value = listOf(personal, work, sameUidOtherPackage, unknownUid)
        val vm = start()
        assertEquals("personal finding excludes actions from another profile or without its uid", listOf(7L), vm.state.value.relatedActions.map { it.id })

        val workFinding = insightFinding().copy(subject = Subject.App(1_010_042, "example.app"))
        source.report.value = InsightReport(2, listOf(workFinding), workFinding)
        runCurrent()
        assertEquals("work finding excludes the personal-profile action", listOf(8L), vm.state.value.relatedActions.map { it.id })
    }

    @Test fun deviceFindingStillMatchesRelatedActionsByFindingKey() = runTest {
        val deviceFinding = insightFinding().copy(subject = Subject.Device)
        source.report.value = InsightReport(2, listOf(deviceFinding), deviceFinding)
        source.actions.value = listOf(insightAction(), insightAction(8).copy(findingKey = "another.finding"))
        val vm = start()
        assertEquals(listOf(7L), vm.state.value.relatedActions.map { it.id })
    }

    @Test fun notPrivilegedDisablesPrivilegedApplyAndKeepsManualPath() = runTest {
        source.privileged.value = false
        val vm = start()
        assertFalse(vm.state.value.privileged)
        assertEquals(listOf(false, true), vm.state.value.finding?.recommendations?.map { it.available })
        source.privileged.value = true
        runCurrent()
        assertEquals(listOf(true, true), vm.state.value.finding?.recommendations?.map { it.available })
source.actions.value = listOf(insightAction().copy(findingKey = "another.finding"))
        runCurrent()
        assertEquals(listOf(false, true), vm.state.value.finding?.recommendations?.map { it.available })
        assertEquals(true, vm.state.value.finding?.recommendations?.first()?.alreadyApplied)
    }

    @Test fun requestAndRestoredDialogNeverApplyWithoutConfirm() = runTest {
        val saved = SavedStateHandle(mapOf("key" to "finding"))
        val original = start(saved)
        original.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        runCurrent()
        assertEquals("details RequestApply must never call apply", 0, source.applied.size)
        val restored = start(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(original.state.value.apply.pending, restored.state.value.apply.pending)
        assertNotNull(restored.state.value.finding)
        assertTrue(source.applied.isEmpty())
        restored.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals(1, source.applied.size)
        assertEquals(ActionType.RESTRICT_BACKGROUND, source.applied.single().second.action)
        assertNull(restored.state.value.apply.pending)
    }

    @Test fun restoredPendingSurvivesUnknownPrivilegeUntilDetectionCompletes() = runTest {
        source.report.value = null
        source.privileged.value = null
        val request = PendingInsightApply("finding", ActionType.RESTRICT_BACKGROUND)
        val saved = SavedStateHandle(mapOf(
            "key" to request.key,
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
        assertTrue("restoration and detection must never apply", source.applied.isEmpty())
        source.privileged.value = false
        runCurrent()
        assertNull("confirmed access loss must still invalidate pending", vm.state.value.apply.pending)
        assertNull(saved.get<String>("insights.pending.key"))
    }

    @Test fun undoUsesSharedRefusalMappingAndChangedOutsideResult() = runTest {
        val vm = start()
        val effects = mutableListOf<InsightUiEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        source.result = ActionResult.Refused(RefusalCode.ALREADY_AT_TARGET)
        vm.onEvent(InsightsEvent.Undo(7))
        runCurrent()
        assertEquals(InsightMessageCode.ALREADY_AT_TARGET, vm.state.value.apply.lastResult?.code)
        source.result = ActionResult.ChangedExternally("not user-facing")
        vm.onEvent(InsightsEvent.Undo(8))
        runCurrent()
        assertEquals(listOf(7L, 8L), source.undone)
        assertEquals(InsightMessageCode.CHANGED_EXTERNALLY, (effects.last() as InsightUiEffect.Message).result.code)
    }

    @Test fun missingFindingHasNoInventedEvidenceAndNoApply() = runTest {
        val vm = start(SavedStateHandle(mapOf("key" to "missing")))
        assertNull(vm.state.value.finding)
        vm.onEvent(InsightsEvent.RequestApply("missing", ActionType.RESTRICT_BACKGROUND))
        vm.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertTrue(source.applied.isEmpty())
        assertEquals(InsightMessageCode.FINDING_UNAVAILABLE, vm.state.value.apply.lastResult?.code)
    }

    @Test fun findingSnapshotsCannotBeMutatedThroughSourceOrUiLists() = runTest {
        val evidence = insightFinding().evidence.toMutableList()
        val series = insightFinding().series.toMutableList()
        source.report.value = InsightReport(1, listOf(insightFinding().copy(evidence = evidence, series = series)), null)
        val vm = start()
        val snapshot = vm.state.value.finding ?: error("missing finding")
        evidence.clear()
        series.clear()
        assertEquals(1, snapshot.evidence.size)
        assertEquals(1, snapshot.series.size)
        try {
            (snapshot.evidence as MutableList<Evidence>).clear()
            fail("evidence must reject mutation")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun dismissAndNotAProblemReachTheSourceEvenAfterTheScreenIsCleared() = runTest {
        val vm = start()
        val store = ViewModelStore().apply { put("vm", vm) }
        vm.onEvent(InsightsEvent.Dismiss("finding"))
        vm.onEvent(InsightsEvent.NotAProblem("finding"))
        store.clear() // Leaving the screen must not cancel feedback already sent.
        runCurrent()
        assertEquals("details Dismiss must reach the source", listOf("finding"), source.dismissed)
        assertEquals("details Not a problem must reach the source", listOf("finding"), source.feedback)
        assertTrue(source.applied.isEmpty())
    }

    @Test fun failedFeedbackIsAFixedCodeMessage() = runTest {
        val failing = object : InsightsRepository by source {
            override suspend fun dismiss(key: String) = throw IllegalStateException("not user-facing")
            override suspend fun notAProblem(key: String) = throw IllegalStateException("not user-facing")
        }
        val vm = FindingDetailsViewModel(failing, backgroundScope, SavedStateHandle(mapOf("key" to "finding")), results)
        val effects = mutableListOf<InsightUiEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.onEvent(InsightsEvent.Dismiss("finding"))
        vm.onEvent(InsightsEvent.NotAProblem("finding"))
        runCurrent()
        assertEquals(
            listOf(InsightMessageCode.FEEDBACK_FAILED, InsightMessageCode.FEEDBACK_FAILED),
            effects.map { (it as InsightUiEffect.Message).result.code },
        )
    }

    @Test fun disappearingFindingClearsSavedPendingAndDoesNotResurface() = runTest {
        val saved = SavedStateHandle(mapOf("key" to "finding"))
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
        val saved = SavedStateHandle(mapOf("key" to "finding"))
        val vm = FindingDetailsViewModel(source, backgroundScope, saved, results)
        vm.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        runCurrent()
        assertNotNull(saved.get<String>("insights.pending.key"))
        source.report.value = InsightReport(2, emptyList(), null)
        runCurrent()
        assertNull("saved pending must clear without a UI subscriber", saved.get<String>("insights.pending.key"))
        source.report.value = InsightReport(3, listOf(insightFinding()), null)
        assertNull(start(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })).state.value.apply.pending)
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

    @Test fun latestUnconsumedResultSurvivesRecreationAndConsumptionIsShared() = runTest {
        val saved = SavedStateHandle(mapOf("key" to "finding"))
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

    @Test fun refusedApplyAfterDetailsIsClearedIsShownOnceOnInsights() = runTest {
        source.actionGate = kotlinx.coroutines.CompletableDeferred()
        source.result = ActionResult.Refused(RefusalCode.PROTECTED)
        val details = start()
        val store = ViewModelStore().apply { put("details", details) }
        details.onEvent(InsightsEvent.RequestApply("finding", ActionType.RESTRICT_BACKGROUND))
        details.onEvent(InsightsEvent.ConfirmApply)
        runCurrent()
        assertEquals("apply must start before leaving details", 1, source.applied.size)
        assertEquals(0, source.completedActions)
        store.clear()
        source.actionGate?.complete(Unit)
        runCurrent()
        assertEquals("application-scoped apply must finish after details is cleared", 1, source.completedActions)
        assertTrue("a refusal has no journal row to communicate its outcome", source.actions.value.isEmpty())

        val insights = InsightsViewModel(source, backgroundScope, applyResults = results)
        backgroundScope.launch { insights.state.collect {} }
        runCurrent()
        assertEquals("Insights must show the refusal from the popped details screen",
            InsightActionMessage(InsightMessageCode.PROTECTED), insights.state.value.apply.lastResult?.copy(seq = 0))
        insights.onEvent(InsightsEvent.ResultShown(checkNotNull(insights.state.value.apply.lastResult)))
        runCurrent()
        assertNull("ResultShown must consume the shared result", insights.state.value.apply.lastResult)
        val fresh = InsightsViewModel(source, backgroundScope, applyResults = results)
        backgroundScope.launch { fresh.state.collect {} }
        runCurrent()
        assertNull("a consumed result must not show on another Insights entry", fresh.state.value.apply.lastResult)
        assertNull("a consumed result must not show on another details entry", start().state.value.apply.lastResult)
        assertEquals("observing or consuming must not replay apply", 1, source.applied.size)
    }

    @Test fun loadedWaitsForNonNullReportAndActions() = runTest {
        source.report.value = null
        val actions = kotlinx.coroutines.flow.MutableSharedFlow<List<com.akane.voltwise.battery.data.db.InsightActionEntity>>(replay = 1)
        val delayed = object : InsightsRepository by source { override val actions = actions }
        val vm = FindingDetailsViewModel(delayed, backgroundScope, SavedStateHandle(mapOf("key" to "finding")), results)
        assertFalse(vm.state.value.loaded)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertFalse(vm.state.value.loaded)
        actions.emit(emptyList())
        runCurrent()
        assertFalse("a null report remains loading after actions emit", vm.state.value.loaded)
        assertNull(vm.state.value.finding)
        source.report.value = InsightReport(2, emptyList(), null)
        runCurrent()
        assertTrue("a published report with a missing finding is loaded", vm.state.value.loaded)
        assertNull(vm.state.value.finding)
    }

    @Test fun deviceActionsAreRelatedByFindingNotNullPackage() = runTest {
        val device = insightFinding().copy(subject = Subject.Device)
        source.report.value = InsightReport(1, listOf(device), device)
        source.actions.value = listOf(insightAction().copy(packageName = null), insightAction(8).copy(packageName = null, findingKey = "other"))
        val vm = start()
        assertEquals(listOf(7L), vm.state.value.relatedActions.map { it.id })
    }
}
