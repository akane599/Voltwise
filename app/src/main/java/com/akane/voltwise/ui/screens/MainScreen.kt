package com.akane.voltwise.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.akane.voltwise.R
import com.akane.voltwise.battery.apps.AppInfoSource
import com.akane.voltwise.ui.NavGraph
import com.akane.voltwise.ui.TestTags
import com.akane.voltwise.ui.components.AppIconLoader
import com.akane.voltwise.ui.components.LocalAppIconLoader
import com.akane.voltwise.ui.components.chart.ProvideTimeAxisFormatter
import com.akane.voltwise.ui.navigation.Routes
import com.akane.voltwise.ui.navigation.openDestination
import com.akane.voltwise.ui.navigation.rememberTopLevelBackStack
import org.koin.compose.koinInject

/** [NavigationRail] replaces [NavigationBar] at this width and above (the Material breakpoint). */
private const val RAIL_MIN_WIDTH_DP = 600

private data class TabItem(val route: Routes, val icon: ImageVector, val labelRes: Int, val testTag: String)

private val TAB_ITEMS = listOf(
    TabItem(Routes.Now, Icons.Rounded.Bolt, R.string.tab_now, TestTags.TAB_NOW),
    TabItem(Routes.Insights, Icons.Rounded.Insights, R.string.insights_tab, TestTags.TAB_INSIGHTS),
    TabItem(Routes.History, Icons.Rounded.History, R.string.history, TestTags.TAB_HISTORY),
    TabItem(Routes.Apps, Icons.Rounded.Apps, R.string.tab_apps, TestTags.TAB_APPS),
    TabItem(Routes.Settings, Icons.Rounded.Settings, R.string.settings, TestTags.TAB_SETTINGS),
)

/**
 * The 5-tab shell: Now · Insights · History · Apps · Settings, bottom bar below [RAIL_MIN_WIDTH_DP], a rail
 * at or above it. [destination] is a `destination` deep-link extra value (see
 * `com.akane.voltwise.ui.navigation.Destinations`), applied once via [onDestinationHandled].
 * Every screen below gets app icons from [AppInfoSource] through [LocalAppIconLoader] and one shared time formatter
 * through `LocalTimeAxisFormatter`.
 */
@Composable
fun MainScreen(destination: String? = null, onDestinationHandled: () -> Unit = {}) {
    val appInfo: AppInfoSource = koinInject()
    val icons = remember(appInfo) { AppInfoIconLoader(appInfo) }
    CompositionLocalProvider(LocalAppIconLoader provides icons) {
        // One time formatter (one time-settings receiver) for every chart and time label below.
        ProvideTimeAxisFormatter { TabShell(destination, onDestinationHandled) }
    }
}

@Composable
private fun TabShell(destination: String?, onDestinationHandled: () -> Unit) {
    val topLevelBackStack = rememberTopLevelBackStack()
    LaunchedEffect(destination) {
        if (destination != null) {
            topLevelBackStack.openDestination(destination)
            onDestinationHandled()
        }
    }
    // NavDisplay's own back handling only fires while its visible stack has more than one entry;
    // this catches back at a non-Now tab's root (jump to Now), and NavDisplay's handler takes
    // precedence whenever it's enabled. At Now's root it is on only while another tab holds a
    // busy entry, which onBack() then shows instead of letting the activity finish and cancel that work; otherwise the
    // system's own (predictive) back runs. isLeavingBlocked() isn't observable, so it is re-read on each recomposition
    // (every tab switch). If the work ended since, onBack() returns false and the press goes on to the activity's own
    // back once this handler is off.
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var passBackOn by remember { mutableStateOf(false) }
    BackHandler(
        enabled = !passBackOn &&
            (topLevelBackStack.selectedTab != Routes.Now || topLevelBackStack.isLeavingBlocked()),
    ) {
        if (!topLevelBackStack.onBack()) passBackOn = true
    }
    if (passBackOn) {
        LaunchedEffect(Unit) {
            backDispatcher?.onBackPressed()
            passBackOn = false
        }
    }

    val useRail = LocalConfiguration.current.screenWidthDp >= RAIL_MIN_WIDTH_DP

    // Each inset is owned by exactly one layer, so it's applied exactly once:
    // - Top (status bar) is never claimed here — every screen inside NavGraph owns it via its own
    //   TopAppBar/Scaffold, same as before this shell existed.
    // - Bottom is claimed by whichever bar is showing: NavigationBar already reserves it inside
    //   its own height in bar mode; nothing does in rail mode, so the shell claims it for content.
    // - Start is claimed by NavigationRail itself in rail mode (a full-bleed edge column using its
    //   own default Vertical + Start insets); in bar mode nothing else claims Horizontal so the
    //   shell claims it for content, matching each screen's old standalone behavior.
    // `consumeWindowInsets` below then stops the screens' own nested Scaffolds from seeing (and
    // re-applying) whatever this shell already spent.
    val shellInsets = WindowInsets.safeDrawing.only(
        if (useRail) WindowInsetsSides.Bottom + WindowInsetsSides.End
        else WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
    )

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag(TestTags.ROOT),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            contentWindowInsets = shellInsets,
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        TAB_ITEMS.forEach { tab ->
                            NavigationBarItem(
                                selected = tab.route == topLevelBackStack.selectedTab,
                                onClick = { topLevelBackStack.select(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.testTag),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            if (useRail) {
                Row(Modifier.fillMaxSize()) {
                    // Full-bleed: keeps its own default (Vertical + Start) insets rather than
                    // whatever the Row/Scaffold would otherwise hand it, so it isn't padded twice.
                    NavigationRail {
                        TAB_ITEMS.forEach { tab ->
                            NavigationRailItem(
                                selected = tab.route == topLevelBackStack.selectedTab,
                                onClick = { topLevelBackStack.select(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.testTag),
                            )
                        }
                    }
                    NavGraph(
                        topLevelBackStack,
                        Modifier
                            .weight(1f)
                            .padding(padding)
                            .consumeWindowInsets(padding)
                            // Start is the rail's, not the content's, even though the rail didn't
                            // consume it from the shared ambient insets itself.
                            .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
                    )
                }
            } else {
                NavGraph(
                    topLevelBackStack,
                    Modifier.padding(padding).consumeWindowInsets(padding),
                )
            }
        }
    }
}

/** [AppIconLoader] over [AppInfoSource]: loads on IO (inside the source), peeks its LRU for [cached]. */
private class AppInfoIconLoader(private val source: AppInfoSource) : AppIconLoader {
    override suspend fun load(packageName: String): ImageBitmap? = source.icon(packageName)?.asImageBitmap()

    override fun cached(packageName: String): ImageBitmap? = source.cachedIcon(packageName)?.asImageBitmap()
}
