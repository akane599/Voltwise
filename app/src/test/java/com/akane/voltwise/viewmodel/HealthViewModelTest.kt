package com.akane.voltwise.viewmodel

import com.akane.voltwise.battery.data.DesignCapacityReading
import com.akane.voltwise.battery.data.db.CapacityEstimateRow
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.measurement.CapacityConfidence
import com.akane.voltwise.battery.measurement.HealthSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeHealthRepository()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.start(): () -> HealthUiState {
        val vm = HealthViewModel(repo, computeDispatcher = dispatcher)
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        return { vm.state.value }
    }

    @Test fun capacityAndHealthAreNowsFiguresForTheSameSessions() = runTest {
        // 60 sessions, newest first: the combined figure reads only the newest 50, the trend all 60.
        val sessions = List(60) { i ->
            val confidence = when (i % 3) { 0 -> "HIGH"; 1 -> "MEDIUM"; else -> "LOW" }
            session("s$i", endTime = T0 - i * DAY, capacityMah = if (i < 50) 4_000 + (i % 7) * 40 else 3_000, confidence = confidence)
        }
        repo.sessions.value = sessions
        repo.design.value = DesignCapacityReading.Known(5_000_000, fromSettings = true)
        val state = start()

        val now = NowMapping.healthSummary(sessions.take(HealthSummary.SESSIONS), 5_000_000)?.let(NowMapping::health)
        with(state()) {
            assertTrue(loaded)
            assertEquals(listOf(HealthSummary.SESSIONS), repo.sessionLimits)
            assertEquals(listOf(HealthViewModel.TREND_SESSIONS), repo.trendLimits)
            assertEquals(now?.capacityMah, summary?.capacityMah)
            assertEquals(now?.confidence, summary?.confidence)
            assertEquals(now?.healthPercent, summary?.healthPercent)
            // The ten older 3,000 mAh sessions are in the trend but can't pull the figure down.
            assertEquals(60, estimates.size)
            assertEquals(3_000, estimates.first().capacityMah)
            assertTrue(summary!!.capacityMah >= 4_000)
            assertEquals(DesignCapacityState.Known(5_000, DesignSource.SETTINGS), design)
        }
    }

    @Test fun summaryPrefersLocalCapacityOverImportedHighConfidence() = runTest {
        val local = session("local", endTime = T0, capacityMah = 5_000, confidence = "MEDIUM")
        val imported = List(3) { i -> session("import:old-$i", endTime = T0 - (i + 1) * DAY, capacityMah = 3_000, confidence = "HIGH") }
        repo.sessions.value = listOf(local) + imported
        repo.design.value = DesignCapacityReading.Known(5_000_000, fromSettings = true)
        val state = start()

        assertEquals(5_000, state().summary?.capacityMah)
        assertEquals(100.0, state().summary!!.healthPercent!!, 1e-9)
        assertEquals(CapacityConfidence.MEDIUM, state().summary?.confidence)
    }

    @Test fun nowSummaryPrefersLocalCapacityOverImportedHighConfidence() {
        val local = session("local", endTime = T0, capacityMah = 5_000, confidence = "MEDIUM")
        val imported = List(3) { i -> session("import:old-$i", endTime = T0 - (i + 1) * DAY, capacityMah = 3_000, confidence = "HIGH") }
        val summary = NowMapping.healthSummary(listOf(local) + imported, 5_000_000)!!

        assertEquals(5_000, summary.estimate.fullMah)
        assertEquals(100.0, summary.healthPercent!!, 1e-9)
        assertEquals(CapacityConfidence.MEDIUM, summary.estimate.confidence)
    }

    @Test fun summariesStillUseImportedCapacityWithoutUsableLocalEstimates() = runTest {
        val imported = List(3) { i -> session("import:old-$i", endTime = T0 - i * DAY, capacityMah = 3_000, confidence = "HIGH") }
        repo.sessions.value = imported
        repo.design.value = DesignCapacityReading.Known(5_000_000, fromSettings = true)
        val state = start()

        assertEquals(3_000, state().summary?.capacityMah)
        assertEquals(60.0, state().summary!!.healthPercent!!, 1e-9)
        assertEquals(3_000, NowMapping.healthSummary(imported, 5_000_000)?.estimate?.fullMah)
    }

    @Test fun theSharedDesignCapacityShowsCheckingThenTheBatterysValue() = runTest {
        repo.sessions.value = listOf(session("a", endTime = T0, capacityMah = 4_500, confidence = "HIGH"))
        repo.design.value = DesignCapacityReading.Checking
        val state = start()

        with(state()) {
            assertEquals(DesignCapacityState.Checking, design)
            assertEquals(4_500, summary?.capacityMah)
            assertNull(summary?.healthPercent)
        }

        // sysfs answered (auto design, rooted): the same design Now's card uses.
        repo.design.value = DesignCapacityReading.Known(5_000_400, fromSettings = false)
        runCurrent()
        with(state()) {
            assertEquals(DesignCapacityState.Known(5_000, DesignSource.BATTERY), design)
            assertEquals(4_500_000 * 100.0 / 5_000_400, summary!!.healthPercent!!, 1e-9)
            assertEquals(
                NowMapping.healthSummary(repo.sessions.value, 5_000_400)?.healthPercent,
                summary?.healthPercent,
            )
        }

        // No root (or nothing plausible reported): unknown, and no health %.
        repo.design.value = DesignCapacityReading.Unknown
        runCurrent()
        with(state()) {
            assertEquals(DesignCapacityState.Unknown, design)
            assertNull(summary?.healthPercent)
        }
    }

    @Test fun noEstimatesYetStillShowsDesignAndCycles() = runTest {
        repo.sessions.value = listOf(
            session("plugged", endTime = T0, type = SessionType.PLUGGED),
            session("short", endTime = T0 - DAY),
            session("junk", endTime = T0 - 2 * DAY, capacityMah = 4_000, confidence = "SOMEDAY"),
        )
        repo.design.value = DesignCapacityReading.Known(5_000_000, fromSettings = true)
        repo.cycles = 312
        val state = start()

        with(state()) {
            assertTrue(loaded)
            assertNull(summary)
            assertTrue(estimates.isEmpty())
            assertEquals(DesignCapacityState.Known(5_000, DesignSource.SETTINGS), design)
            assertEquals(CycleCountState.Count(312), cycles)
        }
    }

    @Test fun cyclesAreHiddenBelowApi34() = runTest {
        repo.cyclesSupported = false
        repo.cycles = 99
        assertEquals(CycleCountState.Unsupported, start()().cycles)
    }

    @Test fun cyclesNotReportedOnApi34WithoutTheExtra() = runTest {
        repo.cycles = null
        assertEquals(CycleCountState.NotReported, start()().cycles)
    }

    @Test fun trendPointsAreOrderedByWhenEachSessionEnded() = runTest {
        repo.sessions.value = listOf(
            // Newest first by start time, as the query returns them; the open one is placed at its latest save.
            session("open", endTime = null, lastSample = T0, startTime = T0 - HOUR, capacityMah = 4_300, confidence = "LOW"),
            session("long", endTime = T0 - 2 * HOUR, startTime = T0 - 30 * HOUR, capacityMah = 4_250, confidence = "HIGH", type = SessionType.CHARGE),
            session("mid", endTime = T0 - 3 * HOUR, startTime = T0 - 10 * HOUR, capacityMah = 4_200, confidence = "MEDIUM"),
        )
        val state = start()

        with(state().estimates) {
            // "long" started first but ended after "mid": points sit where each estimate was measured.
            assertEquals(listOf("mid", "long", "open"), map { it.sessionId })
            assertEquals(listOf(T0 - 3 * HOUR, T0 - 2 * HOUR, T0), map { it.timeMs })
            assertEquals(CapacityPoint("long", T0 - 2 * HOUR, SessionType.CHARGE, 20, 80, 4_250, CapacityConfidence.HIGH), this[1])
            assertEquals(CapacityConfidence.LOW, last().confidence)
        }
    }

    @Test fun setDesignCapacityWritesTheOverrideAndAFailureShowsUntilTheNextWriteSucceeds() = runTest {
        val vm = HealthViewModel(repo, computeDispatcher = dispatcher)
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()

        vm.setDesignCapacity(4_800)
        runCurrent()
        assertEquals(listOf(4_800), repo.designWrites)
        assertEquals(false, vm.state.value.designWriteFailed)

        repo.failWrites = true
        vm.setDesignCapacity(5_000)
        runCurrent()
        assertTrue(vm.state.value.designWriteFailed)

        repo.failWrites = false
        vm.setDesignCapacity(0)
        runCurrent()
        assertEquals(listOf(4_800, 0), repo.designWrites)
        assertEquals(false, vm.state.value.designWriteFailed)
    }

    private class FakeHealthRepository : HealthRepository {
        override val design = MutableStateFlow<DesignCapacityReading>(DesignCapacityReading.Unknown)
        val sessions = MutableStateFlow<List<ChargeSession>>(emptyList())
        val sessionLimits = mutableListOf<Int>()
        val trendLimits = mutableListOf<Int>()
        override var cyclesSupported = true
        var cycles: Int? = null

        override fun recentSessions(limit: Int): Flow<List<ChargeSession>> {
            sessionLimits += limit
            return sessions.map { it.take(limit) }
        }

        // The projection query: sessions with an estimate only, newest first, bounded.
        override fun capacityEstimates(limit: Int): Flow<List<CapacityEstimateRow>> {
            trendLimits += limit
            return sessions.map { rows ->
                rows.mapNotNull { row ->
                    val mah = row.capacityEstimateMah ?: return@mapNotNull null
                    CapacityEstimateRow(
                        row.sessionId, row.type, row.startTime, row.endTime, row.lastSampleTime, row.startLevel, row.endLevel,
                        mah, row.capacityConfidence, row.capacityBasis,
                    )
                }.take(limit)
            }
        }

        override suspend fun cycleCount(): Int? = cycles

        val designWrites = mutableListOf<Int>()
        var failWrites = false

        override suspend fun setDesignCapacity(mAh: Int) {
            check(!failWrites) { "disk full" }
            designWrites += mAh
        }
    }

    private companion object {
        const val T0 = 1_760_001_600_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR

        fun session(
            id: String,
            endTime: Long?,
            type: SessionType = SessionType.DISCHARGE,
            startTime: Long = (endTime ?: T0) - 5 * HOUR,
            lastSample: Long? = endTime,
            capacityMah: Int? = null,
            confidence: String? = null,
        ) = ChargeSession(
            sessionId = id,
            type = type,
            startTime = startTime,
            endTime = endTime,
            startLevel = if (type == SessionType.CHARGE) 20 else 90,
            endLevel = if (type == SessionType.CHARGE) 80 else 40,
            deltaUah = null,
            avgCurrentUa = null,
            estCapacityMah = null,
            lastSampleTime = lastSample,
            capacityEstimateMah = capacityMah,
            capacityConfidence = confidence,
            capacityBasis = confidence?.let { "COUNTER_SPAN" },
        )
    }
}
