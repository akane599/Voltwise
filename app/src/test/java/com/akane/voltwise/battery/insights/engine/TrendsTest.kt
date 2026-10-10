package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.data.db.DailySummary
import com.akane.voltwise.battery.insights.InsightInputsBuilder
import com.akane.voltwise.battery.measurement.DailySummaryAggregator
import com.akane.voltwise.battery.measurement.DayInterval
import java.time.ZoneOffset
import com.akane.voltwise.battery.insights.model.DayInput
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Metric
import org.junit.Assert.*
import org.junit.Test

class TrendsTest {
    @Test fun `opposite device trends have different keys for the same subject and metric`() {
        val up = Trends.detect(deviceInputs(100_000, 200_000)).single()
        val down = Trends.detect(deviceInputs(200_000, 100_000)).single()

        assertEquals(Direction.UP, up.direction)
        assertEquals(Direction.DOWN, down.direction)
        assertEquals(up.subject, down.subject)
        assertEquals(Metric.SCREEN_OFF_PCT_PER_H, up.evidence.single().metric)
        assertEquals(up.evidence.single().metric, down.evidence.single().metric)
        assertNotEquals("Opposite directions must have separate finding identities", up.key, down.key)
    }

    @Test fun `device trend key stays stable across runs in the same direction`() {
        val first = Trends.detect(deviceInputs(100_000, 200_000)).single()
        val later = Trends.detect(deviceInputs(100_000, 300_000, dayOffset = 1)).single()

        assertEquals(Direction.UP, first.direction)
        assertEquals(Direction.UP, later.direction)
        assertNotEquals(first.series, later.series)
        assertEquals("Repeated UP changes must keep the same finding identity", first.key, later.key)
    }

    @Test fun `opposite app trends have different keys for the same subject and metric`() {
        val up = Trends.detect(appInputs(5.0, 15.0)).single()
        val down = Trends.detect(appInputs(15.0, 5.0)).single()

        assertEquals(Direction.UP, up.direction)
        assertEquals(Direction.DOWN, down.direction)
        assertEquals(up.subject, down.subject)
        assertEquals(Metric.POWER_MAH_PER_H, up.evidence.single().metric)
        assertEquals(up.evidence.single().metric, down.evidence.single().metric)
        assertNotEquals("Opposite directions must have separate finding identities", up.key, down.key)
    }

    @Test fun `app trend key stays stable across runs in the same direction`() {
        val first = Trends.detect(appInputs(5.0, 15.0)).single()
        val later = Trends.detect(appInputs(5.0, 20.0, dayOffset = 1)).single()

        assertEquals(Direction.UP, first.direction)
        assertEquals(Direction.UP, later.direction)
        assertNotEquals(first.series, later.series)
        assertEquals("Repeated UP changes must keep the same finding identity", first.key, later.key)
    }

    @Test fun `daily rates exclude short coverage without suppressing sufficient coverage trend`() {
        val base = deviceInputs(100_000, 200_000)
        val short = base.copy(days = base.days.map { it.copy(screenOffCoveredMs = 59_999) })
        assertTrue("Sub-minute daily rates must not produce a trend", Trends.detect(short).isEmpty())
        val sufficient = base.copy(days = base.days.map { it.copy(screenOffCoveredMs = 60_000) })
        assertEquals(Metric.SCREEN_OFF_PCT_PER_H, Trends.detect(sufficient).single().evidence.single().metric)
    }

    @Test fun `measured plugged zeros cannot invent declining shares from unknown historical days`() {
        val summaries = (76L..79).map { epoch ->
            DailySummary(epoch, screenOffMs = 8 * HOUR,
                screenOffDozeMs = 6 * HOUR, screenOffSuspendMs = 7 * HOUR)
        } + (93L..96).map { epoch ->
            val legacy = DailySummary(epoch, screenOffMs = 8 * HOUR)
            DailySummaryAggregator.apply(mapOf(epoch to legacy), DayInterval(
                epoch * 24 * HOUR + 12 * HOUR, epoch * 24 * HOUR + 12 * HOUR + 60_000,
                dozeMs = 0, screenOffDozeMs = 0, screenOffSuspendMs = 0,
            ), ZoneOffset.UTC, updatedAt = 1).single()
        }
        val input = InsightInputsBuilder.build(
            nowMs = 100 * 24 * HOUR, todayEpochDay = 100, fullUah = 4_000_000, sessions = emptyList(), days = summaries, appRows = emptyList(), wakers = emptyList(),
            capacity = emptyList(), dozeWhitelist = null, actions = emptyList(), findings = emptyList(),
        )
        assertTrue("Unknown historical shares cannot count as four measured after-days", Trends.detect(input).isEmpty())
        val measuredZeros = input.copy(days = input.days.map {
            if (it.epochDay >= 93) it.copy(screenOffDozeMs = 0, screenOffSuspendMs = 0) else it
        })
        assertEquals(setOf(Metric.DEEP_DOZE_SHARE, Metric.SCREEN_OFF_DEEP_SLEEP_SHARE),
            Trends.detect(measuredZeros).map { it.evidence.single().metric }.toSet())
    }

    private fun deviceInputs(before: Long, after: Long, dayOffset: Long = 0) =
        inputs(emptyList(), emptyList()).copy(
            nowMs = (100 + dayOffset) * 24 * HOUR,
            todayEpochDay = 100 + dayOffset,
            days = ((76L..79).map { it to before } + (93L..96).map { it to after }).map { (epoch, offUah) ->
                DayInput(epoch + dayOffset, 0, HOUR, 0, HOUR, 0, offUah, 0, null, null, null, null, null)
            },
        )

    private fun appInputs(before: Double, after: Double, dayOffset: Int = 0) =
        ((75..78) + (92..95)).map { session(it + dayOffset) }.let { sessions ->
            inputs(sessions, sessions.mapIndexed { index, session ->
                row(session.id).copy(powerMah = if (index < 4) before else after)
            }).copy(todayEpochDay = 100L + dayOffset)
        }
}
