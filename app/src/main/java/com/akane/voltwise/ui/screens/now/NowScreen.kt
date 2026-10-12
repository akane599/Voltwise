package com.akane.voltwise.ui.screens.now

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akane.voltwise.battery.apps.AppInfoSource
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.service.SamplingDemand
import com.akane.voltwise.ui.screens.insights.infoOrNull
import com.akane.voltwise.viewmodel.NowEvent
import com.akane.voltwise.viewmodel.NowViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private const val DEMAND_TAG = "NowScreen"

/**
 * Now, wired: the Koin [NowViewModel], and 2 s sampling while the screen is started (the [SamplingDemand] token is
 * released at onStop, so nothing polls fast in the background). App icons come from the root's loader
 * (MainScreen); the insights headline's app label from [AppInfoSource]. Navigation leaves through the lambdas; every
 * other [NowEvent] goes to the ViewModel.
 */
@Composable
fun NowScreen(
    onOpenHistory: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenApp: (uid: Int, packageName: String) -> Unit,
    onOpenInsights: () -> Unit,
    onOpenFinding: (key: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: NowViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val demand: SamplingDemand = koinInject()
    LifecycleStartEffect(demand) {
        val token = demand.acquire(DEMAND_TAG)
        onStopOrDispose { token.close() }
    }
    val appInfo: AppInfoSource = koinInject()
    val headlinePackage = state.insightsSummary?.headline?.packageName
    // The headline carries no uid: -1 never reads as a system process, so an uninstalled app reads "unknown".
    val insightsApp by produceState<AppLabel?>(null, headlinePackage, appInfo) {
        value = null // a new headline never shows the previous one's app name while its own loads
        value = headlinePackage?.let { AppLabel.of(-1, it, appInfo.infoOrNull(it)) }
    }
    NowContent(
        state = state,
        onEvent = { event ->
            when (event) {
                NowEvent.OpenHistory -> onOpenHistory()
                NowEvent.OpenHealth -> onOpenHealth()
                NowEvent.OpenApps -> onOpenApps()
                is NowEvent.OpenApp -> onOpenApp(event.uid, event.packageName)
                else -> vm.onEvent(event)
            }
        },
        modifier = modifier,
        insightsApp = insightsApp,
        onOpenInsights = onOpenInsights,
        onOpenFinding = onOpenFinding,
    )
}
