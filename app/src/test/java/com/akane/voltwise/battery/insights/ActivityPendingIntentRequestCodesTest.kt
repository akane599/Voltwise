package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.tile.MonitorTileService
import com.akane.voltwise.battery.util.Notifier
import com.akane.voltwise.battery.widget.WidgetUpdater
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityPendingIntentRequestCodesTest {
    @Test
    fun activityPendingIntentRequestCodesAreDistinct() {
        val requestCodes = setOf(
            Notifier.OPEN_APP_REQUEST_CODE,
            MonitorTileService.OPEN_APP_REQUEST_CODE,
            WidgetUpdater.OPEN_APP_REQUEST_CODE,
            InsightNotifier.REQUEST_CODE,
        )

        assertEquals(4, requestCodes.size)
    }
}
