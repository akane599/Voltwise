package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.model.ActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.AppliedActionInput
import com.akane.voltwise.battery.insights.model.DayInput
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Metric
import org.junit.Assert.*
import org.junit.Test

class TemporalDetectorsTest {
    @Test fun `daily trends require four measured days on both sides and both effect floors`() {
        val prior = (76L..79).map { day(it, 100_000) }
        val recent = (93L..96).map { day(it, 200_000) }
        val input = inputs(emptyList(), emptyList()).copy(days = prior + recent)
        val trend = Trends.detect(input).single { it.evidence.first().metric == Metric.SCREEN_OFF_PCT_PER_H }
        assertEquals("TREND:device:SCREEN_OFF_PCT_PER_H:UP", trend.key)
        assertEquals(Direction.UP, trend.direction)
        assertEquals(2.5, trend.evidence.single().baseline!!, 0.0001)
        assertEquals(5.0, trend.evidence.single().observed, 0.0001)
        assertTrue(Trends.detect(input.copy(days = prior.drop(1) + recent)).isEmpty())
        assertTrue(Trends.detect(input.copy(days = prior + recent.drop(1))).isEmpty())
        assertTrue(Trends.detect(input.copy(days = prior + recent.map { it.copy(screenOffCoveredMs = null) })).isEmpty())
        assertTrue(Trends.detect(input.copy(days = prior + recent.map { day(it.epochDay, 110_000) })).isEmpty())
        assertTrue(Trends.detect(input.copy(fullUah = null)).isEmpty())
        assertTrue(Trends.detect(input.copy(days = prior + recent.map { it.copy(epochDay = 100) })).isEmpty())
    }

    @Test fun `all six daily metrics support decreases and exclude unsupported fields`() {
        val old = (76L..79).map { day(it, 400_000).copy(
            screenOnMs = HOUR, screenOnCoveredMs = HOUR, screenOnDischargeUah = 400_000,
            screenOffSuspendMs = HOUR * 8 / 10, screenOffDozeMs = HOUR * 8 / 10,
            peakTemperatureDeciC = 400,
        ) }
        val recent = (93L..96).map { day(it, 100_000).copy(
            screenOnMs = HOUR, screenOnCoveredMs = HOUR, screenOnDischargeUah = 100_000,
            screenOffSuspendMs = HOUR / 10, screenOffDozeMs = HOUR / 10, peakTemperatureDeciC = 300,
        ) }
        val input = inputs(emptyList(), emptyList()).copy(days = old + recent)
        assertEquals(6, Trends.detect(input).size)
        assertTrue(Trends.detect(input).all { it.direction == Direction.DOWN })
        assertEquals(3, Trends.detect(input.copy(days = (old + recent).map {
            it.copy(screenOffSuspendMs = null, screenOffDozeMs = null, peakTemperatureDeciC = null)
        })).size)
    }

    @Test fun `weekly app comparisons normalize duration and require measured eligible windows`() {
        val sessions = (75..78).map { session(it) } + (92..95).map { session(it, 2 * HOUR) }
        val rows = sessions.mapIndexed { i, s -> row(s.id).copy(powerMah = if (i < 4) 5.0 else 30.0) }
        val input = inputs(sessions, rows)
        val trend = Trends.detect(input).single()
        assertEquals("TREND:$APP:POWER_MAH_PER_H:UP", trend.key)
        assertEquals(15.0, trend.evidence.single().observed, 0.0)
        val withExactZero = Trends.detect(input.copy(appSessions = rows.drop(1))).single()
        assertEquals(0.0, withExactZero.series.first().value, 0.0)
        assertEquals(15.0, withExactZero.evidence.single().observed, 0.0)
        assertTrue(Trends.detect(input.copy(sessions = sessions.map { it.copy(imported = true) })).isEmpty())
        assertTrue(Trends.detect(input.copy(appSessions = rows.map { it.copy(isOthers = true) })).isEmpty())
    }

    @Test fun `action effects exclude straddling and boundary windows and keep reverted associations`() {
        val sessions = (0..5).map { session(it) }
        val at = sessions[2].endMs
        val action = AppliedActionInput(7, "APP_DRAIN_ANOMALY:$APP", ActionType.RESTRICT_BACKGROUND, APP, UID, at, ActionStatus.APPLIED)
        val rows = sessions.mapIndexed { i, s -> row(s.id).copy(powerMah = if (i < 2) 20.0 else if (i == 2) 999.0 else 5.0) }
        val input = inputs(sessions, rows).copy(actions = listOf(action))
        val effect = ActionEffects.detect(input).single()
        assertEquals(Direction.DOWN, effect.direction)
        assertEquals(20.0, effect.evidence.single().baseline!!, 0.0)
        assertEquals(5.0, effect.evidence.single().observed, 0.0)
        assertEquals(5, effect.series.size)
        assertEquals(effect, ActionEffects.detect(input.copy(actions = listOf(action.copy(status = ActionStatus.REVERTED)))).single())
        assertTrue(ActionEffects.detect(input.copy(actions = listOf(action.copy(status = ActionStatus.PREPARED)))).isEmpty())
        assertTrue(ActionEffects.detect(input.copy(actions = listOf(action.copy(appliedAtMs = null)))).isEmpty())
        assertTrue(ActionEffects.detect(input.copy(appSessions = rows.drop(1), sessions = sessions.map {
            it.copy(appWindow = it.appWindow?.copy(fullRowSet = true))
        })).isEmpty())
    }

    @Test fun `device actions use covered drain and nullable app metrics stay unsupported`() {
        val sessions = (0..4).map { session(it).copy(screenOffUah = if (it < 2) 400_000 else 100_000) }
        val action = AppliedActionInput(8, "SCREEN_OFF_DRAIN_HIGH:device", ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS,
            null, null, sessions[2].startMs, ActionStatus.APPLIED)
        val input = inputs(sessions, sessions.map { row(it.id) }).copy(actions = listOf(action))
        assertEquals(Direction.DOWN, ActionEffects.detect(input).single().direction)
        assertTrue(ActionEffects.detect(input.copy(fullUah = null)).isEmpty())
        assertTrue(ActionEffects.detect(input.copy(actions = listOf(action.copy(
            findingKey = "WAKEUP_STORM:$APP", packageName = APP, uid = UID,
        )), appSessions = input.appSessions.map { it.copy(wakeupAlarms = null) })).isEmpty())
    }

    private fun day(epoch: Long, offUah: Long) = DayInput(
        epoch, 0, HOUR, 0, HOUR, 0, offUah, 0, null, null, null, null, null,
    )
}
