package com.akane.voltwise.battery.apps

import com.akane.voltwise.battery.data.db.AppSnapshotKind
import com.akane.voltwise.battery.data.db.SessionAppUsage
import com.akane.voltwise.battery.data.db.toSnapshotUid
import com.akane.voltwise.battery.data.db.toRow
import com.akane.voltwise.battery.util.BatteryStatsParser as Parser
import org.junit.Assert.*
import org.junit.Test

class SnapshotExtensionTest {
    private fun row(uid: Int = 1, counter: Long? = null, power: Double = 1.0) = AppUsageRow(
        uid, "pkg$uid", power, cpuTimeMs = counter,
        wakeupAlarms = counter,
        partialWakelockCount = counter,
        partialWakelockBgMs = counter,
        jobCount = counter,
        jobMs = counter,
        syncCount = counter,
        fgServiceMs = counter,
        topMs = counter,
        mobileActiveMs = counter,
        gpsMs = counter,
        sensorMs = counter,
    )
    private fun snapshot(rows: List<AppUsageRow>, captured: Long = 100) = AppUsageSnapshot(1, 1, captured, rows)

    @Test fun parserFieldsAndNullsRoundTripThroughSnapshotUid() {
        val app = Parser.AppPowerStats(1, "Shared UID 1", 2.0, packages = listOf("com.b", "com.a"),
            wakeupAlarmCount = 1, partialWakelockCount = 2, partialWakelockBgTimeMs = 3,
            jobCount = 4, jobTimeMs = 5, syncCount = 6, foregroundServiceTimeMs = 7,
            topTimeMs = 8, gpsTimeMs = 10, sensorTimeMs = 11)
        val full = Parser.FullSnapshot(apps = listOf(app, Parser.AppPowerStats(2, "uid2", 1.0)),
            network = listOf(Parser.NetworkStats(1, "com.a", mobileRxBytes = 0, mobileTxBytes = 0,
                wifiRxBytes = 0, wifiTxBytes = 0, mobileActiveTimeMs = 9)))
        val rows = full.toAppUsageSnapshot().rows
        val mapped = rows.first()
        assertEquals("com.a", mapped.packageName)
        assertEquals(listOf(1L,2L,3L,4L,5L,6L,7L,8L,9L,10L,11L), counters(mapped))
        assertEquals(mapped, mapped.toSnapshotUid(42).toRow())
        assertTrue(counters(rows.last()).all { it == null })
    }

    private fun counters(row: AppUsageRow) = listOf(
        row.wakeupAlarms,
        row.partialWakelockCount,
        row.partialWakelockBgMs,
        row.jobCount,
        row.jobMs,
        row.syncCount,
        row.fgServiceMs,
        row.topMs,
        row.mobileActiveMs,
        row.gpsMs,
        row.sensorMs,
    )

    @Test fun countersStartingOnExistingUidUseZeroBaselineAndRemainWakerCandidates() {
        val base = snapshot(listOf(row().copy(cpuTimeMs = 10), row(9).copy(wakeupAlarms = 5)))
        val end = snapshot(listOf(row(counter = 200), row(9, power = 10.0).copy(wakeupAlarms = 5)), 200)

        val result = AppUsageDelta.compute(base, end, topN = 1)

        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertEquals(listOf(9, 1), result.rows.map { it.uid })
        val started = result.rows.single { it.uid == 1 }
        assertTrue("All starting extended counters must subtract zero", counters(started).all { it == 200L })
        assertEquals(190L, started.cpuTimeMs)
    }

    @Test fun counterAbsentFromBothSupportedDumpsIsZero() {
        val base = snapshot(listOf(row().copy(cpuTimeMs = 10), row(9).copy(wakeupAlarms = 5, jobCount = 2)))
        val end = snapshot(listOf(row().copy(cpuTimeMs = 20), row(9).copy(wakeupAlarms = 7, jobCount = 3)), 200)

        val result = AppUsageDelta.compute(base, end)

        assertEquals(AppUsageBasis.DELTA, result.basis)
        val idle = result.rows.single { it.uid == 1 }
        assertEquals("Observed jobs absent from both dumps must be zero", 0L, idle.jobCount)
        assertEquals("Observed alarms absent from both dumps must be zero", 0L, idle.wakeupAlarms)
        assertEquals(10L, idle.cpuTimeMs)
    }

