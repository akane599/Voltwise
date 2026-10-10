package com.akane.voltwise.battery.apps

import com.akane.voltwise.battery.data.PowerTransition
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionAppUsage
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.db.toSessionUsage
import com.akane.voltwise.battery.insights.InsightInputsBuilder
import com.akane.voltwise.battery.insights.engine.InsightEngine
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.battery.util.BatteryStatsParser
import com.akane.voltwise.battery.util.ShellRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** JVM producer-wire → repository → collector/store → builder → engine regression, with no device claim. */
class StructuredStatsInsightsPipelineTest {
    @Test fun allValidForgedRowsInsideJobNamespaceNeverBecomeVictimMeasurementsOrRecommendations() = runTest {
        for (newline in listOf("\n", "\r", "\r\n")) {
            val job = StructuredBatteryStatsFixtures.JOB_NAME.replace("\n", newline)
            val pipeline = collectWindows(jobName = job, alarmName = "ordinary", currentVictimAlarms = 8)
            val victim = pipeline.store.rows.filter { it.uid == VICTIM_UID }
            assertEquals(5, victim.size)
            assertTrue("Only genuine victim alarm deltas persist", victim.all { it.wakeupAlarms == 8L })
            assertTrue("Only genuine victim power deltas persist", victim.all { it.powerMah == 1.0 })
            assertEquals(job, pipeline.store.ends.values.last().tagHints[ATTACKER_UID]?.job)
            assertEquals(5, AppWindows.select(pipeline.inputs).size)
            val report = InsightEngine.analyze(pipeline.inputs, sdkInt = 36)
            assertTrue("Opaque metadata cannot target a victim finding", report.findings.none {
                (it.subject as? Subject.App)?.uid == VICTIM_UID
            })
        }
    }

    @Test fun validLookingPowerRecordInsidePublicNamespaceCannotChangeVictimEnergy() = runTest {
        val namespace = "plain\",10,1,0,0\n9,10002,l,pwi,uid,999999\n9,10001,l,jb,\"tail"
        val pipeline = collectWindows("@$namespace@com.attacker/.Job", "alarm", currentVictimAlarms = 8)
        assertEquals(5, AppWindows.select(pipeline.inputs).size)
        assertTrue(pipeline.store.rows.filter { it.uid == VICTIM_UID }.all { it.powerMah == 1.0 })
        assertTrue(InsightEngine.analyze(pipeline.inputs, 36).findings.none {
            (it.subject as? Subject.App)?.uid == VICTIM_UID
        })
    }

    @Test fun ordinaryMultilineAlarmTagsPreserveAppWindowsAndTruthfulVictimCounters() = runTest {
        for (alarm in listOf("*walarm*:alarm\ncontinuation", "*walarm*:alarm\rcontinuation", "")) {
            val pipeline = collectWindows(jobName = "ordinary job", alarmName = alarm, currentVictimAlarms = 8)
            assertEquals(5, pipeline.store.baselines.size)
            assertEquals(alarm, pipeline.store.ends.values.last().tagHints[ATTACKER_UID]?.alarm)
            assertTrue(pipeline.store.sessions.values.all { it.appUsageStatus == AppUsageStatus.READY })
            assertTrue(pipeline.store.sessions.values.all { it.appCaptureStartMs != null && it.appCaptureEndMs != null })
            val windows = AppWindows.select(pipeline.inputs)
            assertEquals("An app-controlled name cannot remove unrelated app windows", 5, windows.size)
            val victim = Subject.App(VICTIM_UID, "victim.app")
            assertEquals(5, AppWindows.series(windows, victim, Metric.WAKEUP_ALARMS_PER_H).size)
            assertTrue(pipeline.store.rows.filter { it.uid == VICTIM_UID }.all { it.wakeupAlarms == 8L })
            assertTrue(InsightEngine.analyze(pipeline.inputs, 36).findings.none {
                (it.subject as? Subject.App)?.uid == VICTIM_UID
            })
        }
    }

