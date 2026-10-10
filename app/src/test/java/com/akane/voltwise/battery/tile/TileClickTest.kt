package com.akane.voltwise.battery.tile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileClickTest {
    @Test
    fun lockedClicksRequireUnlockOnApi31AndLater() {
        for (sdkInt in listOf(31, 34, 37)) {
            assertTrue("Locked tile click on API $sdkInt must wait for unlock", tileClickNeedsUnlock(sdkInt, locked = true))
        }
    }

    @Test
    fun unlockedClicksToggleDirectly() {
        for (sdkInt in listOf(26, 30, 31, 34, 37)) {
            assertFalse("Unlocked tile click on API $sdkInt must toggle directly", tileClickNeedsUnlock(sdkInt, locked = false))
        }
    }

    @Test
    fun olderAndroidMatchesNotificationAuthenticationPolicy() {
        for (sdkInt in listOf(26, 30)) {
            assertFalse("API $sdkInt keeps the notification's legacy policy", tileClickNeedsUnlock(sdkInt, locked = true))
        }
    }
}