    @Test fun countersObservedOnlyAtEndMakeSparseAbsenceZero() {
        val base = snapshot(listOf(row().copy(cpuTimeMs = 10), row(9).copy(wakeupAlarms = 0)))
        val end = snapshot(listOf(row().copy(cpuTimeMs = 20), row(9, counter = 6)), 200)

        val result = AppUsageDelta.compute(base, end)

        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertTrue("Every observed extended counter must record sparse zero",
            counters(result.rows.single { it.uid == 1 }).all { it == 0L })
    }

    @Test fun counterUnobservedInBothDumpsStaysUnknown() {
        val base = snapshot(listOf(row().copy(cpuTimeMs = 10), row(9).copy(wakeupAlarms = 5)))
        val end = snapshot(listOf(row().copy(cpuTimeMs = 20), row(9).copy(wakeupAlarms = 7)), 200)

        val result = AppUsageDelta.compute(base, end)

        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertTrue("Support for alarms must not imply support for unobserved counters",
            result.rows.all { counters(it).drop(1).all { value -> value == null } })
    }

    @Test fun everyExtendedCounterCanEstablishBaselineSupportEvenWhenZero() {
        val supportRows = listOf(
            row(9).copy(wakeupAlarms = 0),
            row(9).copy(partialWakelockCount = 0),
            row(9).copy(partialWakelockBgMs = 0),
            row(9).copy(jobCount = 0),
            row(9).copy(jobMs = 0),
            row(9).copy(syncCount = 0),
            row(9).copy(fgServiceMs = 0),
            row(9).copy(topMs = 0),
            row(9).copy(mobileActiveMs = 0),
            row(9).copy(gpsMs = 0),
            row(9).copy(sensorMs = 0),
        )
        for ((index, support) in supportRows.withIndex()) {
            val result = AppUsageDelta.compute(snapshot(listOf(row(), support)), snapshot(listOf(row(counter = 6))))
            assertTrue("Baseline support from counter $index", counters(result.rows.single()).all { it == 6L })
            val sparse = AppUsageDelta.compute(
                snapshot(listOf(row(), support)), snapshot(listOf(row(power = 2.0))),
            )
            assertEquals("Only counter $index observed in the baseline is known zero",
                counters(support).map { if (it == null) null else 0L }, counters(sparse.rows.single()))
        }
    }

    @Test fun preUpgradeBaselineKeepsExtendedDeltasUnknownForExistingAndNewUids() {
        val base = snapshot(listOf(row().copy(cpuTimeMs = 10), row(9).copy(foregroundTimeMs = 5)))
        val end = snapshot(listOf(row(counter = 200), row(2, counter = 7)), 200)

        val result = AppUsageDelta.compute(base, end)

        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertEquals(setOf(1, 2), result.rows.map { it.uid }.toSet())
        assertTrue(result.rows.all { counters(it).all { value -> value == null } })
        assertEquals(190L, result.rows.single { it.uid == 1 }.cpuTimeMs)
    }

    @Test fun newCountersDifferenceAndUnsupportedEndpointsStayUnknown() {
        val base = snapshot(listOf(row(counter = 10), row(2), row(3, counter = 5)))
        val end = snapshot(listOf(row(counter = 15), row(2, counter = 6), row(3, power = 2.0), row(4, counter = 7)), 200)
        val result = AppUsageDelta.compute(base, end)
        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertTrue(counters(result.rows.single { it.uid == 1 }).all { it == 5L })
        assertTrue(counters(result.rows.single { it.uid == 2 }).all { it == 6L })
        assertTrue(counters(result.rows.single { it.uid == 3 }).all { it == null })
        assertTrue(counters(result.rows.single { it.uid == 4 }).all { it == 7L })
        assertEquals(100L, result.captureStartMs)
        assertEquals(200L, result.captureEndMs)
    }

    @Test fun negativeAlarmCountersAreUnknownWhileExistingCpuClamps() {
        val baseRow = row(counter = 10)
        val endRow = row(counter = 20, power = 2.0).copy(cpuTimeMs = 5,
            wakeupAlarms = 5,
        )
        val result = AppUsageDelta.compute(snapshot(listOf(baseRow)), snapshot(listOf(endRow)))
        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertEquals(0L, result.rows.single().cpuTimeMs)
        assertNull(result.rows.single().wakeupAlarms)
    }

