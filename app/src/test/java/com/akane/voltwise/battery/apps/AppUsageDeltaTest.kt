package com.akane.voltwise.battery.apps

import com.akane.voltwise.battery.util.BatteryStatsParser
import org.junit.Assert.*
import org.junit.Test

class AppUsageDeltaTest {
    private fun row(
        uid: Int,
        power: Double = 0.0,
        cpu: Long? = null,
        fg: Long? = null,
        bg: Long? = null,
        wakelock: Long? = null,
        mobile: Long? = null,
        wifi: Long? = null,
    ) = AppUsageRow(uid, "pkg$uid", power, cpu, fg, bg, wakelock, mobile, wifi)

    private fun snapshot(startedAt: Long?, startCount: Long?, vararg rows: AppUsageRow) =
        AppUsageSnapshot(startedAt, startCount, capturedAt = 0L, rows = rows.toList())

    @Test fun noBaselineIsAbsoluteAndUsesEndValues() {
        val end = snapshot(100L, 1L, row(1, power = 5.0, cpu = 100))
        val result = AppUsageDelta.compute(baseline = null, end = end)
        assertEquals(AppUsageBasis.ABSOLUTE, result.basis)
        assertEquals(listOf(row(1, power = 5.0, cpu = 100)), result.rows)
    }

    @Test fun sameWindowProducesClampedPerUidDelta() {
        val baseline = snapshot(100L, 1L, row(1, power = 2.0, cpu = 1000, mobile = 500))
        val end = snapshot(100L, 1L, row(1, power = 5.0, cpu = 1500, mobile = 400))
        val result = AppUsageDelta.compute(baseline, end)
        assertEquals(AppUsageBasis.DELTA, result.basis)
        val delta = result.rows.single()
        assertEquals(3.0, delta.powerMah, 0.0)
        assertEquals(500L, delta.cpuTimeMs)
        // Mobile bytes counter went down (rounding/reporting jitter); clamp to zero, never negative.
        assertEquals(0L, delta.mobileBytes)
    }

    @Test fun knownApplicationIdentityChangeCannotSubtractUnrelatedRisingCounters() {
        val baseline = snapshot(100L, 1L,
            row(10_123, power = 1.0, cpu = 100).copy(packageName = "com.example.a"),
        ).copy(capturedAt = 1_000L)
        val end = snapshot(100L, 1L,
            row(10_123, power = 3.0, cpu = 150).copy(packageName = "com.example.b"),
        ).copy(capturedAt = 2_000L)

        val result = AppUsageDelta.compute(baseline, end)

        assertEquals("Changed application identity must not be comparable DELTA evidence",
            AppUsageBasis.ABSOLUTE, result.basis)
        assertEquals("Retain end evidence instead of a fabricated cross-package difference", end.rows, result.rows)
        assertNull("Changed application identity must not certify an Insights capture", result.captureStartMs)
        assertEquals(2_000L, result.captureEndMs)
    }

    @Test fun stableApplicationAndSystemOrUnknownNamesKeepComparableDeltas() {
        val cases = listOf(
            Triple(10_123, "com.example.a", "com.example.a"),
            Triple(1000, "com.example.a", "com.example.b"),
            Triple(101_000, "com.example.a", "com.example.b"), // secondary user's system UID
            Triple(10_123, "", "com.example.b"),
            Triple(10_123, "com.example.a", " "),
            Triple(10_123, "UID 10123", "com.example.b"),
            Triple(10_123, "com.example.a", "Shared UID 10123"),
        )
        for ((uid, baseName, endName) in cases) {
            val baseline = snapshot(100L, 1L,
                row(uid, power = 1.0, cpu = 100).copy(packageName = baseName),
            ).copy(capturedAt = 1_000L)
            val end = snapshot(100L, 1L,
                row(uid, power = 3.0, cpu = 150).copy(packageName = endName),
            ).copy(capturedAt = 2_000L)

            val result = AppUsageDelta.compute(baseline, end)

            assertEquals("$uid: $baseName -> $endName", AppUsageBasis.DELTA, result.basis)
            assertEquals(2.0, result.rows.single().powerMah, 0.0)
            assertEquals(50L, result.rows.single().cpuTimeMs)
            assertEquals(endName, result.rows.single().packageName)
            assertEquals(1_000L, result.captureStartMs)
        }
    }

    private fun sharedSnapshot(packages: List<String>, power: Double, cpu: Long, capturedAt: Long) =
        BatteryStatsParser.FullSnapshot(
            startedAt = 100L, startCount = 1L, capturedAt = capturedAt,
            apps = listOf(BatteryStatsParser.AppPowerStats(
                uid = 10_123, packageName = "Shared UID 10123", powerMah = power,
                packages = packages, cpuTimeMs = cpu,
            )),
        ).toAppUsageSnapshot()

