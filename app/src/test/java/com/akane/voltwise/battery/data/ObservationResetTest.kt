package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.measurement.DailySummaryAggregator
import com.akane.voltwise.battery.measurement.ObservationSummary
import java.time.ZoneOffset
import com.akane.voltwise.battery.measurement.Observation
import com.akane.voltwise.battery.measurement.ObservationEngine
import com.akane.voltwise.battery.measurement.PowerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObservationResetTest {
    @Test fun resetDoesNotApplyToChargeSession() {
        assertFalse("Reset must not close CHARGE or reset its ETA", resetApplies(open(SessionType.CHARGE)))
    }

    @Test fun resetDoesNotApplyToPluggedSession() {
        assertFalse("Reset must not close PLUGGED or reset observation state", resetApplies(open(SessionType.PLUGGED)))
    }

    @Test fun resetAppliesToDischargeSession() {
        assertTrue("Reset must close the current DISCHARGE window", resetApplies(open(SessionType.DISCHARGE)))
    }

    @Test fun resetDoesNotApplyToUnknownSession() {
        assertFalse("Only a DISCHARGE session can be reset", resetApplies(open(SessionType.UNKNOWN)))
    }

    @Test fun resetDoesNotApplyWithoutAnOpenSession() {
        assertFalse("Reset without an open session must be a no-op", resetApplies(null))
    }

    @Test fun resetAfterFailedStorageStopRetainsTheStoppedObservation() = runTest {
        val fixture = LifecycleFixture(open(SessionType.DISCHARGE))
        val writer = writer(fixture)
        writer.trySend(LifecycleEvent.STOP)
        runCurrent()
        val stopped = fixture.observation.value
        assertTrue(stopped.stopped)
        assertNotNull(stopped.startedAt)
        assertNull("Stop detaches the session before the failed close", fixture.session)
        assertEquals(1, fixture.failures.size)

        writer.trySend(LifecycleEvent.RESET)
        runCurrent()
        // The device test used to await startedAt == null here, although Reset no longer applies.
        assertEquals("Reset after Stop must retain the stopped observation", stopped,
            withTimeoutOrNull(60_000) { fixture.observation.first { it == stopped } })
        assertEquals("A stale Reset must not try storage again", 1, fixture.failures.size)
    }

    @Test fun activeDischargeResetClearsObservationBeforeStorageFailure() = runTest {
        val fixture = LifecycleFixture(open(SessionType.DISCHARGE))
        val writer = writer(fixture)
        writer.trySend(LifecycleEvent.RESET)
        runCurrent()
        assertNull(fixture.observation.value.startedAt)
        assertNull(fixture.observation.value.latest)
        assertEquals(0L, fixture.observation.value.observedMs)
        assertTrue(fixture.observation.value.stopped)
        assertNull(fixture.session)
        assertEquals(1, fixture.failures.size)
    }

    @Test fun failedRestartMustDiscardUnpersistedTailBeforeCrossMidnightCapture() = runTest {
        for (resetBeforeRead in listOf(false, true)) {
            val engine = ObservationEngine()
            fun point(time: Long, generation: String = "old") = Observation(
                wallMs = time, elapsedMs = time, uptimeMs = time, level = 80,
                chargeUah = 4_000_000, currentUa = -500_000, voltageMv = 4_000,
                power = PowerState.DISCHARGING, interactive = true, dozing = false, generation = generation,
            )
            val midnight = 86_400_000L
            engine.accept(point(midnight - 4_000))
            var persisted = engine.summary
            engine.accept(point(midnight - 2_000))
            assertEquals(2_000L, engine.summary.observedMs)
            engine.stop()
            var failures = 0
            val writer = HistoryWriter<Boolean>(backgroundScope, StandardTestDispatcher(testScheduler),
                HistoryMaintenance(), { null }, { false }, handleEvent = { start ->
                    if (start) {
                        // Android-free callback mirrors start's reset/read order; device test covers wiring.
                        if (resetBeforeRead) { engine.reset(); persisted = ObservationSummary() }
                        throw java.io.IOException("one-shot retention lookup")
                    } else {
                        engine.accept(point(midnight + 4_000, "new"))
                    }
                }, onFailure = { failures++ })
            writer.trySend(true)
            writer.trySend(false)
            runCurrent()
            assertEquals(1, failures)
            val interval = DailySummaryAggregator.interval(persisted, engine.summary, null)!!
            val days = DailySummaryAggregator.apply(emptyMap(), interval, ZoneOffset.UTC, midnight + 4_000)
            if (resetBeforeRead) {
                assertEquals(0L, engine.summary.observedMs)
                assertEquals(midnight + 4_000, engine.summary.startedAt)
                assertEquals(listOf(1L), days.map { it.epochDay })
                assertEquals(0L, days.sumOf { it.screenOnMs })
            } else {
                assertTrue("negative control: stale tail leaks into the stopped day", days.single { it.epochDay == 1L }.screenOnMs > 0)
            }
        }
    }

    @Test fun unsafeLatestSnapshotAdvancesPastTheFirstRecoveryAnchor() = runTest {
        val fixture = RecoveryCallbacks(this)
        fixture.restart()
        runCurrent() // Writer failure is acknowledged; initial sampler callback is still queued.
        assertEquals(1, fixture.failures)
        fixture.missing = false
        fixture.drainSampler() // Delayed initial read, then manual refresh: two NEW endpoints.
        fixture.read(10_001)
        runCurrent()
        assertEquals(0L, fixture.committed.first().observedMs)
        assertEquals(2, fixture.successfulReads)
        assertEquals("negative control: latest state has advanced beyond the first anchor", 1L, fixture.engine.summary.observedMs)
    }

    @Test fun missingReadBarrierCertifiesOneAnchorForWriterFirstAndSamplerFirst() = runTest {
        for (samplerFirst in listOf(false, true)) {
            val fixture = RecoveryCallbacks(this)
            fixture.restart()
            if (samplerFirst) fixture.drainSampler()
            runCurrent()
            assertEquals(1, fixture.failures)
            assertNull("reset precedes the failed retention lookup", fixture.engine.summary.startedAt)
            // Refresh behind the initial callback while reads are still missing.
            fixture.queueRefresh()
            fixture.drainSampler()
            assertEquals(2, fixture.missingReads)
            fixture.allowOne = true
            fixture.missing = false
            fixture.queueRefresh()
            fixture.drainSampler()
            fixture.read(10_001) // Later poll must be held, not accepted by the writer.
            runCurrent()
            val anchor = fixture.committed.single()
            assertEquals("exactly one successful recovery endpoint", 1, fixture.successfulReads)
            assertEquals(1, fixture.heldReads)
            assertEquals("first new capture is an anchor, not the previous run's tail", 0L, anchor.observedMs)
            assertEquals("new", anchor.latest!!.generation)
            assertEquals(10_000L, anchor.latest!!.elapsedMs)
            assertEquals(anchor, fixture.engine.summary)
        }
    }

    @Test fun equalTimeEndpointsNeedACountAsWellAsZeroDuration() = runTest {
        val fixture = RecoveryCallbacks(this)
        fixture.restart()
        runCurrent()
        fixture.drainSampler()
        fixture.missing = false
        fixture.read(10_000)
        fixture.read(10_000)
        runCurrent()
        assertEquals(0L, fixture.engine.summary.observedMs)
        assertEquals("zero duration alone cannot certify one endpoint", 2, fixture.successfulReads)
    }

    @Test fun timedOutRefreshReleasesPendingCallbackBeforeJoin() = runTest { pendingCallbackCleanup(cancel = false) }

    @Test fun cancelledRefreshReleasesPendingCallbackBeforeJoin() = runTest { pendingCallbackCleanup(cancel = true) }

    private suspend fun TestScope.pendingCallbackCleanup(cancel: Boolean) {
        val fixture = RecoveryCallbacks(this)
        fixture.restart()
        runCurrent()
        fixture.drainSampler()
        fixture.missing = false
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        // A synchronous Handler read is not cancelled when its coroutine refresh caller is cancelled.
        val callback = backgroundScope.async {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                fixture.read(10_000)
                order += "read returned"
            }
        }
        try {
            runCurrent()
            entered.await()
            if (cancel) callback.cancel()
            else assertNull(withTimeoutOrNull(1) { callback.await() })
            assertTrue("read is still pending", order.isEmpty())
        } finally {
            order += "gate released"
            release.complete(Unit)
            runCurrent()
            callback.join()
            order += "child joined"
        }
        runCurrent()
        assertEquals(listOf("gate released", "read returned", "child joined"), order)
        assertEquals(1, fixture.successfulReads)
        assertEquals(0L, fixture.committed.single().observedMs)
    }

    /** Deliberately scheduled Android-free callbacks, not a replay of an unknown Handler interleaving. */
    private class RecoveryCallbacks(scope: TestScope) {
        val engine = ObservationEngine().apply { accept(point(1_000, "old")); accept(point(3_000, "old")); stop() }
        val committed = mutableListOf<ObservationSummary>()
        var failures = 0
        var missing = true
        var allowOne = false
        var missingReads = 0
        var successfulReads = 0
        var heldReads = 0
        private val sampler = ArrayDeque<() -> Unit>()
        private val writer = HistoryWriter<Observation?>(scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), HistoryMaintenance(), { null }, { false },
            handleEvent = { point ->
                if (point == null) {
                    engine.reset()
                    throw java.io.IOException("one-shot retention lookup")
                }
                committed += engine.accept(point)
            }, onFailure = { failures++ })

        fun restart() { writer.trySend(null); queueRefresh() }
        fun queueRefresh() { sampler.addLast { read(10_000) } }
        fun drainSampler() { while (sampler.isNotEmpty()) sampler.removeFirst().invoke() }
        fun read(elapsed: Long) {
            if (missing) { missingReads++; return }
            if (allowOne && successfulReads == 1) { heldReads++; return }
            successfulReads++
            writer.trySend(point(elapsed, "new"))
        }
        private companion object {
            fun point(time: Long, generation: String) = Observation(time, time, time, 80,
                4_000_000, -500_000, 4_000, PowerState.DISCHARGING, true, false, generation)
        }
    }

    private fun TestScope.writer(fixture: LifecycleFixture) = HistoryWriter(
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        maintenance = HistoryMaintenance(),
        openSessionId = { fixture.session?.sessionId },
        deleteRow = { false },
        handleEvent = fixture::handle,
        onFailure = { fixture.failures += it },
    )

    private enum class LifecycleEvent { STOP, RESET }

    /** Android-free callbacks mirror BatteryRepository's Stop/Reset order; engine, guard and FIFO are real. */
    private class LifecycleFixture(var session: ChargeSession?) {
        private val engine = ObservationEngine().apply {
            accept(point(1_000))
            accept(point(2_000))
        }
        val observation = MutableStateFlow(engine.summary)
        val failures = mutableListOf<Exception>()

        fun handle(event: LifecycleEvent) {
            when (event) {
                LifecycleEvent.STOP -> {
                    engine.stop()
                    observation.value = engine.summary
                    finishSession()
                }
                LifecycleEvent.RESET -> if (resetApplies(session)) {
                    engine.reset()
                    observation.value = engine.summary
                    finishSession()
                }
            }
        }

        private fun finishSession() {
            val current = session
            session = null
            // Room's query scope can be cancelled without cancelling the repository writer.
            if (current != null) throw CancellationException("Storage closed")
        }

        private fun point(time: Long) = Observation(
            wallMs = time,
            elapsedMs = time,
            uptimeMs = time,
            level = 80,
            chargeUah = 4_000_000,
            currentUa = -500_000,
            voltageMv = 4_000,
            power = PowerState.DISCHARGING,
            interactive = true,
            dozing = false,
            generation = "fixture",
        )
    }

    private fun open(type: SessionType) = ChargeSession(
        sessionId = "open-session",
        type = type,
        startTime = 1_000L,
        endTime = null,
        startLevel = 50,
        endLevel = null,
        deltaUah = null,
        avgCurrentUa = null,
        estCapacityMah = null,
    )
}
