package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.app.WakeupStorm
import com.akane.voltwise.battery.insights.engine.stats.RobustBaseline
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.battery.insights.model.WindowBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AppDetectorTest(private val type: FindingType) {
    private fun detected(inputs: InsightInputs): List<Finding> = appFindings(inputs).filter {
        it.type == type && it.subject == Subject.App(UID, APP)
    }

    @Test fun positiveFlatBaselineUsesAbsoluteFloorAndStableKey() {
        val finding = detected(detectorInputs(type)).single()
        assertEquals("${type.name}:$APP", finding.key)
        assertTrue(finding.score.isFinite())
        assertTrue(finding.evidence.isNotEmpty())
        assertTrue(finding.evidence.all { it.observed.isFinite() })
        assertTrue(finding.recommendations.isEmpty())
    }

    @Test fun nearFlatWakeupHistoryKeepsSeverityMediumAndScoreBelowFlatHistory() {
        org.junit.Assume.assumeTrue(type == FindingType.WAKEUP_STORM)
        val sessions = (0..5).map { session(it) }
        val rows = sessions.mapIndexed { index, session ->
            row(session.id).copy(wakeupAlarms = listOf(0L, 0L, 1L, 1L, 2L, 35L)[index])
        }
        val finding = detected(inputs(sessions, rows)).single()
        val flat = detected(inputs(sessions, rows.mapIndexed { index, row ->
            if (index < 5) row.copy(wakeupAlarms = 0) else row
        })).single()
        assertEquals(Severity.MEDIUM, finding.severity)
        assertEquals(34.0, finding.score, 1e-9)
        assertTrue(finding.score <= flat.score)
        assertEquals(35.0, finding.evidence.single().observed, 0.0)
    }

    @Test fun flatWakeupUsualRangeUsesDetectionFloor() {
        org.junit.Assume.assumeTrue(type == FindingType.WAKEUP_STORM)
        val alarms = listOf(1L, 1L, 1L, 2L, 1L, 1L, 40L)
        val sessions = alarms.indices.map { session(it) }
        val rows = sessions.mapIndexed { index, session ->
            row(session.id).copy(wakeupAlarms = alarms[index])
        }
        val input = inputs(sessions, rows)
        for (multiplier in listOf(1.0, 1.2)) {
            val finding = detected(input.copy(feedback = mapOf("${type.name}:$APP" to multiplier))).single()
            val median = finding.evidence.single().baseline!!
            assertEquals(1.0, median, 0.0)
            assertEquals(alarms.size, finding.series.size)
            finding.series.forEach { point ->
                assertTrue("Every high edge must include the detection floor", point.baselineHigh!! >= median + WakeupStorm.ALARMS_FLOOR_PER_H)
                assertEquals(median + WakeupStorm.ALARMS_FLOOR_PER_H, point.baselineHigh!!, 0.0)
                assertEquals((median - WakeupStorm.ALARMS_FLOOR_PER_H).coerceAtLeast(0.0), point.baselineLow!!, 0.0)
            }
        }
    }

    @Test fun nearFlatWakeupUsualRangeUsesDetectionFloor() {
        org.junit.Assume.assumeTrue(type == FindingType.WAKEUP_STORM)
        val alarms = listOf(0L, 0L, 1L, 1L, 2L, 35L)
        val sessions = alarms.indices.map { session(it) }
        val rows = sessions.mapIndexed { index, session ->
            row(session.id).copy(wakeupAlarms = alarms[index])
        }
        val finding = detected(inputs(sessions, rows)).single()
        assertEquals(1.0, finding.evidence.single().baseline!!, 0.0)
        assertEquals(alarms.size, finding.series.size)
        finding.series.forEach { point ->
            assertEquals(31.0, point.baselineHigh!!, 0.0)
            assertEquals(0.0, point.baselineLow!!, 0.0)
        }
    }

    @Test fun wakeupUsualRangeUsesMedianWhenItExceedsMadBand() {
        org.junit.Assume.assumeTrue(type == FindingType.WAKEUP_STORM)
        val alarms = listOf(140L, 160L, 180L, 200L, 220L, 240L, 400L)
        val sessions = alarms.indices.map { session(it) }
        val rows = sessions.mapIndexed { index, session ->
            row(session.id).copy(wakeupAlarms = alarms[index])
        }
        val finding = detected(inputs(sessions, rows)).single()
        val median = finding.evidence.single().baseline!!
        val band = median
        assertTrue(band > RobustBaseline.Z_THRESHOLD * RobustBaseline.MAD_SCALE * 20.0)
        assertEquals(200.0, median, 0.0)
        assertTrue(band > WakeupStorm.ALARMS_FLOOR_PER_H)
        assertEquals(alarms.size, finding.series.size)
        finding.series.forEach { point ->
            assertEquals(median + band, point.baselineHigh!!, 1e-9)
            assertEquals(median - band, point.baselineLow!!, 1e-9)
        }
    }

    @Test fun currentWindowOlderThanSevenDaysDoesNotProduceAppFindings() {
        val input = detectorInputs(type)
        val currentEnd = input.sessions.last().appWindow!!.captureEndMs
        val stale = input.copy(nowMs = currentEnd + 7 * 24 * HOUR + 1)
        assertTrue("Stale eligible windows must not produce any app finding", appFindings(stale).isEmpty())
    }

    @Test fun currentWindowWithinSevenDaysStillProducesFinding() {
        val input = detectorInputs(type)
        val currentEnd = input.sessions.last().appWindow!!.captureEndMs
        for (age in listOf(7 * 24 * HOUR - 1, 7 * 24 * HOUR)) {
            val recent = input.copy(nowMs = currentEnd + age)
            assertEquals(type, detected(recent).single().type)
        }
    }

    @Test fun normalUsageDoesNotTrigger() {
        val input = detectorInputs(type)
        val normal = input.copy(appSessions = input.appSessions.map { row(it.sessionId).copy(uid = it.uid, packageName = it.packageName) })
        assertTrue(detected(normal).isEmpty())
    }

    @Test fun fewerThanFourBaselineSessionsDoNotTrigger() {
        assertTrue(detected(detectorInputs(type, baselineCount = 3)).isEmpty())
    }

    @Test fun feedbackMultiplierSuppressesTheSameFinding() {
        val input = detectorInputs(type)
        assertFalse(detected(input).isEmpty())
        assertTrue(detected(input.copy(feedback = mapOf("${type.name}:$APP" to 100.0))).isEmpty())
    }

    @Test fun unsupportedCurrentMetricsNeverBecomeZero() {
        val input = detectorInputs(type)
        val lastId = input.sessions.last().id
        assertTrue(detected(input.copy(appSessions = input.appSessions.map {
            if (it.sessionId == lastId) it.unsupported() else it
        })).isEmpty())
    }

    @Test fun unsupportedHistoryCannotSupplyTheFourthBaseline() {
        val input = detectorInputs(type)
        assertTrue(detected(input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
            if (index == 0) row.unsupported() else row
        })).isEmpty())
    }

    @Test fun ineligibleWindowsNeverTrainOrDetect() {
        val input = detectorInputs(type)
        val transforms: List<(com.akane.voltwise.battery.insights.model.SessionInput) -> com.akane.voltwise.battery.insights.model.SessionInput> = listOf(
            { it.copy(imported = true) },
            { it.copy(appWindow = it.appWindow!!.copy(basis = WindowBasis.ABSOLUTE)) },
            { it.copy(appWindow = it.appWindow!!.copy(basis = WindowBasis.WINDOW_RESET)) },
            { it.copy(endMs = it.startMs + HOUR / 2, appWindow = it.appWindow!!.copy(captureEndMs = it.startMs + HOUR / 2)) },
        )
        for (transform in transforms) {
            assertTrue(detected(input.copy(sessions = input.sessions.map(transform))).isEmpty())
            assertTrue(detected(input.copy(sessions = input.sessions.mapIndexed { index, session ->
                if (index == 0) transform(session) else session
            })).isEmpty())
        }
    }

    @Test fun censoredCurrentAbsenceIsNotAnObservedSpike() {
        val input = detectorInputs(type)
        val lastId = input.sessions.last().id
        val absent = input.copy(
            sessions = input.sessions.map { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) },
            appSessions = input.appSessions.map {
                if (it.sessionId == lastId) it.copy(uid = 10002, packageName = "example.other") else it
            },
        )
        assertTrue(detected(absent).isEmpty())
    }

    @Test fun censoredHistoryDoesNotCountAsMeasuredBaseline() {
        val input = detectorInputs(type)
        val censored = input.copy(
            sessions = input.sessions.map { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) },
            appSessions = input.appSessions.mapIndexed { index, row ->
                if (index == 0) row.copy(uid = 10002, packageName = "example.other") else row
            },
        )
        // NEW_HEAVY_APP intentionally accepts below-cutoff absence as not-heavy history.
        if (type == FindingType.NEW_HEAVY_APP) assertEquals(1, detected(censored).size)
        else assertTrue(detected(censored).isEmpty())
    }

    @Test fun stableJobsInTruncatedHistoryDoNotCreateJobStorm() {
        org.junit.Assume.assumeTrue(type == FindingType.JOB_STORM)
        val sessions = (0..9).map { index ->
            session(index).let {
                if (index in 4..8) it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) else it
            }
        }
        val rows = sessions.flatMapIndexed { index, session ->
            if (index in 4..8) {
                val leaders = (0 until 30).map { rank ->
                    row(session.id).copy(uid = 20_000 + rank, packageName = "example.leader$rank", rank = rank,
                        powerMah = 60.0 - rank, jobCount = 0, syncCount = 0)
                }
                val others = row(session.id).copy(uid = -1, packageName = "", rank = 30, isOthers = true,
                    powerMah = 5.0, jobCount = 40, syncCount = 0)
                leaders + others
            } else {
                listOf(row(session.id).copy(jobCount = if (index == 9) 45 else 40, syncCount = 0))
            }
        }
        assertTrue("Stable 40 jobs/h history must not turn 45 jobs/h into JOB_STORM", detected(inputs(sessions, rows)).isEmpty())
    }

    @Test fun aggregateOthersIsNeverASubject() {
        val input = detectorInputs(type)
        assertTrue(appFindings(input.copy(appSessions = input.appSessions.map { it.copy(isOthers = true) })).isEmpty())
    }

    @Test fun confidenceReflectsMeasuredBaselineSize() {
        val finding = detected(detectorInputs(type, baselineCount = 12)).single()
        assertEquals(if (type == FindingType.NEW_HEAVY_APP) Confidence.LOW else Confidence.HIGH, finding.confidence)
    }

    @Test fun inputOrderingDoesNotChangeResults() {
        val input = detectorInputs(type)
        assertEquals(appFindings(input), appFindings(input.copy(sessions = input.sessions.reversed(), appSessions = input.appSessions.reversed())))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<FindingType>> = listOf(
            FindingType.APP_DRAIN_ANOMALY, FindingType.NEW_HEAVY_APP, FindingType.BACKGROUND_RUNAWAY,
            FindingType.STUCK_WAKELOCK, FindingType.WAKEUP_STORM, FindingType.JOB_STORM,
            FindingType.BACKGROUND_LOCATION, FindingType.BACKGROUND_RADIO, FindingType.LINGERING_FOREGROUND_SERVICE,
        ).map { arrayOf(it) }
    }
}
