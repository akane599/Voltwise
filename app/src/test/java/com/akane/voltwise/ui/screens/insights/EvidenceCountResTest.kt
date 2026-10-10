package com.akane.voltwise.ui.screens.insights

import com.akane.voltwise.R
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Subject
import org.junit.Assert.assertEquals
import org.junit.Test

/** A device trend's evidence count comes from daily points (Trends.detect), so it reads as days, not sessions. */
class EvidenceCountResTest {
    private val app = Subject.App(10_001, "com.example")

    @Test
    fun `device trend counts days`() {
        assertEquals(R.plurals.finding_days, evidenceCountRes(FindingType.TREND, Subject.Device))
    }

    @Test
    fun `app trend counts sessions`() {
        assertEquals(R.plurals.finding_sessions, evidenceCountRes(FindingType.TREND, app))
    }

    @Test
    fun `every other finding counts sessions for device and app alike`() {
        FindingType.entries.filter { it != FindingType.TREND }.forEach { type ->
            assertEquals(type.name, R.plurals.finding_sessions, evidenceCountRes(type, Subject.Device))
            assertEquals(type.name, R.plurals.finding_sessions, evidenceCountRes(type, app))
        }
    }
}
