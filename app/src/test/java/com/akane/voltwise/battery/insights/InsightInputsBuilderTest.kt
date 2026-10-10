package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.*
import org.junit.Assert.*
import org.junit.Test

internal const val NOW = 100L * 24 * 60 * 60 * 1_000
internal const val HOUR = 60L * 60 * 1_000
internal fun testSession(id: String = "local") = ChargeSession(
    id, SessionType.DISCHARGE, NOW - 2 * HOUR, NOW - HOUR, 80, 75, 100_000, null, null,
    observationId = "observation", source = "local", observedMs = HOUR, screenOffMs = HOUR,
    appUsageStatus = AppUsageStatus.READY, appUsageBasis = AppUsageBasis.DELTA,
    appCaptureStartMs = NOW - 2 * HOUR, appCaptureEndMs = NOW - HOUR,
)
internal fun testAppRow(id: String = "local", rank: Int = 0, others: Boolean = false) = SessionAppUsage(
    id, rank, 10_001 + rank, "example.app$rank", 10.0, basis = AppUsageBasis.DELTA, isOthers = others,
    jobCount = null, fgServiceMs = 300, topWakelockTag = "hint",
)

class InsightInputsBuilderTest {
    private fun build(
        sessions: List<ChargeSession> = listOf(testSession()),
        rows: List<SessionAppUsage> = emptyList(),
        wakers: List<SessionDeviceWaker> = emptyList(),
        days: List<DailySummary> = emptyList(),
        capacity: List<CapacityEstimateRow> = emptyList(),
        actions: List<InsightActionEntity> = emptyList(),
        findings: List<InsightFindingEntity> = listOf(FindingCodec.encode(testFinding(), 1, feedbackMultiplier = 1.5)),
    ) = InsightInputsBuilder.build(NOW, 100, 4_000_000, sessions, days, rows, wakers, capacity,
        setOf("example.app0"), actions, findings)

    @Test fun workProfileCopyCannotFeedMainProfileAppFindings() {
        val primary = testAppRow().copy(uid = 10_123, packageName = "example.app")
        val workProfile = primary.copy(uid = 1_010_123, rank = 1)
        val others = testAppRow(rank = 2, others = true).copy(uid = -1)
        val inputs = build(rows = listOf(primary, workProfile, others))

        assertEquals(listOf(10_123), inputs.appSessions.filterNot { it.isOthers }.map { it.uid })
        assertEquals("example.app", inputs.appSessions.first().packageName)
        assertEquals(others.powerMah, inputs.appSessions.single { it.isOthers }.powerMah, 0.0)
        assertEquals(-1, inputs.appSessions.single { it.isOthers }.uid)
    }

    @Test fun lonePrimaryProfileAppStillFeedsInputs() {
        val primary = testAppRow().copy(uid = 10_123, packageName = "example.app")
        val inputs = build(rows = listOf(primary))

        assertEquals(10_123, inputs.appSessions.single().uid)
        assertEquals("example.app", inputs.appSessions.single().packageName)
    }

    @Test fun workProfileFilteringDoesNotCreateSpareStoredWakerSlots() {
        val missing = Subject.App(20_000, "example.missing")
        for (wakerCount in listOf(9, 10)) {
            val rows = (0 until 30 + wakerCount).map { rank ->
                testAppRow(rank = rank).copy(
                    uid = if (rank == 30) 1_010_123 else 10_001 + rank,
                    wakeupAlarms = 30L,
                    partialWakelockBgMs = 60_000L,
                )
            } + testAppRow(rank = 30 + wakerCount, others = true).copy(
                uid = -1, wakeupAlarms = 300L, partialWakelockBgMs = 600_000L,
            )
            val inputs = build(rows = rows)
            assertFalse(inputs.appSessions.any { it.uid == 1_010_123 })
            assertEquals(wakerCount, inputs.sessions.single().appWindow!!.wakersStored)
            val window = AppWindows.select(inputs).single()
            for (metric in listOf(Metric.WAKEUP_ALARMS_PER_H, Metric.PARTIAL_WAKELOCK_BG_SHARE)) {
                val point = AppWindows.point(window, missing, metric)!!
                if (wakerCount == 10) {
                    assertNull(point.value)
                    assertTrue(point.censored)
                    val bound = if (metric == Metric.WAKEUP_ALARMS_PER_H) 30.0 else 600_000.0 / HOUR
                    assertEquals(bound, point.upperBound!!, 0.0)
                } else {
                    assertEquals(0.0, point.value!!, 0.0)
                    assertFalse(point.censored)
                }
                assertFalse(point.present)
            }
        }
    }

