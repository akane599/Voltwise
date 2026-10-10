package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.device.ChargingHealth
import com.akane.voltwise.battery.insights.engine.detectors.device.DeviceDetectors
import com.akane.voltwise.battery.insights.engine.recommend.Recommender
import com.akane.voltwise.battery.insights.model.ActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.AppliedActionInput
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.SessionKind
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import org.junit.Assert.*
import org.junit.Test

class InsightEngineTest {
    @Test fun `recommendations cover every finding type and preserve manual privileged paths`() {
        val input = inputs(emptyList(), emptyList())
        val base = listOf(ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RESTRICTED, ActionType.OPEN_APP_SETTINGS)
        for (type in FindingType.entries) {
            val expected = when (type) {
                FindingType.APP_DRAIN_ANOMALY, FindingType.NEW_HEAVY_APP, FindingType.STUCK_WAKELOCK,
                FindingType.WAKEUP_STORM, FindingType.JOB_STORM, FindingType.BACKGROUND_LOCATION, FindingType.BACKGROUND_RADIO -> base
                FindingType.BACKGROUND_RUNAWAY, FindingType.LINGERING_FOREGROUND_SERVICE -> base.dropLast(1) + ActionType.FORCE_STOP + base.last()
                FindingType.DOZE_WHITELISTED_DRAINER -> listOf(ActionType.REMOVE_DOZE_WHITELIST, ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS)
                FindingType.DOZE_BLOCKED, FindingType.SCREEN_OFF_DRAIN_HIGH -> listOf(ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS)
                FindingType.CHARGING_AT_FULL, FindingType.HOT_CHARGING -> listOf(ActionType.ENABLE_HIGH_BATTERY_ALERT)
                else -> emptyList()
            }
            val candidate = finding(type, emptyList(), subject = Subject.App(UID, APP))
            val result = Recommender.recommend(candidate, input, sdkInt = 37)
            assertEquals(type.name, expected, result.recommendations.map { it.action })
            result.recommendations.forEach { rec ->
                assertEquals(rec.action != ActionType.FORCE_STOP, rec.reversible)
                assertEquals(rec.action in setOf(ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RESTRICTED,
                    ActionType.REMOVE_DOZE_WHITELIST, ActionType.FORCE_STOP), rec.requiresPrivilege)
            }
        }
    }

    @Test fun `only applied package plus type suppresses recommendation`() {
        val action = AppliedActionInput(1, "old", ActionType.RESTRICT_BACKGROUND, APP, UID, 0, ActionStatus.APPLIED)
        val candidate = finding(FindingType.APP_DRAIN_ANOMALY, emptyList(), Subject.App(UID, APP))
        val input = inputs(emptyList(), emptyList()).copy(actions = listOf(action))
        assertEquals(2, Recommender.recommend(candidate, input, sdkInt = 37).recommendations.size)
        assertEquals(3, Recommender.recommend(candidate, input.copy(actions = listOf(action.copy(status = ActionStatus.REVERTED))), sdkInt = 37).recommendations.size)
        assertEquals(3, Recommender.recommend(candidate, input.copy(actions = listOf(action.copy(packageName = "other.app"))), sdkInt = 37).recommendations.size)
    }

    @Test fun `applied actions for another uid retain app recommendations`() {
        val actions = listOf(ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RESTRICTED)
        val input = inputs(emptyList(), emptyList()).copy(actions = actions.mapIndexed { index, action ->
            AppliedActionInput(index.toLong(), "old", action, APP, UID + 1, 0, ActionStatus.APPLIED)
        })
        for (type in listOf(FindingType.APP_DRAIN_ANOMALY, FindingType.BACKGROUND_RUNAWAY)) {
            val candidate = finding(type, emptyList(), Subject.App(UID, APP))
            val recommended = Recommender.recommend(candidate, input, sdkInt = 37).recommendations.map { it.action }
            for (action in actions) {
                assertTrue("$type retains $action for a different uid", action in recommended)
            }
        }
    }

    @Test fun `applied actions for the same uid suppress app recommendations`() {
        val actions = listOf(ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RESTRICTED)
        val input = inputs(emptyList(), emptyList()).copy(actions = actions.mapIndexed { index, action ->
            AppliedActionInput(index.toLong(), "old", action, APP, UID, 0, ActionStatus.APPLIED)
        })
        for (type in listOf(FindingType.APP_DRAIN_ANOMALY, FindingType.BACKGROUND_RUNAWAY)) {
            val candidate = finding(type, emptyList(), Subject.App(UID, APP))
            val recommended = Recommender.recommend(candidate, input, sdkInt = 37).recommendations.map { it.action }
            for (action in actions) {
                assertFalse("$type suppresses $action for the same uid", action in recommended)
            }
            assertTrue(ActionType.OPEN_APP_SETTINGS in recommended)
        }
    }