    @Test fun negativePartialWakelockCountersAreUnknownWhileExistingCpuClamps() {
        val baseRow = row(counter = 10)
        val endRow = row(counter = 20, power = 2.0).copy(cpuTimeMs = 5,
            partialWakelockCount = 5,
            partialWakelockBgMs = 5,
        )
        val result = AppUsageDelta.compute(snapshot(listOf(baseRow)), snapshot(listOf(endRow)))
        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertEquals(0L, result.rows.single().cpuTimeMs)
        assertNull(result.rows.single().partialWakelockCount)
        assertNull(result.rows.single().partialWakelockBgMs)
    }

    @Test fun negativeJobCountersAreUnknownWhileExistingCpuClamps() {
        val baseRow = row(counter = 10)
        val endRow = row(counter = 20, power = 2.0).copy(cpuTimeMs = 5,
            jobCount = 5,
            jobMs = 5,
        )
        val result = AppUsageDelta.compute(snapshot(listOf(baseRow)), snapshot(listOf(endRow)))
        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertEquals(0L, result.rows.single().cpuTimeMs)
        assertNull(result.rows.single().jobCount)
        assertNull(result.rows.single().jobMs)
    }

    @Test fun negativeSyncCountersAreUnknownWhileExistingCpuClamps() {
        val baseRow = row(counter = 10)
        val endRow = row(counter = 20, power = 2.0).copy(cpuTimeMs = 5,
            syncCount = 5,
        )
        val result = AppUsageDelta.compute(snapshot(listOf(baseRow)), snapshot(listOf(endRow)))
        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertEquals(0L, result.rows.single().cpuTimeMs)
        assertNull(result.rows.single().syncCount)
    }

    @Test fun negativeProcessStateCountersAreUnknownWhileExistingCpuClamps() {
        val baseRow = row(counter = 10)
        val endRow = row(counter = 20, power = 2.0).copy(cpuTimeMs = 5,
            fgServiceMs = 5,
            topMs = 5,
        )
        val result = AppUsageDelta.compute(snapshot(listOf(baseRow)), snapshot(listOf(endRow)))
        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertEquals(0L, result.rows.single().cpuTimeMs)
        assertNull(result.rows.single().fgServiceMs)
        assertNull(result.rows.single().topMs)
    }

    @Test fun negativeRadioCountersAreUnknownWhileExistingCpuClamps() {
        val baseRow = row(counter = 10)
        val endRow = row(counter = 20, power = 2.0).copy(cpuTimeMs = 5,
            mobileActiveMs = 5,
        )
        val result = AppUsageDelta.compute(snapshot(listOf(baseRow)), snapshot(listOf(endRow)))
        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertEquals(0L, result.rows.single().cpuTimeMs)
        assertNull(result.rows.single().mobileActiveMs)
    }

    @Test fun negativeLocationSensorCountersAreUnknownWhileExistingCpuClamps() {
        val baseRow = row(counter = 10)
        val endRow = row(counter = 20, power = 2.0).copy(cpuTimeMs = 5,
            gpsMs = 5,
            sensorMs = 5,
        )
        val result = AppUsageDelta.compute(snapshot(listOf(baseRow)), snapshot(listOf(endRow)))
        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertEquals(0L, result.rows.single().cpuTimeMs)
        assertNull(result.rows.single().gpsMs)
        assertNull(result.rows.single().sensorMs)
    }

    @Test fun absoluteAndResetRetainNewEndCounters() {
        val end = snapshot(listOf(row(counter = 7)), 200)
        val absolute = AppUsageDelta.compute(null, end)
        assertNull(absolute.captureStartMs)
        assertEquals(200L, absolute.captureEndMs)
        assertTrue(counters(absolute.rows.single()).all { it == 7L })
        val reset = AppUsageDelta.compute(snapshot(listOf(row(counter = 100))).copy(windowStartedAt = 2), end)
        assertEquals(AppUsageBasis.WINDOW_RESET, reset.basis)
        assertTrue(counters(reset.rows.single()).all { it == 7L })
    }

