package com.akane.voltwise.ui.screens.insights

import android.content.ActivityNotFoundException
import androidx.lifecycle.Lifecycle
import com.akane.voltwise.R
import com.akane.voltwise.battery.insights.actions.IntentSpec
import com.akane.voltwise.viewmodel.InsightActionMessage
import com.akane.voltwise.viewmodel.InsightMessageCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFallbacksTest {
    private val batteryOptimization = IntentSpec("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
    private val appDetails = IntentSpec("android.settings.APPLICATION_DETAILS_SETTINGS", "com.example.app")
    private val settingsHome = IntentSpec("android.settings.SETTINGS")

    @Test
    fun `a page without a package falls back to Settings home`() {
        assertEquals(listOf(batteryOptimization, settingsHome), settingsFallbacks(batteryOptimization))
    }

    @Test
    fun `a page with a package falls back to that app's details, then Settings home`() {
        val notifications = notificationSettings("com.example.app")
        assertEquals("android.settings.APP_NOTIFICATION_SETTINGS", notifications.action)
        assertEquals(listOf(notifications, appDetails, settingsHome), settingsFallbacks(notifications))
    }

    @Test
    fun `the app details page is tried once`() {
        assertEquals(listOf(appDetails, settingsHome), settingsFallbacks(appDetails))
    }

    @Test
    fun `Settings home is tried once`() {
        assertEquals(listOf(settingsHome), settingsFallbacks(settingsHome))
    }

    /** Starts every page except those whose index in the chain is in [failing], which throw [failure]; logs each try. */
    private class FakeStart(
        private val chain: List<IntentSpec>,
        private val failing: Set<Int>,
        private val failure: () -> RuntimeException = { SecurityException("not exported") },
    ) : (IntentSpec) -> Unit {
        val tried = mutableListOf<IntentSpec>()
        override fun invoke(page: IntentSpec) {
            tried += page
            if (chain.indexOf(page) in failing) throw failure()
        }
    }

    @Test
    fun `the page asked for opens first and nothing else is tried`() {
        val notifications = notificationSettings("com.example.app")
        val start = FakeStart(settingsFallbacks(notifications), failing = emptySet())
        assertEquals(0, openFirst(notifications, start))
        assertEquals(listOf(notifications), start.tried)
    }

    @Test
    fun `pages are tried in fallback order until one opens`() {
        val notifications = notificationSettings("com.example.app")
        val start = FakeStart(settingsFallbacks(notifications), failing = setOf(0, 1))
        assertEquals(2, openFirst(notifications, start))
        assertEquals(listOf(notifications, appDetails, settingsHome), start.tried)
    }

    @Test
    fun `a page that throws SecurityException falls through to the next one`() {
        val notifications = notificationSettings("com.example.app")
        val start = FakeStart(settingsFallbacks(notifications), failing = setOf(0))
        assertEquals(1, openFirst(notifications, start))
        assertEquals(listOf(notifications, appDetails), start.tried)
    }

    @Test
    fun `a missing page falls through to the next one`() {
        val start = FakeStart(settingsFallbacks(batteryOptimization), failing = setOf(0)) { ActivityNotFoundException() }
        assertEquals(1, openFirst(batteryOptimization, start))
        assertEquals(listOf(batteryOptimization, settingsHome), start.tried)
    }

    @Test
    fun `when every page throws nothing opened`() {
        val notifications = notificationSettings("com.example.app")
        val start = FakeStart(settingsFallbacks(notifications), failing = setOf(0, 1, 2))
        assertNull(openFirst(notifications, start))
        assertEquals(listOf(notifications, appDetails, settingsHome), start.tried)
    }

    @Test
    fun `a page without a package skips app details`() {
        val start = FakeStart(settingsFallbacks(batteryOptimization), failing = setOf(0))
        assertEquals(1, openFirst(batteryOptimization, start))
        assertEquals("Settings home is next, not an app details page", listOf(batteryOptimization, settingsHome), start.tried)
    }

    @Test
    fun `any other failure is not swallowed`() {
        val start = FakeStart(settingsFallbacks(batteryOptimization), failing = setOf(0)) { IllegalStateException() }
        assertThrows(IllegalStateException::class.java) { openFirst(batteryOptimization, start) }
        assertEquals(listOf(batteryOptimization), start.tried)
    }

    @Test
    fun `only a fallback page or no page at all gets a notice`() {
        val notifications = notificationSettings("com.example.app")
        assertNull(settingsNotice(notifications, opened = 0))
        assertNull(settingsNotice(batteryOptimization, opened = 0))
        assertEquals(SettingsNotice.FALLBACK, settingsNotice(notifications, opened = 1))
        assertEquals(SettingsNotice.FALLBACK, settingsNotice(notifications, opened = 2))
        assertEquals(SettingsNotice.UNAVAILABLE, settingsNotice(notifications, opened = null))
        assertEquals(SettingsNotice.UNAVAILABLE, settingsNotice(batteryOptimization, opened = null))
    }

    @Test
    fun `a battery optimization fallback says where to search, other fallbacks don't`() {
        assertEquals(SettingsNotice.BATTERY_OPTIMIZATION_FALLBACK, settingsNotice(batteryOptimization, opened = 1))
        assertEquals(SettingsNotice.FALLBACK, settingsNotice(appDetails, opened = 1))
    }

    @Test
    fun `each notice shows its own text`() {
        assertEquals(R.string.insights_settings_unavailable, SettingsNotice.UNAVAILABLE.messageRes())
        assertEquals(R.string.insights_settings_fallback, SettingsNotice.FALLBACK.messageRes())
        assertEquals(
            R.string.insights_settings_fallback_battery_optimization,
            SettingsNotice.BATTERY_OPTIMIZATION_FALLBACK.messageRes(),
        )
        assertEquals(SettingsNotice.entries.size, SettingsNotice.entries.map { it.messageRes() }.toSet().size)
    }

    @Test
    fun `a held notice waits while Settings covers the app, then shows once on return`() = runTest {
        val lifecycle = MutableStateFlow(Lifecycle.State.STARTED)
        var shown = 0
        val waiting = launch { showOnceResumed(lifecycle) { shown++ } }
        runCurrent()
        assertEquals("not while the app is only started", 0, shown)
        lifecycle.value = Lifecycle.State.CREATED
        runCurrent()
        assertEquals("not while the app is stopped behind Settings", 0, shown)
        lifecycle.value = Lifecycle.State.RESUMED
        runCurrent()
        assertEquals("once the app is resumed", 1, shown)
        assertTrue(waiting.isCompleted)
        lifecycle.value = Lifecycle.State.STARTED
        runCurrent()
        lifecycle.value = Lifecycle.State.RESUMED
        runCurrent()
        assertEquals("not again on the next resume", 1, shown)
    }

    @Test
    fun `a notice held while the app is resumed shows at once`() = runTest {
        var shown = 0
        showOnceResumed(MutableStateFlow(Lifecycle.State.RESUMED)) { shown++ }
        assertEquals(1, shown)
    }

    @Test
    fun `an alert turned on while notifications are off says so and offers Turn on`() {
        val blocked = InsightActionMessage(InsightMessageCode.ONE_SHOT_NOTIFICATIONS_OFF, actionId = 7)
        assertEquals(R.string.insights_message_one_shot_notifications_off, blocked.code.messageRes())
        assertEquals(R.string.settings_notifications_turn_on, blocked.actionLabelRes())
    }

    @Test
    fun `any other result shows its code's message without an action`() {
        val done = InsightActionMessage(InsightMessageCode.ONE_SHOT, actionId = 7)
        assertEquals(R.string.insights_message_one_shot, done.code.messageRes())
        assertNull(done.actionLabelRes())
        val applied = InsightActionMessage(InsightMessageCode.APPLIED)
        assertEquals(R.string.insights_message_applied, applied.code.messageRes())
        assertNull(applied.actionLabelRes())
        val reverted = InsightActionMessage(InsightMessageCode.REVERTED, actionId = 7)
        assertEquals(R.string.insights_message_reverted, reverted.code.messageRes())
        assertNull("REVERTED never offers Turn on", reverted.actionLabelRes())
        InsightMessageCode.entries.filter { it != InsightMessageCode.ONE_SHOT_NOTIFICATIONS_OFF }.forEach { code ->
            assertNull("$code offers no action", InsightActionMessage(code).actionLabelRes())
        }
    }
}
