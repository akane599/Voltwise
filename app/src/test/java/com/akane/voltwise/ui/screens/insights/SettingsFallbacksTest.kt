package com.akane.voltwise.ui.screens.insights

import com.akane.voltwise.R
import com.akane.voltwise.battery.insights.actions.IntentSpec
import com.akane.voltwise.viewmodel.InsightActionMessage
import com.akane.voltwise.viewmodel.InsightMessageCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `an alert turned on while notifications are off says so and offers Turn on`() {
        val blocked = InsightActionMessage(InsightMessageCode.ONE_SHOT, actionId = 7, notificationsBlocked = true)
        assertEquals(R.string.insights_message_one_shot_notifications_off, blocked.messageRes())
        assertEquals(R.string.settings_notifications_turn_on, blocked.actionLabelRes())
    }

    @Test
    fun `any other result shows its code's message without an action`() {
        val done = InsightActionMessage(InsightMessageCode.ONE_SHOT, actionId = 7)
        assertEquals(R.string.insights_message_one_shot, done.messageRes())
        assertNull(done.actionLabelRes())
        val applied = InsightActionMessage(InsightMessageCode.APPLIED)
        assertEquals(R.string.insights_message_applied, applied.messageRes())
        assertNull(applied.actionLabelRes())
    }
}