    @Test fun wakerCandidatesAreRankedAfterDeltaAndCapAtFortyOneWithoutDoubleCounting() {
        val leaders = (1..30).map { row(it, power = 100.0) }
        val candidates = (31..45).map { row(it, power = 1.0).copy(wakeupAlarms = it.toLong(), partialWakelockBgMs = 0) }
        val bgOnly = row(46, power = 1.0).copy(wakeupAlarms = 0, partialWakelockBgMs = 999)
        val base = snapshot(leaders.map { it.copy(powerMah = 99.0) } + candidates.map {
            it.copy(powerMah = 1.0, wakeupAlarms = if (it.uid == 31) 0 else it.wakeupAlarms!! - 1)
        } + bgOnly.copy(wakeupAlarms = 0, partialWakelockBgMs = 0))
        val result = AppUsageDelta.compute(base, snapshot(leaders + candidates + bgOnly))
        assertEquals(SessionAppUsage.MAX_ROWS, result.rows.size)
        assertEquals((1..30).toList(), result.rows.take(30).map { it.uid })
        assertEquals(listOf(31) + (32..40).toList(), result.rows.drop(30).dropLast(1).map { it.uid })
        assertTrue(result.rows.last().isOthers)
        assertEquals(5L, result.rows.last().wakeupAlarms)
        assertEquals(999L, result.rows.last().partialWakelockBgMs)
        assertEquals(30.0, result.rows.sumOf { it.powerMah }, 0.0)
    }

    @Test fun backgroundOnlyWakerAndAlarmTiesUseBackgroundDuration() {
        val rows = listOf(row(1, power = 10.0), row(2, power = 0.0).copy(wakeupAlarms = 2, partialWakelockBgMs = 1),
            row(3, power = 0.0).copy(wakeupAlarms = 2, partialWakelockBgMs = 9),
            row(4, power = 0.0).copy(partialWakelockBgMs = 99))
        assertEquals(listOf(1,3,2,4), AppUsageDelta.compute(null, snapshot(rows), topN = 1).rows.map { it.uid })
    }

    @Test fun tagHintsComeFromEndUidListsAndNeverFromBaselineOrOthers() {
        val uid = 10050
        val full = Parser.FullSnapshot(startedAt = 1, startCount = 1,
            apps = listOf(Parser.AppPowerStats(uid, "Shared UID", 2.0, listOf("com.b", "com.a"))),
            wakelocks = listOf(Parser.WakelockStats(uid, "com.b", tag = "partial", type = Parser.WakelockType.PARTIAL, count = 1, totalTimeMs = 7),
                Parser.WakelockStats(uid, "com.a", tag = "full", type = Parser.WakelockType.FULL, count = 1, totalTimeMs = 99)),
            alarms = listOf(Parser.AlarmStats(uid, "com.b", tag = "alarm", count = 1, wakeups = 3, totalTimeMs = null)),
            jobs = listOf(Parser.JobStats(uid, "com.a", jobName = "job", count = 1, totalTimeMs = 9)))
        val end = full.toAppUsageSnapshot()
        assertNull(end.rows.single().topWakelockTag)
        val base = end.copy(rows = end.rows.map { it.copy(powerMah = 1.0) }, tagHints = mapOf(uid to AppTagHints("old", "old", "old")))
        val selected = AppUsageDelta.compute(base, end).rows.single()
        assertEquals("com.a", selected.packageName)
        assertEquals("partial", selected.topWakelockTag)
        assertEquals("alarm", selected.topAlarmTag)
        assertEquals("job", selected.topJobName)
        val others = AppUsageDelta.compute(base, end, topN = 0).rows.single()
        assertTrue(others.isOthers)
        assertNull(others.topWakelockTag)
        assertNull(others.topAlarmTag)
        assertNull(others.topJobName)
    }

