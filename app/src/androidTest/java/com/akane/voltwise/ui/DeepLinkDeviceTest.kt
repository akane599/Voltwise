package com.akane.voltwise.ui

import android.content.Intent
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import com.akane.voltwise.R
import com.akane.voltwise.battery.BatteryGraph
import com.akane.voltwise.battery.BatteryMainActivity
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.test.DeviceEnvironment
import com.akane.voltwise.ui.navigation.Destinations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Complements [NavigationDeviceTest] (tabs, back-to-root, re-tap): the `destination` intent extra a fresh launch
 * carries (notification tap, QS tile, widgets — see [Destinations]) opens straight to the right tab or pushed
 * screen, and pressing Back at the Now tab's root — the one place [ui.screens.MainScreen]'s own `BackHandler` is
 * disabled — finishes the activity instead of doing nothing. Each case gets its own [ActivityScenario] (a fresh
 * `BatteryMainActivity`, not [NavigationDeviceTest]'s rule-launched one) so the deep link is read from `onCreate`,
 * exactly as a real launch would.
 */
@RunWith(AndroidJUnit4::class)
class DeepLinkDeviceTest {
    private val context get() = DeviceEnvironment.context
    private val device get() = DeviceEnvironment.device
    private fun label(id: Int) = context.getString(id)

    private fun intent(destination: String) = Intent(context, BatteryMainActivity::class.java).apply {
        putExtra(Destinations.EXTRA_DESTINATION, destination)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Launches fresh with [destination], asserts [marker] appears (above the fold, no scroll), runs [andThen] while
     * that activity is still alive, then closes it.
     */
    private fun opensTo(destination: String, marker: BySelector, description: String = destination, andThen: () -> Unit = {}) {
        val scenario = ActivityScenario.launch<BatteryMainActivity>(intent(destination))
        try {
            assertTrue("destination=$destination must show $description", device.wait(Until.hasObject(marker), 120_000) == true)
            andThen()
        } finally {
            scenario.close()
        }
    }

    private fun text(id: Int) = By.text(label(id)).pkg(context.packageName)
    private fun desc(id: Int) = By.desc(label(id)).pkg(context.packageName)

    /** Now shows one of the two monitoring-button states, whichever the emulator happens to be in. */
    private fun nowMarker() = By.text(Pattern.compile("^(${Pattern.quote(label(R.string.now_start_monitoring))}|" +
        "${Pattern.quote(label(R.string.now_stop_monitoring))})$")).pkg(context.packageName)

    @Test fun deepLinksOpenEachTopLevelTab() {
        DeviceEnvironment.requireDisposableEmulator()
        // Never the bottom bar's own tab labels: those are present on every tab, deep link or not.
        opensTo(Destinations.NOW, nowMarker(), "the monitoring button")
        opensTo(Destinations.HISTORY, text(R.string.history_mode_days))
        opensTo(Destinations.APPS, desc(R.string.apps_refresh))
        opensTo(Destinations.SETTINGS, text(R.string.settings_monitoring_title))
    }

    @Test fun launchExtrasCannotPreArmInsightsApplyDialog() {
        DeviceEnvironment.requireDisposableEmulator()
        val launch = intent(Destinations.INSIGHTS).apply {
            putExtra("insights.pending.key", "BACKGROUND_RUNAWAY:${context.packageName}")
            putExtra("insights.pending.action", "FORCE_STOP")
        }
        assertInsightsLaunchIgnoresViewModelArgs(launch)
    }

    @Test fun nonStringFindingKeyDoesNotCrashInsightsLaunch() {
        DeviceEnvironment.requireDisposableEmulator()
        assertInsightsLaunchIgnoresViewModelArgs(intent(Destinations.INSIGHTS).apply {
            putExtra("key", 1)
        })
    }

    private fun assertInsightsLaunchIgnoresViewModelArgs(launch: Intent) {
        val scenario = ActivityScenario.launch<BatteryMainActivity>(launch)
        try {
            scenario.onActivity { activity ->
                val extras = activity.defaultViewModelCreationExtras
                val args = extras[DEFAULT_ARGS_KEY]
                assertNotNull("the default argument bundle must be explicitly empty", args)
                assertTrue("launch extras must not become ViewModel arguments", args!!.isEmpty)
                assertSame(activity, extras[SAVED_STATE_REGISTRY_OWNER_KEY])
                assertSame(activity, extras[VIEW_MODEL_STORE_OWNER_KEY])
            }
            assertTrue("the destination extra must still open Insights",
                device.wait(Until.hasObject(text(R.string.insights_analyze)), 120_000) == true)
            device.waitForIdle()
            assertEquals("malformed launch extras must not crash the activity", Lifecycle.State.RESUMED, scenario.state)
            assertFalse("launch extras must not show the apply dialog",
                device.hasObject(text(R.string.insights_cancel)))
        } finally {
            scenario.close()
        }
    }

    @Test fun deepLinksPushHealthAndStatusOntoTheirOwningTab() {
        DeviceEnvironment.requireDisposableEmulator()
        // Health is pushed onto Now's stack; Status onto Settings'.
        opensTo(Destinations.HEALTH, text(R.string.health_title))
        opensTo(Destinations.STATUS, text(R.string.status_access_title))
    }

    @Test fun sessionDeepLinkSelectsHistoryAndOpensThatSessionsDetails() {
        DeviceEnvironment.requireDisposableEmulator()
        val start = System.currentTimeMillis() - 26 * 60 * 60_000L
        val session = ChargeSession(
            "deeplink-session", SessionType.DISCHARGE, start, start + 5 * 60_000L, 70, 66, 40_000, -480_000, null,
            observationId = "deeplink-import", lastSampleTime = start + 5 * 60_000L, observedMs = 5 * 60_000L,
            counterCoveredMs = 5 * 60_000L, screenOnMs = 5 * 60_000L, screenOffMs = 0,
            source = "import:deeplink BatteryManager counter observations",
        )
        runBlocking { BatteryGraph.repo.sessionDao.insert(session) }
        try {
            opensTo(Destinations.session("deeplink-session"), text(R.string.sessiondetails_type_discharge)) {
                assertTrue("Session details must show the delete action",
                    device.wait(Until.hasObject(desc(R.string.sessiondetails_delete)), 5_000) == true)
            }
        } finally {
            runBlocking { BatteryGraph.repo.sessionDao.deleteSession("deeplink-session", recordingGeneration = null) }
        }
    }

    @Test fun backAtTheNowTabsRootFinishesTheActivityInsteadOfDoingNothing() {
        DeviceEnvironment.requireDisposableEmulator()
        val scenario = ActivityScenario.launch(BatteryMainActivity::class.java)
        try {
            assertTrue("The Now tab's root must appear before Back",
                device.wait(Until.hasObject(nowMarker()), 120_000) == true)
            device.waitForIdle()
            // RESUMED alone is insufficient when a SystemUI window covers the activity.
            var hasWindowFocus = false
            scenario.onActivity { hasWindowFocus = it.hasWindowFocus() }
            var focusWaitedMs = 0
            while (!hasWindowFocus && focusWaitedMs < 5_000) {
                Thread.sleep(100)
                focusWaitedMs += 100
                scenario.onActivity { hasWindowFocus = it.hasWindowFocus() }
            }
            assertTrue("The launched activity must have window focus before Back", hasWindowFocus)
            device.pressBack()
            var waitedMs = 0
            while (scenario.state != Lifecycle.State.DESTROYED && waitedMs < 10_000) {
                Thread.sleep(100)
                waitedMs += 100
            }
            assertEquals("Back at the Now tab's root must finish the activity", Lifecycle.State.DESTROYED, scenario.state)
        } finally {
            scenario.close()
        }
    }
}
