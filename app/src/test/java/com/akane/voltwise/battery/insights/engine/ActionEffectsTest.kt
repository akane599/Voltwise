package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.model.ActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.AppliedActionInput
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SessionKind
import com.akane.voltwise.battery.insights.model.Severity
import org.junit.Assert.*
import org.junit.Test

class ActionEffectsTest {
    @Test fun `effect scores rank applied then newest application then numeric action id`() {
        val sessions = listOf(0, 1, 4, 5).map { session(it) }
        val rows = sessions.mapIndexed { index, session ->
            row(session.id).copy(powerMah = if (index < 2) 40.0 else 5.0)
        }
        val at = sessions[1].endMs + HOUR
        val action = AppliedActionInput(9, "APP_DRAIN_ANOMALY:$APP", ActionType.RESTRICT_BACKGROUND,
            APP, UID, at, ActionStatus.APPLIED)
        val input = inputs(sessions, rows).copy(actions = listOf(
            action,
            action.copy(id = 10, appliedAtMs = at + HOUR),
            action.copy(id = 2, appliedAtMs = at + HOUR),
            action.copy(id = 11, appliedAtMs = at + 2 * HOUR, status = ActionStatus.REVERTED),
        ))

        val effects = ActionEffects.detect(input).sortedWith(findingOrder)

        assertEquals("Status, recency and numeric ID determine display order",
            listOf(10L, 2L, 9L, 11L), effects.map { it.key.substringAfterLast(':').toLong() })
        assertTrue(effects.zipWithNext().all { (newer, older) -> newer.score > older.score })
        assertTrue(effects.all { it.score in 0.0..100.0 })
        assertTrue(effects.all { it.severity == Severity.INFO })
        assertTrue(effects.all { it.confidence == Confidence.MEDIUM })
        assertEquals(effects, ActionEffects.detect(input.copy(actions = input.actions.reversed())).sortedWith(findingOrder))
    }

    @Test fun `new heavy app actions fall back from window drain share to measurable power`() {
        val sessions = (0..3).map { session(it) }
        val rows = sessions.mapIndexed { index, session ->
            row(session.id).copy(powerMah = if (index < 2) 40.0 else 5.0)
        }
        for (type in listOf(ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RESTRICTED)) {
            val action = AppliedActionInput(5, "NEW_HEAVY_APP:$APP", type,
                APP, UID, sessions[1].endMs + HOUR, ActionStatus.APPLIED, metric = Metric.WINDOW_DRAIN_SHARE)
            val effects = ActionEffects.detect(inputs(sessions, rows).copy(actions = listOf(action)))
            assertEquals("one action effect for $type", 1, effects.size)
            val effect = effects.single()
            assertEquals(FindingType.ACTION_EFFECT, effect.type)
            assertEquals(Metric.POWER_MAH_PER_H, effect.evidence.single().metric)
            assertEquals(40.0, effect.evidence.single().baseline!!, 0.0)
            assertEquals(5.0, effect.evidence.single().observed, 0.0)
            assertEquals(4, effect.evidence.single().sessions)
            assertEquals(Direction.DOWN, effect.direction)
        }
    }

