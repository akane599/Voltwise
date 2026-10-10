package com.akane.voltwise.viewmodel

import android.graphics.Bitmap
import com.akane.voltwise.battery.apps.AppInfo
import com.akane.voltwise.battery.apps.AppInfoSource
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageRow
import com.akane.voltwise.battery.apps.AppUsageSnapshot
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionAppUsage
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.sampling.ChargerType
import com.akane.voltwise.battery.measurement.CapacityConfidence
import com.akane.voltwise.battery.measurement.CurrentCalibration
import com.akane.voltwise.battery.measurement.CurrentUnit
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.ui.components.chart.TimeWindow
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeSessionDetailsRepository()
    private val appInfo = FakeAppInfo(mapOf(CHROME to "Chrome", YOUTUBE to "YouTube"))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.start(): Pair<SessionDetailsViewModel, () -> SessionDetailsUiState> {
        val vm = SessionDetailsViewModel(repo, appInfo, ID, computeDispatcher = dispatcher)
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        return vm to { vm.state.value }
    }

    private fun (() -> SessionDetailsUiState).ready(): SessionDetailsUiState.Ready = this() as SessionDetailsUiState.Ready

    @Test fun lowChargeDrainPrefersLocalCapacityAndFallsBackToImportedOnly() = runTest {
        repo.row.value = discharge().copy(counterCoveredMs = 3 * HOUR)
        repo.rows.value = listOf(sample(T0 + 3 * HOUR, level = 40, counter = 1_200_000))
        val local = discharge().copy(sessionId = "local", capacityEstimateMah = 5_000, capacityConfidence = "MEDIUM")
        val imported = List(3) { i -> discharge().copy(sessionId = "import:old-$i", capacityEstimateMah = 3_000) }
        repo.sessions.value = listOf(local) + imported
        val (_, state) = start()

        assertEquals(8.0, state.ready().summary.percentPerHour!!, 1e-9)
        assertEquals(8.0, (state.ready().insights as SessionInsights.Drain).screenOn.percentPerHour!!, 1e-9)

        repo.sessions.value = imported
        runCurrent()
        assertEquals(400_000 * 100.0 / 3_000_000, state.ready().summary.percentPerHour!!, 1e-9)
        assertEquals(400_000 * 100.0 / 3_000_000, (state.ready().insights as SessionInsights.Drain).screenOn.percentPerHour!!, 1e-9)
    }

    @Test fun dischargeHeaderChartsAndDrainComeFromTheRowAndCalibratedReadings() = runTest {
        repo.row.value = discharge()
        repo.calibration.value = CurrentCalibration(CurrentUnit.MILLIAMPS)
        repo.rows.value = listOf(
            sample(T0, level = 90, raw = -400, temperature = 300),
            sample(T0 + HOUR, level = 80, raw = -500, temperature = 320),
            // Monitoring restarted: a gap marker between the two observations.
            sample(T0 + 2 * HOUR, level = 70, raw = null, observation = "gen-2"),
            // An interruption recorded on the row: another marker.
            sample(T0 + 3 * HOUR, level = 60, raw = -100, observation = "gen-2", boundary = "gap", counter = 2_400_000),
        )
        val (_, state) = start()

        with(state.ready()) {
            assertEquals(SessionType.DISCHARGE, summary.type)
            assertFalse(summary.recording)
            assertEquals(T0, summary.startedAtMs)
            assertEquals(T0 + 3 * HOUR, summary.endedAtMs)
            assertEquals(90, summary.startLevel)
            assertEquals(60, summary.endLevel)
            assertEquals(1_200.0, summary.chargeMah!!, 1e-9)
            assertEquals(4.5, summary.energyWh!!, 1e-9)
            assertEquals(400.0, summary.averageMa!!, 1e-9)
            // Over the newest reading's counter capacity (2.4 Ah at 60 % → 4 Ah), the one the drain cells use.
            assertEquals(10.0, summary.percentPerHour!!, 1e-9)
            assertEquals(30.0, summary.chargePercent!!, 1e-9)
            assertEquals(0.5, summary.counterCoverage!!, 1e-9)
            assertEquals(SessionCapacity(4_000, CapacityConfidence.HIGH), summary.capacity)
            assertTrue(summary.measured)
            assertTrue(canDelete)
            assertFalse(deleteFailed)

            assertEquals(TimeWindow(T0, T0 + 3 * HOUR), charts.window)
            // Raw values in mA units are calibrated to mA for display; null current stays a gap.
            assertEquals(listOf(-400.0, -500.0, null, null, null, -100.0), charts.currentMa.map { it.value })
            assertEquals(listOf(90.0, 80.0, null, 70.0, null, 60.0), charts.level.map { it.value })
            assertEquals(T0 + HOUR + HOUR / 2, charts.level[2].timeMs)
            assertEquals(30.0, charts.temperature.first().value!!, 1e-9)

            // Partial coverage cannot identify either screen bucket's denominator; deep sleep is still measured.
            val drain = insights as SessionInsights.Drain
            assertNull(drain.screenOn.currentMa)
            assertNull(drain.screenOn.percentPerHour)
            assertNull(drain.screenOff.currentMa)
            assertNull(drain.screenOff.percentPerHour)
            assertEquals(90.0, drain.deepSleepPercent!!, 1e-9)
            assertTrue(drain.deepSleepScreenOff)
        }

        // With complete coverage, %/h falls back to Health when no counter reading supplies capacity.
        // Fahrenheit is applied to the chart values.
        repo.row.value = discharge().copy(counterCoveredMs = 3 * HOUR)
        repo.settings.value = AppSettings(temperatureUnitIndex = 1)
        repo.rows.value = listOf(sample(T0, level = 90, temperature = 300, counter = null), sample(T0 + HOUR, level = 80, counter = null))
        repo.sessions.value = listOf(discharge().copy(capacityEstimateMah = 5_000, capacityConfidence = "HIGH"))
        runCurrent()
        with(state.ready()) {
            assertTrue(useFahrenheit)
            assertEquals(86.0, charts.temperature.first().value!!, 1e-9)
            assertEquals(8.0, (insights as SessionInsights.Drain).screenOn.percentPerHour!!, 1e-9)
        }
    }

    @Test fun legacyRowsShowNoMeasuredFigures() = runTest {
        repo.row.value = discharge().copy(observationId = null, source = "legacy", estCapacityMah = 3_900)
        val (_, state) = start()
        with(state.ready().summary) {
            assertFalse(measured)
            assertNull(chargeMah)
            assertNull(averageMa)
            assertNull(capacity)
        }
        assertNull(state.ready().insights)
        assertFalse(state.ready().charts.hasReadings)
    }

    @Test fun chargeSessionsShowChargingInsights() = runTest {
        repo.row.value = charge()
        repo.rows.value = listOf(
            sample(T0, level = 15, raw = 2_000_000, status = 2, plugged = 1, temperature = 310),
            sample(T0 + 5 * MINUTE, level = 19, raw = 2_000_000, status = 2, plugged = 1),
            sample(T0 + 7 * MINUTE, level = 20, raw = 2_000_000, status = 2, plugged = 1),
            sample(T0 + 40 * MINUTE, level = 79, raw = 1_000_000, status = 2, plugged = 1),
            sample(T0 + 55 * MINUTE, level = 80, raw = 1_000_000, status = 2, plugged = 1),
            sample(T0 + HOUR, level = 85, raw = 500_000, status = 2, plugged = 1, temperature = 390),
        )
        val (_, state) = start()

        with(state.ready()) {
            assertEquals("Charged, not used", 2_500.0, summary.chargeMah!!, 1e-9)
            val charging = insights as SessionInsights.Charging
            assertEquals(ChargerType.USB, charging.charger)
            // 10 Wh over 1 h of counter coverage.
            assertEquals(10.0, charging.averagePowerW!!, 1e-9)
            assertEquals(7.8, charging.peakPowerW!!, 1e-9)
            assertEquals(40.1, charging.peakTemperature!!, 1e-9)
            assertEquals(48 * MINUTE, charging.twentyToEightyMs)
            assertNull(apps)
        }

        // An unknown charger name falls back to the readings' plug; no energy → the readings' mean power.
        repo.row.value = charge().copy(chargerType = "FUTURE_PAD", energyNwh = null)
        runCurrent()
        with(state.ready().insights as SessionInsights.Charging) {
            assertEquals(ChargerType.AC, charger)
            assertTrue(averagePowerW!! > 0)
        }

        // Started above 20 %: no 20 → 80 time.
        repo.rows.value = repo.rows.value.drop(3)
        runCurrent()
        assertNull((state.ready().insights as SessionInsights.Charging).twentyToEightyMs)
    }

    @Test fun peakPowerRecalibratesRawReadingsAndIgnoresTheStoredPeak() = runTest {
        repo.row.value = charge().copy(peakPowerMw = 8, energyNwh = null)
        repo.rows.value = listOf(
            sample(T0, level = 20, raw = 1_000, status = 2, plugged = 1),
            sample(T0 + MINUTE, level = 21, raw = 2_000, status = 2, plugged = 1),
        )
        val (_, state) = start()
        assertEquals(0.0078, (state.ready().insights as SessionInsights.Charging).peakPowerW!!, 1e-9)

        repo.calibration.value = CurrentCalibration(CurrentUnit.MILLIAMPS)
        runCurrent()
        with(state.ready().insights as SessionInsights.Charging) {
            assertEquals("Current calibration replaces the IDENTITY-era stored peak", 7.8, peakPowerW!!, 1e-9)
            assertEquals(5.85, averagePowerW!!, 1e-9)
            assertTrue("Peak is at least the calibrated readings' mean", peakPowerW!! >= averagePowerW!!)
        }
        assertEquals(listOf(1_000.0, 2_000.0), state.ready().charts.currentMa.map { it.value })
    }

    @Test fun peakPowerFallsBackToStoredWattsOnlyWithoutUsablePowerReadings() {
        val calibration = CurrentCalibration(CurrentUnit.MILLIAMPS)
        val session = charge()
        assertEquals(18.2, SessionDetailsMapping.charging(session, emptyList(), calibration, false).peakPowerW!!, 1e-9)
        val incomplete = listOf(
            sample(T0, level = 20, raw = null),
            sample(T0 + MINUTE, level = 21, raw = 2_000).copy(voltageMv = null),
        )
        assertEquals(18.2, SessionDetailsMapping.charging(session, incomplete, calibration, false).peakPowerW!!, 1e-9)
        assertNull(SessionDetailsMapping.charging(session.copy(peakPowerMw = null), incomplete, calibration, false).peakPowerW)
    }

    @Test fun peakPowerUsesTheLargestMagnitudeIncludingZeroAndSingleReadings() {
        val calibration = CurrentCalibration(CurrentUnit.MILLIAMPS)
        val readings = listOf(
            sample(T0, level = 20, raw = 1_000),
            sample(T0 + MINUTE, level = 21, raw = -2_000),
        )
        val session = charge().copy(energyNwh = null)
        val charging = SessionDetailsMapping.charging(session, readings, calibration, false)
        assertEquals("Stored peaks use a positive magnitude even for negative current", 7.8, charging.peakPowerW!!, 1e-9)
        assertTrue(charging.peakPowerW!! >= kotlin.math.abs(charging.averagePowerW!!))
        val zero = listOf(sample(T0, level = 20, raw = 0))
        assertEquals("A measured zero must not fall back to the stored peak", 0.0, SessionDetailsMapping.charging(session, zero, calibration, false).peakPowerW!!, 1e-9)
    }

    @Test fun appsFollowTheStoredStatusRowsAndBasis() = runTest {
        repo.row.value = discharge().copy(appUsageStatus = AppUsageStatus.READY, appUsageBasis = AppUsageBasis.WINDOW_RESET)
        repo.usage.value = listOf(
            usage(0, 10_123, CHROME, 60.0),
            usage(1, 1_000, "System UID 1000", 20.0),
            usage(2, 10_555, "com.removed.app", 10.0),
            usage(3, 10_201, YOUTUBE, 0.0),
            usage(4, -1, "", 10.0, others = true),
        )
        val (_, state) = start()

        with(state.ready().apps as SessionApps.Ready) {
            assertEquals(AppUsageBasis.WINDOW_RESET, basis)
            assertFalse(soFar)
            // Zero-power rows are dropped; shares are of every row's power, the folded tail included.
            assertEquals(listOf(AppLabel.Named("Chrome"), AppLabel.SystemProcess, AppLabel.Unknown), rows.map { it.label })
            assertEquals(listOf(0.6f, 0.2f, 0.1f), rows.map { it.share })
            assertEquals(10.0, othersMah!!, 1e-9)
            assertEquals(0.1f, othersShare)
        }

        val statuses = mapOf(
            AppUsageStatus.NO_ACCESS to SessionApps.NoAccess,
            AppUsageStatus.FAILED to SessionApps.Failed,
            AppUsageStatus.PENDING to SessionApps.Collecting,
            null to SessionApps.NotRecorded,
        )
        statuses.forEach { (status, expected) ->
            repo.row.value = discharge().copy(appUsageStatus = status)
            runCurrent()
            assertEquals(status.toString(), expected, state.ready().apps)
        }

        // Left open by a stopped process (not recording): its plug-in read never comes.
        repo.row.value = discharge().copy(endTime = null, appUsageStatus = AppUsageStatus.PENDING)
        runCurrent()
        assertEquals(SessionApps.Failed, state.ready().apps)

        // READY without any power used.
        repo.row.value = discharge().copy(appUsageStatus = AppUsageStatus.READY, appUsageBasis = AppUsageBasis.DELTA)
        repo.usage.value = listOf(usage(0, 10_123, CHROME, 0.0))
        runCurrent()
        assertEquals(SessionApps.Empty, state.ready().apps)
    }

    @Test fun aRecordingSessionAppendsLiveReadingsAndShowsAppsSoFar() = runTest {
        repo.row.value = discharge().copy(endTime = null, activeKey = 1, lastSampleTime = T0 + HOUR, appUsageStatus = AppUsageStatus.PENDING)
        repo.rows.value = listOf(sample(T0, level = 90), sample(T0 + HOUR, level = 80))
        repo.recordingGeneration.value = "gen-1"
        val (vm, state) = start()

        with(state.ready()) {
            assertTrue(summary.recording)
            assertFalse(canDelete)
            assertEquals(SessionApps.Pending, apps)
        }

        // Live captures newer than the last save are appended; another power state or session is not.
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + HOUR + 2 * SECOND, level = 79, sessionId = null))
        runCurrent()
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + HOUR + 4 * SECOND, level = 79, status = 2, plugged = 1))
        runCurrent()
        repo.realtime.value = BatteryRepository.Realtime(sample(T0 + HOUR + 6 * SECOND, level = 79, sessionId = "other"))
        runCurrent()
        with(state.ready()) {
            assertEquals(T0 + HOUR + 2 * SECOND, summary.endedAtMs)
            assertEquals(79, summary.endLevel)
            assertEquals(3, charts.level.size)
        }

        // A save that covers the live reading replaces it (no duplicate point).
        repo.rows.value = repo.rows.value + sample(T0 + HOUR + 2 * SECOND, level = 79)
        runCurrent()
        assertEquals(3, state.ready().charts.level.size)

        // Apps so far: the cached dump since this session's baseline, only when newer than the baseline.
        repo.baselines[ID] = snapshot(T0 + MINUTE, CHROME to 10.0, YOUTUBE to 5.0)
        repo.cached.value = snapshot(T0 + HOUR, CHROME to 40.0, YOUTUBE to 15.0)
        runCurrent()
        with(state.ready().apps as SessionApps.Ready) {
            assertTrue(soFar)
            assertEquals(AppUsageBasis.DELTA, basis)
            assertEquals(T0 + HOUR, capturedAtMs)
            assertEquals(listOf(30.0, 10.0), rows.map { it.powerMah })
        }
        repo.cached.value = snapshot(T0, CHROME to 40.0)
        runCurrent()
        assertEquals(SessionApps.Pending, state.ready().apps)

        // Delete is refused while recording.
        vm.onEvent(SessionDetailsEvent.Delete)
        runCurrent()
        assertTrue(repo.deletes.isEmpty())

        // Monitoring stops: the session is no longer recording (live points are dropped) and can be deleted.
        repo.recordingGeneration.value = null
        runCurrent()
        with(state.ready()) {
            assertFalse(summary.recording)
            assertTrue(canDelete)
        }
    }

    @Test fun deleteGoesToTheRepositoryThenReportsDeletedOrTheFailure() = runTest {
        repo.row.value = discharge()
        val (vm, state) = start()

        repo.deleteResult = false
        vm.onEvent(SessionDetailsEvent.Delete)
        runCurrent()
        assertEquals(listOf(ID), repo.deletes)
        assertTrue(state.ready().deleteFailed)
        vm.onEvent(SessionDetailsEvent.DismissDeleteError)
        runCurrent()
        assertFalse(state.ready().deleteFailed)

        repo.deleteResult = true
        vm.onEvent(SessionDetailsEvent.Delete)
        runCurrent()
        assertEquals(listOf(ID, ID), repo.deletes)
        assertEquals(SessionDetailsUiState.Deleted, state())

        // Navigation events are the screen's.
        vm.onEvent(SessionDetailsEvent.Back)
        vm.onEvent(SessionDetailsEvent.OpenApp(10_123, CHROME))
        runCurrent()
        assertEquals(SessionDetailsUiState.Deleted, state())
    }

    @Test fun deletionWaitsForWriterAcknowledgmentAfterMonitoringStops() = runTest {
        repo.row.value = discharge().copy(endTime = null, activeKey = 1)
        repo.recordingGeneration.value = null // Stop published, but the writer has not drained yet.
        val acknowledgment = CompletableDeferred<Unit>()
        repo.deleteAcknowledgment = acknowledgment
        val (vm, state) = start()
        assertTrue(state.ready().canDelete)

        vm.onEvent(SessionDetailsEvent.Delete)
        runCurrent()
        assertEquals(listOf(ID), repo.deletes)
        assertFalse(state.ready().canDelete)
        assertFalse(state.ready().deleteFailed)
        vm.onEvent(SessionDetailsEvent.Delete)
        runCurrent()
        assertEquals("No duplicate request while the writer is pending", listOf(ID), repo.deletes)

        acknowledgment.complete(Unit)
        runCurrent()
        assertEquals(SessionDetailsUiState.Deleted, state())
    }

    @Test fun aSessionThatIsNotInHistoryIsMissing() = runTest {
        val (_, state) = start()
        assertEquals(SessionDetailsUiState.Missing, state())
        repo.row.value = discharge()
        runCurrent()
        assertTrue(state() is SessionDetailsUiState.Ready)
        repo.row.value = null
        runCurrent()
        assertEquals(SessionDetailsUiState.Missing, state())
    }

    @Test fun longSessionsAreDownsampledForTheCharts() = runTest {
        repo.row.value = discharge()
        repo.rows.value = List(6_000) { i -> sample(T0 + i * 2 * SECOND, level = 90, raw = if (i == 3_000) -3_000_000 else -400_000) }
        val (_, state) = start()
        val points = state.ready().charts.currentMa
        assertTrue(points.size <= 2_400)
        assertEquals(T0, points.first().timeMs)
        assertEquals(T0 + 5_999 * 2 * SECOND, points.last().timeMs)
        assertEquals(-3_000.0, points.minOf { it.value ?: 0.0 }, 1e-9)
    }

    @Test fun levelSpanNeedsTheStartAtOrBelowTheFromLevel() {
        val readings = listOf(sample(T0, level = 20), sample(T0 + MINUTE, level = 50), sample(T0 + 3 * MINUTE, level = 81))
        assertEquals(3 * MINUTE, SessionDetailsMapping.levelSpanMs(readings, 20, 80))
        assertNull(SessionDetailsMapping.levelSpanMs(readings.drop(1), 20, 80))
        assertNull(SessionDetailsMapping.levelSpanMs(readings.take(2), 20, 80))
        assertNull(SessionDetailsMapping.levelSpanMs(emptyList(), 20, 80))
    }

    private class FakeSessionDetailsRepository : SessionDetailsRepository {
        val row = MutableStateFlow<ChargeSession?>(null)
        val rows = MutableStateFlow<List<BatterySample>>(emptyList())
        val usage = MutableStateFlow<List<SessionAppUsage>>(emptyList())
        val sessions = MutableStateFlow<List<ChargeSession>>(emptyList())
        val cached = MutableStateFlow<AppUsageSnapshot?>(null)
        val baselines = mutableMapOf<String, AppUsageSnapshot>()
        val deletes = mutableListOf<String>()
        var deleteResult = true
        var deleteAcknowledgment: CompletableDeferred<Unit>? = null

        override fun session(id: String): Flow<ChargeSession?> = row.map { it?.takeIf { session -> session.sessionId == id } }
        override fun samples(id: String): Flow<List<BatterySample>> = rows
        override fun appUsage(id: String): Flow<List<SessionAppUsage>> = usage
        override val recordingGeneration = MutableStateFlow<String?>(null)
        override val realtime = MutableStateFlow(BatteryRepository.Realtime())
        override val calibration = MutableStateFlow(CurrentCalibration.IDENTITY)
        override val settings = MutableStateFlow(AppSettings())
        override fun recentSessions(limit: Int): Flow<List<ChargeSession>> = sessions.map { it.take(limit) }
        override val cachedAppUsage: Flow<AppUsageSnapshot?> = cached
        override suspend fun baseline(sessionId: String) = baselines[sessionId]

        override suspend fun deleteSession(id: String): Boolean {
            deletes += id
            deleteAcknowledgment?.await()
            if (deleteResult) row.value = null
            return deleteResult
        }
    }

    private class FakeAppInfo(private val labels: Map<String, String>) : AppInfoSource {
        override suspend fun info(packageName: String) =
            AppInfo(packageName, labels[packageName] ?: packageName, isSystem = false, installed = packageName in labels)
        override suspend fun icon(packageName: String): Bitmap? = null
    }

    private companion object {
        const val ID = "session-1"
        const val T0 = 1_760_001_600_000L
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val CHROME = "com.android.chrome"
        const val YOUTUBE = "com.google.android.youtube"

        /** 3 h on battery: 1 h on (400 mAh), 2 h off (200 mAh), half covered by the counter, 90 % asleep screen-off. */
        fun discharge() = ChargeSession(
            sessionId = ID,
            type = SessionType.DISCHARGE,
            startTime = T0,
            endTime = T0 + 3 * HOUR,
            startLevel = 90,
            endLevel = 60,
            deltaUah = 1_200_000,
            avgCurrentUa = -400_000,
            estCapacityMah = null,
            observationId = "gen-1",
            lastSampleTime = T0 + 3 * HOUR,
            observedMs = 3 * HOUR,
            counterCoveredMs = 3 * HOUR / 2,
            screenOnMs = HOUR,
            screenOffMs = 2 * HOUR,
            screenOnUah = 400_000,
            screenOffUah = 200_000,
            cpuSuspendMs = 2 * HOUR,
            source = "BatteryManager observed interval",
            energyNwh = 4_500_000_000,
            peakPowerMw = 3_000,
            peakTemperatureDeciC = 330,
            screenOffSuspendMs = 108 * MINUTE,
            capacityEstimateMah = 4_000,
            capacityConfidence = "HIGH",
            capacityBasis = "COUNTER_SPAN",
        )

        /** 1 h charging on USB: 2,500 mAh, 10 Wh, peak 18.2 W and 40.1 °C. */
        fun charge() = ChargeSession(
            sessionId = ID,
            type = SessionType.CHARGE,
            startTime = T0,
            endTime = T0 + HOUR,
            startLevel = 15,
            endLevel = 85,
            deltaUah = 2_500_000,
            avgCurrentUa = 2_500_000,
            estCapacityMah = null,
            observationId = "gen-1",
            lastSampleTime = T0 + HOUR,
            observedMs = HOUR,
            counterCoveredMs = HOUR,
            source = "BatteryManager observed interval",
            chargerType = "USB",
            energyNwh = 10_000_000_000,
            peakPowerMw = 18_200,
            peakTemperatureDeciC = 401,
            appUsageStatus = AppUsageStatus.NOT_APPLICABLE,
        )

        // Status 3 = discharging, 2 = charging; plugged 0 = battery, 1 = AC.
        fun sample(
            timestamp: Long,
            level: Int,
            raw: Long? = -400_000,
            status: Int = 3,
            plugged: Int = 0,
            temperature: Int? = 310,
            observation: String = "gen-1",
            boundary: String? = null,
            counter: Long? = 2_400_000,
            sessionId: String? = ID,
        ) = BatterySample(
            timestamp = timestamp,
            levelPercent = level,
            status = status,
            plugged = plugged,
            currentNowUa = raw,
            chargeCounterUah = counter,
            voltageMv = 3_900,
            temperatureDeciC = temperature,
            health = 2,
            screenOn = true,
            observationId = observation,
            sessionId = sessionId,
            source = "BatteryManager",
            boundaryReason = boundary,
        )

        fun usage(rank: Int, uid: Int, packageName: String, mah: Double, others: Boolean = false) =
            SessionAppUsage(ID, rank, uid, packageName, mah, isOthers = others, basis = AppUsageBasis.WINDOW_RESET)

        fun snapshot(capturedAt: Long, vararg apps: Pair<String, Double>) = AppUsageSnapshot(
            windowStartedAt = T0 - HOUR,
            windowStartCount = 7,
            capturedAt = capturedAt,
            rows = apps.mapIndexed { index, (packageName, mah) -> AppUsageRow(10_000 + index, packageName, mah) },
        )
    }
}
