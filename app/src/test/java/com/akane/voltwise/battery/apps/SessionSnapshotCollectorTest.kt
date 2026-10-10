package com.akane.voltwise.battery.apps

import com.akane.voltwise.battery.apps.SessionSnapshotCollector.Companion.BASELINE_DEBOUNCE_MS
import com.akane.voltwise.battery.apps.SessionSnapshotCollector.Companion.END_DEBOUNCE_MS
import com.akane.voltwise.battery.apps.SessionSnapshotCollector.Companion.STARTUP_SWEEP_DELAY_MS
import com.akane.voltwise.battery.apps.SessionSnapshotCollector.Companion.SWEEP_DEBOUNCE_MS
import com.akane.voltwise.battery.data.PowerTransition
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.battery.util.BatteryStatsParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionSnapshotCollectorTest {
    private class FakeStats : AppStatsSource {
        val calls = mutableListOf<Boolean>()
        val results = ArrayDeque<AppStatsResult>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun snapshot(force: Boolean): AppStatsResult {
            calls += force
            gate?.await()
            return results.removeFirstOrNull() ?: AppStatsResult.Failed("unexpected dump")
        }
    }

    private class FakeStore : SessionSnapshotStore {
        class Row(val type: SessionType, var closed: Boolean, var status: AppUsageStatus?, var basis: AppUsageBasis? = null)

        val sessions = linkedMapOf<String, Row>()
        val open = MutableStateFlow<OpenSession?>(null)
        val baselines = mutableMapOf<String, AppUsageSnapshot>()
        val ends = mutableMapOf<String, AppUsageSnapshot>()
        val usage = mutableMapOf<String, List<AppUsageRow>>()
        var failWrites = false
        var statusGate: CompletableDeferred<Unit>? = null
        var endGate: CompletableDeferred<Unit>? = null
        val captureWindows = mutableMapOf<String, Pair<Long?, Long?>>()
        var openSessionError: Exception? = null
        var openSessionFailures = 0

        override fun openSession(): Flow<OpenSession?> = openSessionError?.let { error -> flow { throw error } } ?: flow {
            if (openSessionFailures > 0) {
                openSessionFailures--
                throw IllegalStateException("database is locked")
            }
            emitAll(open)
        }
        override suspend fun hasBaseline(sessionId: String) = sessionId in baselines
        override suspend fun baseline(sessionId: String) = baselines[sessionId]
        override suspend fun saveBaseline(sessionId: String, snapshot: AppUsageSnapshot): Boolean {
            if (sessionId !in sessions || sessionId in baselines) return false
            baselines[sessionId] = snapshot
            return true
        }
        override suspend fun saveEnd(sessionId: String, end: AppUsageSnapshot, result: AppUsageDeltaResult): Boolean {
            check(!failWrites) { "disk I/O error" }
            endGate?.await()
            val row = sessions[sessionId] ?: return false
            ends[sessionId] = end
            usage[sessionId] = result.rows
            row.status = AppUsageStatus.READY
            row.basis = result.basis
            captureWindows[sessionId] = result.captureStartMs to result.captureEndMs
            return true
        }
        override suspend fun setStatus(sessionId: String, status: AppUsageStatus): Boolean {
            check(!failWrites) { "disk I/O error" }
            statusGate?.await()
            val row = sessions[sessionId] ?: return false
            row.status = status
            return true
        }
        override suspend fun pendingClosedDischarges() = sessions.entries
            .filter { (_, row) -> row.type == SessionType.DISCHARGE && row.closed && row.status == AppUsageStatus.PENDING }
            .map { it.key }.reversed()

        fun openDischarge(id: String) {
            sessions[id] = Row(SessionType.DISCHARGE, closed = false, status = AppUsageStatus.PENDING)
            open.value = OpenSession(id, SessionType.DISCHARGE)
        }

        fun openCharge(id: String) {
            sessions[id] = Row(SessionType.CHARGE, closed = false, status = AppUsageStatus.NOT_APPLICABLE)
            open.value = OpenSession(id, SessionType.CHARGE)
        }

        fun status(id: String) = sessions.getValue(id).status
    }

    private val stats = FakeStats()
    private val store = FakeStore()
    private val transitions = MutableSharedFlow<PowerTransition>(extraBufferCapacity = 16)
    private val warnings = mutableListOf<String>()
    private val diagnostics = mutableListOf<DiagnosticCode>()

    private val collector = SessionSnapshotCollector(
        stats, store, transitions, log = {}, warn = { warnings += it }, onDiagnostic = { diagnostics += it },
    )
    private fun TestScope.start() {
        backgroundScope.launch { collector.run() }
        runCurrent()
    }

    private fun TestScope.plugIn(ended: String, started: String) {
        store.sessions.getValue(ended).closed = true
        store.openCharge(started)
        assertTrue(transitions.tryEmit(PowerTransition(PowerState.DISCHARGING, PowerState.CHARGING, 0, 0, ended, started)))
        runCurrent()
    }

    private fun TestScope.unplug(ended: String, started: String) {
        store.sessions.getValue(ended).closed = true
        store.openDischarge(started)
        assertTrue(transitions.tryEmit(PowerTransition(PowerState.CHARGING, PowerState.DISCHARGING, 0, 0, ended, started)))
        runCurrent()
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun full(windowStart: Long, vararg power: Pair<Int, Double>) = BatteryStatsParser.FullSnapshot(
        capturedAt = 1_000, startedAt = windowStart, startCount = 7, batteryRealtimeMs = 10, batteryUptimeMs = 5,
        apps = power.map { (uid, mah) -> BatteryStatsParser.AppPowerStats(uid, "app$uid", mah, listOf("app$uid")) },
    )

    private fun ready(windowStart: Long, vararg power: Pair<Int, Double>) = AppStatsResult.Ready(full(windowStart, *power))

    private fun baseline(windowStart: Long, vararg power: Pair<Int, Double>) = full(windowStart, *power).toAppUsageSnapshot()

    @Test fun incompleteBaselineRecordsAdvancedIncompleteOnceAndSavesNoBaseline() = runTest {
        assertIncompleteBaseline(full(100, 1 to 1.0).copy(appMeasurementsComplete = false))
    }

    @Test fun rejectedPowerBaselineRecordsAdvancedIncompleteOnceAndSavesNoBaseline() = runTest {
        assertIncompleteBaseline(full(100, 1 to 1.0).copy(rejectedAppPowerRecords = 1))
    }

    private suspend fun TestScope.assertIncompleteBaseline(snapshot: BatteryStatsParser.FullSnapshot) {
        store.openDischarge("A")
        stats.results += AppStatsResult.Ready(snapshot)
        start()
        advance(BASELINE_DEBOUNCE_MS)
        assertEquals(listOf(true), stats.calls)
        assertTrue("Incomplete measurements must not become a baseline", store.baselines.isEmpty())
        assertEquals("Records ADVANCED_INCOMPLETE once for the baseline skip",
            listOf(DiagnosticCode.ADVANCED_INCOMPLETE), diagnostics)
        advance(10 * BASELINE_DEBOUNCE_MS)
        assertEquals("No duplicate diagnostic without another dump", 1, diagnostics.size)
    }

    @Test fun incompleteEndRecordsAdvancedIncompleteAndStoresNoCaptureStart() = runTest {
        assertIncompleteEnd(full(100, 1 to 3.0).copy(capturedAt = 2_000, appMeasurementsComplete = false))
    }

    @Test fun rejectedPowerEndRecordsAdvancedIncompleteAndStoresNoCaptureStart() = runTest {
        assertIncompleteEnd(full(100, 1 to 3.0).copy(capturedAt = 2_000, rejectedAppPowerRecords = 1))
    }

    private suspend fun TestScope.assertIncompleteEnd(snapshot: BatteryStatsParser.FullSnapshot) {
        store.openDischarge("A")
        store.baselines["A"] = baseline(100, 1 to 1.0)
        stats.results += AppStatsResult.Ready(snapshot)
        start()
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertEquals(AppUsageStatus.READY, store.status("A"))
        assertNotNull("Accepted end evidence is retained", store.ends["A"])
        assertEquals(null to 2_000L, store.captureWindows["A"])
        assertEquals("Records ADVANCED_INCOMPLETE once for the non-comparable END",
            listOf(DiagnosticCode.ADVANCED_INCOMPLETE), diagnostics)
    }

    @Test fun completeBaselineAndEndRecordNoDiagnosticsAndKeepComparableCapture() = runTest {
        store.openDischarge("A")
        stats.results += ready(100, 1 to 1.0)
        start()
        advance(BASELINE_DEBOUNCE_MS)
        assertNotNull(store.baselines["A"])
        assertTrue("Complete baseline records no diagnostic", diagnostics.isEmpty())
        stats.results += AppStatsResult.Ready(full(100, 1 to 3.0).copy(capturedAt = 2_000))
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertEquals(1_000L to 2_000L, store.captureWindows["A"])
        assertTrue("Complete END records no diagnostic", diagnostics.isEmpty())
    }

    @Test fun plugInWaitsTenSecondsThenStoresTheEndAndTheDelta() = runTest {
        store.openDischarge("A")
        store.baselines["A"] = baseline(100, 1 to 1.0, 2 to 5.0)
        start()
        stats.results += ready(100, 1 to 3.0, 2 to 5.5)
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS - 1)
        assertTrue(stats.calls.isEmpty())
        advance(1)
        assertEquals("One forced dump", listOf(true), stats.calls)
        assertEquals(AppUsageStatus.READY, store.status("A"))
        assertEquals(AppUsageBasis.DELTA, store.sessions.getValue("A").basis)
        assertEquals(listOf(1 to 2.0, 2 to 0.5), store.usage.getValue("A").map { it.uid to it.powerMah })
        assertNotNull(store.ends["A"])
        assertEquals(AppUsageStatus.NOT_APPLICABLE, store.status("C"))
    }

    @Test fun unplugWithinTheDebounceCancelsTheEndAndTheSessionIsMarkedFailed() = runTest {
        store.openDischarge("A")
        start()
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS / 2)
        unplug("C", "B")
        assertEquals(AppUsageStatus.FAILED, store.status("A"))
        advance(END_DEBOUNCE_MS)
        assertTrue("The cancelled end never dumps", stats.calls.isEmpty())
        assertNull(store.ends["A"])
    }

    @Test fun noAccessAtPlugInMarksTheSessionNoAccess() = runTest {
        store.openDischarge("A")
        start()
        stats.results += AppStatsResult.NoAccess
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertEquals(AppUsageStatus.NO_ACCESS, store.status("A"))
        assertTrue(store.usage.isEmpty())
    }

    @Test fun aFailedDumpAtPlugInMarksTheSessionFailed() = runTest {
        store.openDischarge("A")
        start()
        stats.results += AppStatsResult.Failed("Helper protocol unavailable")
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertEquals(AppUsageStatus.FAILED, store.status("A"))
    }

    @Test fun withoutABaselineTheEndUsesAbsoluteValues() = runTest {
        store.openDischarge("A")
        start()
        stats.results += ready(100, 1 to 3.0)
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertEquals(AppUsageBasis.ABSOLUTE, store.sessions.getValue("A").basis)
        assertEquals(listOf(1 to 3.0), store.usage.getValue("A").map { it.uid to it.powerMah })
    }

    @Test fun aStatsWindowResetDuringTheSessionUsesTheEndValues() = runTest {
        store.openDischarge("A")
        store.baselines["A"] = baseline(100, 1 to 9.0)
        start()
        stats.results += ready(200, 1 to 2.0)
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertEquals(AppUsageBasis.WINDOW_RESET, store.sessions.getValue("A").basis)
        assertEquals(listOf(1 to 2.0), store.usage.getValue("A").map { it.uid to it.powerMah })
    }

    @Test fun aNewDischargeSessionGetsOneBaselineAfterThirtySeconds() = runTest {
        store.openCharge("C")
        start()
        stats.results += ready(100, 1 to 1.0)
        unplug("C", "B")
        advance(BASELINE_DEBOUNCE_MS - 1)
        assertTrue(stats.calls.isEmpty())
        advance(1)
        assertEquals(listOf(true), stats.calls)
        assertEquals(1.0, store.baselines.getValue("B").rows.single().powerMah, 0.0)
        assertEquals("The open session keeps its in-memory PENDING", AppUsageStatus.PENDING, store.status("B"))
        advance(10 * BASELINE_DEBOUNCE_MS)
        assertEquals("No second baseline, no periodic dumps", 1, stats.calls.size)
    }

    @Test fun aChangeOfOpenSessionBeforeThirtySecondsCancelsTheBaseline() = runTest {
        store.openCharge("C")
        start()
        unplug("C", "B")
        advance(BASELINE_DEBOUNCE_MS - 1_000)
        stats.results += AppStatsResult.NoAccess
        plugIn("B", "C2")
        advance(BASELINE_DEBOUNCE_MS)
        assertEquals("Only the plug-in's end dump ran", listOf(true), stats.calls)
        assertTrue(store.baselines.isEmpty())
        assertEquals(AppUsageStatus.NO_ACCESS, store.status("B"))
    }

    @Test fun startTakesABaselineForTheOpenDischargeSessionAndFailsAbandonedOnes() = runTest {
        store.sessions["stopped"] = FakeStore.Row(SessionType.DISCHARGE, closed = true, status = AppUsageStatus.PENDING)
        store.sessions["done"] = FakeStore.Row(SessionType.DISCHARGE, closed = true, status = AppUsageStatus.READY, AppUsageBasis.DELTA)
        store.sessions["charge"] = FakeStore.Row(SessionType.CHARGE, closed = true, status = AppUsageStatus.NOT_APPLICABLE)
        store.sessions["legacy"] = FakeStore.Row(SessionType.DISCHARGE, closed = true, status = null)
        store.openDischarge("B")
        start()
        stats.results += ready(100, 1 to 1.0)
        advance(STARTUP_SWEEP_DELAY_MS - 1)
        assertEquals(AppUsageStatus.PENDING, store.status("stopped"))
        advance(1)
        assertEquals(AppUsageStatus.FAILED, store.status("stopped"))
        assertEquals(AppUsageStatus.READY, store.status("done"))
        assertEquals(AppUsageStatus.NOT_APPLICABLE, store.status("charge"))
        assertNull("Legacy rows stay without a status", store.status("legacy"))
        assertEquals(AppUsageStatus.PENDING, store.status("B"))
        assertNotNull(store.baselines["B"])
    }

    @Test fun aResetThatReopensTheDischargeSessionFailsTheClosedOneWithoutATransition() = runTest {
        store.openDischarge("A")
        start()
        advance(STARTUP_SWEEP_DELAY_MS)
        // Reset: the repository closes A (no open session until the next capture), then opens B. No transition.
        store.sessions.getValue("A").closed = true
        store.open.value = null
        runCurrent()
        store.openDischarge("B")
        runCurrent()
        advance(SWEEP_DEBOUNCE_MS - 1)
        assertEquals(AppUsageStatus.PENDING, store.status("A"))
        advance(1)
        assertEquals(AppUsageStatus.FAILED, store.status("A"))
        assertEquals("The new open session is not swept", AppUsageStatus.PENDING, store.status("B"))
    }

    @Test fun aPlugInSeenAsAnOpenSessionChangeBeforeItsTransitionStillGetsItsEnd() = runTest {
        store.openDischarge("A")
        start()
        stats.results += ready(100, 1 to 3.0)
        // The open-session query answers before the transition arrives.
        store.sessions.getValue("A").closed = true
        store.openCharge("C")
        runCurrent()
        advance(1_000)
        assertTrue(transitions.tryEmit(PowerTransition(PowerState.DISCHARGING, PowerState.CHARGING, 0, 0, "A", "C")))
        runCurrent()
        advance(SWEEP_DEBOUNCE_MS)
        assertEquals("Reserved by its transition: the sweep leaves it", AppUsageStatus.PENDING, store.status("A"))
        advance(END_DEBOUNCE_MS)
        assertEquals(AppUsageStatus.READY, store.status("A"))
    }

    @Test fun aSessionThatAlreadyHasABaselineKeepsItAcrossARestart() = runTest {
        store.openDischarge("B")
        store.baselines["B"] = baseline(100, 1 to 1.0)
        start()
        advance(10 * BASELINE_DEBOUNCE_MS)
        assertTrue(stats.calls.isEmpty())
    }

    @Test fun anEndPastItsDebounceSurvivesALaterTransition() = runTest {
        store.openDischarge("A")
        start()
        stats.gate = CompletableDeferred()
        stats.results += ready(100, 1 to 3.0)
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertEquals(1, stats.calls.size)
        unplug("C", "B")
        assertEquals("The sweep leaves an end in progress alone", AppUsageStatus.PENDING, store.status("A"))
        stats.gate?.complete(Unit)
        runCurrent()
        assertEquals(AppUsageStatus.READY, store.status("A"))
    }

    @Test fun aStorageErrorLeavesThePendingSessionForTheSweepAndTheCollectorRunning() = runTest {
        store.openDischarge("A")
        start()
        store.failWrites = true
        stats.results += ready(100, 1 to 3.0)
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertEquals(AppUsageStatus.PENDING, store.status("A"))
        store.failWrites = false
        stats.results += ready(100, 1 to 4.0)
        unplug("C", "B")
        assertEquals(AppUsageStatus.FAILED, store.status("A"))
        advance(BASELINE_DEBOUNCE_MS)
        assertNotNull("Still collecting after the error", store.baselines["B"])
    }

    @Test fun aFailingOpenSessionQueryDoesNotStopEnds() = runTest {
        store.openSessionError = IllegalStateException("database is closed")
        store.openDischarge("A")
        start()
        stats.results += ready(100, 1 to 3.0)
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertEquals(AppUsageStatus.READY, store.status("A"))
    }

    @Test fun baselinesResumeAfterAnOpenSessionQueryError() = runTest {
        store.openSessionFailures = 1
        store.openDischarge("B")
        start()
        stats.results += ready(100, 1 to 1.0)
        assertEquals(1, warnings.count { "open session query failed" in it })
        advance(SessionSnapshotCollector.OPEN_SESSION_RETRY_MS - 1)
        advance(BASELINE_DEBOUNCE_MS)
        assertTrue("Still waiting for the retry", stats.calls.isEmpty())
        advance(1)
        assertEquals(listOf(true), stats.calls)
        assertNotNull(store.baselines["B"])
    }

    @Test fun guardedFailuresAreWarnings() = runTest {
        store.openDischarge("A")
        start()
        store.failWrites = true
        stats.results += ready(100, 1 to 3.0)
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertEquals(listOf("end failed (IllegalStateException: disk I/O error)"), warnings)
    }

    @Test fun onlyDischargeSessionsStartPending() {
        assertEquals(AppUsageStatus.PENDING, SessionSnapshotCollector.initialStatus(SessionType.DISCHARGE))
        listOf(SessionType.CHARGE, SessionType.PLUGGED, SessionType.UNKNOWN).forEach {
            assertEquals(AppUsageStatus.NOT_APPLICABLE, SessionSnapshotCollector.initialStatus(it))
        }
    }

    @Test fun readyFinalizationEmitsOnceAfterUsageAndCaptureWindowPersisted() = runTest {
        store.openDischarge("A")
        store.baselines["A"] = baseline(100, 1 to 1.0).copy(capturedAt = 123)
        val events = mutableListOf<String>()
        backgroundScope.launch { collector.finalizedSessions.collect { id ->
            assertEquals(AppUsageStatus.READY, store.status(id))
            assertEquals(123L to 456L, store.captureWindows[id])
            assertEquals(2.0, store.usage.getValue(id).single().powerMah, 0.0)
            events += id
        } }
        start()
        store.endGate = CompletableDeferred()
        stats.results += AppStatsResult.Ready(full(100, 1 to 3.0).copy(capturedAt = 456))
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertTrue(events.isEmpty())
        assertEquals(AppUsageStatus.PENDING, store.status("A"))
        assertFalse(store.captureWindows.containsKey("A"))
        store.endGate?.complete(Unit)
        runCurrent()
        assertEquals(listOf("A"), events)
        assertTrue(collector.finalizedSessions.replayCache.isEmpty())
        stats.results += AppStatsResult.Ready(full(100, 1 to 3.0).copy(capturedAt = 456))
        plugIn("A", "C2")
        advance(END_DEBOUNCE_MS)
        assertEquals(listOf("A"), events)
    }

    @Test fun noAccessFinalizationEmitsAfterStatusPersistence() = runTest {
        assertStatusFinalization(AppStatsResult.NoAccess, AppUsageStatus.NO_ACCESS)
    }

    @Test fun failedFinalizationEmitsAfterStatusPersistence() = runTest {
        assertStatusFinalization(AppStatsResult.Failed("no dump"), AppUsageStatus.FAILED)
    }

    private suspend fun TestScope.assertStatusFinalization(result: AppStatsResult, expected: AppUsageStatus) {
        store.openDischarge("A")
        val events = mutableListOf<String>()
        backgroundScope.launch { collector.finalizedSessions.collect { id ->
            assertEquals(expected, store.status(id))
            events += id
        } }
        start()
        store.statusGate = CompletableDeferred()
        stats.results += result
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertTrue(events.isEmpty())
        assertEquals(AppUsageStatus.PENDING, store.status("A"))
        store.statusGate?.complete(Unit)
        runCurrent()
        assertEquals(listOf("A"), events)
        advance(STARTUP_SWEEP_DELAY_MS)
        assertEquals(listOf("A"), events)
    }

    @Test fun failedPersistenceDoesNotEmitFinalization() = runTest {
        store.openDischarge("A")
        val events = mutableListOf<String>()
        backgroundScope.launch { collector.finalizedSessions.collect { events += it } }
        start()
        store.failWrites = true
        stats.results += ready(100, 1 to 3.0)
        plugIn("A", "C")
        advance(END_DEBOUNCE_MS)
        assertTrue(events.isEmpty())
        assertEquals(AppUsageStatus.PENDING, store.status("A"))
    }

    @Test fun deletedSessionDoesNotEmitFinalization() = runTest {
        store.openDischarge("A")
        val events = mutableListOf<String>()
        backgroundScope.launch { collector.finalizedSessions.collect { events += it } }
        start()
        stats.results += AppStatsResult.NoAccess
        plugIn("A", "C")
        store.sessions.remove("A")
        advance(END_DEBOUNCE_MS)
        assertTrue(events.isEmpty())
    }

    @Test fun rowsCollapseToOnePerUid() {
        val rows = listOf(
            AppUsageRow(1, "com.a", 1.0, cpuTimeMs = 10, wifiBytes = null),
            AppUsageRow(2, "com.b", 2.0),
            AppUsageRow(1, "com.a.extra", 0.5, cpuTimeMs = null, wifiBytes = 7),
        ).collapseByUid()
        assertEquals(listOf(1, 2), rows.map { it.uid })
        val merged = rows.first()
        assertEquals("com.a", merged.packageName)
        assertEquals(1.5, merged.powerMah, 1e-9)
        assertEquals(10L, merged.cpuTimeMs)
        assertEquals(7L, merged.wifiBytes)
        assertNull(merged.mobileBytes)
    }
}