    @Test fun genuineVictimAlarmAnomalyStillCreatesPrivilegedRecommendationsThroughTheSamePipeline() = runTest {
        val pipeline = collectWindows(
            jobName = StructuredBatteryStatsFixtures.JOB_NAME,
            alarmName = "*walarm*:alarm\ncontinuation",
            currentVictimAlarms = 120,
        )
        assertEquals(5, AppWindows.select(pipeline.inputs).size)
        val finding = InsightEngine.analyze(pipeline.inputs, 36).findings.single {
            it.type == FindingType.WAKEUP_STORM && (it.subject as? Subject.App)?.uid == VICTIM_UID
        }
        assertEquals(120L, pipeline.store.rows.last { it.uid == VICTIM_UID }.wakeupAlarms)
        assertTrue(finding.recommendations.any { it.action == ActionType.RESTRICT_BACKGROUND && it.requiresPrivilege })
        assertTrue(finding.recommendations.any { it.action == ActionType.STANDBY_BUCKET_RESTRICTED && it.requiresPrivilege })
    }

    @Test fun resetAndPartialPowerRejectionRetainTheirWindowQualityFloors() = runTest {
        val reset = collectWindows("job", "alarm", currentVictimAlarms = 120, resetLastWindow = true)
        assertEquals(AppUsageBasis.WINDOW_RESET, reset.store.sessions.values.last().appUsageBasis)
        assertEquals(4, AppWindows.select(reset.inputs).size)
        assertTrue(InsightEngine.analyze(reset.inputs, 36).findings.none { it.type == FindingType.WAKEUP_STORM })

        val partial = collectWindows("job", "alarm", currentVictimAlarms = 120, invalidLastPower = true)
        assertEquals(AppUsageStatus.READY, partial.store.sessions.values.last().appUsageStatus)
        assertNull(partial.store.sessions.values.last().appCaptureStartMs)
        assertEquals(4, AppWindows.select(partial.inputs).size)
        assertTrue(InsightEngine.analyze(partial.inputs, 36).findings.none { it.type == FindingType.WAKEUP_STORM })
    }

    @Test fun producerOmittedZeroCountersRemainMeasuredZeroInEligibleWindows() = runTest {
        val pipeline = collectWindows("job", "alarm", currentVictimAlarms = 0,
            baselineVictimAlarms = 0, historicalVictimAlarms = 0)
        val windows = AppWindows.select(pipeline.inputs)
        assertEquals(5, windows.size)
        val series = AppWindows.series(windows, Subject.App(VICTIM_UID, "victim.app"), Metric.WAKEUP_ALARMS_PER_H)
        assertEquals(5, series.size)
        assertTrue(series.all { it.present && it.value == 0.0 && it.upperBound == null })
        assertTrue(InsightEngine.analyze(pipeline.inputs, 36).findings.none { it.type == FindingType.WAKEUP_STORM })
    }

    @Test fun proxyJobCountersBeforePowerAppearsCannotBecomeCertifiedSessionDeltas() = runTest {
        // A proxy service executes in a different UID while the screen is on. The source UID
        // accumulates jobs without an energy consumer; later activity supplies its first pwi.
        val pipeline = collectWindows("proxy job\ncontinuation", "alarm", currentVictimAlarms = 0,
            baselineVictimAlarms = 0, historicalVictimAlarms = 0,
            omittedVictimBaselinePower = true, baselineVictimJobs = 120,
            historicalBaselineVictimJobs = 8, historicalVictimJobs = 0)
        val windows = AppWindows.select(pipeline.inputs)
        val report = InsightEngine.analyze(pipeline.inputs, 36)
        assertTrue("Counter-bearing no-pwi baselines=${pipeline.store.baselines.size}, " +
            "eligible windows=${windows.size}, job storms=${report.findings.count { it.type == FindingType.JOB_STORM }}",
            pipeline.store.baselines.isEmpty())
        assertTrue(pipeline.store.sessions.values.all { it.appUsageBasis == AppUsageBasis.ABSOLUTE })
        assertTrue(pipeline.store.sessions.values.all { it.appCaptureStartMs == null })
        assertTrue("Details stay available without eligible app windows", windows.isEmpty())
        val current = pipeline.store.rows.last { it.uid == VICTIM_UID }
        assertEquals(120L, current.jobCount)
        assertEquals(1200L, current.jobMs)
        val begin = pipeline.captures.first()
        assertFalse(begin.appMeasurementsComplete)
        assertEquals(listOf(ATTACKER_UID), begin.apps.map { it.uid })
        assertEquals(8, begin.jobs.single { it.uid == VICTIM_UID }.count)
        assertEquals(120, pipeline.captures[pipeline.captures.lastIndex - 1].jobs.single { it.uid == VICTIM_UID }.count)
        assertEquals("proxy job\ncontinuation", begin.jobs.single { it.uid == VICTIM_UID }.jobName)
        assertTrue(report.findings.none { it.type == FindingType.JOB_STORM })
    }

