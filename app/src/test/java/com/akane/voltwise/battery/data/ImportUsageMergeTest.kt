package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import org.junit.Assert.*
import org.junit.Test

class ImportUsageMergeTest {
    private fun session(end: Long = 2000) = ChargeSession(
        "session", SessionType.DISCHARGE, 1000, end, 60, 59, end - 1000, -1000, null,
        activeKey = null, observationId = "observation", lastSampleTime = end,
        observedMs = end - 1000, counterCoveredMs = end - 1000,
        screenOnMs = end - 1000, screenOnUah = end - 1000,
        appUsageStatus = AppUsageStatus.READY, appUsageBasis = AppUsageBasis.DELTA,
    )

    @Test fun sessionsOnlyPayloadDoesNotClaimAReadyBreakdown() {
        val plan = HistoryPolicy.planSessionImport(null, session())
        assertEquals(ImportSessionDisposition.ADDED, plan.disposition)
        assertNull(plan.session.appUsageStatus)
        assertNull(plan.session.appUsageBasis)
    }

    @Test fun conflictingOriginAndSameWindowMeasurementsRemainRejected() {
        val previous = HistoryPolicy.session(session())
        for (incoming in listOf(session(1500).copy(observationId = "foreign"), session().copy(endLevel = 58))) {
            assertThrows(IllegalArgumentException::class.java) {
                HistoryPolicy.planSessionImport(previous, incoming)
            }
        }
    }
}
