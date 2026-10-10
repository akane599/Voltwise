package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.device.ChargingHealth
import com.akane.voltwise.battery.insights.model.CapacityPointInput
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SessionKind
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ChargingHealthTest {
    @Test fun `full charging requires three observed two hour full plugged sessions in fourteen days`() {
        val sessions = (0..2).map { session(it, 2 * HOUR).copy(kind = SessionKind.PLUGGED, startLevel = 100, endLevel = 100) }
        val input = inputs(sessions, emptyList())
        val finding = ChargingHealth.detect(input).single()
        assertEquals(FindingType.CHARGING_AT_FULL, finding.type)
        assertEquals(3, finding.evidence.single().sessions)
        assertEquals(2.0 * HOUR, finding.evidence.single().observed, 0.0)
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.drop(1))).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.map { it.copy(startLevel = null) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.map { it.copy(startLevel = 99) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.map { it.copy(observedMs = HOUR) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(nowMs = input.nowMs + 14 * 24 * HOUR)).isEmpty())
    }

    @Test fun `hot charging requires repeated measured peaks at forty degrees`() {
        val sessions = (0..2).map { session(it).copy(kind = SessionKind.CHARGE, peakTemperatureDeciC = 400) }
        val input = inputs(sessions, emptyList())
        assertEquals(FindingType.HOT_CHARGING, ChargingHealth.detect(input).single().type)
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.drop(1))).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.map { it.copy(peakTemperatureDeciC = 399) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.map { it.copy(peakTemperatureDeciC = null) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(sessions = sessions.map { it.copy(kind = SessionKind.DISCHARGE) })).isEmpty())
    }

    @Test fun `steady five percent annual decline with two percent scatter over eighty nine daily points fires`() {
        val day = 24 * HOUR
        val points = (0 until 89).map { index ->
            val scatter = if (index % 2 == 0) 0.02 else -0.02
            CapacityPointInput(index * day, 4500.0 * (1.0 - 0.05 * index / 365.25 + scatter), 2)
        }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 88 * day, capacity = points)
        val findings = ChargingHealth.detect(input)
        assertEquals("A realistic noisy five percent annual decline must fire", 1, findings.size)
        assertEquals(FindingType.HEALTH_DECLINE, findings.single().type)
        assertTrue(findings.single().evidence.single().observed <= -3.0)
    }

    @Test fun `flat serially correlated capacity stays below two percent false declines`() {
        val random = Random(303)
        val day = 24 * HOUR
        val trials = 500
        val falseDeclines = (0 until trials).count {
            var noise = 0.0
            val points = (0 until 60).map { index ->
                noise = 0.5 * noise + random.nextDouble(-0.02, 0.02)
                CapacityPointInput(index * day, 4500.0 * (1.0 + noise), 2)
            }
            val input = inputs(emptyList(), emptyList()).copy(nowMs = 59 * day, capacity = points)
            ChargingHealth.detect(input).any { it.type == FindingType.HEALTH_DECLINE }
        }
        println("Flat AR(1) capacity: $falseDeclines/$trials false declines (seed 303)")
        assertTrue("Flat AR(1) capacity produced $falseDeclines/$trials false declines; maximum is 10", falseDeclines <= 10)
    }

    @Test fun `seeded iid two percent scatter with ten percent annual decline over eighty nine days fires`() {
        val random = Random(304)
        val day = 24 * HOUR
        val points = (0..89).map { index ->
            CapacityPointInput(index * day, 4500.0 * (1.0 - 0.10 * index / 365.25 + random.nextDouble(-0.02, 0.02)), 2)
        }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 89 * day, capacity = points)
        assertEquals("A noisy ten percent annual decline must fire", FindingType.HEALTH_DECLINE, ChargingHealth.detect(input).single().type)
    }

    @Test fun `health minimum is six distinct points spanning thirty days`() {
        val day = 24 * HOUR
        val points = (0..5).map { CapacityPointInput(it * 8 * day, 4500.0 - it * 10, 2) }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 40 * day, capacity = points)
        assertEquals(FindingType.HEALTH_DECLINE, ChargingHealth.detect(input).single().type)
        assertTrue("Five declining points cannot meet the significance gate", ChargingHealth.detect(input.copy(capacity = points.take(5))).isEmpty())
        assertTrue("Repeated timestamps cannot supply the sixth point", ChargingHealth.detect(input.copy(capacity = points.take(5) + points.first())).isEmpty())
    }

    @Test fun `flat capacity with the same two percent daily scatter stays silent`() {
        val day = 24 * HOUR
        val points = (0 until 89).map { index ->
            val scatter = if (index % 2 == 0) 0.02 else -0.02
            CapacityPointInput(index * day, 4500.0 * (1.0 + scatter), 2)
        }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 88 * day, capacity = points)
        assertTrue("Flat estimates with identical scatter must stay silent", ChargingHealth.detect(input).isEmpty())
    }

    @Test fun `flat capacity estimate noise does not indicate health decline`() {
        val points = listOf(4000.0, 4080.0, 3920.0, 4040.0, 3960.0, 4000.0).mapIndexed { index, mah ->
            CapacityPointInput(index * 144 * HOUR, mah, 2)
        }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 30 * 24 * HOUR, capacity = points)
        assertTrue("Flat noisy estimates must not report HEALTH_DECLINE", ChargingHealth.detect(input).isEmpty())
    }

    @Test fun `clustered recent capacity estimates do not indicate health decline`() {
        val day = 24 * HOUR
        val points = listOf(CapacityPointInput(0, 4000.0, 2)) +
            listOf(3900.0, 4000.0, 3950.0, 3950.0, 4000.0).mapIndexed { index, mah ->
                CapacityPointInput(30 * day + index * HOUR, mah, 2)
            }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 30 * day + 4 * HOUR, capacity = points)
        assertTrue("A noisy recent cluster must not report HEALTH_DECLINE", ChargingHealth.detect(input).isEmpty())
    }

    @Test fun `steady material capacity decline still produces negative annual health evidence`() {
        val day = 24 * HOUR
        val points = listOf(4400.0, 4358.0, 4311.0, 4269.0, 4225.0, 4180.0).mapIndexed { index, mah ->
            CapacityPointInput(index * 12 * day, mah, 2)
        }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 60 * day, capacity = points)
        val finding = ChargingHealth.detect(input).single()
        assertEquals(FindingType.HEALTH_DECLINE, finding.type)
        assertEquals(Direction.DOWN, finding.direction)
        assertEquals(Metric.CAPACITY_CHANGE_PCT_PER_YEAR, finding.evidence.single().metric)
        assertTrue("A steady decline must retain negative annual evidence", finding.evidence.single().observed < 0.0)
    }

    @Test fun `health requires a statistically significant decline`() {
        val day = 24 * HOUR
        // S = -17, z = -16 / sqrt(133 / 3) = -2.403, just beyond the -2.33 gate.
        val points = listOf(1, 2, 0, 3, 4, 5, 6).mapIndexed { index, value ->
            CapacityPointInput(index * 7 * day, 4000.0 - value * 10, 2)
        }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 42 * day, capacity = points)
        assertEquals(FindingType.HEALTH_DECLINE, ChargingHealth.detect(input).single().type)
        // One additional rising pair yields S = -15 and z = -2.103.
        val notSignificant = points.mapIndexed { index, point ->
            when (index) {
                0 -> point.copy(mah = points[1].mah)
                1 -> point.copy(mah = points[0].mah)
                else -> point
            }
        }
        assertTrue("A material but insignificant decline must stay silent", ChargingHealth.detect(input.copy(capacity = notSignificant)).isEmpty())
    }

    @Test fun `health requires a material annual decline even when every pair declines`() {
        val day = 24 * HOUR
        val points = (0..6).map { CapacityPointInput(it * 10 * day, 4000.0 + (3 - it) * 3, 2) }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 60 * day, capacity = points)
        // About -2.74% per year: statistically significant, but magnitude is not material.
        assertTrue("A clear decline under three percent per year must stay silent", ChargingHealth.detect(input).isEmpty())
        val material = points.mapIndexed { index, point -> point.copy(mah = 4000.0 + (3 - index) * 4) }
        assertEquals(FindingType.HEALTH_DECLINE, ChargingHealth.detect(input.copy(capacity = material)).single().type)
    }

    @Test fun `health uses robust slope converted to annual percentage with point span confidence and age gates`() {
        val day = 24 * HOUR
        val points = (0..8).map { CapacityPointInput(it * 5 * day, 4000.0 - it * 5, 2) }
        val input = inputs(emptyList(), emptyList()).copy(nowMs = 40 * day, capacity = points)
        val finding = ChargingHealth.detect(input).single()
        assertEquals(FindingType.HEALTH_DECLINE, finding.type)
        assertEquals(Direction.DOWN, finding.direction)
        assertEquals(Metric.CAPACITY_CHANGE_PCT_PER_YEAR, finding.evidence.single().metric)
        assertEquals(-365.25 / 3980 * 100, finding.evidence.single().observed, 0.0001)
        assertTrue(ChargingHealth.detect(input.copy(capacity = points.take(4))).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(capacity = points.map { it.copy(atMs = it.atMs / 2) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(capacity = points.map { it.copy(confidence = 1) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(capacity = points.map { it.copy(mah = 4000.0) })).isEmpty())
        assertTrue(ChargingHealth.detect(input.copy(nowMs = 131 * day)).isEmpty())
        val outlier = input.copy(capacity = points.mapIndexed { i, p -> if (i == 2) p.copy(mah = 9000.0) else p })
        assertEquals(Direction.DOWN, ChargingHealth.detect(outlier).single().direction)
        assertEquals(finding, ChargingHealth.detect(input.copy(capacity = points.reversed())).single())
        val sameTimeEstimates = points.flatMap { point ->
            listOf(point.copy(mah = point.mah - 20), point.copy(mah = point.mah + 20))
        }
        assertEquals(finding, ChargingHealth.detect(input.copy(capacity = sameTimeEstimates)).single())
    }
}
