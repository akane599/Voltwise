package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.InsightInputsBuilder
import com.akane.voltwise.battery.insights.model.DayInput
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SessionInput
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class TrendThresholdTest {
    @Test fun `daily relative floor rejects large absolute change on a high baseline`() {
        val days = (76L..79).map { day(it, 1_000_000) } + (93L..96).map { day(it, 1_100_000) }
        val input = inputs(emptyList(), emptyList()).copy(days = days)
        assertTrue(Trends.detect(input).isEmpty())
        val changed = input.copy(days = days.map {
            if (it.epochDay >= 93) it.copy(screenOffDischargeUah = 1_400_000) else it
        })
        assertEquals(setOf(Metric.SCREEN_OFF_PCT_PER_H, Metric.DAILY_USE_PCT),
            Trends.detect(changed).map { it.evidence.single().metric }.toSet())
    }

    @Test fun `app comparison requires measured values and both relative and absolute floors`() {
        val sessions = (75..78).map { session(it) } + (92..95).map { session(it) }
        fun report(before: Double, after: Double) = Trends.detect(inputs(sessions,
            sessions.mapIndexed { i, s -> row(s.id).copy(powerMah = if (i < 4) before else after) }))
        assertTrue(report(100.0, 110.0).isEmpty())
        assertTrue(report(1.0, 2.0).isEmpty())
        assertEquals(1, report(5.0, 10.0).size)
        assertTrue(report(Double.NaN, 10.0).isEmpty())
    }

    @Test fun `Tokyo excludes windows from incomplete today`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val before = (20L..23).map { localSession(it, zone) }
        val after = (2L..4).map { localSession(it, zone) }
        val today = localSession(0, zone, hour = 1, durationHours = 7)
        assertTrue(Trends.detect(appInputs(before, after + today, zone)).isEmpty())
    }

    @Test fun `Los Angeles includes a window ending yesterday evening`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val before = (20L..23).map { localSession(it, zone) }
        val after = (2L..4).map { localSession(it, zone) } + localSession(1, zone, hour = 17)
        val trend = Trends.detect(appInputs(before, after, zone)).single()
        assertEquals(10.0, trend.evidence.single().observed, 0.0)
        assertTrue(trend.series.any { it.atMs == after.last().endMs })
    }

    @Test fun `Tokyo split starts at local midnight seven days ago`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val before = (20L..23).map { localSession(it, zone) }
        val after = (2L..4).map { localSession(it, zone) } + localSession(7, zone, hour = 1, durationHours = 7)
        val trend = Trends.detect(appInputs(before, after, zone)).single()
        assertEquals(8, trend.evidence.single().sessions)
        assertEquals(10.0, trend.evidence.single().observed, 0.0)
    }

    @Test fun `Los Angeles split keeps the preceding local evening on before side`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val before = (20L..22).map { localSession(it, zone) } + localSession(8, zone, hour = 18)
        val after = (2L..5).map { localSession(it, zone) }
        val trend = Trends.detect(appInputs(before, after, zone)).single()
        assertEquals(5.0, trend.evidence.single().baseline!!, 0.0)
        assertEquals(10.0, trend.evidence.single().observed, 0.0)
    }

    @Test fun `history starts at local midnight twenty eight days ago`() {
        for (zone in listOf(ZoneId.of("Asia/Tokyo"), ZoneId.of("America/Los_Angeles"))) {
            val before = (20L..22).map { localSession(it, zone) }
            val after = (2L..5).map { localSession(it, zone) }
            val inside = before + localSession(28, zone, hour = 1)
            assertEquals(zone.id, 1, Trends.detect(appInputs(inside, after, zone)).size)
            val outside = before + localSession(29, zone, hour = 18)
            assertTrue(zone.id, Trends.detect(appInputs(outside, after, zone)).isEmpty())
        }
    }

    @Test fun `device series uses each local midnight across daylight saving change`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val days = (10L..13).map { day(today - it, 100_000) } +
            (1L..4).map { day(today - it, 200_000) }
        val input = inputs(emptyList(), emptyList()).copy(todayEpochDay = today, days = days, zone = zone)
        val series = Trends.detect(input).single().series
        assertEquals(days.sortedBy { it.epochDay }.map { localMidnight(it.epochDay, zone) }, series.map { it.atMs })
        assertEquals(days.sortedBy { it.epochDay }.map { it.epochDay }, series.map {
            java.time.Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate().toEpochDay()
        })
        // Fall-back occurred inside the history, so a single fixed offset cannot satisfy both sides.
        assertNotEquals(localMidnight(today - 10, zone) - (today - 10) * 24 * HOUR,
            localMidnight(today - 1, zone) - (today - 1) * 24 * HOUR)
    }

    @Test fun `uncensored app absences are measured zeros but censored absences are not`() {
        val before = (75..78).map { session(it) }
        val after = (92..95).map { session(it) }
        val rows = before.map { row(it.id).copy(powerMah = 20.0) }
        val input = inputs(before + after, rows)
        val findings = Trends.detect(input)
        assertEquals("Exact-zero after windows must produce a DOWN trend", 1, findings.size)
        val trend = findings.single()
        assertEquals(Direction.DOWN, trend.direction)
        assertEquals(20.0, trend.evidence.single().baseline!!, 0.0)
        assertEquals(0.0, trend.evidence.single().observed, 0.0)
        assertEquals(8, trend.evidence.single().sessions)
        assertEquals(List(4) { 0.0 }, trend.series.takeLast(4).map { it.value })
        val censored = input.copy(
            sessions = before + after.map { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) },
            appSessions = rows + after.flatMap {
                listOf(row(it.id).copy(packageName = "other.app", uid = UID + 1),
                    row(it.id).copy(isOthers = true))
            },
        )
        assertFalse("A censored absence cannot provide the original app's after measurements",
            Trends.detect(censored).any { it.subject == trend.subject })
    }

    @Test fun `UTC explicit zone preserves original window edges and device timestamps`() {
        val sessions = (75..78).map { session(it) } + (92..95).map { session(it) }
        val input = inputs(sessions, sessions.mapIndexed { i, s ->
            row(s.id).copy(powerMah = if (i < 4) 5.0 else 10.0)
        }).copy(days = (76L..79).map { day(it, 100_000) } + (93L..96).map { day(it, 200_000) })
        assertEquals(Trends.detect(input), Trends.detect(input.copy(zone = ZoneOffset.UTC)))
        val findings = Trends.detect(input)
        assertEquals(2, findings.size)
        assertEquals(input.days.map { it.epochDay * 24 * HOUR }, findings.first().series.map { it.atMs })
        assertEquals(sessions.map { it.endMs }, findings.last().series.map { it.atMs })
    }

    @Test fun `builder carries local day zone without changing epoch day`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val input = InsightInputsBuilder.build(
            nowMs = localMidnight(today, zone) + 12 * HOUR, todayEpochDay = today,
            fullUah = null, sessions = emptyList(), days = emptyList(),
            appRows = emptyList(), wakers = emptyList(), capacity = emptyList(), dozeWhitelist = null,
            actions = emptyList(), findings = emptyList(), zone = zone,
        )
        assertEquals(zone, input.zone)
        assertEquals(today, input.todayEpochDay)
    }

    private val today = LocalDate.of(2026, 11, 10).toEpochDay()

    private fun localMidnight(epochDay: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun localSession(daysAgo: Long, zone: ZoneId, hour: Int = 12, durationHours: Int = 1): SessionInput {
        val start = localMidnight(today - daysAgo, zone) + hour * HOUR
        val duration = durationHours * HOUR
        val base = session(daysAgo.toInt(), duration)
        return base.copy(
            startMs = start, endMs = start + duration,
            appWindow = base.appWindow!!.copy(
                captureStartMs = start, captureEndMs = start + duration,
            ),
        )
    }

    private fun appInputs(before: List<SessionInput>, after: List<SessionInput>, zone: ZoneId) =
        inputs(before + after, (before + after).map { s ->
            row(s.id).copy(powerMah = (if (s in before) 5.0 else 10.0) * (s.endMs - s.startMs) / HOUR)
        }).copy(todayEpochDay = today, zone = zone)

    private fun day(epoch: Long, energy: Long) = DayInput(
        epoch, 0, HOUR, 0, HOUR, 0, energy, 0, null, null, null, null, null,
    )
}
