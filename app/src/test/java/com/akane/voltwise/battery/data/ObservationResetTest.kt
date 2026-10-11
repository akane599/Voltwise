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
