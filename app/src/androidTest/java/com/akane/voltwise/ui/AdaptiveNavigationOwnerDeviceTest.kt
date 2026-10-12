package com.akane.voltwise.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.battery.apps.AppInfo
import com.akane.voltwise.battery.apps.AppStatsResult
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.HistoryImportResult
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.DailySummary
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.util.BatteryStatsParser
import com.akane.voltwise.battery.util.ShellRunner
import com.akane.voltwise.ui.navigation.Routes
import com.akane.voltwise.ui.navigation.TopLevelBackStack
import com.akane.voltwise.ui.navigation.blockLeavingWhileBusy
import com.akane.voltwise.ui.navigation.rememberTopLevelBackStack
import com.akane.voltwise.ui.screens.AdaptiveTabShell
import com.akane.voltwise.ui.theme.MainTheme
import com.akane.voltwise.viewmodel.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real shell + production entry decorators, without Koin or storage. Requires a device, not a JVM oracle. */
@RunWith(AndroidJUnit4::class)
class AdaptiveNavigationOwnerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun resizing599To600To599PreservesEntryOwnersControlsAndBusyExport() {
        val width = mutableIntStateOf(599)
        val repository = SuspendedData()
        lateinit var stack: TopLevelBackStack
        lateinit var history: HistoryViewModel
        lateinit var historyHandle: SavedStateHandle
        lateinit var apps: AppsViewModel
        lateinit var appsHandle: SavedStateHandle
        lateinit var data: DataViewModel
        var savedHistoryText = mutableStateOf("")
        compose.setContent {
            val base = LocalConfiguration.current
            val configuration = remember(base, width.intValue) {
                Configuration(base).apply { screenWidthDp = width.intValue }
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MainTheme {
                    val navigation = rememberTopLevelBackStack()
                    SideEffect { stack = navigation }
                    AdaptiveTabShell(navigation) { modifier ->
                        DecoratedTabContent(navigation, modifier, entryProvider<NavKey> {
                            entry<Routes.Now> { Text("Now") }
                            entry<Routes.Insights> { Text("Insights") }
                            entry<Routes.Settings> { Text("Settings") }
                            entry<Routes.History> {
                                val vm = viewModel {
                                    val handle = createSavedStateHandle()
                                    historyHandle = handle
                                    HistoryViewModel(EmptyHistory(), handle)
                                }
                                val text = rememberSaveable { mutableStateOf("default") }
                                SideEffect { history = vm; savedHistoryText = text }
                                Text(text.value)
                            }
                            entry<Routes.Apps> {
                                val vm = viewModel {
                                    val handle = createSavedStateHandle()
                                    appsHandle = handle
                                    AppsViewModel(EmptyApps(), handle)
                                }
                                SideEffect { apps = vm }
                                Text(vm.query)
                            }
                            entry<Routes.SettingsData> {
                                val vm = viewModel { DataViewModel(repository, createSavedStateHandle()) }
                                remember(vm) {
                                    vm.blockLeavingWhileBusy(
                                        { busy, blocked -> navigation.blockLeaving(Routes.SettingsData, busy, blocked) },
                                        { !vm.state.value.idle },
                                    )
                                }
                                SideEffect { data = vm }
                                Text("Data")
                            }
                        })
                    }
                }
            }
        }
        compose.runOnIdle { stack.select(Routes.History) }
        compose.waitForIdle()
        val originalHistory = history
        val originalHistoryHandle = historyHandle
        compose.runOnIdle {
            history.onEvent(HistoryEvent.SelectMode(HistoryMode.SESSIONS))
            history.onEvent(HistoryEvent.SelectFilter(SessionFilter.CHARGE))
            savedHistoryText.value = "retained scroll/control"
            stack.select(Routes.Apps)
        }
        compose.waitForIdle()
        val originalApps = apps
        val originalAppsHandle = appsHandle
        compose.runOnIdle {
            apps.onEvent(AppsEvent.SetQuery("volt"))
            apps.onEvent(AppsEvent.SetSort(AppSort.CPU))
            apps.onEvent(AppsEvent.SetShowSystem(true))
            stack.select(Routes.Settings)
            stack.navigate(Routes.SettingsData)
        }
        compose.waitForIdle()
        val originalData = data
        compose.runOnIdle {
            data.onEvent(DataEvent.SelectRange(HistoryRange.WEEK))
            data.onEvent(DataEvent.Pick(DataTask.EXPORT_JSON))
            data.onFileChosen(DataTask.EXPORT_JSON, "content://test/export")
        }
        compose.waitUntil(5_000) { repository.started.isCompleted }
        compose.runOnIdle { stack.select(Routes.Now) }
        for (nextWidth in listOf(600, 599)) {
            compose.runOnIdle { width.intValue = nextWidth }
            compose.waitForIdle()
            compose.runOnIdle {
                assertFalse("Resize must not cancel off-screen Data work", repository.cancelled.get())
                assertTrue(stack.isLeavingBlocked())
                assertFalse("Busy Data must still block its root link", stack.openRoot(Routes.Settings))
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertSame("Data entry ViewModel", originalData, data)
                assertEquals(HistoryRange.WEEK, data.state.value.range)
                assertEquals(DataTask.EXPORT_JSON, data.state.value.running)
                stack.select(Routes.History)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertSame("History entry ViewModel", originalHistory, history)
                assertSame("History SavedStateHandle", originalHistoryHandle, historyHandle)
                assertEquals(HistoryMode.SESSIONS.name, historyHandle.get<String>(HistoryViewModel.KEY_MODE))
                assertEquals(SessionFilter.CHARGE.name, historyHandle.get<String>(HistoryViewModel.KEY_FILTER))
                assertEquals("retained scroll/control", savedHistoryText.value)
                stack.select(Routes.Apps)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertSame("Apps entry ViewModel", originalApps, apps)
                assertSame("Apps SavedStateHandle", originalAppsHandle, appsHandle)
                assertEquals("volt", apps.query)
                assertEquals(AppSort.CPU.name, appsHandle.get<String>(AppsViewModel.KEY_SORT))
                assertEquals(true, appsHandle.get<Boolean>(AppsViewModel.KEY_SHOW_SYSTEM))
                stack.select(Routes.Now)
            }
        }
        repository.finish.complete(Unit)
        compose.waitUntil(5_000) { originalData.state.value.outcome == DataOutcome.Exported(DataTask.EXPORT_JSON) }
        compose.runOnIdle {
            assertFalse(repository.cancelled.get())
            assertFalse(stack.isLeavingBlocked())
        }
    }

    private class EmptyHistory : HistoryRepository {
        override val realtime = flowOf(BatteryRepository.Realtime())
        override val isMonitoring = flowOf(false)
        override val recordingObservation = flowOf<String?>(null)
        override fun days(fromDay: Long, toDay: Long) = flowOf(emptyList<DailySummary>())
        override fun sessions(type: SessionType?, limit: Int) = flowOf(emptyList<ChargeSession>())
    }

    private class EmptyApps : AppsRepository {
        override val cached = MutableStateFlow<BatteryStatsParser.FullSnapshot?>(null)
        override val shizuku = flowOf(ShizukuState(running = false, granted = false))
        override suspend fun snapshot(force: Boolean) = AppStatsResult.NoAccess
        override fun access() = AccessSnapshot(ShellRunner.Mode.NONE, null, ShizukuState(running = false, granted = false))
        override suspend fun info(packageName: String) = AppInfo(packageName, packageName, isSystem = false, installed = false)
        override suspend fun launchablePackages() = emptySet<String>()
    }

    private class SuspendedData : DataRepository {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        override suspend fun storedHistory() = StoredHistory(0, 0)
        override suspend fun exportJson(uri: String, fromMs: Long, toMs: Long, samples: Boolean, sessions: Boolean) {
            started.complete(Unit)
            try { finish.await() } catch (e: CancellationException) { cancelled.set(true); throw e }
        }
        override suspend fun exportCsv(treeUri: String, fromMs: Long, toMs: Long, samples: Boolean, sessions: Boolean) = Unit
        override suspend fun importJson(uri: String) = HistoryImportResult(0, 0, 0, 0)
        override suspend fun importCsv(uri: String) = HistoryImportResult(0, 0, 0, 0)
        override suspend fun saveSettings(uri: String) = Unit
        override suspend fun restoreSettings(uri: String) = SettingsRestore.Applied(0, 0, 0)
        override suspend fun clearAll() = Unit
    }
}