    @Test fun everyJournalStatusMapsOrIsIntentionallyExcludedFromActionEffects() {
        val expected = mapOf(
            InsightActionStatus.APPLIED to ActionStatus.APPLIED,
            InsightActionStatus.REVERTED to ActionStatus.REVERTED,
            InsightActionStatus.ONE_SHOT to ActionStatus.ONE_SHOT,
        )
        // These lifecycle states do not prove an applied action, even with an appliedAt stamp.
        val excluded = setOf(InsightActionStatus.PREPARED, InsightActionStatus.FAILED, InsightActionStatus.UNKNOWN)
        assertEquals(InsightActionStatus.entries.toSet(), expected.keys + excluded)
        assertTrue(expected.keys.intersect(excluded).isEmpty())
        for (status in InsightActionStatus.entries) {
            val row = InsightActionEntity(
                findingKey = "action", type = ActionType.RESTRICT_BACKGROUND.name, userId = 0,
                status = status, priorStateVersion = 1, createdAt = 1, appliedAt = 2,
            )
            val mapped = build(actions = listOf(row)).actions
            assertEquals("journal status $status", expected[status]?.let(::listOf).orEmpty(), mapped.map { it.status })
            assertTrue("missing application time must exclude $status", build(actions = listOf(row.copy(appliedAt = null))).actions.isEmpty())
        }
    }

    @Test fun appliedActionKeepsMetricWhenStoredFindingChangesLeadEvidence() {
        val finding = testFinding("JOB_STORM:example.app0").copy(
            type = FindingType.JOB_STORM,
            subject = Subject.App(10_001, "example.app0"),
            evidence = listOf(Evidence(Metric.SYNCS_PER_H, 60.0, 1.0, MetricUnit.COUNT_PER_H, 5)),
        )
        val action = InsightActionEntity(
            findingKey = finding.key, type = ActionType.RESTRICT_BACKGROUND.name,
            packageName = "example.app0", uid = 10_001, userId = 0,
            status = InsightActionStatus.APPLIED, priorStateVersion = 1,
            createdAt = 1, appliedAt = 2, metric = Metric.JOBS_PER_H.name,
        )
        val stored = FindingCodec.encode(finding, 1)
        assertEquals(Metric.JOBS_PER_H, build(actions = listOf(action), findings = listOf(stored)).actions.single().metric)
        assertEquals(Metric.JOBS_PER_H, build(actions = listOf(action), findings = emptyList()).actions.single().metric)
        assertNull(build(actions = listOf(action.copy(metric = "FUTURE_METRIC")), findings = listOf(stored)).actions.single().metric)
    }

    @Test fun appliedActionMetricComesFromMatchingStoredFindingsLeadEvidence() {
        for ((type, metric) in listOf(
            FindingType.JOB_STORM to Metric.SYNCS_PER_H,
            FindingType.BACKGROUND_LOCATION to Metric.SENSOR_MS_PER_H,
            FindingType.BACKGROUND_RUNAWAY to Metric.FGS_MS_PER_H,
        )) {
            val finding = testFinding("$type:example.app0").copy(
                type = type,
                subject = Subject.App(10_001, "example.app0"),
                evidence = listOf(
                    Evidence(metric, 60.0, 1.0, metric.unit, 5),
                    Evidence(Metric.CPU_MS_PER_H, 300_000.0, null, MetricUnit.MS_PER_H, 2),
                ),
            )
            val action = InsightActionEntity(1, finding.key, ActionType.RESTRICT_BACKGROUND.name,
                packageName = "example.app0", uid = 10_001, userId = 0,
                status = InsightActionStatus.APPLIED, priorStateVersion = 1, createdAt = 1, appliedAt = 2)
            val stored = FindingCodec.encode(finding, 1, feedbackMultiplier = 1.5)
            val input = build(actions = listOf(action), findings = listOf(FindingCodec.encode(testFinding(), 1), stored))
            assertEquals(metric, input.actions.single().metric)
            assertEquals(finding.key, input.actions.single().findingKey)
            assertEquals(1.5, input.feedback[finding.key]!!, 0.0)

            for (records in listOf(
                emptyList(),
                listOf(stored.copy(key = "unrelated:example.app0")),
                listOf(stored.copy(evidenceJson = "{broken")),
                listOf(stored.copy(evidenceVersion = 99)),
                listOf(FindingCodec.encode(finding.copy(evidence = emptyList()), 1)),
            )) {
                assertNull(build(actions = listOf(action), findings = records).actions.single().metric)
            }
        }
    }


