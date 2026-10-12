package com.akane.voltwise.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.R
import com.akane.voltwise.battery.apps.AppInfo
import com.akane.voltwise.battery.apps.AppInfoSource
import com.akane.voltwise.battery.apps.AppUsageSnapshot
import com.akane.voltwise.battery.data.DesignCapacityReading
import com.akane.voltwise.battery.data.RepositoryRecoveryTest
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.insights.model.InsightReport
import com.akane.voltwise.battery.service.MonitoringController
import com.akane.voltwise.test.DeviceEnvironment
import com.akane.voltwise.ui.screens.now.NowScreen
import com.akane.voltwise.ui.theme.MainTheme
import com.akane.voltwise.viewmodel.NowRepository
import com.akane.voltwise.viewmodel.NowViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real production composable; battery inputs/failures/storage are explicitly isolated and scripted (the same
 * fixture as [RepositoryRecoveryTest]). Was `DashboardRecoveryDeviceTest`: the same intent carried to Now — after
 * a missing/recovered reading and a fresh ViewModel (a process restart over a repository still monitoring), the
 * screen shows live values and the right Start/Stop state, without inventing an observation.
 */
@RunWith(AndroidJUnit4::class)
class NowRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * [NowRepository] over the scripted fixture's real [com.akane.voltwise.battery.data.BatteryRepository]. Per-app
     * stats and design capacity aren't part of this recovery scenario, so they're fixed to empty defaults.
     */
    private class FixtureNowRepository(private val fixture: RepositoryRecoveryTest.Fixture) : NowRepository {
        private val repo = fixture.repository
        override val realtime = repo.realtimeFlow
        override val calibration = fixture.calibration.state
        override val settings = fixture.settings.flow
        override val design: Flow<DesignCapacityReading> = flowOf(DesignCapacityReading.Unknown)
        override val cachedAppUsage: Flow<AppUsageSnapshot?> = flowOf(null)
        override val insights: Flow<InsightReport?> = flowOf(null)
        override val lastAnalyzedAt: Flow<Long?> = flowOf(null)
        override val eligibleSessionCount: Flow<Int> = flowOf(0)
        override val activeSession = repo.activeSessionFlow
        override fun samplesSince(fromMs: Long) = repo.samplesBetween(fromMs, Long.MAX_VALUE)
        override fun day(epochDay: Long) = fixture.database.dailySummaryDao().day(epochDay)
        override fun recentSessions(limit: Int) = repo.sessionDao.filteredSessions(null, "", limit)
        override fun dischargeSessions(limit: Int) = repo.sessionDao.filteredSessions(SessionType.DISCHARGE, "", limit)
        override suspend fun baseline(sessionId: String): AppUsageSnapshot? = null
        override fun resetObservation() = repo.resetObservation()
        override fun undoCalibration() = fixture.calibration.undoLastCorrection()
        override fun dismissCalibrationNotice() = fixture.calibration.dismissNotice()
    }

    private object NoAppInfo : AppInfoSource {
        override suspend fun info(packageName: String) = AppInfo(packageName, packageName, isSystem = false, installed = false)
        override suspend fun icon(packageName: String): Bitmap? = null
    }

    private fun viewModel(fixture: RepositoryRecoveryTest.Fixture) = NowViewModel(
        FixtureNowRepository(fixture),
        MonitoringController(ApplicationProvider.getApplicationContext(), fixture.repository),
        NoAppInfo,
    )

    @Test fun missingAndRecoveredReadingsShowWithTheRightStartStopStateAcrossAFreshViewModel() {
        DeviceEnvironment.requireDisposableEmulator()
        val fixture = RepositoryRecoveryTest.Fixture()
        val models = ViewModelStore()
        val context = DeviceEnvironment.context
        val vm = viewModel(fixture)
        models.put("scripted-now", vm)
        // A mutable slot, not a 2nd setContent: the "process restart" step below swaps it in place, which is
        // all a rule that only allows one setContent call can be asked to do again.
        val current = mutableStateOf(vm)
        try {
            fixture.context.missingBattery = true
            compose.setContent {
                MainTheme(dynamicColor = false) {
                    NowScreen(
                        onOpenHistory = {},
                        onOpenHealth = {},
                        onOpenApps = {},
                        onOpenApp = { _, _ -> },
                        onOpenInsights = {},
                        onOpenFinding = {},
                        vm = current.value,
                    )
                }
            }
            runBlocking { fixture.refresh() }
            // Repository changes precede the ViewModel's Default-dispatcher pipeline and UI collection.
            // Wait for both the active model's expected state and the displayed controls at each transition.
            compose.waitUntil(120_000) {
                val hero = vm.state.value.hero
                fixture.repository.error.value?.contains("not supplied") == true &&
                    !hero.hasReading && !hero.monitoring &&
                    compose.onNodeWithText(context.getString(R.string.now_waiting_reading)).isDisplayed() &&
                    compose.onNodeWithText(context.getString(R.string.now_start_monitoring)).isDisplayed()
            }
            compose.onNodeWithText(context.getString(R.string.now_waiting_reading)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.now_start_monitoring)).assertIsDisplayed()
            DeviceEnvironment.screenshot("now-unavailable-scripted")

            fixture.context.missingBattery = false
            runBlocking { fixture.refresh() }
            compose.waitUntil(120_000) {
                val hero = vm.state.value.hero
                fixture.repository.error.value == null && fixture.repository.realtimeFlow.value.level == 80 &&
                    hero.hasReading && hero.level == 80 && !hero.monitoring &&
                    compose.onNodeWithText("80%").isDisplayed() &&
                    compose.onNodeWithText(context.getString(R.string.now_start_monitoring)).isDisplayed()
            }
            compose.onNodeWithText("80%").assertIsDisplayed()
            assertNull(fixture.repository.observation.value.startedAt)
            DeviceEnvironment.screenshot("now-recovered-scripted")

            // Drives the fixture's own repository state (never a click), so this never starts the real
            // production BatteryMonitorService/singleton repository the button's MonitoringController targets.
            fixture.repository.startSampling()
            compose.waitUntil(120_000) {
                val hero = vm.state.value.hero
                fixture.repository.isMonitoringFlow.value && hero.hasReading && hero.level == 80 && hero.monitoring &&
                    compose.onNodeWithText(context.getString(R.string.now_stop_monitoring)).isDisplayed()
            }
            compose.onNodeWithText(context.getString(R.string.now_stop_monitoring)).assertIsDisplayed()
            DeviceEnvironment.screenshot("now-monitoring-started-scripted")

            // A process restart: a fresh ViewModel over the same, still-monitoring repository must show the
            // live value and the right Start/Stop state at once, not "waiting for a reading" / Start again.
            val restarted = viewModel(fixture)
            models.put("scripted-now-restarted", restarted)
            compose.runOnUiThread { current.value = restarted }
            // The old frame already showed 80% / Stop; only this model's non-default state proves recovery.
            compose.waitUntil(120_000) {
                val hero = restarted.state.value.hero
                hero.hasReading && hero.level == 80 && hero.monitoring &&
                    compose.onNodeWithText("80%").isDisplayed() &&
                    compose.onNodeWithText(context.getString(R.string.now_stop_monitoring)).isDisplayed()
            }
            compose.onNodeWithText("80%").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.now_stop_monitoring)).assertIsDisplayed()
            DeviceEnvironment.screenshot("now-restarted-scripted")

            fixture.repository.stopSampling()
            compose.waitUntil(120_000) {
                val hero = restarted.state.value.hero
                !fixture.repository.isMonitoringFlow.value && hero.hasReading && hero.level == 80 && !hero.monitoring &&
                    compose.onNodeWithText(context.getString(R.string.now_start_monitoring)).isDisplayed()
            }
            compose.onNodeWithText(context.getString(R.string.now_start_monitoring)).assertIsDisplayed()
            DeviceEnvironment.screenshot("now-monitoring-stopped-scripted")
        } finally {
            compose.runOnUiThread { models.clear() }
            runBlocking { fixture.close() }
        }
    }
}
