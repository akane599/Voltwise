package com.akane.voltwise.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.akane.voltwise.battery.apps.AppInfo
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.apps.AppStatsRepository
import com.akane.voltwise.battery.apps.AppStatsResult
import com.akane.voltwise.battery.util.BatteryStatsParser
import com.akane.voltwise.battery.util.BatteryStatsParser.AppPowerStats
import com.akane.voltwise.battery.util.DumpOutput
import com.akane.voltwise.battery.util.ShellRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class AppsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeAppStats()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.start(saved: SavedStateHandle = SavedStateHandle()): Pair<AppsViewModel, () -> AppsUiState> {
        val vm = AppsViewModel(source, saved, clock = { NOW }, computeDispatcher = dispatcher)
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        return vm to { vm.state.value }
    }

    @Test fun startReadsOnceAndListsAppsByBatteryWithSharesOfAllApps() = runTest {
        source.next = { AppStatsResult.Ready(dump()) }
        val (vm, state) = start()

        // Nothing read yet: no summary and no problem is the loading state.
        assertTrue(source.calls.isEmpty())
        assertNull(state().summary)
        assertNull(state().problem)

        vm.onStart()
        runCurrent()
        assertEquals(listOf(false), source.calls)
        with(state()) {
            assertFalse(loading)
            assertNull(problem)
            assertEquals(NOW, nowMs)
            assertEquals(StatsSummary.of(dump()), summary)
            // Chrome is a preinstalled (system) package with a launcher entry: an app. GMS (no launcher entry) and the
            // system uid are system components, hidden by default; the uninstalled uid-only row is an unknown app.
            assertEquals(listOf(CHROME_UID, YOUTUBE_UID, GONE_UID), rows.map { it.uid })
            assertEquals(listOf(AppLabel.Named("Chrome"), AppLabel.Named("YouTube"), AppLabel.Unknown), rows.map { it.label })
            assertEquals(listOf(CHROME, YOUTUBE, "UID $GONE_UID"), rows.map { it.packageName })
            assertEquals(124.0, rows[0].value!!, 1e-9)
            // Shares are of every app's mAh, hidden system components included (240 mAh).
            assertEquals(124f / 240f, rows[0].share, 1e-6f)
            assertEquals(8f / 240f, rows[2].share, 1e-6f)
            assertEquals(2, hiddenSystem)
        }
    }

    @Test fun theSystemToggleSortAndSearchLiveInSavedStateAndSurviveRecreation() = runTest {
        source.cached.value = dump()
        val saved = SavedStateHandle()
        val (vm, state) = start(saved)

        vm.onEvent(AppsEvent.SetShowSystem(true))
        runCurrent()
        with(state()) {
            assertTrue(showSystem)
            assertEquals(0, hiddenSystem)
            assertEquals(listOf(CHROME_UID, YOUTUBE_UID, GMS_UID, SYSTEM_UID, GONE_UID), rows.map { it.uid })
            assertEquals(AppLabel.SystemProcess, rows.first { it.uid == SYSTEM_UID }.label)
        }

        vm.onEvent(AppsEvent.SetSort(AppSort.CPU))
        vm.onEvent(AppsEvent.SetQuery("goo"))
        runCurrent()
        assertEquals("goo", vm.query)

        // A new ViewModel over the same saved state (process death) comes back with the same controls.
        val (restored, restoredState) = start(saved)
        assertEquals("goo", restored.query)
        with(restoredState()) {
            assertEquals(AppSort.CPU, sort)
            assertTrue(showSystem)
            assertEquals("goo", query)
            assertEquals(listOf(YOUTUBE_UID, GMS_UID), rows.map { it.uid })
        }

        // An unknown stored sort name falls back to Battery.
        saved[AppsViewModel.KEY_SORT] = "BOGUS"
        runCurrent()
        assertEquals(AppSort.BATTERY, restoredState().sort)
    }

    @Test fun searchMatchesLabelsAndPackagesIgnoringCaseAndCountsHiddenSystemMatches() = runTest {
        source.cached.value = dump()
        val (vm, state) = start()

        vm.onEvent(AppsEvent.SetQuery("  CHROME "))
        runCurrent()
        assertEquals(listOf(CHROME_UID), state().rows.map { it.uid })

        // "google" matches YouTube's and GMS's package names; GMS is a hidden system component.
        vm.onEvent(AppsEvent.SetQuery("google"))
        runCurrent()
        assertEquals(listOf(YOUTUBE_UID), state().rows.map { it.uid })
        assertEquals(1, state().hiddenSystem)

        vm.onEvent(AppsEvent.SetQuery("tube"))
        runCurrent()
        assertEquals(listOf(YOUTUBE_UID), state().rows.map { it.uid })
        assertEquals(0, state().hiddenSystem)

        vm.onEvent(AppsEvent.SetQuery("nothing like it"))
        runCurrent()
        assertTrue(state().rows.isEmpty())
        assertEquals(0, state().hiddenSystem)
    }

    @Test fun eachSortShowsItsMetricLargestFirstWithUnknownValuesLast() = runTest {
        source.cached.value = dump()
        val (vm, state) = start()
        vm.onEvent(AppsEvent.SetShowSystem(true))

        vm.onEvent(AppsEvent.SetSort(AppSort.CPU))
        runCurrent()
        // CPU: YouTube 90 s > GMS 60 s > Chrome 30 s > system 10 s; the uid-only row has no CPU figure.
        assertEquals(listOf(YOUTUBE_UID, GMS_UID, CHROME_UID, SYSTEM_UID, GONE_UID), state().rows.map { it.uid })
        assertNull(state().rows.last().value)
        assertEquals(0f, state().rows.last().share)
        assertEquals(90_000.0 / 190_000.0, state().rows.first().share.toDouble(), 1e-6)

        vm.onEvent(AppsEvent.SetSort(AppSort.FOREGROUND))
        runCurrent()
        assertEquals(CHROME_UID, state().rows.first().uid)

        vm.onEvent(AppsEvent.SetSort(AppSort.BACKGROUND))
        runCurrent()
        assertEquals(GMS_UID, state().rows.first().uid)

        vm.onEvent(AppsEvent.SetSort(AppSort.NETWORK))
        runCurrent()
        // Mobile + Wi-Fi, both directions: YouTube 5,000,000 B; Chrome 1,500 B (only Wi-Fi known).
        assertEquals(listOf(YOUTUBE_UID, CHROME_UID), state().rows.take(2).map { it.uid })
        assertEquals(5_000_000.0, state().rows.first().value!!, 1e-9)
        assertEquals(1_500.0, state().rows[1].value!!, 1e-9)
    }

    @Test fun permanentShizukuDenialIsDistinctFromOrdinaryDenial() = runTest {
        source.next = { AppStatsResult.NoAccess }
        val blocked = ShizukuState(running = true, granted = false, blocked = true)
        source.shizuku.value = blocked
        source.accessNow = AccessSnapshot(ShellRunner.Mode.NONE, null, blocked)
        val (vm, state) = start()
        vm.onStart()
        runCurrent()
        assertEquals(StatsProblem.NoAccess(AccessProblem.SHIZUKU_NOT_ALLOWED, blocked = true), state().problem)

        val requestable = blocked.copy(blocked = false)
        source.accessNow = source.accessNow.copy(shizuku = requestable)
        source.shizuku.value = requestable
        runCurrent()
        assertEquals(StatsProblem.NoAccess(AccessProblem.SHIZUKU_NOT_ALLOWED), state().problem)
    }

    @Test fun noAccessAndFailuresAreExplainedAndStaleDataStays() = runTest {
        val (vm, state) = start()

        // Shizuku runs but hasn't allowed BatStats.
        source.next = { AppStatsResult.NoAccess }
        source.accessNow = AccessSnapshot(ShellRunner.Mode.NONE, "Shizuku authorization required", ShizukuState(running = true, granted = false))
        vm.onStart()
        runCurrent()
        assertEquals(StatsProblem.NoAccess(AccessProblem.SHIZUKU_NOT_ALLOWED), state().problem)
        assertNull(state().summary)

        // A good read clears it.
        source.next = { AppStatsResult.Ready(dump()) }
        vm.onEvent(AppsEvent.Refresh)
        runCurrent()
        assertNull(state().problem)
        assertNotNull(state().summary)

        // A failed refresh keeps the last dump on screen under the notice.
        source.next = { AppStatsResult.Failed(AppStatsRepository.FORMAT_UNAVAILABLE) }
        vm.onEvent(AppsEvent.Refresh)
        runCurrent()
        assertEquals(StatsProblem.Failed(ReadProblem.FORMAT), state().problem)
        assertNotNull(state().summary)
        assertEquals(3, state().rows.size)
    }

    @Test fun accessAndReadProblemsMapFromTheAccessFacts() {
        val none = ShizukuState(running = false, granted = false)
        assertEquals(AccessProblem.NOT_SET_UP, AccessProblem.of(AccessSnapshot(ShellRunner.Mode.NONE, null, none)))
        assertEquals(
            AccessProblem.SHIZUKU_NOT_ALLOWED,
            AccessProblem.of(AccessSnapshot(ShellRunner.Mode.NONE, null, ShizukuState(running = true, granted = false))),
        )
        // ADB grants on Android 16: the dump is refused for cross-user access.
        assertEquals(AccessProblem.ADB_NOT_ENOUGH, AccessProblem.of(AccessSnapshot(ShellRunner.Mode.ADB, DumpOutput.REFUSED_CROSS_USER, none)))
        assertEquals(AccessProblem.REFUSED, AccessProblem.of(AccessSnapshot(ShellRunner.Mode.ADB, DumpOutput.REFUSED, none)))
        assertEquals(AccessProblem.REFUSED, AccessProblem.of(AccessSnapshot(ShellRunner.Mode.ROOT, DumpOutput.REFUSED_CROSS_USER, none)))
        // ADB grants revoked since: the forced probe finds no mode, and the old refusal no longer explains anything.
        assertEquals(AccessProblem.NOT_SET_UP, AccessProblem.of(AccessSnapshot(ShellRunner.Mode.NONE, DumpOutput.REFUSED_CROSS_USER, none)))

        assertEquals(ReadProblem.FORMAT, ReadProblem.of(AppStatsRepository.FORMAT_UNAVAILABLE, ShellRunner.Mode.SHIZUKU))
        assertEquals(ReadProblem.SHIZUKU, ReadProblem.of("Shizuku is connected but the dump failed: x", ShellRunner.Mode.SHIZUKU))
        assertEquals(ReadProblem.OTHER, ReadProblem.of("Root is available but the dump failed: x", ShellRunner.Mode.ROOT))
    }

    @Test fun readsHappenOnStartAndRefreshOnlyAndAShizukuChangeRereadsWithAFreshProbe() = runTest {
        source.next = { AppStatsResult.Ready(dump()) }
        val (vm, _) = start()
        runCurrent()
        assertTrue("creating the ViewModel reads nothing", source.calls.isEmpty())

        vm.onStart()
        runCurrent()
        vm.onEvent(AppsEvent.Refresh)
        runCurrent()
        assertEquals(listOf(false, true), source.calls)

        // Shizuku allowed while visible: read again, forcing a new access probe.
        source.shizuku.value = ShizukuState(running = true, granted = true)
        runCurrent()
        assertEquals(listOf(false, true, true), source.calls)

        // While away, a change only marks the next start as forced.
        vm.onStop()
        source.shizuku.value = ShizukuState(running = false, granted = false)
        runCurrent()
        assertEquals(3, source.calls.size)
        vm.onStart()
        runCurrent()
        assertEquals(listOf(false, true, true, true), source.calls)

        // An ordinary start after that is not forced (the 60 s cache answers).
        vm.onStop()
        vm.onStart()
        runCurrent()
        assertEquals(false, source.calls.last())
    }

    @Test fun loadingWhileAReadRunsAndAStartDoesNotStackReads() = runTest {
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        source.next = { AppStatsResult.Ready(dump()) }
        val (vm, state) = start()

        vm.onStart()
        runCurrent()
        assertTrue(state().loading)
        vm.onStop()
        vm.onStart()
        runCurrent()
        assertEquals("a second start joins the read in flight", 1, source.calls.size)

        gate.complete(Unit)
        runCurrent()
        assertFalse(state().loading)
        assertNotNull(state().summary)
    }

    @Test fun aSharedUidRowSaysSoWithItsMemberCountWhateverTheDumpOrder() = runTest {
        val gsf = "com.google.android.gsf"
        source.infos[gsf] = AppInfo(gsf, "Google Services Framework", isSystem = true, installed = true)
        val sharedFirst = AppPowerStats(GMS_UID, "Shared UID $GMS_UID", 30.0, listOf(gsf, GMS))
        source.cached.value = dump().copy(apps = dump().apps.map { if (it.uid == GMS_UID) sharedFirst else it })
        val (vm, state) = start()
        vm.onEvent(AppsEvent.SetShowSystem(true))
        runCurrent()

        val shared = state().rows.single { it.uid == GMS_UID }
        assertEquals("the whole uid's power, not split", 30.0, shared.value!!, 1e-9)
        assertEquals(2, shared.sharedBy)
        // The representative is the sorted first member, not the dump's first package.
        assertEquals(GMS, shared.packageName)
        assertEquals(AppLabel.Named("Google Play services"), shared.label)
        assertEquals("single-package uids aren't shared", 0, state().rows.single { it.uid == CHROME_UID }.sharedBy)

        source.cached.value = dump().copy(
            apps = dump().apps.map { if (it.uid == GMS_UID) sharedFirst.copy(packages = listOf(GMS, gsf)) else it },
        )
        runCurrent()
        assertEquals("package order doesn't change the row", shared, state().rows.single { it.uid == GMS_UID })
    }

    @Test fun aReadThatFinishesLateNeverOverwritesANewerOne() = runTest {
        val first = CompletableDeferred<Unit>()
        source.gate = first
        source.next = { AppStatsResult.NoAccess }
        val (vm, state) = start()
        vm.onStart()
        runCurrent()

        // Pull-to-refresh while the first read waits; it succeeds first.
        source.gate = null
        source.next = { AppStatsResult.Ready(dump()) }
        vm.onEvent(AppsEvent.Refresh)
        runCurrent()
        assertNull(state().problem)

        first.complete(Unit)
        runCurrent()
        assertNull("the older read's NoAccess is dropped", state().problem)
        assertFalse(state().loading)
    }

    companion object {
        const val NOW = 1_760_001_600_000L
        const val CHROME = "com.android.chrome"
        const val YOUTUBE = "com.google.android.youtube"
        const val GMS = "com.google.android.gms"
        const val CHROME_UID = 10_123
        const val YOUTUBE_UID = 10_201
        const val GMS_UID = 10_050
        const val SYSTEM_UID = 1_000
        const val GONE_UID = 10_999
        private const val SECOND = 1_000L
        private const val HOUR = 3_600_000L

        fun dump(capturedAt: Long = NOW - 60 * SECOND) = BatteryStatsParser.FullSnapshot(
            capturedAt = capturedAt,
            startedAt = NOW - 14 * HOUR,
            startCount = 12,
            batteryRealtimeMs = 14 * HOUR,
            batteryUptimeMs = 5 * HOUR,
            screenOnTimeMs = 3 * HOUR,
            screenOffTimeMs = 11 * HOUR,
            screenOnDischargePercent = 27,
            screenOffDischargePercent = 11,
            estimatedCapacityMah = 4_512.0,
            doze = BatteryStatsParser.DozeStats(8 * HOUR, 20, 6 * HOUR, 8, 2 * HOUR, 12),
            apps = listOf(
                AppPowerStats(
                    CHROME_UID, CHROME, 124.0, listOf(CHROME),
                    cpuTimeMs = 30 * SECOND, foregroundTimeMs = 2 * HOUR, backgroundTimeMs = 5 * SECOND,
                    wifiRxBytes = 1_000, wifiTxBytes = 500,
                ),
                AppPowerStats(
                    YOUTUBE_UID, YOUTUBE, 58.0, listOf(YOUTUBE),
                    cpuTimeMs = 90 * SECOND, foregroundTimeMs = HOUR, backgroundTimeMs = 10 * SECOND,
                    mobileRxBytes = 4_000_000, mobileTxBytes = 200_000, wifiRxBytes = 700_000, wifiTxBytes = 100_000,
                ),
                AppPowerStats(GMS_UID, GMS, 30.0, listOf(GMS), cpuTimeMs = 60 * SECOND, backgroundTimeMs = HOUR),
                AppPowerStats(SYSTEM_UID, "android", 20.0, listOf("android"), cpuTimeMs = 10 * SECOND),
                AppPowerStats(GONE_UID, "UID $GONE_UID", 8.0),
            ),
        )
    }
}