    @Test fun readyWindowCountsOnlyNonOthersAndUsesOthersForFullRowSet() {
        val rows = (0..38).map { testAppRow(rank = it) } + testAppRow(rank = 39, others = true)
        val full = build(rows = rows).sessions.single().appWindow!!
        assertTrue(full.fullRowSet)
        val complete = build(rows = rows.filterNot { it.isOthers } + testAppRow(rank = 39))
            .sessions.single().appWindow!!
        assertFalse(complete.fullRowSet)
        assertEquals(WindowBasis.DELTA, full.basis)
        assertEquals(HOUR, full.captureEndMs - full.captureStartMs)
        for (status in AppUsageStatus.entries.filter { it != AppUsageStatus.READY }) {
            assertNull(build(sessions = listOf(testSession().copy(appUsageStatus = status)), rows = rows).sessions.single().appWindow)
        }
        assertNull(build(sessions = listOf(testSession().copy(appCaptureEndMs = null))).sessions.single().appWindow)
    }

    @Test fun thirtyPowerRowsWithOthersCensorMissingAppInsteadOfMeasuringZero() {
        val rows = (0 until 30).map { testAppRow(rank = it).copy(powerMah = 40.0 - it) }
        val inputs = build(
            sessions = listOf(testSession(), testSession("complete")),
            rows = rows + testAppRow(rank = 30, others = true) +
                rows.map { it.copy(sessionId = "complete") },
        )
        val truncated = inputs.sessions.first { it.id == "local" }.appWindow!!
        assertTrue(truncated.fullRowSet)
        assertFalse(inputs.sessions.first { it.id == "complete" }.appWindow!!.fullRowSet)

        val missing = Subject.App(20_000, "example.missing")
        val windows = AppWindows.select(inputs)
        val cutoff = AppWindows.point(windows.first { it.session.id == "local" }, missing, Metric.POWER_MAH_PER_H)!!
        assertNull(cutoff.value)
        assertEquals(11.0, cutoff.upperBound!!, 0.0)
        assertTrue(cutoff.censored)
        assertFalse(cutoff.present)
        val complete = AppWindows.point(windows.first { it.session.id == "complete" }, missing, Metric.POWER_MAH_PER_H)!!
        assertEquals(0.0, complete.value!!, 0.0)
        assertNull(complete.upperBound)
        assertFalse(complete.censored)
        assertFalse(complete.present)
    }

    @Test fun importsOpenUnknownAndOldSessionsCannotFeedAppAnalysis() {
        val imported = testSession("import:session").copy(source = "import:file")
        val legacy = testSession("legacy").copy(observationId = null)
        val inputs = build(sessions = listOf(testSession(), imported, legacy,
            testSession("open").copy(endTime = null), testSession("unknown").copy(type = SessionType.UNKNOWN),
            testSession("old").copy(endTime = NOW - InsightInputsBuilder.HISTORY_MS - 1)))
        assertEquals(listOf("local", "import:session", "legacy"), inputs.sessions.map { it.id })
        assertTrue(inputs.sessions[1].imported)
        assertFalse(inputs.sessions[2].imported)
        assertEquals(2, AppWindows.select(inputs).size)
        val absolute = build(sessions = listOf(testSession().copy(appUsageBasis = AppUsageBasis.ABSOLUTE)))
        assertEquals(WindowBasis.ABSOLUTE, absolute.sessions.single().appWindow!!.basis)
        assertTrue(AppWindows.select(absolute).isEmpty())
    }