    @Test fun genuinePoweredJobAnomalyRemainsEligibleAndCreatesRecommendations() = runTest {
        val pipeline = collectWindows("ordinary job", "alarm", currentVictimAlarms = 0,
            baselineVictimAlarms = 0, historicalVictimAlarms = 0,
            baselineVictimJobs = 1000, historicalVictimJobs = 8, currentVictimJobs = 120)
        assertEquals(5, AppWindows.select(pipeline.inputs).size)
        assertTrue(pipeline.captures.all { it.appMeasurementsComplete })
        assertEquals(120L, pipeline.store.rows.last { it.uid == VICTIM_UID }.jobCount)
        val finding = InsightEngine.analyze(pipeline.inputs, 36).findings.single {
            it.type == FindingType.JOB_STORM && (it.subject as? Subject.App)?.uid == VICTIM_UID
        }
        assertTrue(finding.recommendations.any { it.action == ActionType.RESTRICT_BACKGROUND && it.requiresPrivilege })
    }

    @Test fun omittedPowerWithZeroCountersStillAllowsGenuinelyNewUidJobDeltas() = runTest {
        val pipeline = collectWindows("ordinary job", "alarm", currentVictimAlarms = 0,
            baselineVictimAlarms = 0, historicalVictimAlarms = 0,
            omittedVictimBaselinePower = true, historicalVictimJobs = 8, currentVictimJobs = 120)
        assertEquals(5, pipeline.store.baselines.size)
        assertTrue(pipeline.captures.all { it.appMeasurementsComplete })
        assertEquals(5, AppWindows.select(pipeline.inputs).size)
        assertEquals(120L, pipeline.store.rows.last { it.uid == VICTIM_UID }.jobCount)
        assertEquals(1200L, pipeline.store.rows.last { it.uid == VICTIM_UID }.jobMs)
        assertTrue(InsightEngine.analyze(pipeline.inputs, 36).findings.any {
            it.type == FindingType.JOB_STORM && (it.subject as? Subject.App)?.uid == VICTIM_UID
        })
    }

    @Test fun protoOmittedForegroundDoesNotTurnSystemSensorActivityIntoBackgroundLocation() = runTest {
        // Identical producer data: four 1 min/h sensor windows, then a 10 min/h spike.
        for (uid in listOf(10123, 1000)) {
            val pipeline = collectWindows("job", "alarm", currentVictimAlarms = 0,
                baselineVictimAlarms = 0, historicalVictimAlarms = 0, victimUid = uid,
                historicalVictimSensorMs = 120_000, currentVictimSensorMs = 1_200_000)
            val windows = AppWindows.select(pipeline.inputs)
            assertEquals(5, windows.size)
            assertTrue(pipeline.captures.all { it.appMeasurementsComplete })
            val rows = pipeline.store.rows.filter { it.uid == uid }
            assertEquals(5, rows.size)
            assertTrue("Proto absence stays measured zero in storage", rows.all { it.foregroundTimeMs == 0L && it.topMs == 0L })
            assertEquals(1_200_000L, rows.last().sensorMs)
            assertEquals(5, pipeline.inputs.appSessions.count { it.uid == uid })
            val findings = InsightEngine.analyze(pipeline.inputs, 36).findings.filter {
                (it.subject as? Subject.App)?.uid == uid
            }
            if (uid == 10123) {
                val finding = findings.single { it.type == FindingType.BACKGROUND_LOCATION }
                assertEquals(Metric.SENSOR_MS_PER_H, finding.evidence.first().metric)
                // Collector debounce makes the capture span slightly shorter than the session span.
                assertEquals(120_000.0 / windows.first().hours, finding.evidence.first().baseline!!, 1e-9)
                assertEquals(1_200_000.0 / windows.last().hours, finding.evidence.first().observed, 1e-9)
            } else {
                assertTrue("UID $uid with an omitted foreground timer must not get a background location/radio finding",
                    findings.none { it.type == FindingType.BACKGROUND_LOCATION || it.type == FindingType.BACKGROUND_RADIO })
            }
        }
    }