    @Test fun idleBaselineWakerSurvivesSnapshotCapAndAppearsInSessionDelta() {
        val idle = (1..239).map { Parser.KernelWakelockStats("idle$it", 0, 0) } +
            Parser.KernelWakelockStats("zz_idle", 0, 0)
        val active = (1..10).map { Parser.KernelWakelockStats("active$it", 1, 1_000) }
        val baseline = Parser.FullSnapshot(startedAt = 1, startCount = 1,
            kernelWakelocks = idle + active).toAppUsageSnapshot()
        val end = Parser.FullSnapshot(startedAt = 1, startCount = 1,
            kernelWakelocks = idle.dropLast(1) + active +
                Parser.KernelWakelockStats("zz_idle", 900, 7_200_000)).toAppUsageSnapshot()

        val delta = AppUsageDelta.compute(baseline, end).deviceWakers
        assertTrue("Idle baseline waker must survive into the session delta",
            DeviceWaker("KERNEL_WAKELOCK", "zz_idle", 900, 7_200_000) in delta)
        assertEquals(true, baseline.wakersComplete)
        assertEquals(10, baseline.deviceWakers.size)
    }

    @Test fun lentSnapshotCapacityKeepsIdleBaselineWakersInSessionDeltaForEitherKind() {
        for ((kernelCount, reasonCount) in listOf(160 to 10, 20 to 120)) {
            val full = Parser.FullSnapshot(startedAt = 1, startCount = 1,
                kernelWakelocks = (1..kernelCount).map { Parser.KernelWakelockStats("k$it", 1, 1) } +
                    Parser.KernelWakelockStats("idleKernel", 0, 0),
                wakeupReasons = (1..reasonCount).map { Parser.WakeupReasonStats("r$it", 1, 1) } +
                    Parser.WakeupReasonStats("idleReason", 0, 0))
            val baseline = full.toAppUsageSnapshot()
            val end = full.copy(
                kernelWakelocks = full.kernelWakelocks.dropLast(1) +
                    Parser.KernelWakelockStats("idleKernel", 900, 7_200_000),
                wakeupReasons = full.wakeupReasons.dropLast(1) +
                    Parser.WakeupReasonStats("idleReason", 900, 1),
            ).toAppUsageSnapshot()

            assertEquals(kernelCount + reasonCount, baseline.deviceWakers.size)
            assertEquals(true, baseline.wakersComplete)
            assertEquals(true, baseline.header("s", AppSnapshotKind.BASELINE).wakersComplete)
            val delta = AppUsageDelta.compute(baseline, end).deviceWakers
            assertTrue(DeviceWaker("KERNEL_WAKELOCK", "idleKernel", 900, 7_200_000) in delta)
            assertTrue(DeviceWaker("WAKEUP_REASON", "idleReason", 900, 1) in delta)
        }
    }

    @Test fun countRankedReasonSurvivesMoreThanTwoHundredActiveKernels() {
        val kernels = (1..250).map { Parser.KernelWakelockStats("k$it", 1, 10_000L + it) }
        val full = Parser.FullSnapshot(startedAt = 1, startCount = 1,
            kernelWakelocks = kernels,
            wakeupReasons = listOf(Parser.WakeupReasonStats("reason", 1, 1)))
        val baseline = full.toAppUsageSnapshot()
        val end = full.copy(wakeupReasons = listOf(
            Parser.WakeupReasonStats("reason", 3_001, 1_501),
        )).toAppUsageSnapshot()

        assertTrue("Count-ranked reason must survive into the session delta",
            DeviceWaker("WAKEUP_REASON", "reason", 3_000, 1_500) in
                AppUsageDelta.compute(baseline, end).deviceWakers)
        assertEquals(false, baseline.wakersComplete)
    }

    @Test fun nonzeroWakersWithinBothKindCapsRemainComplete() {
        val full = Parser.FullSnapshot(
            kernelWakelocks = (1..150).map { Parser.KernelWakelockStats("k$it", it, it.toLong()) },
            wakeupReasons = (1..50).map { Parser.WakeupReasonStats("r$it", it, it.toLong()) },
        )
        val snapshot = full.toAppUsageSnapshot()

        assertEquals(200, snapshot.deviceWakers.size)
        assertEquals((1..150).map { "k$it" }.toSet() + (1..50).map { "r$it" },
            snapshot.deviceWakers.map { it.name }.toSet())
        assertEquals(true, snapshot.wakersComplete)
        assertEquals(true, snapshot.header("s", AppSnapshotKind.BASELINE).wakersComplete)
    }