    @Test fun sharedUidPackageOrderAndNonRepresentativeMembershipChangeKeepComparableDelta() {
        val baseline = sharedSnapshot(listOf("com.example.b", "com.example.a"), 1.0, 100, 1_000)
        for (members in listOf(
            listOf("com.example.a", "com.example.b"),
            listOf("com.example.c", "com.example.a"),
        )) {
            val end = sharedSnapshot(members, 3.0, 150, 2_000)
            val result = AppUsageDelta.compute(baseline, end)

            assertEquals(AppUsageBasis.DELTA, result.basis)
            assertEquals("com.example.a", result.rows.single().packageName)
            assertEquals(2.0, result.rows.single().powerMah, 0.0)
            assertEquals(50L, result.rows.single().cpuTimeMs)
            assertEquals(1_000L, result.captureStartMs)
        }
    }

    @Test fun changedSharedUidRepresentativeConservativelyRefusesComparableDelta() {
        val baseline = sharedSnapshot(listOf("com.example.a", "com.example.b"), 1.0, 100, 1_000)
        val end = sharedSnapshot(listOf("com.example.b", "com.example.c"), 3.0, 150, 2_000)

        val result = AppUsageDelta.compute(baseline, end)

        assertEquals(AppUsageBasis.ABSOLUTE, result.basis)
        assertEquals(end.rows, result.rows)
        assertNull(result.captureStartMs)
    }

    @Test fun secondaryUserApplicationIdentityChangeAlsoRefusesComparableDelta() {
        val baseline = snapshot(100L, 1L,
            row(110_123, power = 1.0, cpu = 100).copy(packageName = "com.example.a"),
        )
        val end = snapshot(100L, 1L,
            row(110_123, power = 3.0, cpu = 150).copy(packageName = "com.example.b"),
        )

        val result = AppUsageDelta.compute(baseline, end)

        assertEquals(AppUsageBasis.ABSOLUTE, result.basis)
        assertEquals(end.rows, result.rows)
        assertNull(result.captureStartMs)
    }

    @Test fun uidMissingFromBaselineCountsFromZero() {
        val baseline = snapshot(100L, 1L, row(1, power = 1.0))
        val end = snapshot(100L, 1L, row(1, power = 1.0), row(2, power = 3.0, cpu = 200))
        val result = AppUsageDelta.compute(baseline, end)
        assertEquals(AppUsageBasis.DELTA, result.basis)
        val newApp = result.rows.single { it.uid == 2 }
        assertEquals(3.0, newApp.powerMah, 0.0)
        assertEquals(200L, newApp.cpuTimeMs)
    }

    @Test fun nullFieldsStayNullWhenBothSidesAreNull() {
        val baseline = snapshot(100L, 1L, row(1, power = 1.0, wakelock = null))
        val end = snapshot(100L, 1L, row(1, power = 2.0, wakelock = null))
        val result = AppUsageDelta.compute(baseline, end)
        assertNull(result.rows.single().wakelockTimeMs)
    }

    @Test fun oneSidedNullFieldIsTreatedAsZeroNotDropped() {
        val baseline = snapshot(100L, 1L, row(1, power = 1.0, wakelock = null))
        val end = snapshot(100L, 1L, row(1, power = 1.0, wakelock = 500))
        val result = AppUsageDelta.compute(baseline, end)
        assertEquals(500L, result.rows.single().wakelockTimeMs)
    }

    @Test fun windowMismatchIsAResetAndUsesEndValues() {
        val baseline = snapshot(100L, 1L, row(1, power = 9.0, cpu = 9000))
        val end = snapshot(200L, 1L, row(1, power = 1.0, cpu = 100))
        val result = AppUsageDelta.compute(baseline, end)
        assertEquals(AppUsageBasis.WINDOW_RESET, result.basis)
        assertEquals(1.0, result.rows.single().powerMah, 0.0)
        assertEquals(100L, result.rows.single().cpuTimeMs)
    }

    @Test fun sameWindowButDecreasedTotalIsAlsoAReset() {
        val baseline = snapshot(100L, 1L, row(1, power = 9.0))
        val end = snapshot(100L, 1L, row(1, power = 1.0))
        val result = AppUsageDelta.compute(baseline, end)
        assertEquals(AppUsageBasis.WINDOW_RESET, result.basis)
        assertEquals(1.0, result.rows.single().powerMah, 0.0)
    }

    @Test fun uninstalledAppCannotFakeAResetWhileOthersRise() {
        // uid 1 was heavy in the baseline but is gone from end (uninstalled between snapshots);
        // uid 2 genuinely rose. The naive whole-list total (9.0+1.0=10.0 -> 3.0) looks like a
        // decrease, but the total over uids common to both snapshots (uid 2 only: 1.0 -> 3.0) rose.
        val baseline = snapshot(100L, 1L, row(1, power = 9.0), row(2, power = 1.0))
        val end = snapshot(100L, 1L, row(2, power = 3.0))
        val result = AppUsageDelta.compute(baseline, end)
        assertEquals(AppUsageBasis.DELTA, result.basis)
        val delta = result.rows.single()
        assertEquals(2, delta.uid)
        assertEquals(2.0, delta.powerMah, 0.0)
    }

    @Test fun rowsWithNoUsageAreDropped() {
        val end = snapshot(100L, 1L, row(1, power = 0.0), row(2, power = 3.0))
        val result = AppUsageDelta.compute(null, end)
        assertEquals(listOf(2), result.rows.map { it.uid })
    }

