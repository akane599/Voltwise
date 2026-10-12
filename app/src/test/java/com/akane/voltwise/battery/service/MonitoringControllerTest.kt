package com.akane.voltwise.battery.service

import android.content.Intent
import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringControllerTest {
    private val store = FakeKeyValueStore()
    private val running = MutableStateFlow(false)
    private var result = MonitoringControl.StartResult.STARTED
    private var starts = 0
    private var stops = 0

    private fun controller() = MonitoringController(
        isMonitoring = running,
        store = store,
        startService = {
            starts++
            result
        },
        stopService = { stops++ },
    )

    @Test
    fun missingFlagKeepsLegacyUpdateResume() {
        val controller = controller()
        assertTrue(store.values.isEmpty())
        assertTrue(controller.monitoringWanted)
        assertTrue(resumesMonitoring(Intent.ACTION_MY_PACKAGE_REPLACED, true, controller.monitoringWanted))
        assertEquals(0, store.writes)
    }

    @Test
    fun stopPersistsOptOutAcrossControllerRecreation() {
        controller().stop()
        assertEquals(1, stops)
        val recreated = controller()
        assertFalse(recreated.monitoringWanted)
        assertFalse(resumesMonitoring(Intent.ACTION_MY_PACKAGE_REPLACED, true, recreated.monitoringWanted))
        assertTrue(resumesMonitoring(Intent.ACTION_BOOT_COMPLETED, true, recreated.monitoringWanted))
    }

    @Test
    fun successfulStartPersistsWantedAfterStop() {
        val controller = controller()
        controller.stop()
        assertEquals(MonitoringControl.StartResult.STARTED, controller.start())
        assertEquals(1, starts)
        val recreated = controller()
        assertTrue(recreated.monitoringWanted)
        assertTrue(resumesMonitoring(Intent.ACTION_MY_PACKAGE_REPLACED, true, recreated.monitoringWanted))
    }

    @Test
    fun alreadyRunningStartSetsWantedWithoutStartingServiceAgain() {
        val controller = controller()
        controller.stop()
        running.value = true
        assertEquals(MonitoringControl.StartResult.ALREADY_RUNNING, controller.start())
        assertTrue(controller().monitoringWanted)
        assertEquals(0, starts)
    }

    @Test
    fun blockedStartDoesNotUndoExplicitStop() {
        val controller = controller()
        controller.stop()
        result = MonitoringControl.StartResult.BLOCKED
        assertEquals(MonitoringControl.StartResult.BLOCKED, controller.start())
        assertFalse(controller().monitoringWanted)
        assertEquals(1, store.writes)
    }

    @Test
    fun promptStartPersistsWantedAfterExplicitStop() {
        controller().stop()
        recordPromptStart(START_FROM_PROMPT_ACTION, store)
        assertTrue("A successful prompt tap must opt back into update resumes", controller().monitoringWanted)
        assertTrue(resumesMonitoring(Intent.ACTION_MY_PACKAGE_REPLACED, true, controller().monitoringWanted))
        assertEquals("Acknowledgement must not start the service a second time", 0, starts)
    }

    @Test
    fun unmarkedServiceStartsDoNotUndoExplicitStop() {
        controller().stop()
        for (action in listOf(null, "", Intent.ACTION_MY_PACKAGE_REPLACED)) {
            recordPromptStart(action, store)
            assertFalse(controller().monitoringWanted)
        }
        assertEquals(1, store.writes)
    }

    @Test
    fun stopRecordsIntentBeforeStoppingService() {
        val controller = MonitoringController(
            isMonitoring = running,
            store = store,
            startService = { result },
            stopService = { assertFalse(controller().monitoringWanted) },
        )
        controller.stop()
    }
}
