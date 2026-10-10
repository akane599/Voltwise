package com.akane.voltwise.viewmodel

import android.graphics.Bitmap
import com.akane.voltwise.battery.insights.model.*
import androidx.lifecycle.SavedStateHandle
import com.akane.voltwise.battery.apps.AppInfo
import com.akane.voltwise.battery.apps.AppInfoSource
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageRow
import com.akane.voltwise.battery.apps.AppUsageSnapshot
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.DesignCapacityReading
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.DailySummary
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.sampling.ChargerType
import com.akane.voltwise.battery.measurement.CalibrationState
import com.akane.voltwise.battery.measurement.CapacityConfidence
import com.akane.voltwise.battery.measurement.CurrentCalibration
import com.akane.voltwise.battery.measurement.CurrentUnit
import com.akane.voltwise.battery.measurement.DailySummaryAggregator
import com.akane.voltwise.battery.measurement.EtaBasis
import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.battery.service.MonitoringControl
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.ui.components.chart.TimeWindow
import java.time.ZoneOffset
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeNowRepository()
    private val monitoring = FakeMonitoring()
    private val appInfo = FakeAppInfo(mapOf(CHROME to "Chrome", YOUTUBE to "YouTube", MAPS to "Maps"))
    private var now = T0

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.start(saved: SavedStateHandle = SavedStateHandle()): Pair<NowViewModel, () -> NowUiState> {
        val vm = NowViewModel(
            repo, monitoring, appInfo, clock = { now }, zone = { ZoneOffset.UTC },
            computeDispatcher = dispatcher, savedStateHandle = saved,
        )
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        return vm to { vm.state.value }
    }

    @Test fun lowChargeDrainAndHealthPreferLocalCapacityAndFallBackToImportedOnly() = runTest {
        monitoring.isMonitoring.value = true
        repo.realtime.value = BatteryRepository.Realtime(sample(T0).copy(levelPercent = 40, chargeCounterUah = 1_200_000))
        repo.discharge.value = listOf(drainSession("open", endTime = null))
        val local = session("local", capacityMah = 5_000, confidence = "MEDIUM")
        val imported = List(3) { i -> session("import:old-$i", capacityMah = 3_000, confidence = "HIGH") }
        repo.sessions.value = listOf(local) + imported
        repo.design.value = DesignCapacityReading.Known(5_000_000, fromSettings = true)
        val (_, state) = start()

        assertEquals(5_000, state().health?.capacityMah)
        assertEquals(100.0, state().health!!.healthPercent!!, 1e-9)
        assertEquals(8.0, state().sinceUnplug!!.screenOn.percentPerHour!!, 1e-9)

        repo.sessions.value = imported
        runCurrent()
        assertEquals(3_000, state().health?.capacityMah)
        assertEquals(60.0, state().health!!.healthPercent!!, 1e-9)
        assertEquals(400_000 * 100.0 / 3_000_000, state().sinceUnplug!!.screenOn.percentPerHour!!, 1e-9)
    }

    @Test fun insightsDistinguishNotAnalyzedFromAllGoodAndIgnoreInformationalFindings() = runTest {
        repo.insights.value = InsightReport(0, emptyList(), null)
        val (_, state) = start()
        assertNull(state().insightsSummary)
        // Report timestamps are not proof of analysis; only lastAnalyzedAt is authoritative.
        repo.insights.value = InsightReport(T0, emptyList(), null)
        runCurrent()
        assertNull(state().insightsSummary)
        repo.insights.value = InsightReport(0, emptyList(), null)
        runCurrent()
        repo.lastAnalyzedAt.value = T0
        runCurrent()
        assertEquals(InsightsSummary(null, 0), state().insightsSummary)
        assertTrue(state().insightsSummary!!.allGood)
        repo.insights.value = InsightReport(T0, listOf(actionEffect), null)
        runCurrent()
        assertTrue(state().insightsSummary!!.allGood)
        assertEquals(0, state().insightsSummary!!.activeFindingCount)
    }

    @Test fun insightsMapHeadlineIdentityAndCountOnlyActiveNonInformationalFindings() = runTest {
        repo.lastAnalyzedAt.value = T0
        val headline = finding(Severity.HIGH)
        repo.insights.value = InsightReport(T0, listOf(headline, finding(Severity.LOW), actionEffect), headline)
        val (_, state) = start()
        assertEquals(InsightsSummary(InsightHeadline(headline.key, headline.type, Severity.HIGH, CHROME), 2), state().insightsSummary)
        assertFalse(state().insightsSummary!!.allGood)
        val device = headline.copy(subject = Subject.Device)
        repo.insights.value = InsightReport(T0, listOf(device), device)
        runCurrent()
        assertNull(state().insightsSummary!!.headline!!.packageName)
    }

    // R8-5: Insights says "still learning" below 5 comparable sessions; Now must not say "all good" meanwhile.
    @Test fun aQuietReportWithTooFewComparableSessionsIsLearningNotAllGood() = runTest {
        repo.insights.value = InsightReport(T0, emptyList(), null)
        repo.lastAnalyzedAt.value = 1
        repo.eligible.value = 1
        val (_, state) = start()
        assertFalse("1 of 5 comparable sessions is not 'all good'", state().insightsSummary!!.allGood)
        assertEquals(InsightsSummary(null, 0, learning = true), state().insightsSummary)
        // An action effect (informational, shown with its fix) is neither a finding nor a change here either.
        repo.insights.value = InsightReport(T0, listOf(actionEffect), null)
        runCurrent()
        assertTrue(state().insightsSummary!!.learning)
        assertFalse(state().insightsSummary!!.allGood)
    }

    @Test fun enoughComparableSessionsAndAQuietReportIsAllGood() = runTest {
        repo.insights.value = InsightReport(T0, emptyList(), null)
        repo.lastAnalyzedAt.value = 1
        repo.eligible.value = 1
        val (_, state) = start()
        repo.eligible.value = 5
        runCurrent()
        assertFalse(state().insightsSummary!!.learning)
        assertTrue(state().insightsSummary!!.allGood)
    }

    // R9-5: an app finding needs 4 history windows plus the current one, so 4 eligible windows is still learning.
    @Test fun fourComparableSessionsAreStillLearningUntilAFifthGivesAppsABaseline() = runTest {
        repo.insights.value = InsightReport(T0, emptyList(), null)
        repo.lastAnalyzedAt.value = 1
        repo.eligible.value = 4
        val (_, state) = start()
        assertTrue("4 windows leave only 3 history windows: no app finding is possible yet", state().insightsSummary!!.learning)
        assertFalse(state().insightsSummary!!.allGood)
        repo.eligible.value = 5
        runCurrent()
        assertFalse(state().insightsSummary!!.learning)
        assertTrue(state().insightsSummary!!.allGood)
    }

    // R9-6: Insights lists a directional trend under Changes; Now must not say "nothing is draining more than usual".
    @Test fun anInformationalDirectionalTrendIsAChangeToReviewNotAllGood() = runTest {
        val trend = finding(Severity.INFO).copy(key = "TREND:screen_off", type = FindingType.TREND, subject = Subject.Device)
        repo.insights.value = InsightReport(T0, listOf(trend), null)
        repo.lastAnalyzedAt.value = 1
        val (_, state) = start()
        assertFalse("Insights shows this trend under Changes", state().insightsSummary!!.allGood)
        assertEquals(InsightsSummary(null, 0, changeCount = 1), state().insightsSummary)
        // Insights' FINDINGS body (the Changes panel) wins over "still learning"; Now follows.
        repo.eligible.value = 1
        runCurrent()
        assertFalse(state().insightsSummary!!.learning)
        assertFalse(state().insightsSummary!!.allGood)
        repo.eligible.value = 5
        // Negative control: an informational trend without a direction is not listed by Insights, so Now stays all good.
        repo.insights.value = InsightReport(T0, listOf(trend.copy(direction = null)), null)
        runCurrent()
        assertTrue(state().insightsSummary!!.allGood)
    }

    // R10-4: the capacity decline is informational and directional, so Insights lists it under Changes; Now counts it.
    @Test fun anActiveHealthDeclineIsAChangeToReviewButAnActionEffectIsNot() = runTest {
        repo.insights.value = InsightReport(T0, listOf(healthDecline), null)
        repo.lastAnalyzedAt.value = 1
        repo.eligible.value = 5
        val (_, state) = start()
        assertFalse("a falling capacity is not 'nothing is draining more than usual'", state().insightsSummary!!.allGood)
        assertEquals(InsightsSummary(null, 0, changeCount = 1), state().insightsSummary)
        // It needs no app windows: without Shizuku or root the change still wins over "still learning".
        repo.eligible.value = 0
        runCurrent()
        assertEquals(InsightsSummary(null, 0, changeCount = 1), state().insightsSummary)
        // Negative control: an applied fix's measured effect is shown with the fix, never counted as a change.
        repo.eligible.value = 5
        repo.insights.value = InsightReport(T0, listOf(actionEffect), null)
        runCurrent()
        assertEquals(InsightsSummary(null, 0), state().insightsSummary)
        assertTrue(state().insightsSummary!!.allGood)
    }

    @Test fun anActiveFindingWinsOverLearning() = runTest {
        val headline = finding(Severity.HIGH)
        repo.insights.value = InsightReport(T0, listOf(headline), headline)
        repo.lastAnalyzedAt.value = 1
        repo.eligible.value = 1
        val (_, state) = start()
        assertEquals(InsightsSummary(InsightHeadline(headline.key, headline.type, Severity.HIGH, CHROME), 1), state().insightsSummary)
        assertFalse(state().insightsSummary!!.learning)
        assertFalse(state().insightsSummary!!.allGood)
    }

    private fun finding(severity: Severity) = Finding(
        key = "APP_DRAIN_ANOMALY:$CHROME:$severity", type = FindingType.APP_DRAIN_ANOMALY,
        severity = severity, confidence = Confidence.HIGH, score = 1.0,
        subject = Subject.App(10_001, CHROME), direction = Direction.UP,
        evidence = emptyList(), series = emptyList(), recommendations = emptyList(),
    )

    /** What ChargingHealth emits for a falling capacity: device-wide, INFO and DOWN, no baseline. */
    private val healthDecline = Finding(
        key = "HEALTH_DECLINE:device", type = FindingType.HEALTH_DECLINE,
        severity = Severity.INFO, confidence = Confidence.MEDIUM, score = 50.0,
        subject = Subject.Device, direction = Direction.DOWN,
        evidence = listOf(Evidence(Metric.CAPACITY_CHANGE_PCT_PER_YEAR, -6.0, null, MetricUnit.PCT_PER_YEAR, 6)),
        series = emptyList(), recommendations = emptyList(),
    )

    /** An applied fix's measured effect: INFO and directional, but listed with the fix under Applied fixes. */
    private val actionEffect = finding(Severity.INFO).copy(
        key = "ACTION_EFFECT:$CHROME:POWER_MAH_PER_H:7", type = FindingType.ACTION_EFFECT, direction = Direction.DOWN,
    )

    @Test fun heroAndReadoutsUseTheCalibratedReadingAndHoldTheEtaAcrossACaptureWithoutIt() = runTest {
        monitoring.isMonitoring.value = true
        val mA = CurrentCalibration(CurrentUnit.MILLIAMPS)
        repo.realtime.value = BatteryRepository.Realtime(sample(T0, raw = -412, eta = 5 * HOUR, basis = "LIVE_RATE"), mA)
        val (_, state) = start()

        with(state()) {
            assertTrue(hero.hasReading)
            assertEquals(67, hero.level)
            assertEquals(PowerState.DISCHARGING, hero.power)
            assertNull(hero.charger)
            assertEquals(Eta(5 * HOUR, EtaBasis.LIVE_RATE), hero.eta)
            // Raw -412 in mA units → calibrated -412 mA; power = I × V.
            assertEquals(-412.0, readouts.currentMa!!, 1e-9)
            assertEquals(-412.0 * 3.87 / 1_000, readouts.powerW!!, 1e-9)
            assertEquals(31.5, readouts.temperatureC!!, 1e-6)
            assertEquals(3.87, readouts.voltageV!!, 1e-9)
        }

        // The next raw capture has no ETA yet (the writer adds it just after): keep the last one, counted down.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 2 * SECOND, raw = -420), mA)
        runCurrent()
        assertEquals(Eta(5 * HOUR - 2 * SECOND, EtaBasis.LIVE_RATE), state().hero.eta)

        // Held for 60 s at most.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 61 * SECOND, raw = -420), mA)
        runCurrent()
        assertNull(state().hero.eta)

        // A basis this build doesn't know still shows the time, without a basis.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 62 * SECOND, eta = HOUR, basis = "FUTURE_MODEL"), mA)
        runCurrent()
        assertEquals(Eta(HOUR, null), state().hero.eta)

        // A power change drops a held estimate; the charger shows while plugged.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 64 * SECOND, status = 2, plugged = 1), mA)
        runCurrent()
        assertNull(state().hero.eta)
        assertEquals(PowerState.CHARGING, state().hero.power)
        assertEquals(ChargerType.AC, state().hero.charger)

        // Without monitoring, time to full still shows while charging: every capture carries Android's own.
        monitoring.isMonitoring.value = false
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 66 * SECOND, status = 2, plugged = 1, eta = HOUR, basis = "ANDROID"), mA)
        runCurrent()
        assertEquals(Eta(HOUR, EtaBasis.ANDROID), state().hero.eta)

        // Time left does not: the discharge estimate is the monitoring writer's.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 68 * SECOND, eta = 3 * HOUR, basis = "LIVE_RATE"), mA)
        runCurrent()
        assertNull(state().hero.eta)
    }

    @Test fun startBlockedShowsUntilTheNextAttemptAndStopStops() = runTest {
        repo.realtime.value = BatteryRepository.Realtime(sample(T0))
        val (vm, state) = start()
        monitoring.result = MonitoringControl.StartResult.BLOCKED

        vm.onEvent(NowEvent.ToggleMonitoring)
        runCurrent()
        assertEquals(1, monitoring.starts)
        assertTrue(state().hero.startBlocked)
        assertFalse(state().hero.monitoring)

        monitoring.result = MonitoringControl.StartResult.STARTED
        vm.onEvent(NowEvent.ToggleMonitoring)
        runCurrent()
        assertFalse(state().hero.startBlocked)
        assertTrue(state().hero.monitoring)

        vm.onEvent(NowEvent.ToggleMonitoring)
        runCurrent()
        assertEquals(1, monitoring.stops)
        assertFalse(state().hero.monitoring)
        // Navigation events are the screen's: the ViewModel ignores them.
        vm.onEvent(NowEvent.OpenApps)
        assertEquals(2, monitoring.starts)
    }

    @Test fun externalMonitoringStartClearsStaleStartBlockedNotice() = runTest {
        repo.realtime.value = BatteryRepository.Realtime(sample(T0))
        val (vm, state) = start()
        monitoring.result = MonitoringControl.StartResult.BLOCKED

        vm.onEvent(NowEvent.ToggleMonitoring)
        runCurrent()
        assertTrue(state().hero.startBlocked)

        monitoring.isMonitoring.value = true
        runCurrent()
        monitoring.isMonitoring.value = false
        runCurrent()
        assertFalse("a prior refusal must not return after monitoring stops elsewhere", state().hero.startBlocked)
    }

    @Test fun liveTraceIsSeededFromStoredRowsThenAppendedFromRealtimeTrimmedAndRecalibrated() = runTest {
        repo.calibration.value = CalibrationState(effective = CurrentCalibration(CurrentUnit.MILLIAMPS))
        repo.samples.value = listOf(
            sample(T0 - 12 * MINUTE, raw = -300),
            sample(T0 - 9 * MINUTE, raw = -400),
            sample(T0 - 8 * MINUTE, raw = -410),
        )
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 - 8 * MINUTE, raw = -410))
        val (_, state) = start()

        with(state().trace) {
            assertEquals(TraceRange.LIVE, range)
            assertEquals(T0 - 10 * MINUTE, repo.queries.single())
            assertEquals(listOf(T0 - 9 * MINUTE, T0 - 8 * MINUTE), points.map { it.timeMs })
            assertEquals(listOf(-400.0, -410.0), points.map { it.value })
            assertEquals(TimeWindow(T0 - 18 * MINUTE, T0 - 8 * MINUTE), window)
            assertEquals(NowMapping.LIVE_MAX_GAP_MS, maxGapMs)
        }

        repo.realtime.value = BatteryRepository.Realtime(sample(T0, raw = -500))
        runCurrent()
        assertEquals(-500.0, state().trace.points.last().value!!, 1e-9)
        assertEquals(TimeWindow(T0 - 10 * MINUTE, T0), state().trace.window)

        // Older or foreign captures are not appended; a new monitoring generation breaks the line.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 - SECOND, raw = -1))
        runCurrent()
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + SECOND, raw = -1, source = "import"))
        runCurrent()
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 2 * SECOND, raw = -520, observation = "gen-2"))
        runCurrent()
        val points = state().trace.points
        assertEquals(listOf(T0 - 9 * MINUTE, T0 - 8 * MINUTE, T0, T0 + SECOND, T0 + 2 * SECOND), points.map { it.timeMs })
        assertNull("gap marker between generations", points[3].value)

        // Ten minutes on, the seeded rows fell out of the window.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 9 * MINUTE + 30 * SECOND, raw = -450, observation = "gen-2"))
        runCurrent()
        assertTrue(state().trace.points.all { it.timeMs >= T0 - 30 * SECOND })

        // A calibration change recalibrates what is already on screen (rows stay raw).
        repo.calibration.value = CalibrationState()
        runCurrent()
        assertEquals(-0.45, state().trace.points.last().value!!, 1e-9)
    }

    @Test fun selectedTraceRangeSurvivesViewModelAndSavedStateRecreation() = runTest {
        val saved = SavedStateHandle()
        val (vm, state) = start(saved)
        vm.onEvent(NowEvent.SelectRange(TraceRange.DAY))
        runCurrent()
        assertEquals(TraceRange.DAY, state().trace.range)

        val (_, recreated) = start(saved)
        assertEquals("the shared saved handle restores the selected range", TraceRange.DAY, recreated().trace.range)

        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val (_, afterDeath) = start(restored)
        assertEquals("a restored saved handle retains the selected range", TraceRange.DAY, afterDeath().trace.range)
        assertEquals(T0 - 24 * HOUR, repo.queries.last())
        assertTrue("enum selections are saved by name", saved.keys().any { saved.get<Any?>(it) == TraceRange.DAY.name })
    }

    @Test fun historyRangesQueryStoredRowsForTheirSpanWithGapMarkers() = runTest {
        repo.samples.value = listOf(
            sample(T0 - 50 * MINUTE, raw = -100_000),
            sample(T0 - 20 * MINUTE, raw = -200_000, observation = "gen-2"),
            sample(T0 - 10 * MINUTE, raw = -300_000, observation = "gen-2", boundary = "Collection interrupted"),
            sample(T0 - 5 * MINUTE, raw = -400_000, observation = "gen-2", source = "import"),
        )
        val (vm, state) = start()
        vm.onEvent(NowEvent.SelectRange(TraceRange.HOUR))
        runCurrent()

        with(state().trace) {
            assertEquals(TraceRange.HOUR, range)
            assertEquals(T0 - HOUR, repo.queries.last())
            assertEquals(listOf(-100.0, null, -200.0, null, -300.0), points.map { it.value })
            assertEquals(TimeWindow(T0 - HOUR, T0), window)
            assertNull(maxGapMs)
        }
    }

    @Test fun aFullDayOfRowsIsDownsampledForTheChart() = runTest {
        repo.samples.value = List(6_000) { i -> sample(T0 - 24 * HOUR + i * 14 * SECOND, raw = -100_000 - (i % 97) * 1_000L) }
        val (vm, state) = start()
        vm.onEvent(NowEvent.SelectRange(TraceRange.DAY))
        runCurrent()

        val points = state().trace.points
        assertTrue("downsampled to ${points.size}", points.size <= NowMapping.DOWNSAMPLE_BUCKETS * 4)
        assertEquals(T0 - 24 * HOUR, points.first().timeMs)
        assertEquals(repo.samples.value.last().timestamp, points.last().timeMs)
        assertEquals(-196.0, points.minOf { it.value!! }, 1e-9)
    }

    @Test fun sinceUnplugIsTheOpenDischargeSessionRow() = runTest {
        monitoring.isMonitoring.value = true
        // Counter full charge from the latest reading: 2.8 Ah × 100 / 67 %.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0))
        repo.discharge.value = listOf(drainSession("open", endTime = null))
        val (_, state) = start()

        with(state().sinceUnplug!!) {
            assertTrue(current)
            assertEquals(T0 - 3 * HOUR, startedAtMs)
            assertEquals(T0 - MINUTE, endedAtMs)
            assertEquals(HOUR, screenOn.durationMs)
            assertEquals(400.0, screenOn.currentMa!!, 1e-9)
            assertEquals(400.0 * 100_000 / (2_800_000L * 100 / 67), screenOn.percentPerHour!!, 1e-9)
            assertEquals(100.0, screenOff.currentMa!!, 1e-9)
            assertEquals(50.0, deepSleepPercent!!, 1e-9)
        }
        assertEquals(T0, state().nowMs)

        // No usable counter (the emulator's 10 mAh): the combined capacity estimate stands in (5,000 mAh → 8 %/h).
        repo.sessions.value = listOf(session("h", capacityMah = 5_000, confidence = "HIGH"))
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + 2 * SECOND).copy(chargeCounterUah = 10_000))
        runCurrent()
        assertEquals(8.0, state().sinceUnplug!!.screenOn.percentPerHour!!, 1e-9)

        // Neither: mA only.
        repo.sessions.value = emptyList()
        runCurrent()
        assertNull(state().sinceUnplug!!.screenOn.percentPerHour)
        assertEquals(400.0, state().sinceUnplug!!.screenOn.currentMa!!, 1e-9)
    }

    @Test fun pluggedInItShowsTheLastClosedWindowAndNothingFromTheChargeSession() = runTest {
        monitoring.isMonitoring.value = true
        repo.realtime.value = BatteryRepository.Realtime(sample(T0, status = 2))
        repo.active.value = session("charge", type = SessionType.CHARGE)
        repo.discharge.value = listOf(drainSession("last", endTime = T0 - 20 * MINUTE))
        val (_, state) = start()

        with(state().sinceUnplug!!) {
            assertFalse(current)
            assertEquals(T0 - 3 * HOUR, startedAtMs)
            assertEquals(T0 - 20 * MINUTE, endedAtMs)
            assertEquals(400.0, screenOn.currentMa!!, 1e-9)
        }

        // A row left open by a stopped process is history too, ending at its last save.
        monitoring.isMonitoring.value = false
        repo.discharge.value = listOf(drainSession("stale", endTime = null))
        runCurrent()
        assertFalse(state().sinceUnplug!!.current)
        assertEquals(T0 - MINUTE, state().sinceUnplug!!.endedAtMs)

        // Never on battery yet: nothing, and no window borrowed from another session type.
        repo.discharge.value = emptyList()
        runCurrent()
        assertNull(state().sinceUnplug)
    }

    @Test fun resetStartsANewWindowFromTheNextSession() = runTest {
        monitoring.isMonitoring.value = true
        repo.realtime.value = BatteryRepository.Realtime(sample(T0))
        repo.discharge.value = listOf(drainSession("before", endTime = null))
        val (vm, state) = start()

        vm.onEvent(NowEvent.ResetObservation)
        assertEquals(1, repo.resets)
        // The repository closes the session ("Observation reset by user") and the next capture opens a new one.
        repo.discharge.value = listOf(drainSession("before", endTime = T0))
        runCurrent()
        assertFalse(state().sinceUnplug!!.current)
        repo.discharge.value = listOf(
            drainSession("after", endTime = null, startTime = T0 + 2 * SECOND, screenOnMs = 0, screenOffMs = 0, onUah = null, offUah = null),
            drainSession("before", endTime = T0),
        )
        runCurrent()
        with(state().sinceUnplug!!) {
            assertTrue(current)
            assertEquals(T0 + 2 * SECOND, startedAtMs)
            assertEquals(0L, screenOn.durationMs)
            assertNull(screenOn.currentMa)
        }
    }

    @Test fun resetIsIgnoredUnlessTheWindowIsCurrent() = runTest {
        // Plugged in while the confirmation was open: the open session is the CHARGE one, which Reset must not end.
        monitoring.isMonitoring.value = true
        repo.realtime.value = BatteryRepository.Realtime(sample(T0, status = 2))
        repo.active.value = session("charge", type = SessionType.CHARGE)
        repo.discharge.value = listOf(drainSession("last", endTime = T0 - 20 * MINUTE))
        val (vm, state) = start()
        assertFalse(state().sinceUnplug!!.current)

        vm.onEvent(NowEvent.ResetObservation)
        assertEquals(0, repo.resets)

        // Never on battery: nothing to reset either.
        repo.discharge.value = emptyList()
        runCurrent()
        vm.onEvent(NowEvent.ResetObservation)
        assertEquals(0, repo.resets)
    }

    @Test fun todayFollowsTheLocalDayAcrossMidnight() = runTest {
        val today = DailySummaryAggregator.epochDay(T0, ZoneOffset.UTC)
        repo.days.value = mapOf(
            today to DailySummary(today, screenOnMs = 7_800_000, screenOnDischargeUah = 800_000, screenOffDischargeUah = 440_000, chargedUah = 800_000),
        )
        repo.realtime.value = BatteryRepository.Realtime(sample(T0))
        val (_, state) = start()
        // The latest reading's counter gives the full capacity (2.8 Ah at 67 %), the same one Since unplug uses.
        val fullUah = 2_800_000L * 100 / 67
        assertEquals(
            TodayState(
                usedMah = 1_240.0,
                chargedMah = 800.0,
                screenOnMs = 7_800_000,
                usedPercent = 1_240_000 * 100.0 / fullUah,
                chargedPercent = 800_000 * 100.0 / fullUah,
            ),
            state().today,
        )

        now = (today + 1) * 24 * HOUR + SECOND
        repo.realtime.value = BatteryRepository.Realtime(sample(now))
        runCurrent()
        assertNull(state().today)
    }

    @Test fun healthCombinesStoredEstimatesAndUsesTheDesignOverride() = runTest {
        repo.sessions.value = listOf(
            session("a", capacityMah = 4_200, confidence = "MEDIUM"),
            session("b", capacityMah = 4_100, confidence = "HIGH"),
            session("c", capacityMah = 4_300, confidence = "LOW"),
            session("d", capacityMah = 9_999, confidence = "GARBAGE"),
            session("e", capacityMah = null, confidence = null),
        )
        val (_, state) = start()
        with(state().health!!) {
            assertEquals(4_100, capacityMah)
            assertEquals(CapacityConfidence.HIGH, confidence)
            assertNull(healthPercent)
        }

        repo.design.value = DesignCapacityReading.Known(5_000_000, fromSettings = true)
        runCurrent()
        assertEquals(82.0, state().health!!.healthPercent!!, 1e-9)

        // Auto design on a rooted phone: the shared source's sysfs value gives the same % as on the Health screen.
        repo.design.value = DesignCapacityReading.Known(4_100_000, fromSettings = false)
        runCurrent()
        assertEquals(100.0, state().health!!.healthPercent!!, 1e-9)
        repo.design.value = DesignCapacityReading.Checking
        runCurrent()
        assertNull(state().health!!.healthPercent)

        repo.sessions.value = emptyList()
        runCurrent()
        assertNull(state().health)
    }

    @Test fun topAppsComeOnlyFromTheCachedDumpSinceUnplugWhenNewerThanTheBaseline() = runTest {
        // The seam has no way to start a dump: Now only ever reads the cache.
        repo.cached.value = AppUsageSnapshot(100, 1, T0 - 5 * MINUTE, listOf(row(CHROME, 10_001, 130.0), row(YOUTUBE, 10_002, 60.0), row(MAPS, 10_003, 8.0), row(GMS, 10_004, 2.0)))
        repo.baselines["s1"] = AppUsageSnapshot(100, 1, T0 - 2 * HOUR, listOf(row(CHROME, 10_001, 6.0), row(YOUTUBE, 10_002, 0.0)))
        repo.active.value = session("s1", type = SessionType.DISCHARGE)
        val (_, state) = start()

        with(state().topApps as TopAppsState.Ready) {
            assertEquals(AppUsageBasis.DELTA, basis)
            assertEquals(T0 - 5 * MINUTE, capturedAtMs)
            assertEquals(listOf("Chrome", "YouTube", "Maps").map { AppLabel.Named(it) }, rows.map { it.label })
            assertEquals(listOf(124.0, 60.0, 8.0), rows.map { it.powerMah })
            // Shares are of all apps, including the ones not shown (194 mAh).
            assertEquals((124.0 / 194.0).toFloat(), rows.first().share, 1e-6f)
            assertEquals(10_001, rows.first().uid)
            assertEquals(CHROME, rows.first().packageName)
        }

        // A baseline newer than the dump means the dump predates this session: absolute values.
        repo.baselines["s2"] = AppUsageSnapshot(100, 1, T0 - MINUTE, emptyList())
        repo.active.value = session("s2", type = SessionType.DISCHARGE)
        runCurrent()
        assertEquals(AppUsageBasis.ABSOLUTE, (state().topApps as TopAppsState.Ready).basis)
        assertEquals(130.0, (state().topApps as TopAppsState.Ready).rows.first().powerMah, 1e-9)

        // Charging: no discharge session, absolute too.
        repo.active.value = session("c1", type = SessionType.CHARGE)
        runCurrent()
        assertEquals(AppUsageBasis.ABSOLUTE, (state().topApps as TopAppsState.Ready).basis)

        // Rows without an installed, labelled package never show a raw id.
        repo.cached.value = AppUsageSnapshot(100, 1, T0, listOf(row("System UID 1000", 1_000, 50.0), row("UID 10555", 10_555, 20.0)))
        runCurrent()
        assertEquals(listOf(AppLabel.SystemProcess, AppLabel.Unknown), (state().topApps as TopAppsState.Ready).rows.map { it.label })

        repo.cached.value = null
        runCurrent()
        assertEquals(TopAppsState.Empty, state().topApps)
    }

    @Test fun calibrationNoticeUndoKeepAndResetGoToTheRepository() = runTest {
        val detected = CurrentCalibration(CurrentUnit.MILLIAMPS)
        repo.calibration.value = CalibrationState(effective = detected, detected = detected, noticePending = true)
        val (vm, state) = start()
        assertEquals(detected, state().calibrationNotice)

        vm.onEvent(NowEvent.UndoCalibration)
        vm.onEvent(NowEvent.KeepCalibration)
        assertEquals(1, repo.undos)
        assertEquals(1, repo.dismissals)

        repo.calibration.value = CalibrationState(effective = detected, detected = detected)
        runCurrent()
        assertNull(state().calibrationNotice)
        assertNotNull(state().hero)
    }

    private class FakeNowRepository : NowRepository {
        override val realtime = MutableStateFlow(BatteryRepository.Realtime())
        override val calibration = MutableStateFlow(CalibrationState())
        override val settings = MutableStateFlow(AppSettings())
        override val insights = MutableStateFlow<InsightReport?>(null)
        override val lastAnalyzedAt = MutableStateFlow<Long?>(null)
        // Enough comparable sessions by default, so only the learning tests see "still learning".
        val eligible = MutableStateFlow(5)
        override val eligibleSessionCount: Flow<Int> = eligible
        override val design = MutableStateFlow<DesignCapacityReading>(DesignCapacityReading.Unknown)
        val cached = MutableStateFlow<AppUsageSnapshot?>(null)
        override val cachedAppUsage: Flow<AppUsageSnapshot?> = cached
        val active = MutableStateFlow<ChargeSession?>(null)
        override val activeSession: Flow<ChargeSession?> = active
        val samples = MutableStateFlow<List<BatterySample>>(emptyList())
        val queries = mutableListOf<Long>()
        val days = MutableStateFlow<Map<Long, DailySummary>>(emptyMap())
        val sessions = MutableStateFlow<List<ChargeSession>>(emptyList())
        val discharge = MutableStateFlow<List<ChargeSession>>(emptyList())
        val baselines = mutableMapOf<String, AppUsageSnapshot>()
        var resets = 0
        var undos = 0
        var dismissals = 0

        override fun samplesSince(fromMs: Long): Flow<List<BatterySample>> {
            queries += fromMs
            return samples.map { rows -> rows.filter { it.timestamp >= fromMs } }
        }

        override fun day(epochDay: Long): Flow<DailySummary?> = days.map { it[epochDay] }
        override fun recentSessions(limit: Int): Flow<List<ChargeSession>> = sessions.map { it.take(limit) }
        override fun dischargeSessions(limit: Int): Flow<List<ChargeSession>> = discharge.map { it.take(limit) }
        override suspend fun baseline(sessionId: String) = baselines[sessionId]
        override fun resetObservation() { resets++ }
        override fun undoCalibration() { undos++ }
        override fun dismissCalibrationNotice() { dismissals++ }
    }

    private class FakeMonitoring : MonitoringControl {
        override val isMonitoring = MutableStateFlow(false)
        override var monitoringWanted = true
        var result = MonitoringControl.StartResult.STARTED
        var starts = 0
        var stops = 0

        override fun start(): MonitoringControl.StartResult {
            starts++
            if (result != MonitoringControl.StartResult.BLOCKED) monitoringWanted = true
            if (result == MonitoringControl.StartResult.STARTED) isMonitoring.value = true
            return result
        }

        override fun stop() {
            stops++
            monitoringWanted = false
            isMonitoring.value = false
        }
    }

    private class FakeAppInfo(private val labels: Map<String, String>) : AppInfoSource {
        override suspend fun info(packageName: String) = AppInfo(packageName, labels[packageName] ?: packageName, isSystem = false, installed = true)
        override suspend fun icon(packageName: String): Bitmap? = null
    }

    private companion object {
        const val T0 = 1_760_001_600_000L
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val CHROME = "com.android.chrome"
        const val YOUTUBE = "com.google.android.youtube"
        const val MAPS = "com.google.android.apps.maps"
        const val GMS = "com.google.android.gms"

        // Status 3 = discharging, 2 = charging; plugged 0 = battery, 1 = AC.
        fun sample(
            timestamp: Long,
            raw: Long? = -412_000,
            status: Int = 3,
            plugged: Int = if (status == 2) 1 else 0,
            eta: Long? = null,
            basis: String? = null,
            observation: String = "gen-1",
            boundary: String? = null,
            source: String = "BatteryManager",
        ) = BatterySample(
            timestamp = timestamp,
            levelPercent = 67,
            status = status,
            plugged = plugged,
            currentNowUa = raw,
            chargeCounterUah = 2_800_000,
            voltageMv = 3_870,
            temperatureDeciC = 315,
            health = 2,
            screenOn = true,
            observationId = observation,
            etaMs = eta,
            etaBasis = basis,
            source = source,
            boundaryReason = boundary,
        )

        /** A DISCHARGE row as SessionReport writes it: 1 h on (400 mAh), 2 h off (200 mAh), fully covered, 50 % asleep. */
        fun drainSession(
            id: String,
            endTime: Long?,
            startTime: Long = T0 - 3 * HOUR,
            screenOnMs: Long = HOUR,
            screenOffMs: Long = 2 * HOUR,
            onUah: Long? = 400_000,
            offUah: Long? = 200_000,
        ) = ChargeSession(
            sessionId = id,
            type = SessionType.DISCHARGE,
            startTime = startTime,
            endTime = endTime,
            startLevel = 90,
            endLevel = 67,
            deltaUah = null,
            avgCurrentUa = null,
            estCapacityMah = null,
            lastSampleTime = T0 - MINUTE,
            observedMs = screenOnMs + screenOffMs,
            counterCoveredMs = screenOnMs + screenOffMs,
            screenOnMs = screenOnMs,
            screenOffMs = screenOffMs,
            screenOnUah = onUah,
            screenOffUah = offUah,
            cpuSuspendMs = (screenOnMs + screenOffMs) / 2,
        )

        fun session(
            id: String,
            type: SessionType = SessionType.DISCHARGE,
            capacityMah: Int? = null,
            confidence: String? = null,
        ) = ChargeSession(
            sessionId = id,
            type = type,
            startTime = T0 - 2 * HOUR,
            endTime = null,
            startLevel = 90,
            endLevel = 67,
            deltaUah = null,
            avgCurrentUa = null,
            estCapacityMah = null,
            capacityEstimateMah = capacityMah,
            capacityConfidence = confidence,
            capacityBasis = confidence?.let { "COUNTER_SPAN" },
        )

        fun row(packageName: String, uid: Int, mah: Double) = AppUsageRow(uid = uid, packageName = packageName, powerMah = mah)
    }
}
