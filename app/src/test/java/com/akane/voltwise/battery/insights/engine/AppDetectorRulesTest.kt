package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.detectors.app.AppContext
import com.akane.voltwise.battery.insights.engine.detectors.app.AppDrainAnomaly
import com.akane.voltwise.battery.insights.engine.detectors.app.BackgroundRunaway
import com.akane.voltwise.battery.insights.engine.detectors.app.JobStorm
import com.akane.voltwise.battery.insights.engine.detectors.app.NewHeavyApp
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AppDetectorRulesTest {
    private fun context(input: InsightInputs) = AppContext(input, AppWindows.select(input), Subject.App(UID, APP))

    @Test fun runawayNeedsTwoConsecutiveObservedSessionsAndTwentyPercentBackgroundWakelock() {
        val input = detectorInputs(FindingType.BACKGROUND_RUNAWAY)
        assertEquals(1, BackgroundRunaway.detect(context(input)).size)
        val interrupted = input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
            if (index == 4) row.copy(bgMs = 0, fgServiceMs = 0) else row
        })
        assertTrue(BackgroundRunaway.detect(context(interrupted)).isEmpty())
        val noHold = input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
            if (index >= 4) row.copy(partialWakelockBgMs = HOUR / 5 - 1) else row
        })
        assertTrue(BackgroundRunaway.detect(context(noHold)).isEmpty())
        val boundary = input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
            if (index >= 4) row.copy(partialWakelockBgMs = HOUR / 5) else row
        })
        assertEquals(1, BackgroundRunaway.detect(context(boundary)).size)
    }

    @Test fun backgroundOnlyAppWithSparseForegroundStillProducesLocationFinding() {
        val input = detectorInputs(FindingType.BACKGROUND_LOCATION).let { input ->
            input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
                row.copy(fgMs = null, topMs = 0, bgMs = HOUR / 2, gpsMs = if (index < 4) HOUR / 4 else HOUR / 2)
            })
        }
        val findings = appFindings(input).filter { it.type == FindingType.BACKGROUND_LOCATION }
        assertEquals(1, findings.size)
        val evidence = findings.single().evidence.first()
        assertEquals(Metric.GPS_MS_PER_H, evidence.metric)
        assertEquals((HOUR / 4).toDouble(), evidence.baseline!!, 0.0)
        assertEquals((HOUR / 2).toDouble(), evidence.observed, 0.0)

        val unknownTop = input.copy(appSessions = input.appSessions.map { it.copy(topMs = null) })
        assertTrue(appFindings(unknownTop).none { it.type == FindingType.BACKGROUND_LOCATION })
    }

    @Test fun systemUidWithSparseForegroundDoesNotProduceBackgroundRadioFinding() {
        val input = detectorInputs(FindingType.BACKGROUND_RADIO).let { input ->
            input.copy(appSessions = input.appSessions.map { it.copy(uid = 1001, fgMs = null, topMs = 0) })
        }
        assertTrue(appFindings(input).none { it.type == FindingType.BACKGROUND_RADIO })

        val application = input.copy(appSessions = input.appSessions.map { it.copy(uid = 10123) })
        assertEquals(1, appFindings(application).count { it.type == FindingType.BACKGROUND_RADIO })
        assertTrue(appFindings(input).any { it.type == FindingType.APP_DRAIN_ANOMALY })
    }

    @Test fun systemUidsWithZeroForegroundDoNotProduceBackgroundFindings() {
        for (type in listOf(FindingType.BACKGROUND_RADIO, FindingType.BACKGROUND_LOCATION,
            FindingType.LINGERING_FOREGROUND_SERVICE)) {
            val input = detectorInputs(type).let { input ->
                input.copy(appSessions = input.appSessions.map { it.copy(uid = 10123, fgMs = 0, topMs = 0, gpsMs = 0) })
            }
            val application = appFindings(input).single { it.type == type }
            if (type == FindingType.BACKGROUND_LOCATION) {
                assertEquals("Exercise the sensor fallback, not GPS", Metric.SENSOR_MS_PER_H, application.evidence.first().metric)
            }
            for (uid in listOf(1000, 1001)) {
                val system = input.copy(appSessions = input.appSessions.map { it.copy(uid = uid) })
                assertTrue("UID $uid with zero foreground cannot establish $type",
                    appFindings(system).none { it.type == type })
                assertTrue("Power-only findings remain supported",
                    appFindings(system).any { it.type == FindingType.APP_DRAIN_ANOMALY })
            }
        }
    }

    @Test fun seededStationaryZeroForegroundStaysSilentAndAppSpikesRemainDetectable() {
        val trials = 50
        for ((mode, seed) in listOf("flat" to 3580, "noisy" to 3581, "AR1" to 3582)) {
            val random = Random(seed)
            for (type in listOf(FindingType.BACKGROUND_RADIO, FindingType.BACKGROUND_LOCATION,
                FindingType.LINGERING_FOREGROUND_SERVICE)) {
                var systemFindings = 0
                var stationaryAppFindings = 0
                var appSpikes = 0
                repeat(trials) {
                    var noise = 0.0
                    val template = detectorInputs(type)
                    val stationary = template.copy(appSessions = template.appSessions.map { row ->
                        noise = when (mode) {
                            "flat" -> 0.0
                            "noisy" -> random.nextDouble(-10_000.0, 10_000.0)
                            else -> 0.8 * noise + random.nextDouble(-5_000.0, 5_000.0)
                        }
                        val activity = (60_000 + noise).toLong()
                        row.copy(uid = 10123, fgMs = 0, topMs = 0, gpsMs = 0,
                            sensorMs = activity, mobileActiveMs = activity, fgServiceMs = activity)
                    })
                    val spike = stationary.copy(appSessions = stationary.appSessions.mapIndexed { index, row ->
                        if (index < 4) row else row.copy(sensorMs = 1_000_000, mobileActiveMs = 1_000_000,
                            fgServiceMs = 1_800_000)
                    })
                    if (appFindings(stationary).any { it.type == type }) stationaryAppFindings++
                    if (appFindings(spike).any { it.type == type }) appSpikes++
                    val system = spike.copy(appSessions = spike.appSessions.map { it.copy(uid = 1000) })
                    if (appFindings(system).any { it.type == type }) systemFindings++
                }
                println("$type $mode: $stationaryAppFindings/$trials stationary app findings, " +
                    "$systemFindings/$trials system findings (bound 0), $appSpikes/$trials app spikes (seed $seed)")
                assertEquals(0, stationaryAppFindings)
                assertEquals(0, systemFindings)
                assertEquals(trials, appSpikes)
            }
        }
    }

    @Test fun foregroundActivityDisqualifiesLocationRadioAndLingeringService() {
        for (type in listOf(FindingType.BACKGROUND_LOCATION, FindingType.BACKGROUND_RADIO, FindingType.LINGERING_FOREGROUND_SERVICE)) {
            val input = detectorInputs(type)
            val active = input.copy(appSessions = input.appSessions.map { it.copy(fgMs = HOUR / 2, topMs = HOUR / 2) })
            assertTrue(appFindings(active).none { it.type == type })
        }
    }

    @Test fun unchangedFgsRateDoesNotBecomeLingeringInLongerWindows() {
        for (currentHours in listOf(3L, 10L)) {
            val sessions = (0..4).map { session(it, (if (it < 4) 2L else currentHours) * HOUR) }
            val rows = sessions.map { row(it.id).copy(fgServiceMs = (it.endMs - it.startMs) / 2) }
            assertTrue(
                "An unchanged 30 min/h FGS rate must not fire in a $currentHours-hour window",
                appFindings(inputs(sessions, rows)).none { it.type == FindingType.LINGERING_FOREGROUND_SERVICE },
            )
        }
    }

    @Test fun fourfoldFgsRateIncreaseStillFiresLingering() {
        val sessions = (0..4).map { session(it, 2 * HOUR) }
        val rows = sessions.mapIndexed { index, session ->
            row(session.id).copy(fgServiceMs = if (index < 4) HOUR / 2 else 2 * HOUR)
        }
        val finding = appFindings(inputs(sessions, rows)).single { it.type == FindingType.LINGERING_FOREGROUND_SERVICE }
        assertEquals(Metric.FGS_TO_FOREGROUND_RATIO, finding.evidence.first().metric)
        assertEquals(15.0, finding.evidence.first().baseline!!, 0.0)
        assertEquals(60.0, finding.evidence.first().observed, 0.0)
    }

    @Test fun backgroundDominanceRequiresThreeMinutesPerHourWhenNeverForeground() {
        for (hours in listOf(1L, 2L, 10L)) {
            val session = session(0, hours * HOUR)
            val boundary = row(session.id).copy(bgMs = hours * 180_000L)
            assertTrue(context(inputs(listOf(session), listOf(boundary))).backgroundDominant())
            assertFalse(context(inputs(listOf(session), listOf(boundary.copy(bgMs = boundary.bgMs!! - 1)))).backgroundDominant())
        }
    }

    @Test fun jobStormCanUseSupportedSyncsWithoutJobs() {
        val input = detectorInputs(FindingType.JOB_STORM)
        val finding = JobStorm.detect(context(input.copy(appSessions = input.appSessions.map { it.copy(jobCount = null) }))).single()
        assertEquals(Metric.SYNCS_PER_H, finding.evidence.first().metric)
    }

    @Test fun newHeavyNeedsFifteenPercentAndBackgroundDominanceThenGraduatesToBaseline() {
        val input = detectorInputs(FindingType.NEW_HEAVY_APP)
        fun currentPower(power: Double) = input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
            if (index == 4) row.copy(powerMah = power) else row
        })
        assertTrue(NewHeavyApp.detect(context(currentPower(29.9))).isEmpty())
        assertEquals(1, NewHeavyApp.detect(context(currentPower(30.0))).size)
        val foreground = input.copy(appSessions = input.appSessions.map { it.copy(bgMs = 0) })
        assertTrue(NewHeavyApp.detect(context(foreground)).isEmpty())
        val veteran = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        assertTrue(NewHeavyApp.detect(context(veteran)).isEmpty())
        assertEquals(1, AppDrainAnomaly.detect(context(veteran)).size)
    }

    @Test fun newHeavyCountsCensoredAbsenceAndPriorMeasuredButNotHeavyWindows() {
        val input = detectorInputs(FindingType.NEW_HEAVY_APP)
        val censored = input.copy(sessions = input.sessions.map { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) })
        assertEquals(1, NewHeavyApp.detect(context(censored)).size)
        val oneMeasured = censored.copy(appSessions = censored.appSessions.mapIndexed { index, row ->
            if (index == 0) row.copy(uid = UID, packageName = APP) else row
        })
        assertEquals(1, NewHeavyApp.detect(context(oneMeasured)).size)
        val previouslyHeavy = oneMeasured.copy(appSessions = oneMeasured.appSessions.mapIndexed { index, row ->
            if (index == 0) row.copy(powerMah = 40.0) else row
        })
        assertTrue(NewHeavyApp.detect(context(previouslyHeavy)).isEmpty())
    }

    @Test fun nonzeroMadRequiresRobustZEvenWhenAbsoluteFloorPasses() {
        val input = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        val baseline = listOf(0.0, 10.0, 20.0, 30.0)
        val noisy = input.copy(appSessions = input.appSessions.mapIndexed { index, row ->
            row.copy(powerMah = baseline.getOrNull(index) ?: 40.0)
        })
        assertTrue(AppDrainAnomaly.detect(context(noisy)).isEmpty())
        val clear = noisy.copy(appSessions = noisy.appSessions.mapIndexed { index, row -> if (index == 4) row.copy(powerMah = 100.0) else row })
        assertEquals(1, AppDrainAnomaly.detect(context(clear)).size)
    }

    @Test fun censoredHistoricalUpperBoundsReduceConfidenceAndAreNotChartMeasurements() {
        val sessions = (0..8).map { index ->
            session(index).let { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) }
        }
        val rows = sessions.mapIndexed { index, session ->
            when {
                index < 4 -> row(session.id)
                index < 8 -> row(session.id).copy(uid = 10002, packageName = "example.other")
                else -> highRow(session.id)
            }
        }
        val finding = AppDrainAnomaly.detect(context(inputs(sessions, rows))).single()
        assertEquals(Confidence.LOW, finding.confidence)
        assertEquals(4, finding.evidence.first().sessions)
        assertEquals(5, finding.series.size)
        val highCutoff = inputs(sessions, rows.map { if (it.uid == 10002) it.copy(powerMah = 100.0) else it })
        assertTrue(AppDrainAnomaly.detect(context(highCutoff)).isEmpty())
    }

    @Test fun latestEligibleWindowDoesNotResurfaceAnOlderSpike() {
        val input = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        val last = session(5)
        val quietNow = input.copy(sessions = input.sessions + last, appSessions = input.appSessions + row(last.id), nowMs = last.endMs + HOUR)
        assertTrue(AppDrainAnomaly.detect(context(quietNow)).isEmpty())
    }

    @Test fun findingListIsRankedCappedAndDeterministic() {
        val input = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        val many = input.copy(appSessions = (0..19).flatMap { app ->
            input.appSessions.map { it.copy(uid = UID + app, packageName = "example.app$app") }
        })
        val findings = appFindings(many)
        assertEquals(12, findings.size)
        assertEquals(findings, findings.sortedWith(compareByDescending<com.akane.voltwise.battery.insights.model.Finding> { it.score }.thenBy { it.key }))
        assertEquals(findings.size, findings.map { it.key }.distinct().size)
        assertFalse(findings.any { it.subject == Subject.Device })
    }

    @Test fun medianScaleFloorSuppressesSubDoublingChangesAndRespectsFeedback() {
        for (history in listOf(listOf(100.0, 100.0, 100.0, 100.0), listOf(99.0, 100.0, 100.0, 101.0))) {
            val template = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
            fun findings(power: Double, multiplier: Double = 1.0) = AppDrainAnomaly.detect(context(template.copy(
                appSessions = template.appSessions.mapIndexed { index, row ->
                    row.copy(powerMah = history.getOrNull(index) ?: power)
                },
                feedback = mapOf("APP_DRAIN_ANOMALY:$APP" to multiplier),
            )))
            assertTrue("A near-flat baseline must not amplify a sub-doubling rise", findings(199.9).isEmpty())
            val doubled = findings(200.0).single()
            assertEquals(com.akane.voltwise.battery.insights.model.Severity.MEDIUM, doubled.severity)
            assertEquals(30.0, doubled.score, 1e-9)
            doubled.series.forEach { point ->
                assertEquals(0.0, point.baselineLow!!, 0.0)
                assertEquals(200.0, point.baselineHigh!!, 0.0)
            }
            assertTrue("Feedback also scales the median-relative requirement", findings(249.9, 1.5).isEmpty())
            assertEquals(1, findings(250.0, 1.5).size)
            assertEquals(com.akane.voltwise.battery.insights.model.Severity.HIGH, findings(300.0).single().severity)
        }
    }

    @Test fun madBandStillDominatesWhenWiderThanMedian() {
        val template = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        val history = listOf(0.0, 100.0, 200.0, 300.0)
        val finding = AppDrainAnomaly.detect(context(template.copy(
            appSessions = template.appSessions.mapIndexed { index, row ->
                row.copy(powerMah = history.getOrNull(index) ?: 700.0)
            },
        ))).single()
        assertEquals(200.0, finding.evidence.single().baseline!!, 0.0)
        finding.series.forEach { point ->
            assertEquals(0.0, point.baselineLow!!, 0.0)
            assertEquals(200.0 + 3.0 * 1.4826 * 100.0, point.baselineHigh!!, 1e-9)
        }
    }

    @Test fun badFeedbackCannotWeakenOrPoisonThresholds() {
        val input = detectorInputs(FindingType.APP_DRAIN_ANOMALY)
        val normal = appFindings(input)
        for (multiplier in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.5)) {
            assertEquals(normal, appFindings(input.copy(feedback = mapOf("APP_DRAIN_ANOMALY:$APP" to multiplier))))
        }
    }
}