    @Test fun deviceHeaderAndCapacityLendingWakersAreMapped() {
        val full = Parser.FullSnapshot(screenOffTimeMs = 123,
            doze = Parser.DozeStats(1, 2, 3, 4, 5, 6),
            kernelWakelocks = (1..200).map { Parser.KernelWakelockStats("k$it", it, it.toLong()) },
            wakeupReasons = listOf(Parser.WakeupReasonStats("reason", 2, 999)))
        val snapshot = full.toAppUsageSnapshot()
        val header = snapshot.header("s", AppSnapshotKind.END)
        assertEquals(3L, header.deepIdleMs)
        assertEquals(4L, header.deepIdleCount)
        assertEquals(5L, header.lightIdleMs)
        assertEquals(6L, header.lightIdleCount)
        assertEquals(123L, header.screenOffMs)
        assertEquals(false, header.wakersComplete)
        assertEquals(200, snapshot.deviceWakers.size)
        assertEquals(DeviceWaker("KERNEL_WAKELOCK", "k200", 200, 200), snapshot.deviceWakers.first())
        assertEquals(DeviceWaker("WAKEUP_REASON", "reason", 2, 999), snapshot.deviceWakers.last())
        assertFalse(snapshot.deviceWakers.any { it.name == "k1" })
        assertEquals(true, full.copy(wakeupReasons = emptyList()).toAppUsageSnapshot().wakersComplete)
        assertEquals(true, full.copy(kernelWakelocks = full.kernelWakelocks.take(150)).toAppUsageSnapshot().wakersComplete)
        val unsupported = Parser.FullSnapshot().toAppUsageSnapshot().header("s", AppSnapshotKind.BASELINE)
        assertNull(unsupported.deepIdleMs)
        assertNull(unsupported.screenOffMs)
    }

    @Test fun deviceWakersMatchKindAndNameWithCompleteAndIncompleteBaseline() {
        val base = snapshot(emptyList()).copy(deviceWakers = listOf(DeviceWaker("KERNEL_WAKELOCK", "same", 4, 40)), wakersComplete = true)
        val end = snapshot(emptyList()).copy(deviceWakers = listOf(DeviceWaker("KERNEL_WAKELOCK", "same", 7, 70),
            DeviceWaker("WAKEUP_REASON", "same", 2, 20), DeviceWaker("KERNEL_WAKELOCK", "new", 5, 50)))
        val complete = AppUsageDelta.compute(base, end).deviceWakers
        assertEquals(listOf("new", "same", "same"), complete.map { it.name })
        assertEquals(listOf(50L,30L,20L), complete.map { it.totalMs })
        assertEquals(listOf(5L,3L,2L), complete.map { it.count })
        for (known in listOf(false, null)) {
            val incomplete = AppUsageDelta.compute(base.copy(wakersComplete = known), end).deviceWakers
            assertEquals(listOf(DeviceWaker("KERNEL_WAKELOCK", "same", 3, 30)), incomplete)
        }
        assertTrue(AppUsageDelta.compute(null, end).deviceWakers.isEmpty())
        assertTrue(AppUsageDelta.compute(base.copy(windowStartedAt = 2), end).deviceWakers.isEmpty())
    }

    @Test fun deviceWakerRankingUsesDeltaAndKeepsOnlyTen() {
        val base = snapshot(emptyList()).copy(wakersComplete = true,
            deviceWakers = listOf(DeviceWaker("KERNEL_WAKELOCK", "old", 9, 999)))
        val end = snapshot(emptyList()).copy(deviceWakers = (1..12).map { DeviceWaker("KERNEL_WAKELOCK", "k$it", 1, it.toLong()) } +
            DeviceWaker("KERNEL_WAKELOCK", "old", 1, 990))
        val result = AppUsageDelta.compute(base, end).deviceWakers
        assertEquals(10, result.size)
        assertEquals((12 downTo 3).map { "k$it" }, result.map { it.name })
    }

    @Test fun duplicateUidCollapseIncludesEveryNewCounter() {
        val merged = listOf(row(counter = 3), row(counter = 7), row(2)).collapseByUid()
        assertTrue(counters(merged.first()).all { it == 10L })
        assertTrue(counters(merged.last()).all { it == null })
    }
}