    @Test fun `sync sourced job storm effect follows syncs rather than rising jobs`() {
        val sessions = (0..3).map { session(it) }
        val rows = sessions.mapIndexed { index, session ->
            row(session.id).copy(
                syncCount = if (index < 2) 60 else 2,
                jobCount = if (index < 2) 1 else 2,
            )
        }
        val action = AppliedActionInput(4, "JOB_STORM:$APP", ActionType.RESTRICT_BACKGROUND,
            APP, UID, sessions[1].endMs + HOUR, ActionStatus.APPLIED, metric = Metric.SYNCS_PER_H)
        val input = inputs(sessions, rows).copy(actions = listOf(action))
        val effect = ActionEffects.detect(input).single()
        assertEquals(Metric.SYNCS_PER_H, effect.evidence.single().metric)
        assertEquals(Direction.DOWN, effect.direction)
        assertEquals(60.0, effect.evidence.single().baseline!!, 0.0)
        assertEquals(2.0, effect.evidence.single().observed, 0.0)

        // Stored evidence takes precedence over the legacy metric suffix, too.
        assertEquals(effect, ActionEffects.detect(input.copy(actions = listOf(
            action.copy(findingKey = "JOB_STORM:$APP:JOBS_PER_H"),
        ))).single())
        val fallback = ActionEffects.detect(input.copy(actions = listOf(action.copy(metric = null)))).single()
        assertEquals(Metric.JOBS_PER_H, fallback.evidence.single().metric)
        assertEquals(Direction.UP, fallback.direction)
        val explicit = ActionEffects.detect(input.copy(actions = listOf(
            action.copy(metric = null, findingKey = "JOB_STORM:$APP:SYNCS_PER_H"),
        ))).single()
        assertEquals(effect, explicit)
        val unsupported = ActionEffects.detect(input.copy(actions = listOf(
            action.copy(metric = Metric.TEMPERATURE_C, findingKey = "JOB_STORM:$APP:SYNCS_PER_H"),
        ))).single()
        assertEquals(effect, unsupported)
        // A supported but unavailable metric must not be replaced with another measurement.
        assertTrue(ActionEffects.detect(input.copy(appSessions = rows.map { it.copy(syncCount = null) })).isEmpty())
    }

    @Test fun `device actions reject app metrics and retain supported device metrics`() {
        val sessions = (0..3).map { index ->
            session(index).copy(
                screenOnMs = HOUR / 2, screenOffMs = HOUR / 2,
                screenOnCoveredMs = HOUR / 2, screenOffCoveredMs = HOUR / 2,
                screenOnUah = if (index < 2) 100_000 else 200_000,
                screenOffUah = if (index < 2) 200_000 else 100_000,
            )
        }
        val action = AppliedActionInput(6, "SCREEN_OFF_DRAIN:device", ActionType.RESTRICT_BACKGROUND,
            null, null, sessions[1].endMs + HOUR, ActionStatus.APPLIED)
        val input = inputs(sessions, emptyList())
        for (metric in listOf(Metric.POWER_MAH_PER_H, Metric.WINDOW_DRAIN_SHARE, Metric.CAPACITY_MAH)) {
            val effects = ActionEffects.detect(input.copy(actions = listOf(action.copy(metric = metric))))
            assertEquals("one device action effect for unsupported $metric", 1, effects.size)
            val effect = effects.single()
            assertEquals(Metric.SCREEN_OFF_PCT_PER_H, effect.evidence.single().metric)
            assertEquals(10.0, effect.evidence.single().baseline!!, 0.0)
            assertEquals(5.0, effect.evidence.single().observed, 0.0)
            assertEquals(Direction.DOWN, effect.direction)
        }
        val supported = ActionEffects.detect(input.copy(actions = listOf(
            action.copy(metric = Metric.SCREEN_ON_PCT_PER_H),
        ))).single()
        assertEquals(Metric.SCREEN_ON_PCT_PER_H, supported.evidence.single().metric)
        assertEquals(Direction.UP, supported.direction)
        assertEquals(5.0, supported.evidence.single().baseline!!, 0.0)
        assertEquals(10.0, supported.evidence.single().observed, 0.0)
    }