    @Test fun allNullableCountersAndDeviceMeasurementsRemainUnsupported() {
        val session = testSession().copy(screenOffCoveredMs = 500, screenOffDozeMs = 200, screenOffSuspendMs = 300)
        val inputs = build(sessions = listOf(session), rows = listOf(testAppRow(), testAppRow("open")),
            wakers = listOf(SessionDeviceWaker("local", "KERNEL_WAKELOCK", "waker", 3, 500, 0),
                SessionDeviceWaker("local", "FUTURE_KIND", "other", 1, 2, 1)),
            days = listOf(DailySummary(100, screenOffCoveredMs = 500, screenOffDozeMs = 200, screenOffSuspendMs = 300)))
        assertNull(inputs.appSessions.single().jobCount)
        assertNull(inputs.appSessions.single().gpsMs)
        assertEquals(300L, inputs.appSessions.single().fgServiceMs)
        assertEquals("hint", inputs.appSessions.single().topWakelockTag)
        assertNull(inputs.sessions.single().dozeMs)
        assertEquals(200L, inputs.sessions.single().screenOffDozeMs)
        assertEquals(300L, inputs.days.single().screenOffSuspendMs)
        assertEquals(1, inputs.deviceWakers.size)
        assertEquals(1.5, inputs.feedback[testFinding().key]!!, 0.0)
    }

    @Test fun capacityExcludesSourceMarkedImportsButKeepsLocalEstimate() {
        val local = CapacityEstimateRow("local", SessionType.CHARGE, NOW - HOUR, NOW, null, 20, 80,
            4_400, "HIGH", "COUNTER_SPAN", source = "local")
        val imported = local.copy(sessionId = "old-phone", endTime = NOW - 26 * 24 * HOUR,
            capacityEstimateMah = 4_600, source = "import:x")

        assertEquals(listOf(CapacityPointInput(NOW, 4_400.0, 3)), build(capacity = listOf(imported, local)).capacity)
    }

    @Test fun capacityExcludesIdMarkedImportsButKeepsLocalEstimate() {
        val local = CapacityEstimateRow("local", SessionType.CHARGE, NOW - HOUR, NOW, null, 20, 80,
            4_400, "HIGH", "COUNTER_SPAN", source = "local")
        val imported = local.copy(sessionId = "import:old-phone", capacityEstimateMah = 4_600)

        assertEquals(listOf(CapacityPointInput(NOW, 4_400.0, 3)), build(capacity = listOf(imported, local)).capacity)
    }

    @Test fun capacityDoesNotFallBackToImportsWhenThereAreNoLocalEstimates() {
        val imported = CapacityEstimateRow("old-phone", SessionType.CHARGE, NOW - HOUR, NOW, null, 20, 80,
            4_600, "HIGH", "COUNTER_SPAN", source = "import:x")

        assertTrue(build(capacity = listOf(imported, imported.copy(sessionId = "import:old-phone", source = "legacy"))).capacity.isEmpty())
    }

    @Test fun localAndLegacyCapacityEstimatesKeepTheirValuesConfidenceAndOrder() {
        val local = CapacityEstimateRow("local", SessionType.CHARGE, NOW - HOUR, NOW, null, 20, 80,
            4_400, "HIGH", "COUNTER_SPAN", source = "local")
        val legacy = local.copy(sessionId = "legacy", endTime = NOW - HOUR, capacityEstimateMah = 4_500,
            capacityConfidence = "MEDIUM", source = "legacy")

        assertEquals(listOf(CapacityPointInput(NOW, 4_400.0, 3), CapacityPointInput(NOW - HOUR, 4_500.0, 2)),
            build(capacity = listOf(local, legacy)).capacity)
    }

    @Test fun capacityAndActionsUseOnlySupportedClosedAppliedRecords() {
        val point = CapacityEstimateRow("local", SessionType.CHARGE, NOW - HOUR, NOW, null, 20, 80, 4_000, "HIGH", "COUNTER_SPAN")
        val action = InsightActionEntity(1, testFinding().key, ActionType.FORCE_STOP.name, userId = 0,
            status = InsightActionStatus.APPLIED, priorStateVersion = 1, createdAt = 1, appliedAt = 2)
        val inputs = build(capacity = listOf(point, point.copy(capacityConfidence = "FUTURE"), point.copy(endTime = null)),
            actions = InsightActionStatus.entries.map { action.copy(id = it.ordinal.toLong(), status = it) } +
                action.copy(type = "FUTURE") + action.copy(appliedAt = null))
        assertEquals(listOf(CapacityPointInput(NOW, 4_000.0, 3)), inputs.capacity)
        assertEquals(setOf(ActionStatus.APPLIED, ActionStatus.REVERTED, ActionStatus.ONE_SHOT), inputs.actions.map { it.status }.toSet())
    }
}
