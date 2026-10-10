package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.device.percentRate
import org.junit.Assert.*
import org.junit.Test

class DeviceMeasurementsTest {
    @Test fun `percent rate requires a full minute of charge coverage`() {
        for (coverage in listOf(null, -1L, 0L, 1L, 59_999L)) {
            assertNull("Coverage $coverage cannot define a rate", percentRate(1_000, coverage, 4_000_000))
        }
        assertEquals(1.5, percentRate(1_000, 60_000, 4_000_000)!!, 1e-9)
        assertEquals(0.0, percentRate(0, 60_000, 4_000_000)!!, 0.0)
    }

    @Test fun `percent rate rejects unknown charge and invalid capacity`() {
        for (charge in listOf(null, -1L)) assertNull(percentRate(charge, 60_000, 4_000_000))
        for (capacity in listOf(null, 0L, -1L)) assertNull(percentRate(1_000, 60_000, capacity))
    }
}
