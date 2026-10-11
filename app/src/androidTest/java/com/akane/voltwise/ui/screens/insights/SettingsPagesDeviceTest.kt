package com.akane.voltwise.ui.screens.insights

import android.net.Uri
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.battery.insights.actions.IntentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPagesDeviceTest {
    @Test fun notificationPageTakesItsPackageAsAnExtraAndNoData() {
        val intent = notificationSettings("com.example.app").toIntent()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals("com.example.app", intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertNull(intent.data)
    }

    @Test fun appDetailsPageTakesItsPackageAsPackageData() {
        val intent = IntentSpec(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "com.example.app").toIntent()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(Uri.fromParts("package", "com.example.app", null), intent.data)
        assertFalse(intent.hasExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test fun pageWithoutAPackageCarriesNeither() {
        val intent = IntentSpec(Settings.ACTION_SETTINGS).toIntent()
        assertEquals(Settings.ACTION_SETTINGS, intent.action)
        assertNull(intent.data)
        assertNull(intent.extras)
    }
}
