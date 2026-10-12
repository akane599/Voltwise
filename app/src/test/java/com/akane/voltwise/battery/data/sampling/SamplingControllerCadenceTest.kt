package com.akane.voltwise.battery.data.sampling

import com.akane.voltwise.battery.measurement.*
import org.junit.Assert.*
import org.junit.Test

class SamplingControllerCadenceTest {
    /** Fake handler queue/clock, using the controller's real token/cadence authority and sequencer. */
    private class Sampler {
        val queue = ArrayDeque<() -> Unit>()
        val demand = SamplerDemandState { queue.addLast { inputsChanged() } }
        val engine = ObservationEngine()
        private val sequencer = StateEventSequencer<Observation>()
        val points = mutableListOf<Observation>()
        var monitoring = true
        var screenOn = false
        var time = 0L
        var scheduled: Long? = null
        var afterStamp: (() -> Unit)? = null

        fun next() = SamplingPolicy.pollIntervalMs(monitoring, demand.held, screenOn)
        fun capture(boundary: Boundary = Boundary.SAMPLE) {
            val point = SamplingPolicy.stamp(
                Observation(1_000_000 + time, time, time, 80, 4_000_000 - time / 100,
                    -36_000, 4000, PowerState.DISCHARGING, screenOn, false, "one", boundary = boundary),
                demand.held,
            )
            if (monitoring) for (capture in sequencer.offer(point, point)) {
                points += capture.point
                engine.accept(capture.point)
            }
            afterStamp?.also { afterStamp = null; it() }
        }
        fun captureAndSchedule(boundary: Boundary = Boundary.SAMPLE) {
            capture(boundary)
            scheduled = next()
        }
        fun inputsChanged() {
            demand.synchronize()
            val next = next()
            if (SamplingPolicy.needsHandoffCapture(scheduled, next)) captureAndSchedule()
            else if (next == null) scheduled = null
        }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
    }

    @Test fun lastReleaseBetweenStampAndScheduleHandsOffImmediatelyWithoutFalseGap() {
        val sampler = Sampler()
        val token = sampler.demand.acquire("Now")
        sampler.drain()
        sampler.time = 2_000
        sampler.afterStamp = { token.close() }
        sampler.captureAndSchedule()
        assertEquals(2_000L, sampler.points.last().expectedIntervalMs)
        sampler.drain()
        assertEquals("queued last release must add an immediate 300s handoff", 3, sampler.points.size)
        assertEquals(300_000L, sampler.points.last().expectedIntervalMs)
        assertEquals(300_000L, sampler.scheduled)
        sampler.time += 300_000
        sampler.captureAndSchedule()
        assertEquals("300s CPU-awake wait must not fabricate a gap", 0, sampler.engine.summary.gaps)
        assertEquals(300_000L + 2_000, sampler.engine.summary.screenOff.durationMs)
        assertEquals(300_000L + 2_000, sampler.engine.summary.screenOff.chargeCoveredMs)
        assertNull(sampler.engine.summary.lastIssue)
    }

    @Test fun acquireBetweenStampAndScheduleQueuesAnImmediateRealtimeHandoff() {
        val sampler = Sampler()
        sampler.captureAndSchedule()
        var token: AutoCloseable? = null
        sampler.time = 300_000
        sampler.afterStamp = { token = sampler.demand.acquire("Now") }
        sampler.captureAndSchedule()
        assertEquals(300_000L, sampler.points.last().expectedIntervalMs)
        sampler.drain()
        assertEquals(3, sampler.points.size)
        assertEquals(2_000L, sampler.points.last().expectedIntervalMs)
        assertEquals(2_000L, sampler.scheduled)
        token!!.close()
        sampler.drain()
        assertEquals(300_000L, sampler.scheduled)
        assertEquals(0, sampler.engine.summary.gaps)
    }

    @Test fun screenEventKeepsItsEvidenceAndQueuedReleaseUsesTheNewScreenState() {
        for (screenOn in listOf(true, false)) {
            val sampler = Sampler()
            sampler.screenOn = !screenOn
            val token = sampler.demand.acquire("Now")
            sampler.drain()
            sampler.time = 2_000
            sampler.screenOn = screenOn
            sampler.afterStamp = { token.close() }
            sampler.captureAndSchedule(Boundary.SCREEN)
            sampler.drain()
            assertEquals(3, sampler.points.size)
            assertEquals(Boundary.SCREEN, sampler.points[1].boundary)
            val interval = if (screenOn) 30_000L else 300_000L
            assertEquals(interval, sampler.points.last().expectedIntervalMs)
            assertEquals(interval, sampler.scheduled)
            sampler.time += interval
            sampler.captureAndSchedule()
            assertEquals(0, sampler.engine.summary.gaps)
        }
    }

    @Test fun overlappingTokensAndRepeatedCloseOnlyNotifyOnEffectiveEdges() {
        val sampler = Sampler()
        val first = sampler.demand.acquire("Now")
        val second = sampler.demand.acquire("Details")
        assertEquals(1, sampler.queue.size)
        sampler.drain()
        first.close()
        first.close()
        assertTrue(sampler.queue.isEmpty())
        assertTrue(sampler.demand.held)
        second.close()
        second.close()
        assertEquals(1, sampler.queue.size)
        sampler.drain()
        assertFalse(sampler.demand.held)
        assertEquals(300_000L, sampler.scheduled)
    }

    @Test fun monitoringOffReleaseCancelsPollingAndNeverObserves() {
        val sampler = Sampler()
        sampler.monitoring = false
        val token = sampler.demand.acquire("Now")
        sampler.drain()
        assertEquals(2_000L, sampler.scheduled)
        sampler.afterStamp = { token.close() }
        sampler.captureAndSchedule()
        sampler.drain()
        assertNull(sampler.scheduled)
        assertTrue(sampler.points.isEmpty())
        assertNull(sampler.engine.summary.startedAt)
    }
}