/** A fake reader: [next] answers reads (a Ready result also becomes [cached], as the repository does). */
class FakeAppStats : AppsRepository, AppDetailsRepository {
    override fun findingsFor(uid: Int, packageName: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.akane.voltwise.battery.insights.model.Finding>())
    override val cached = MutableStateFlow<BatteryStatsParser.FullSnapshot?>(null)
    override val shizuku = MutableStateFlow(ShizukuState(running = false, granted = false))
    var next: (force: Boolean) -> AppStatsResult = { AppStatsResult.NoAccess }
    var gate: CompletableDeferred<Unit>? = null
    var accessNow = AccessSnapshot(ShellRunner.Mode.NONE, null, ShizukuState(running = false, granted = false))
    val calls = mutableListOf<Boolean>()
    var launchable = setOf(AppsViewModelTest.CHROME, AppsViewModelTest.YOUTUBE)
    val infos = mutableMapOf(
        AppsViewModelTest.CHROME to AppInfo(AppsViewModelTest.CHROME, "Chrome", isSystem = true, installed = true),
        AppsViewModelTest.YOUTUBE to AppInfo(AppsViewModelTest.YOUTUBE, "YouTube", isSystem = false, installed = true),
        AppsViewModelTest.GMS to AppInfo(AppsViewModelTest.GMS, "Google Play services", isSystem = true, installed = true),
    )
    var sessions: List<AppSessionUsage> = emptyList()
    val historyCalls = mutableListOf<Triple<Int, Long, Long>>()

    override suspend fun snapshot(force: Boolean): AppStatsResult {
        calls += force
        // The answer is decided when the read starts; [gate] holds it back.
        val waitFor = gate
        val result = next(force)
        waitFor?.await()
        if (result is AppStatsResult.Ready) cached.value = result.snapshot
        return result
    }

    override fun access() = accessNow

    override suspend fun info(packageName: String) =
        infos[packageName] ?: AppInfo(packageName, packageName, isSystem = false, installed = false)

    override suspend fun launchablePackages() = launchable

    override suspend fun history(uid: Int, packageName: String, fromMs: Long, toMs: Long): List<AppSessionUsage> {
        historyCalls += Triple(uid, fromMs, toMs)
        return sessions
    }
}