    @Test fun `charging actions compare charging metrics rather than unrelated discharge drain`() {
        for ((type, kind, metric) in listOf(
            Triple("HOT_CHARGING", SessionKind.CHARGE, Metric.TEMPERATURE_C),
            Triple("CHARGING_AT_FULL", SessionKind.PLUGGED, Metric.PLUGGED_AT_FULL_MS),
        )) {
            val sessions = (0..3).map { index ->
                session(index, if (index < 2) 3 * HOUR else HOUR).copy(
                    kind = kind, startLevel = 100, endLevel = 100,
                    peakTemperatureDeciC = if (index < 2) 440 else 350,
                )
            }
            val action = AppliedActionInput(1, "$type:device", ActionType.ENABLE_HIGH_BATTERY_ALERT,
                null, null, sessions[1].endMs + HOUR, ActionStatus.APPLIED)
            val input = inputs(sessions, emptyList()).copy(actions = listOf(action))
            val effect = ActionEffects.detect(input).single()
            for (sourceMetric in listOf(metric, Metric.WINDOW_DRAIN_SHARE, Metric.POWER_MAH_PER_H)) {
                assertEquals(effect, ActionEffects.detect(input.copy(actions = listOf(
                    action.copy(metric = sourceMetric),
                ))).single())
            }
            assertEquals(metric, effect.evidence.single().metric)
            assertEquals(Direction.DOWN, effect.direction)
            assertEquals(if (kind == SessionKind.CHARGE) 44.0 else 3.0 * HOUR, effect.evidence.single().baseline!!, 0.0)
            assertEquals(if (kind == SessionKind.CHARGE) 35.0 else HOUR.toDouble(), effect.evidence.single().observed, 0.0)
            assertTrue(ActionEffects.detect(input.copy(sessions = sessions.drop(1))).isEmpty())
            assertTrue(ActionEffects.detect(input.copy(sessions = sessions.map { it.copy(imported = true) })).isEmpty())
            assertTrue(ActionEffects.detect(input.copy(sessions = sessions.map { it.copy(kind = SessionKind.DISCHARGE) })).isEmpty())
            assertTrue(ActionEffects.detect(input.copy(sessions = sessions.map {
                it.copy(startLevel = null, peakTemperatureDeciC = null)
            })).isEmpty())
        }
    }

    @Test fun `app effects use capture boundaries and ignore straddling ineligible and censored windows`() {
        val sessions = (0..4).map { session(it) }
        val at = sessions[2].startMs + HOUR / 2
        val action = AppliedActionInput(3, "WAKEUP_STORM:$APP", ActionType.RESTRICT_BACKGROUND,
            APP, UID, at, ActionStatus.APPLIED)
        val rows = sessions.mapIndexed { i, s -> row(s.id).copy(wakeupAlarms = if (i < 2) 40 else if (i == 2) 999 else 10) }
        val input = inputs(sessions, rows).copy(actions = listOf(action))
        val effect = ActionEffects.detect(input).single()
        assertEquals(40.0, effect.evidence.single().baseline!!, 0.0)
        assertEquals(10.0, effect.evidence.single().observed, 0.0)
        assertEquals(4, effect.evidence.single().sessions)
        assertEquals(effect, ActionEffects.detect(input.copy(sessions = sessions.reversed(), appSessions = rows.reversed())).single())
        for (status in listOf(ActionStatus.FAILED, ActionStatus.UNKNOWN, ActionStatus.ONE_SHOT)) {
            assertTrue(ActionEffects.detect(input.copy(actions = listOf(action.copy(status = status)))).isEmpty())
        }
        assertTrue(ActionEffects.detect(input.copy(actions = listOf(action.copy(uid = null)))).isEmpty())
        assertTrue(ActionEffects.detect(input.copy(appSessions = rows.map { it.copy(wakeupAlarms = 10) })).isEmpty())
        assertTrue(ActionEffects.detect(input.copy(sessions = sessions.map { it.copy(appWindow = null) })).isEmpty())
        val extraWakers = (0 until 10).map { index ->
            row(sessions.first().id).copy(uid = 20_000 + index, packageName = "example.waker$index",
                rank = 30 + index, wakeupAlarms = 40)
        }
        val others = row(sessions.first().id).copy(uid = -1, packageName = "", rank = 40, isOthers = true)
        val censored = input.copy(sessions = sessions.map { it.copy(appWindow = it.appWindow?.copy(fullRowSet = true)) },
            appSessions = rows.mapIndexed { i, row -> if (i == 0) row.copy(packageName = "other.app") else row } +
                extraWakers + others)
        assertTrue(ActionEffects.detect(censored).isEmpty())
    }
}