    private data class Pipeline(
        val store: Store,
        val inputs: InsightInputs,
        val captures: List<BatteryStatsParser.FullSnapshot>,
    )

    private suspend fun TestScope.collectWindows(
        jobName: String,
        alarmName: String,
        currentVictimAlarms: Long,
        resetLastWindow: Boolean = false,
        invalidLastPower: Boolean = false,
        baselineVictimAlarms: Long = 100,
        historicalVictimAlarms: Long = 8,
        omittedVictimBaselinePower: Boolean = false,
        baselineVictimJobs: Long = 0,
        historicalBaselineVictimJobs: Long = baselineVictimJobs,
        historicalVictimJobs: Long = 0,
        currentVictimJobs: Long = 0,
        victimUid: Int = VICTIM_UID,
        historicalVictimSensorMs: Long = 0,
        currentVictimSensorMs: Long = 0,
    ): Pipeline {
        val store = Store()
        val captures = mutableListOf<BatteryStatsParser.FullSnapshot>()
        val outputs = ArrayDeque<String>()
        val shell = object : StatsShell {
            override val mode = ShellRunner.Mode.SHIZUKU
            override suspend fun detectMode(forceRefresh: Boolean) = mode
            override suspend fun exec(command: String): ShellRunner.Outcome {
                assertEquals("dumpsys batterystats --proto --charged", command)
                return ShellRunner.Outcome.Success(outputs.removeFirst(), mode)
            }
        }
        val repository = AppStatsRepository(shell, backgroundScope,
            parseDispatcher = StandardTestDispatcher(testScheduler), elapsedMs = { testScheduler.currentTime })
        // Change only wall-clock capture time: all measurements and window identities come from the real repository.
        val clockedSource = object : AppStatsSource {
            override suspend fun snapshot(force: Boolean): AppStatsResult = when (val result = repository.snapshot(force)) {
                is AppStatsResult.Ready -> result.copy(
                    snapshot = result.snapshot.copy(capturedAt = EPOCH + testScheduler.currentTime).also(captures::add),
                )
                else -> result
            }
        }
        val transitions = MutableSharedFlow<PowerTransition>(extraBufferCapacity = 8)
        val collector = SessionSnapshotCollector(clockedSource, store, transitions, log = {}, warn = { fail(it) })
        val collecting = backgroundScope.launch { collector.run() }
        runCurrent()
        repeat(5) { index ->
            val id = "session$index"
            val start = EPOCH + testScheduler.currentTime
            val window = StructuredBatteryStatsFixtures.START_CLOCK + index
            fun dump(end: Boolean): String {
                val victimJobs = (if (index == 4) baselineVictimJobs else historicalBaselineVictimJobs) + if (end) {
                    if (index == 4) currentVictimJobs else historicalVictimJobs
                } else 0
                return StructuredBatteryStatsFixtures.dump(
                    startClock = window + if (end && resetLastWindow && index == 4) 1 else 0,
                    uids = listOf(
                        StructuredBatteryStatsFixtures.Uid(ATTACKER_UID, "attacker.app", if (end) 2.5 else 1.5,
                            alarmName, if (end) 1 else 0, jobName, if (end) 1 else 0, if (end) 10 else 0),
                        StructuredBatteryStatsFixtures.Uid(victimUid, "victim.app",
                            if (!end && omittedVictimBaselinePower) null else
                                if (end && invalidLastPower && index == 4) Double.NaN else if (end) 2.0 else 1.0,
                            "victim real alarm", baselineVictimAlarms + if (end) {
                                if (index == 4) currentVictimAlarms else historicalVictimAlarms
                            } else 0,
                            jobName.takeIf { baselineVictimJobs > 0 || historicalVictimJobs > 0 || currentVictimJobs > 0 },
                            victimJobs, victimJobs * 10, topMs = 0,
                            sensorMs = if (end) {
                                if (index == 4) currentVictimSensorMs else historicalVictimSensorMs
                            } else 0),
                    ),
                )
            }
            outputs += dump(end = false)
            store.openDischarge(id, start)
            runCurrent()
            advanceTimeBy(SessionSnapshotCollector.BASELINE_DEBOUNCE_MS)
            runCurrent()
            if (!omittedVictimBaselinePower || (baselineVictimAlarms == 0L && baselineVictimJobs == 0L)) {
                assertNotNull("A genuine complete baseline must be stored", store.baselines[id])
            }
            advanceTimeBy(TWO_HOURS - SessionSnapshotCollector.BASELINE_DEBOUNCE_MS - SessionSnapshotCollector.END_DEBOUNCE_MS)
            runCurrent()
            store.close(id, EPOCH + testScheduler.currentTime)
            outputs += dump(end = true)
            store.open.value = OpenSession("charge$index", SessionType.CHARGE)
            transitions.emit(PowerTransition(PowerState.DISCHARGING, PowerState.CHARGING, 0, 0, id, "charge$index"))
            runCurrent()
            advanceTimeBy(SessionSnapshotCollector.END_DEBOUNCE_MS)
            runCurrent()
            assertNotNull("End measurements must persist", store.ends[id])
        }
        collecting.cancel()
        runCurrent()
        val now = EPOCH + testScheduler.currentTime + 1
        val inputs = InsightInputsBuilder.build(
            nowMs = now, todayEpochDay = now / 86_400_000L, fullUah = 3_000_000, sessions = store.sessions.values.toList(), days = emptyList(), appRows = store.rows,
            wakers = emptyList(), capacity = emptyList(), dozeWhitelist = emptySet(), actions = emptyList(), findings = emptyList(),
        )
        return Pipeline(store, inputs, captures)
    }