    @Test fun `live whitelisted drainer retains removal despite an applied journal row`() {
        val candidate = finding(FindingType.DOZE_WHITELISTED_DRAINER, emptyList(), Subject.App(UID, APP))
        val applied = AppliedActionInput(1, "old", ActionType.REMOVE_DOZE_WHITELIST, APP, UID, 0, ActionStatus.APPLIED)
        val input = inputs(emptyList(), emptyList()).copy(actions = listOf(applied))

        assertEquals(
            listOf(ActionType.REMOVE_DOZE_WHITELIST, ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS),
            Recommender.recommend(candidate, input, sdkInt = 37).recommendations.map { it.action },
        )
    }

    @Test fun `restriction recommendations require API 28 and preserve fallback and other actions`() {
        val input = inputs(emptyList(), emptyList())
        for (sdk in listOf(26, 27, 28, 29, 30, 37)) {
            for (type in FindingType.entries) {
                val candidate = finding(type, emptyList(), Subject.App(UID, APP))
                val supported = Recommender.recommend(candidate, input, sdkInt = 37).recommendations
                val expected = if (sdk >= 28) supported else supported.filterNot {
                    it.action == ActionType.RESTRICT_BACKGROUND || it.action == ActionType.STANDBY_BUCKET_RESTRICTED ||
                        it.action == ActionType.STANDBY_BUCKET_RARE
                }
                assertEquals("$type API $sdk", expected, Recommender.recommend(candidate, input, sdk).recommendations)
            }
        }
        val candidate = finding(FindingType.APP_DRAIN_ANOMALY, emptyList(), Subject.App(UID, APP))
        assertTrue(Recommender.recommend(candidate, input, 28).recommendations.any {
            it.action == ActionType.STANDBY_BUCKET_RESTRICTED
        })
        val applied = AppliedActionInput(1, candidate.key, ActionType.STANDBY_BUCKET_RESTRICTED, APP, UID, 0, ActionStatus.APPLIED)
        assertFalse(Recommender.recommend(candidate, input.copy(actions = listOf(applied)), 28).recommendations.any {
            it.action == ActionType.STANDBY_BUCKET_RESTRICTED
        })
    }

    @Test fun `analysis forwards SDK to recommendation policy`() {
        val input = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        for (sdk in listOf(27, 28)) {
            val result = InsightEngine.analyze(input, sdk)
            val appFinding = result.findings.first { it.type == FindingType.APP_DRAIN_ANOMALY }
            assertEquals("API $sdk", sdk >= 28, appFinding.recommendations.any {
                it.action == ActionType.STANDBY_BUCKET_RESTRICTED
            })
            assertEquals("API $sdk background restriction", sdk >= 28, appFinding.recommendations.any {
                it.action == ActionType.RESTRICT_BACKGROUND
            })
            assertTrue(appFinding.recommendations.any { it.action == ActionType.OPEN_APP_SETTINGS })
        }
    }

    @Test fun `rank sorts severity then confidence then score then stable key`() {
        val low = finding(FindingType.NEW_HEAVY_APP, emptyList(), severity = Severity.LOW, score = 100.0)
        val high = low.copy(key = "high", severity = Severity.HIGH, score = 1.0)
        val confidence = high.copy(key = "confidence", confidence = Confidence.HIGH, score = 0.0)
        val score = confidence.copy(key = "score", score = 2.0)
        val tie = score.copy(key = "aaa")
        assertEquals(listOf(tie, score, confidence, high, low), listOf(low, high, score, tie, confidence).sortedWith(findingOrder))
    }

    @Test fun `report cap retains applied action effect ahead of higher scoring app trends`() {
        val input = actionEffectWithAppTrends(appCount = 13)
        val trends = Trends.detect(input)
        val effect = ActionEffects.detect(input).single()
        assertEquals(13, trends.size)
        assertTrue(trends.all { it.severity == Severity.INFO && it.score > effect.score })

        val report = InsightEngine.analyze(input, sdkInt = 37)

        assertEquals(12, report.findings.size)
        assertTrue("Applied action effect survives the report cap", report.findings.contains(effect))
        assertEquals(11, report.findings.count { it.type == FindingType.TREND })
        assertEquals(report.findings.sortedWith(findingOrder), report.findings)
        assertNull(report.headline)
    }

