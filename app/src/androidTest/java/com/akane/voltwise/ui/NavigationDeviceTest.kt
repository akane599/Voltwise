package com.akane.voltwise.ui

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.akane.voltwise.R
import com.akane.voltwise.battery.BatteryGraph
import com.akane.voltwise.battery.BatteryMainActivity
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.service.BatteryMonitorService
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.test.DeviceEnvironment
import com.akane.voltwise.ui.TestTags
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    @get:Rule(order = 0) val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<BatteryMainActivity>()
    private lateinit var savedSettings: AppSettings
    private var savedFont: String? = null
    private var changedOrientation = false
    private fun label(id: Int) = DeviceEnvironment.context.getString(id)
    private fun click(id: Int) = compose.onNodeWithText(label(id)).performClick()
    private fun scroll(id: Int) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(id)))
        compose.onNodeWithText(label(id)).assertIsDisplayed()
    }
    private fun nowShowing() = compose.onAllNodes(
        hasText(label(R.string.now_start_monitoring)) or hasText(label(R.string.now_stop_monitoring)),
    ).fetchSemanticsNodes().isNotEmpty()
    private fun awaitNow() {
        compose.waitUntil(120_000) { nowShowing() }
        compose.onNodeWithTag(TestTags.TAB_NOW).assertIsSelected()
    }
    private fun tab(tag: String) = compose.onNodeWithTag(tag).performClick()
    private fun backToNow() {
        // A semantics click completes before recomposition removes a Dialog window.
        // Settle that window before sending Back through the separate Android input path.
        compose.waitForIdle()
        DeviceEnvironment.device.waitForIdle()
        DeviceEnvironment.device.pressBack()
        try { awaitNow() }
        catch (failure: Throwable) {
            runCatching { DeviceEnvironment.screenshot("navigation-back-failure") }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    private fun capture(name: String) { compose.waitForIdle(); DeviceEnvironment.screenshot(name) }

    @Before fun prepare() = runBlocking {
        DeviceEnvironment.requireDisposableEmulator()
        DeviceEnvironment.device.wakeUp()
        DeviceEnvironment.device.executeShellCommand("wm dismiss-keyguard")
        savedFont = DeviceEnvironment.device.executeShellCommand("settings get system font_scale").trim()
        savedSettings = BatteryGraph.settings.flow.first()
        DeviceEnvironment.context.stopService(Intent(DeviceEnvironment.context, BatteryMonitorService::class.java))
        BatteryGraph.repo.stopSampling()
        BatteryGraph.settings.update { it.copy(dynamicColors = false) }
    }

    @After fun restore() = runBlocking {
        if (changedOrientation) {
            DeviceEnvironment.device.setOrientationNatural()
            DeviceEnvironment.device.unfreezeRotation()
        }
        if (::savedSettings.isInitialized) BatteryGraph.settings.update { savedSettings }
        val font = savedFont ?: return@runBlocking
        if (font.matches(Regex("[0-9.]+"))) DeviceEnvironment.device.executeShellCommand("settings put system font_scale $font")
        else DeviceEnvironment.device.executeShellCommand("settings delete system font_scale")
    }

    @Test fun screensRemainReachableAndInvalidImportPreservesSettings() {
        compose.waitUntil(120_000) { BatteryGraph.repo.realtimeFlow.value.level != null }
        awaitNow()
        compose.onNodeWithText(label(R.string.now_start_monitoring)).assertIsDisplayed()
        capture("now-dark")
        scroll(R.string.now_apps_title); capture("now-cards")

        tab(TestTags.TAB_APPS)
        compose.onNodeWithContentDescription(label(R.string.apps_refresh)).assertIsDisplayed()
        // Without Shizuku or root (ADB grants can't read per-app stats on API 36) Apps shows the access banner;
        // with access, the list and its search field.
        compose.waitUntil(120_000) { showing(R.string.apps_access_set_up) || showing(R.string.apps_search) }
        capture("apps-access")
        if (showing(R.string.apps_access_set_up)) {
            // "Set up access" opens Settings › Status on the Apps tab; Back returns to Apps.
            click(R.string.apps_access_set_up)
            compose.waitUntil(120_000) { showing(R.string.status_access_title) }
            compose.onNodeWithTag(TestTags.TAB_APPS).assertIsSelected()
            capture("access-setup-status")
            DeviceEnvironment.device.pressBack()
            compose.waitUntil(120_000) { showing(R.string.apps_access_set_up) }
        }
        backToNow()

        // History -> a session's details -> Back to History (Sessions still selected) -> Back to Now.
        val session = navigationSession()
        try {
            runBlocking { BatteryGraph.repo.sessionDao.insert(session) }
            tab(TestTags.TAB_HISTORY)
            compose.onNodeWithText(label(R.string.history_mode_days)).assertIsSelected()
            capture("history-days")
            click(R.string.history_mode_sessions)
            compose.onNodeWithText(label(R.string.history_filter_all)).assertIsDisplayed()
            val row = sessionRowText()
            // The list is lazy and may hold the emulator's own sessions: scroll until the inserted row is composed.
            compose.waitUntil(120_000) {
                runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText(row)) }.isSuccess
            }
            capture("history-sessions")
            compose.onNodeWithText(row).performClick()
            // SessionDetails: the session type as its title and its delete action (the session has no readings).
            compose.waitUntil(120_000) { showing(R.string.sessiondetails_type_discharge) }
            compose.onNodeWithContentDescription(label(R.string.sessiondetails_delete)).assertIsDisplayed()
            compose.onNodeWithTag(TestTags.TAB_HISTORY).assertIsSelected()
            capture("session-details")
            DeviceEnvironment.device.pressBack()
            // History comes back at its restored scroll position (the inserted row), so the mode and
            // filter headers may be scrolled out of the lazy list: wait for the row, then scroll up.
            try {
                compose.waitUntil(120_000) {
                    compose.onAllNodesWithContentDescription(label(R.string.sessiondetails_delete)).fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodesWithText(row).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (failure: Throwable) {
                runCatching { DeviceEnvironment.screenshot("session-details-back-failure") }
                    .exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(R.string.history_filter_all)))
            compose.onNodeWithText(label(R.string.history_filter_all)).assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(R.string.history_mode_sessions)))
            compose.onNodeWithText(label(R.string.history_mode_sessions)).assertIsSelected()
            // History is a tab root: Back returns to Now.
            backToNow()
        } finally {
            runBlocking { BatteryGraph.repo.sessionDao.deleteSession(session.sessionId, recordingGeneration = null) }
        }

        tab(TestTags.TAB_SETTINGS)
        openSettingsData()
        compose.onNodeWithText(label(R.string.data_settings_restore)).performScrollTo().assertIsDisplayed()
        capture("settings-data")
        // An invalid settings file through Data's real restore path; the picker is answered with a file in the app cache.
        val invalid = File(DeviceEnvironment.context.cacheDir, "navigation-invalid-settings.json").apply { writeText("{invalid}") }
        try {
            val before = runBlocking { BatteryGraph.settings.flow.first() }
            answeringOpenDocument(Uri.fromFile(invalid)) { click(R.string.data_settings_restore) }
            compose.waitUntil(120_000) { showing(R.string.data_failed_settings_invalid) }
            compose.onNodeWithText(label(R.string.data_failed_settings_invalid)).performScrollTo().assertIsDisplayed()
            Assert.assertEquals(before, runBlocking { BatteryGraph.settings.flow.first() })
            capture("settings-data-invalid-import")
        } finally {
            invalid.delete()
        }

        // Back to Settings, then Settings › Status; Back returns to Settings.
        DeviceEnvironment.device.pressBack()
        compose.waitUntil(120_000) { showing(R.string.settings_monitoring_title) }
        scroll(R.string.settings_status_link)
        click(R.string.settings_status_link)
        compose.waitUntil(120_000) { showing(R.string.status_access_title) }
        compose.onNodeWithTag(TestTags.TAB_SETTINGS).assertIsSelected()
        capture("settings-status")
        DeviceEnvironment.device.pressBack()
        compose.waitUntil(120_000) { showing(R.string.settings_monitoring_title) }
        backToNow()
    }

    private fun showing(id: Int) = compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty()

    /** The visible screen's vertical scroll position (its one scrollable container). */
    private fun scrollOffset(): Float =
        compose.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    /**
     * A closed, imported discharge session from yesterday, so it sorts near the top of History's newest-first list
     * (a session row doesn't touch the daily totals).
     */
    private fun navigationSession(): ChargeSession {
        val start = System.currentTimeMillis() - SESSION_AGE_MS
        return ChargeSession(
            "navigation-session", SessionType.DISCHARGE, start, start + 7 * 60_000L, 63, 58, 70_000, -600_000, null,
            observationId = "navigation-import", lastSampleTime = start + 7 * 60_000L, observedMs = 7 * 60_000L,
            counterCoveredMs = 7 * 60_000L, screenOnMs = 7 * 60_000L, screenOffMs = 0,
            source = "import:navigation BatteryManager counter observations",
        )
    }

    /** The session row's second line: "7 min · 63% → 58%". */
    private fun sessionRowText(): String {
        val context = DeviceEnvironment.context
        return context.getString(
            R.string.history_session_detail,
            context.getString(R.string.now_duration_minutes, "7"),
            context.getString(
                R.string.history_level_change,
                context.getString(R.string.percent_value, "63"),
                context.getString(R.string.percent_value, "58"),
            ),
        )
    }

    /** Settings › Data from the Settings tab. */
    private fun openSettingsData() {
        scroll(R.string.settings_data_link)
        click(R.string.settings_data_link)
        compose.waitUntil(120_000) { showing(R.string.data_stored_title) }
    }

    /**
     * Runs [block] while every `ACTION_OPEN_DOCUMENT` this process starts is answered at once with [uri] (the
     * system picker never opens), and waits until one was.
     */
    private fun answeringOpenDocument(uri: Uri, block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
                } else {
                    null
                }
        }
        instrumentation.addMonitor(monitor)
        try {
            block()
            compose.waitUntil(10_000) { monitor.hits > 0 }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun largeTextDarkThemeKeepsActionsAndResetExplanationReachable() {
        DeviceEnvironment.device.executeShellCommand("settings put system font_scale 2.0")
        runBlocking { BatteryGraph.settings.update { it.copy(dynamicColors = false) } }
        compose.waitUntil(120_000) { compose.activity.resources.configuration.fontScale >= 1.99f }
        awaitNow()
        compose.onNodeWithText(label(R.string.now_start_monitoring)).assertIsDisplayed()
        capture("now-dark-font200")
        scroll(R.string.now_apps_title); capture("now-cards-dark-font200")
        // Settings' only destructive row at 200 %: its explanation and both actions stay readable and apart.
        tab(TestTags.TAB_SETTINGS)
        scroll(R.string.settings_reset_calibration); click(R.string.settings_reset_calibration)
        compose.onNodeWithText(label(R.string.settings_reset_calibration_body)).assertIsDisplayed()
        val confirm = compose.onNodeWithText(label(R.string.settings_reset_calibration_confirm)).fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText(label(R.string.settings_cancel)).fetchSemanticsNode().boundsInRoot
        Assert.assertFalse("Dialog actions must not overlap at 200% font", confirm.overlaps(cancel))
        capture("settings-reset-calibration-dark-font200")
        click(R.string.settings_cancel) // Inspect the control without forgetting the calibration.
        backToNow()
        changedOrientation = true
        DeviceEnvironment.device.setOrientationLeft()
        compose.waitUntil(120_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        awaitNow()
        capture("now-dark-font200-landscape")
        scroll(R.string.now_apps_title)
        capture("now-cards-dark-font200-landscape")
    }

    @Test fun tabsAreReachableBackReturnsToNowAndRetapPopsToRoot() {
        compose.waitUntil(120_000) { BatteryGraph.repo.realtimeFlow.value.level != null }
        awaitNow()

        // Each tab opens its screen directly (no push); back from a tab root returns to Now.
        tab(TestTags.TAB_APPS)
        compose.onNodeWithContentDescription(label(R.string.apps_refresh)).assertIsDisplayed()
        backToNow()

        tab(TestTags.TAB_HISTORY)
        compose.onNodeWithText(label(R.string.history_mode_days)).assertIsDisplayed()
        backToNow()

        tab(TestTags.TAB_SETTINGS)
        compose.onNodeWithText(label(R.string.settings_monitoring_title)).assertIsDisplayed()
        backToNow()

        // Now's Health panel pushes Health onto the Now tab; re-tapping Now pops back to its root.
        scroll(R.string.now_health_title); click(R.string.now_health_title)
        compose.waitUntil(120_000) { !nowShowing() }
        compose.onNodeWithText(label(R.string.health_title)).assertIsDisplayed()
        capture("health")
        tab(TestTags.TAB_NOW)
        awaitNow()
    }

    /**
     * Every tab keeps its screen state and ViewModel while another tab is shown: History's mode and filter, a
     * scrolled Settings page, and (when this device can read per-app stats) Apps' search and sort.
     */
    @Test fun tabStateSurvivesARoundTripThroughAnotherTab() {
        compose.waitUntil(120_000) { BatteryGraph.repo.realtimeFlow.value.level != null }
        awaitNow()

        tab(TestTags.TAB_HISTORY)
        click(R.string.history_mode_sessions)
        compose.waitUntil(120_000) { showing(R.string.history_filter_discharge) }
        click(R.string.history_filter_discharge)
        compose.onNodeWithText(label(R.string.history_filter_discharge)).assertIsSelected()
        tab(TestTags.TAB_NOW)
        awaitNow()
        tab(TestTags.TAB_HISTORY)
        compose.onNodeWithText(label(R.string.history_mode_sessions)).assertIsSelected()
        compose.onNodeWithText(label(R.string.history_filter_discharge)).assertIsSelected()
        capture("tab-state-history")

        // Settings scrolled to its Data link (the last panel) keeps its scroll position after the round trip.
        tab(TestTags.TAB_SETTINGS)
        scroll(R.string.settings_data_link)
        compose.waitForIdle()
        val scrolled = scrollOffset()
        Assert.assertTrue("Settings should be scrolled down, was $scrolled", scrolled > 0f)
        tab(TestTags.TAB_NOW)
        awaitNow()
        tab(TestTags.TAB_SETTINGS)
        compose.waitForIdle()
        Assert.assertEquals(scrolled, scrollOffset(), 1f)
        compose.onNodeWithText(label(R.string.settings_data_link)).assertIsDisplayed()

        tab(TestTags.TAB_APPS)
        compose.waitUntil(120_000) { showing(R.string.apps_access_set_up) || showing(R.string.apps_search) }
        if (showing(R.string.apps_search)) {
            compose.onNodeWithText(label(R.string.apps_search)).performTextInput("go")
            click(R.string.apps_sort_cpu)
            tab(TestTags.TAB_NOW)
            awaitNow()
            tab(TestTags.TAB_APPS)
            compose.onNodeWithText("go").assertIsDisplayed()
            compose.onNodeWithText(label(R.string.apps_sort_cpu)).assertIsSelected()
        }
        // Without Shizuku or root (the ordinary emulator) Apps has no list, so only History and Settings are checked.
        backToNow()
    }

    private companion object {
        const val SESSION_AGE_MS = 26 * 60 * 60_000L
    }
}