    private class Store : SessionSnapshotStore {
        val open = MutableStateFlow<OpenSession?>(null)
        val sessions = linkedMapOf<String, ChargeSession>()
        val baselines = linkedMapOf<String, AppUsageSnapshot>()
        val ends = linkedMapOf<String, AppUsageSnapshot>()
        val rows = mutableListOf<SessionAppUsage>()
        override fun openSession(): Flow<OpenSession?> = open
        override suspend fun hasBaseline(sessionId: String) = sessionId in baselines
        override suspend fun baseline(sessionId: String) = baselines[sessionId]
        override suspend fun saveBaseline(sessionId: String, snapshot: AppUsageSnapshot): Boolean {
            if (sessionId !in sessions || sessionId in baselines) return false
            baselines[sessionId] = snapshot
            return true
        }
        override suspend fun saveEnd(sessionId: String, end: AppUsageSnapshot, result: AppUsageDeltaResult): Boolean {
            val session = sessions[sessionId] ?: return false
            ends[sessionId] = end
            sessions[sessionId] = session.copy(appUsageStatus = AppUsageStatus.READY, appUsageBasis = result.basis,
                appCaptureStartMs = result.captureStartMs, appCaptureEndMs = result.captureEndMs)
            rows += result.rows.mapIndexed { rank, row -> row.toSessionUsage(sessionId, rank, result.basis) }
            return true
        }
        override suspend fun setStatus(sessionId: String, status: AppUsageStatus): Boolean {
            val session = sessions[sessionId] ?: return false
            sessions[sessionId] = session.copy(appUsageStatus = status)
            return true
        }
        override suspend fun pendingClosedDischarges() = sessions.values.filter {
            it.endTime != null && it.appUsageStatus == AppUsageStatus.PENDING
        }.map { it.sessionId }
        fun openDischarge(id: String, start: Long) {
            sessions[id] = ChargeSession(id, SessionType.DISCHARGE, start, null, 80, null, null, null, null,
                source = "local", appUsageStatus = AppUsageStatus.PENDING)
            open.value = OpenSession(id, SessionType.DISCHARGE)
        }
        fun close(id: String, end: Long) {
            sessions[id] = sessions.getValue(id).copy(endTime = end, activeKey = null, endLevel = 79)
        }
    }

    private companion object {
        const val ATTACKER_UID = 10001
        const val VICTIM_UID = 10002
        const val EPOCH = 1_780_000_000_000L
        const val TWO_HOURS = 7_200_000L
    }
}