    @Test fun `report cap retains newest applied effect instead of alphabetically first reverted effect`() {
        val day = 24 * HOUR
        val sessions = listOf(28, 29, 41, 42, 94, 95, 97, 98).map { session(it) }
        val rows = sessions.flatMapIndexed { index, session ->
            val effects = listOf("a.app", "z.app").mapIndexed { app, packageName ->
                row(session.id).copy(
                    packageName = packageName, uid = UID + app,
                    powerMah = when {
                        index < 2 -> 40.0
                        index < 6 -> 20.0
                        else -> 5.0
                    },
                )
            }
            effects + (1..11).map { app ->
                row(session.id).copy(
                    packageName = "heavy.app$app", uid = UID + 10 + app,
                    powerMah = if (index == sessions.lastIndex) 40.0 else 5.0,
                )
            }
        }
        val input = inputs(sessions, rows).copy(
            nowMs = 100 * day,
            actions = listOf(
                AppliedActionInput(1, "APP_DRAIN_ANOMALY:a.app", ActionType.RESTRICT_BACKGROUND,
                    "a.app", UID, 40 * day, ActionStatus.REVERTED),
                AppliedActionInput(2, "APP_DRAIN_ANOMALY:z.app", ActionType.RESTRICT_BACKGROUND,
                    "z.app", UID + 1, 97 * day, ActionStatus.APPLIED),
            ),
        )
        val effects = ActionEffects.detect(input)
        assertEquals(2, effects.size)
        assertEquals(11, appFindings(input).count { it.severity != Severity.INFO })

        val report = InsightEngine.analyze(input, sdkInt = 37)

        assertEquals(12, report.findings.size)
        assertEquals(11, report.findings.count { it.severity != Severity.INFO })
        assertEquals("Newest applied effect survives the report cap",
            "ACTION_EFFECT:z.app:POWER_MAH_PER_H:2",
            report.findings.single { it.type == FindingType.ACTION_EFFECT }.key)
        assertEquals(report.findings.first { it.severity != Severity.INFO }, report.headline)
        assertEquals(report, InsightEngine.analyze(input.copy(actions = input.actions.reversed()), sdkInt = 37))
    }

    @Test fun `uncapped action effect and trends keep their original display order`() {
        val input = actionEffectWithAppTrends(appCount = 3)
        val expected = (Trends.detect(input) + ActionEffects.detect(input)).sortedWith(findingOrder)

        val report = InsightEngine.analyze(input, sdkInt = 37)

        assertEquals(4, expected.size)
        assertEquals(expected, report.findings)
        assertEquals(FindingType.ACTION_EFFECT, report.findings.last().type)
        assertNull(report.headline)
    }

    private fun actionEffectWithAppTrends(appCount: Int): InsightInputs {
        val sessions = (75..78).map { session(it) } + (92..95).map { session(it) }
        val rows = sessions.flatMapIndexed { index, session ->
            (0 until appCount).map { app ->
                row(session.id).copy(
                    packageName = if (app == 0) APP else "example.app$app",
                    uid = UID + app,
                    powerMah = if (index < 4) 20.0 else 5.0,
                )
            }
        }
        val action = AppliedActionInput(
            7, "APP_DRAIN_ANOMALY:$APP", ActionType.RESTRICT_BACKGROUND, APP, UID,
            sessions[3].endMs + HOUR, ActionStatus.APPLIED,
        )
        return inputs(sessions, rows).copy(actions = listOf(action))
    }

    @Test fun `analyze globally caps ranks and selects noninformational headline deterministically`() {
        val base = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        val rows = base.appSessions.flatMap { row -> (1..4).map { row.copy(packageName = "app.n$it", uid = UID + it) } }
        val plugged = (0..2).map { session(it + 5, 2 * HOUR).copy(kind = SessionKind.PLUGGED, startLevel = 100, endLevel = 100) }
        val input = base.copy(sessions = base.sessions + plugged, appSessions = rows, nowMs = plugged.last().endMs + HOUR)
        val candidates = appFindings(input) + DeviceDetectors.detect(input) + Trends.detect(input) + ChargingHealth.detect(input) + ActionEffects.detect(input)
        assertTrue(candidates.size > 12)
        val report = InsightEngine.analyze(input, sdkInt = 37)
        assertEquals(12, report.findings.size)
        assertEquals(candidates.sortedWith(findingOrder).take(12).map { it.key }, report.findings.map { it.key })
        assertEquals(report.findings.first { it.severity != Severity.INFO }, report.headline)
        assertEquals(input.nowMs, report.generatedAtMs)
        assertEquals(report, InsightEngine.analyze(input, sdkInt = 37))
        assertEquals(report, InsightEngine.analyze(input.copy(sessions = input.sessions.reversed(), appSessions = rows.reversed()), sdkInt = 37))
        assertNull(InsightEngine.analyze(inputs(emptyList(), emptyList()), sdkInt = 37).headline)
        val action = AppliedActionInput(9, "APP_DRAIN_ANOMALY:$APP", ActionType.RESTRICT_BACKGROUND, APP, UID,
            base.sessions[2].startMs, ActionStatus.APPLIED)
        val effectOnly = base.copy(appSessions = base.appSessions.mapIndexed { i, r ->
            row(r.sessionId).copy(powerMah = if (i < 2) 20.0 else 5.0)
        }, actions = listOf(action))
        val info = InsightEngine.analyze(effectOnly, sdkInt = 37)
        assertTrue(info.findings.isNotEmpty())
        assertTrue(info.findings.all { it.severity == Severity.INFO })
        assertNull(info.headline)
    }
}
