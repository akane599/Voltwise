package com.akane.voltwise.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.akane.voltwise.ui.navigation.Routes
import com.akane.voltwise.ui.navigation.TOP_LEVEL_TABS
import com.akane.voltwise.ui.navigation.TopLevelBackStack
import com.akane.voltwise.ui.screens.AppDetailsScreen
import com.akane.voltwise.ui.screens.AppsScreen
import com.akane.voltwise.ui.screens.DataScreen
import com.akane.voltwise.ui.screens.HealthScreen
import com.akane.voltwise.ui.screens.HistoryScreen
import com.akane.voltwise.ui.screens.SessionDetailsScreen
import com.akane.voltwise.ui.screens.SettingsScreen
import com.akane.voltwise.ui.screens.StatusScreen
import com.akane.voltwise.ui.screens.insights.FindingDetailsScreen
import com.akane.voltwise.ui.screens.insights.InsightsScreen
import com.akane.voltwise.ui.screens.now.NowScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Entries for every [Routes] key, rendered against [topLevelBackStack]'s currently visible tab.
 * One entry provider is shared by all 5 tabs: a detail route (e.g. [Routes.SettingsStatus]) is pushed onto
 * whichever tab is active when it's reached (Settings; or the access banner or notice of Apps, Insights,
 * FindingDetails, SessionDetails or AppDetails).
 *
 * Every tab's stack is decorated all the time, each with its own saved-state and ViewModel stores; `NavDisplay`
 * only gets the visible tab's entries. Switching tabs therefore pops nothing: each tab keeps its screen state
 * (History's mode and filter, Apps' search and sort, scroll positions) and its ViewModels. Only a real pop (back,
 * re-tapping the tab) clears an entry's state.
 */
@Composable
fun NavGraph(
    topLevelBackStack: TopLevelBackStack,
    modifier: Modifier = Modifier,
) {
    // Up actions name their own entry so a double tap (or a late leave signal) pops it at most once.
    val popBack: (NavKey) -> Unit = { entry -> topLevelBackStack.onBack(entry) }
    // One-shot: Now's Today card asks History for today's figures.
    var historyShowToday by rememberSaveable { mutableStateOf(false) }
    val entryProvider = entryProvider<NavKey> {
        // Now -> Health, AppDetails, FindingDetails (pushed); Today opens History › Days at today, "See all" opens Apps
        // and the Insights card opens Insights, each at its root
        entry<Routes.Now> {
            NowScreen(
                onOpenHistory = {
                    historyShowToday = true
                    topLevelBackStack.openRoot(Routes.History)
                },
                onOpenHealth = { topLevelBackStack.navigate(Routes.Health) },
                onOpenApps = { topLevelBackStack.openRoot(Routes.Apps) },
                onOpenApp = { uid, packageName -> topLevelBackStack.navigate(Routes.AppDetails(uid, packageName)) },
                onOpenInsights = { topLevelBackStack.openRoot(Routes.Insights) },
                onOpenFinding = { key -> topLevelBackStack.navigate(Routes.FindingDetails(key)) },
            )
        }

        // Insights -> FindingDetails (pushed); its access notice -> Settings › Status
        entry<Routes.Insights> {
            InsightsScreen(
                onOpenFinding = { key -> topLevelBackStack.navigate(Routes.FindingDetails(key)) },
                onOpenAccessSetup = { topLevelBackStack.navigate(Routes.SettingsStatus) },
            )
        }

        // FindingDetails -> Settings › Status (no access); Back, also once Not a problem / Dismiss took the finding away
        entry<Routes.FindingDetails> { args ->
            FindingDetailsScreen(
                findingKey = args.key,
                onBack = { popBack(args) },
                onOpenAccessSetup = { topLevelBackStack.navigate(Routes.SettingsStatus) },
            )
        }

        // History -> SessionDetails
        entry<Routes.History> {
            HistoryScreen(
                onOpenSession = { id -> topLevelBackStack.navigate(Routes.SessionDetails(id)) },
                showToday = historyShowToday,
                onTodayShown = { historyShowToday = false },
            )
        }

        // SessionDetails -> AppDetails (from the per-app list), Settings › Status (no access); Back after a delete
        entry<Routes.SessionDetails> { args ->
            SessionDetailsScreen(
                onBack = { popBack(args) },
                onOpenApp = { uid, packageName -> topLevelBackStack.navigate(Routes.AppDetails(uid, packageName)) },
                onOpenAccessSetup = { topLevelBackStack.navigate(Routes.SettingsStatus) },
                vm = koinViewModel(parameters = { parametersOf(args.sessionId) }),
            )
        }

        // Apps -> AppDetails; the access banner -> Settings › Status
        entry<Routes.Apps> {
            AppsScreen(
                onOpenApp = { uid, packageName -> topLevelBackStack.navigate(Routes.AppDetails(uid, packageName)) },
                onOpenAccessSetup = { topLevelBackStack.navigate(Routes.SettingsStatus) },
            )
        }

        // AppDetails -> FindingDetails (pushed, from its Findings); its access notice -> Settings › Status
        entry<Routes.AppDetails> { args ->
            AppDetailsScreen(
                uid = args.uid,
                packageName = args.packageName,
                onBack = { popBack(args) },
                onOpenAccessSetup = { topLevelBackStack.navigate(Routes.SettingsStatus) },
                onOpenFinding = { key -> topLevelBackStack.navigate(Routes.FindingDetails(key)) },
            )
        }

        // Health (from Now's Health card or the "health" deep link) -> SessionDetails; design capacity is set in place
        entry<Routes.Health> { key ->
            HealthScreen(
                onBack = { popBack(key) },
                onOpenSession = { id -> topLevelBackStack.navigate(Routes.SessionDetails(id)) },
            )
        }

        // Settings -> SettingsData, SettingsStatus (pushed)
        entry<Routes.Settings> {
            SettingsScreen(
                onOpenData = { topLevelBackStack.navigate(Routes.SettingsData) },
                onOpenStatus = { topLevelBackStack.navigate(Routes.SettingsStatus) },
            )
        }

        // A running task holds re-taps of Settings and links to its root off this entry too, even while another tab is
        // visible (Back is guarded inside).
        entry<Routes.SettingsData> { key ->
            DataScreen(
                onBack = { popBack(key) },
                blockLeaving = { isBusy, onBlocked -> topLevelBackStack.blockLeaving(key, isBusy, onBlocked) },
            )
        }

        entry<Routes.SettingsStatus> { key ->
            StatusScreen(onBack = { popBack(key) })
        }
    }
    val entries = TOP_LEVEL_TABS.associateWith { tab ->
        key(tab) {
            rememberDecoratedNavEntries(
                backStack = topLevelBackStack.stack(tab),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider,
            )
        }
    }
    NavDisplay(
        entries = entries.getValue(topLevelBackStack.selectedTab),
        onBack = { topLevelBackStack.onBack() },
        modifier = modifier,
    )
}
