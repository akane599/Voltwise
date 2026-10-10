package com.akane.voltwise.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.R
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.test.DeviceEnvironment
import com.akane.voltwise.ui.theme.MainTheme
import com.akane.voltwise.viewmodel.AccessProblem
import com.akane.voltwise.viewmodel.AppListRow
import com.akane.voltwise.viewmodel.AppSort
import com.akane.voltwise.viewmodel.AppsEvent
import com.akane.voltwise.viewmodel.AppsUiState
import com.akane.voltwise.viewmodel.StatsProblem
import com.akane.voltwise.viewmodel.StatsSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the stateless [AppsContent] with hand-built [AppsUiState]s (no ViewModel, no privileged read): sort chips
 * forward [AppsEvent.SetSort] and the list re-renders in whatever order the (simulated) recompute hands back,
 * search forwards [AppsEvent.SetQuery] and filters the visible rows, the system-apps switch forwards
 * [AppsEvent.SetShowSystem], and the no-access banner shows its explanation and setup action.
 */
@RunWith(AndroidJUnit4::class)
class AppsContentDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun label(id: Int, vararg args: Any): String = DeviceEnvironment.context.getString(id, *args)
    private lateinit var events: MutableList<AppsEvent>

    private fun row(uid: Int, packageName: String, label: AppLabel, value: Double?, share: Float) =
        AppListRow(uid, packageName, label, value, share)

    private val chrome = row(10_123, "com.android.chrome", AppLabel.Named("Chrome"), 200.0, 0.6f)
    private val maps = row(10_311, "com.google.android.apps.maps", AppLabel.Named("Maps"), 50.0, 0.15f)
    private val spotify = row(10_402, "com.spotify.music", AppLabel.Named("Spotify"), 20.0, 0.06f)

    /** [initial] as a mutable holder, so a test can push a later state (simulating a ViewModel recompute). */
    private fun setContent(
        initial: AppsUiState,
        initialQuery: String = "",
    ): Pair<androidx.compose.runtime.MutableState<AppsUiState>, androidx.compose.runtime.MutableState<String>> {
        events = mutableListOf()
        val stateHolder = mutableStateOf(initial)
        val queryHolder = mutableStateOf(initialQuery)
        compose.setContent {
            MainTheme(dynamicColor = false) {
                AppsContent(
                    state = stateHolder.value,
                    query = queryHolder.value,
                    onEvent = { event ->
                        events += event
                        // The real ViewModel echoes the search text synchronously; simulated here so typing works.
                        if (event is AppsEvent.SetQuery) queryHolder.value = event.query
                    },
                )
            }
        }
        return stateHolder to queryHolder
    }

    /** A read that came back: without it [AppsContent] shows the loading skeleton regardless of [AppsUiState.rows]. */
    private val summary = StatsSummary(
        startedAtMs = 0L, capturedAtMs = 0L, onBatteryMs = null, screenOnMs = null, screenOffMs = null,
        screenOnUsedPercent = null, screenOffUsedPercent = null, deepDozeMs = null, lightDozeMs = null, capacityMah = null,
    )

    private fun loaded() = AppsUiState(summary = summary, rows = listOf(chrome, maps, spotify), sort = AppSort.BATTERY)

    @Test fun sortChipFiresSetSortAndTheListReflectsTheNewOrder() {
        val (state, _) = setContent(loaded())
        // Largest first under Battery: Chrome (200) above Maps (50) above Spotify (20).
        val chromeTop = compose.onNodeWithText("Chrome").fetchSemanticsNode().boundsInRoot.top
        val mapsTop = compose.onNodeWithText("Maps").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Chrome (largest) must be above Maps under Battery sort", chromeTop < mapsTop)

        compose.onNodeWithText(label(R.string.apps_sort_cpu)).performClick()
        assertEquals(listOf(AppsEvent.SetSort(AppSort.CPU)), events)

        // The ViewModel would recompute rows for the new sort; simulated here as CPU time, Maps now largest.
        compose.runOnUiThread {
            state.value = state.value.copy(
                sort = AppSort.CPU,
                rows = listOf(
                    maps.copy(value = 900.0, share = 0.7f),
                    chrome.copy(value = 300.0, share = 0.2f),
                    spotify.copy(value = 50.0, share = 0.05f),
                ),
            )
        }
        val mapsTopAfter = compose.onNodeWithText("Maps").fetchSemanticsNode().boundsInRoot.top
        val chromeTopAfter = compose.onNodeWithText("Chrome").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Maps (largest under CPU) must now be above Chrome", mapsTopAfter < chromeTopAfter)
    }

    @Test fun searchFiltersTheVisibleRowsAndForwardsTheQuery() {
        val (state, query) = setContent(loaded())
        compose.onNode(hasSetTextAction()).performTextInput("spotify")
        assertEquals(listOf(AppsEvent.SetQuery("spotify")), events)
        assertEquals("spotify", query.value)

        // The ViewModel would recompute the filtered rows; simulated here.
        compose.runOnUiThread { state.value = state.value.copy(rows = listOf(spotify)) }
        compose.onNodeWithText("Spotify").assertIsDisplayed()
        compose.onAllNodesWithText("Chrome").assertCountEquals(0)
        compose.onAllNodesWithText("Maps").assertCountEquals(0)
    }

    @Test fun searchWithOnlyHiddenSystemMatchesOffersToShowSystemApps() {
        setContent(loaded().copy(rows = emptyList(), hiddenSystem = 1), initialQuery = "play services")
        compose.onNodeWithText(label(R.string.apps_empty_search_title, "play services")).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.apps_show_system)).performClick()
        assertEquals(listOf(AppsEvent.SetShowSystem(true)), events)
    }

    @Test fun systemToggleFiresSetShowSystem() {
        setContent(loaded())
        compose.onNode(hasText(label(R.string.apps_system_toggle)) and hasClickAction()).performClick()
        assertEquals(listOf(AppsEvent.SetShowSystem(true)), events)
    }

    @Test fun noAccessShowsTheExplanationAndTheSetupAction() {
        setContent(AppsUiState(problem = StatsProblem.NoAccess(AccessProblem.NOT_SET_UP)))
        compose.onNodeWithText(label(R.string.apps_access_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.apps_access_not_set_up)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.apps_no_access_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.apps_access_set_up)).performClick()
        assertEquals(listOf(AppsEvent.OpenAccessSetup), events)
    }
}