    @Test fun topNKeepsHighestPowerAndFoldsRestIntoOthers() {
        val rows = (1..5).map { row(it, power = it.toDouble(), cpu = it * 10L) }
        val end = snapshot(100L, 1L, *rows.toTypedArray())
        val result = AppUsageDelta.compute(null, end, topN = 3)
        assertEquals(4, result.rows.size)
        assertEquals(listOf(5, 4, 3), result.rows.take(3).map { it.uid })
        val others = result.rows.last()
        assertTrue(others.isOthers)
        assertEquals(-1, others.uid)
        assertEquals("", others.packageName)
        assertEquals(3.0, others.powerMah, 0.0) // uid 2 (power 2) + uid 1 (power 1)
        assertEquals(30L, others.cpuTimeMs) // 20 + 10
    }

    @Test fun highCountWakeupReasonSurvivesLongKernelWakelockDeltas() {
        val kernels = (1..12).map { DeviceWaker("KERNEL_WAKELOCK", "kernel.$it", 5, 60_000L * it) }
        val reason = DeviceWaker("WAKEUP_REASON", "storm", 300, 1_000)
        val baseline = snapshot(100L, 1L).copy(wakersComplete = true)
        val end = snapshot(100L, 1L).copy(deviceWakers = kernels + reason)

        val result = AppUsageDelta.compute(baseline, end)

        assertEquals(AppUsageBasis.DELTA, result.basis)
        assertTrue("High-count wakeup reason must survive the session cap", reason in result.deviceWakers)
        assertEquals(10, result.deviceWakers.size)
        assertEquals(kernels.takeLast(9).reversed(), result.deviceWakers.filter { it.kind == "KERNEL_WAKELOCK" })
    }

    @Test fun deviceWakerQuotasRankSessionDeltasByKindAndBreakTiesByName() {
        val kernels = (1..12).map { DeviceWaker("KERNEL_WAKELOCK", "kernel.$it", 100L - it, 60_000L * it) }
        val reasons = (1..6).map { DeviceWaker("WAKEUP_REASON", "reason.$it", 100L * it, 60_000L * (7 - it)) } +
            DeviceWaker("WAKEUP_REASON", "reason.a", 600, 0)
        val deltas = kernels + reasons
        // Absolute totals rank oppositely: selection must happen after subtracting each baseline.
        val baseWakers = deltas.mapIndexed { index, waker ->
            waker.copy(count = 10_000L * (deltas.size - index), totalMs = 1_000_000L * (deltas.size - index))
        }
        val endWakers = deltas.zip(baseWakers) { delta, base ->
            delta.copy(count = base.count + delta.count, totalMs = base.totalMs + delta.totalMs)
        }
        val baseline = snapshot(100L, 1L).copy(deviceWakers = baseWakers)
        val end = snapshot(100L, 1L).copy(deviceWakers = endWakers)

        val result = AppUsageDelta.compute(baseline, end).deviceWakers

        assertEquals(kernels.takeLast(6).reversed() + listOf(reasons[5], reasons[6], reasons[4], reasons[3]), result)
        assertEquals(result, AppUsageDelta.compute(
            baseline.copy(deviceWakers = baseWakers.reversed()),
            end.copy(deviceWakers = endWakers.reversed()),
        ).deviceWakers)
    }

    @Test fun spareKernelSlotsAreFilledByCountRankedWakeupReasons() {
        val kernels = listOf(DeviceWaker("KERNEL_WAKELOCK", "kernel", 1, 60_000))
        val reasons = (1..12).map { DeviceWaker("WAKEUP_REASON", "reason.$it", it.toLong(), 0) }
        val baseline = snapshot(100L, 1L).copy(wakersComplete = true)
        val end = snapshot(100L, 1L).copy(deviceWakers = kernels + reasons)

        assertEquals(kernels + reasons.takeLast(9).reversed(), AppUsageDelta.compute(baseline, end).deviceWakers)
        assertEquals(reasons.takeLast(10).reversed(), AppUsageDelta.compute(
            baseline, end.copy(deviceWakers = reasons),
        ).deviceWakers)
    }

    @Test fun kernelTimeTiesUseNameRatherThanCountOrInputOrder() {
        val kernels = listOf(
            DeviceWaker("KERNEL_WAKELOCK", "z", 300, 60_000),
            DeviceWaker("KERNEL_WAKELOCK", "a", 1, 60_000),
        )
        val baseline = snapshot(100L, 1L).copy(wakersComplete = true)
        val end = snapshot(100L, 1L).copy(deviceWakers = kernels)

        assertEquals(kernels.reversed(), AppUsageDelta.compute(baseline, end).deviceWakers)
    }

    @Test fun othersRowIsOmittedWhenNothingOverflowsTopN() {
        val end = snapshot(100L, 1L, row(1, power = 1.0), row(2, power = 2.0))
        val result = AppUsageDelta.compute(null, end, topN = 5)
        assertTrue(result.rows.none { it.isOthers })
    }
}
