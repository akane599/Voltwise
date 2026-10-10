package com.akane.voltwise.ui.screens.insights

import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.MetricUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The details chart's caption and TalkBack summary come from what its series actually measures. */
class ChartMeasureTest {
    private fun evidence(metric: Metric, baseline: Double?) = Evidence(metric, 1.0, baseline, metric.unit, 5)

    @Test
    fun `capacity decline charts its mAh capacity estimates, not the yearly change`() {
        // ChargingHealth fills HEALTH_DECLINE's series with capacity estimates; its evidence is the % per year change.
        val measure = chartMeasure(FindingType.HEALTH_DECLINE, listOf(evidence(Metric.CAPACITY_CHANGE_PCT_PER_YEAR, null)))

        assertEquals(Metric.CAPACITY_MAH, measure?.metric)
        assertEquals(MetricUnit.MAH, measure?.metric?.unit)
        assertNull("no usual capacity to draw", measure?.usual)
    }

    @Test
    fun `other findings chart their first evidence metric against its baseline`() {
        val measure = chartMeasure(
            FindingType.APP_DRAIN_ANOMALY,
            listOf(evidence(Metric.POWER_MAH_PER_H, 40.0), evidence(Metric.CPU_MS_PER_H, 1_000.0)),
        )

        assertEquals(ChartMeasure(Metric.POWER_MAH_PER_H, 40.0), measure)
    }

    @Test
    fun `no evidence leaves an ordinary finding without a chart`() {
        assertNull(chartMeasure(FindingType.APP_DRAIN_ANOMALY, emptyList()))
    }
}
