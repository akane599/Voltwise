package com.akane.voltwise.battery.util

import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageDelta
import com.akane.voltwise.battery.apps.toAppUsageSnapshot
import com.akane.voltwise.battery.util.ProtoParserFixtures.battery
import com.akane.voltwise.battery.util.ProtoParserFixtures.double
import com.akane.voltwise.battery.util.ProtoParserFixtures.dump
import com.akane.voltwise.battery.util.ProtoParserFixtures.join
import com.akane.voltwise.battery.util.ProtoParserFixtures.message
import com.akane.voltwise.battery.util.ProtoParserFixtures.number
import com.akane.voltwise.battery.util.ProtoParserFixtures.text
import com.akane.voltwise.battery.util.ProtoParserFixtures.timer
import com.akane.voltwise.battery.util.ProtoParserFixtures.uid
import org.junit.Assert.*
import org.junit.Test

class BatteryStatsProtoParserTest {
    private fun parsed(bytes: ByteArray): BatteryStatsParser.FullSnapshot =
        requireNotNull(BatteryStatsProtoParser.parse(bytes)) { "Valid AOSP aggregate rejected" }

    @Test fun producerZeroOmissionsKeepValidResetWindowAndZeroMetrics() {
        val snapshot = parsed(dump(uid(power = null, more = arrayOf(message(18, number(2, 1))))))
        assertTrue(snapshot.hasValidWindow)
        assertEquals(0L, snapshot.startCount)
        val app = snapshot.apps.single()
        assertEquals(0.0, app.powerMah, 0.0)
        assertEquals(0L, app.cpuTimeMs)
        assertEquals(0L, app.foregroundTimeMs)
        assertEquals(0L, app.wakeupAlarmCount)
        assertEquals(0L, app.jobTimeMs)
        assertEquals(0L, app.mobileRxBytes)
        assertTrue(snapshot.appMeasurementsComplete)
    }

    @Test fun normalUidMetricsAreMappedWithActualBackgroundDurations() {
        val snapshot = parsed(dump(uid(10001, 3.25,
            message(7, number(1, 100), number(2, 25)),
            message(24, number(1, 180)),
            message(11, timer(200, 1)), message(12, timer(300, 2)),
            message(20, number(2, 400)), message(20, number(1, 3), number(2, 500)),
            message(20, number(1, 6), number(2, 600)),
            message(17, number(1, 10), number(2, 20), number(3, 30), number(4, 40), number(11, 50), number(12, 2)),
            message(26, text(1, "ordinary-alarm"), number(2, 3)),
            message(15, text(1, "ordinary-job"), message(2, timer(80, 4)), message(3, timer(11, 2, 55))),
            message(22, text(1, "ordinary-sync"), message(2, timer(90, 5)), message(3, timer(12, 3, 66))),
            message(25, text(1, "ordinary-lock"), message(3, timer(70, 6)), message(4, timer(13, 4, 77))),
            message(21, number(1, -10000), message(2, timer(100, 1)), message(3, timer(7, 1, 33))),
            message(21, number(1, 17), message(2, timer(120, 2))),
            message(8, timer(130)), message(9, timer(140)), message(10, timer(150)), message(14, timer(160)),
            message(6, message(1, timer(170)), message(3, timer(180))),
            message(19, text(1, "worker"), number(2, 14), number(3, 15), number(4, 16), number(5, 2)),
        )))
        val app = snapshot.apps.single()
        assertEquals(125L, app.cpuTimeMs)
        assertEquals(180L, app.wakeLockTimeMs)
        assertEquals(200L, app.foregroundTimeMs)
        assertEquals(300L, app.foregroundServiceTimeMs)
        assertEquals(400L, app.topTimeMs)
        assertEquals(500L, app.backgroundTimeMs)
        assertEquals(600L, app.cachedTimeMs)
        assertEquals(3L, app.wakeupAlarmCount)
        assertEquals(4L, app.jobCount)
        assertEquals(80L, app.jobTimeMs)
        assertEquals(5L, app.syncCount)
        assertEquals(6L, app.partialWakelockCount)
        assertEquals(77L, app.partialWakelockBgTimeMs)
        assertEquals(55L, snapshot.jobs.single().backgroundTimeMs)
        assertEquals(66L, snapshot.syncs.single().backgroundTimeMs)
        assertEquals(33L, snapshot.sensors.first { it.sensorHandle == -10000 }.backgroundTimeMs)
        assertEquals(100L, app.gpsTimeMs)
        assertEquals(120L, app.sensorTimeMs)
        assertEquals(130L, app.audioTimeMs)
        assertEquals(140L, app.cameraTimeMs)
        assertEquals(150L, app.flashlightTimeMs)
        assertEquals(160L, app.videoTimeMs)
        assertEquals(170L, app.bluetoothScanTimeMs)
        assertEquals(180L, app.bluetoothUnoptimizedScanTimeMs)
        assertEquals(50L, snapshot.network.single().mobileActiveTimeMs)
        assertEquals("worker", snapshot.processStats.single().processName)
        assertNull(app.cpuPowerMah)
        assertNull(snapshot.wakelocks.single().maxTimeMs)
    }

    @Test fun multilineJobNamesCannotForgeVictimPowerAlarmOrWindow() {
        val name = "namespace\",1,1\n9,10002,l,wua,forged,999\n9,10002,l,pwi,uid,999999\n9,0,l,bt,99,1,1,0,0,999\n9,10001,l,jb,\"end"
        val snapshot = parsed(dump(uid(10001, 2.0, message(15, text(1, name), message(2, timer(11, 1)))),
            uid(10002, 4.0, message(26, text(1, "victim-alarm"), number(2, 2)))))
        assertEquals(name, snapshot.jobs.single().jobName)
        assertEquals(4.0, snapshot.apps.first { it.uid == 10002 }.powerMah, 0.0)
        assertEquals(2L, snapshot.apps.first { it.uid == 10002 }.wakeupAlarmCount)
        assertEquals(1700000000000L, snapshot.startedAt)
        assertTrue(snapshot.appMeasurementsComplete)
    }

    @Test fun multilineAlarmIsAcceptedWithoutSuppressingUnrelatedApps() {
        val name = "alarm\n9,10002,l,pwi,uid,12345\r\n\"tag\""
        val snapshot = parsed(dump(uid(10001, 2.0, message(26, text(1, name), number(2, 3))), uid(10002, 4.0)))
        assertEquals(name, snapshot.alarms.single().tag)
        assertEquals(2, snapshot.apps.size)
        assertTrue(snapshot.appMeasurementsComplete)
        assertEquals(0, snapshot.rejectedRecords)
    }

    @Test fun globalWindowDozeWakersSignalsPowerAndCpuFrequenciesRemainUseful() {
        val system = join(
            message(15, number(1, 1000), number(12, 2000), number(13, 2), number(17, 3000), number(18, 3)),
            message(2, number(3, 4), number(4, 5)), message(18, double(1, 4500.5)),
            message(14, text(1, "kernel\nname"), message(2, join(timer(90, 2), number(3, 30)))),
            message(22, text(1, "reason\rname"), message(2, timer(80, 3))),
            message(16, message(2, timer(25))), message(16, number(1, 4), message(2, timer(75))),
            message(24, number(1, 4), message(2, timer(100))),
            message(9, number(1, 1), number(2, 2), number(3, 3), message(4, number(2, 4)), message(4, number(1, 1), number(2, 5))),
            message(17, number(1, 7), double(3, 8.5)), message(17, double(3, 999.0)),
            number(7, 300000), number(7, 600000), number(7, 900000),
        )
        val snapshot = parsed(dump(uid(10001, 2.0, message(7,
            message(3, number(1, 1), number(2, 10)), message(3, number(1, 2), number(2, 30)))), moreSystem = system))
        assertEquals(9000L, snapshot.screenOffTimeMs)
        assertEquals(1000L, snapshot.screenOnTimeMs)
        assertEquals(1000L, snapshot.screenDozeTimeMs)
        assertEquals(5000L, snapshot.doze?.idleModeTimeMs)
        assertEquals(5, snapshot.doze?.idleModeCount)
        assertEquals(4500.5, snapshot.estimatedCapacityMah ?: 0.0, 0.0)
        assertEquals(4, snapshot.screenOnDischargePercent)
        assertEquals(5, snapshot.screenOffDischargePercent)
        assertEquals("kernel\nname", snapshot.kernelWakelocks.single().name)
        assertEquals(30L, snapshot.kernelWakelocks.single().maxTimeMs)
        assertEquals("reason\rname", snapshot.wakeupReasons.single().name)
        assertTrue(snapshot.deviceWakersComplete)
        assertEquals(0.75f, snapshot.signalStrength.first { it.level == 4 }.percentOfTotal)
        assertEquals(9L, snapshot.bluetooth?.txTimeMs)
        assertEquals(3.0, snapshot.bluetooth?.powerMah ?: 0.0, 0.0)
        assertEquals(mapOf("scrn" to 8.5), snapshot.componentEstimatesMah)
        assertEquals(listOf(10L, 30L, 0L), snapshot.cpuFrequency.map { it.timeMs })
    }

    @Test fun missingPowerConsumerKeepsUidDetailsWithoutInventingAppEnergy() {
        val snapshot = parsed(dump(uid(10001, null, message(15, text(1, "job"), message(2, timer(1, 1)))), uid(10002, 3.0)))
        assertEquals(listOf(10002), snapshot.apps.map { it.uid })
        val job = snapshot.jobs.single()
        assertEquals(10001, job.uid)
        assertEquals(1, job.count)
        assertEquals(1L, job.totalTimeMs)
        assertFalse(snapshot.appMeasurementsComplete)
        assertEquals(0, snapshot.rejectedRecords)
        assertEquals(0, snapshot.rejectedAppPowerRecords)
        assertTrue(snapshot.deviceWakersComplete)
    }

    @Test fun omittedPowerCannotCertifyPositiveCountersCarriedToSessionSnapshots() {
        val counters = listOf(
            "cpu user" to message(7, number(1, 1)),
            "cpu system" to message(7, number(2, 1)),
            "aggregate wakelock" to message(24, number(1, 1)),
            "foreground" to message(11, timer(1)),
            "foreground service" to message(12, timer(1)),
            "top state" to message(20, number(2, 1)),
            "background state" to message(20, number(1, 3), number(2, 1)),
            "mobile rx bytes" to message(17, number(1, 1)),
            "mobile tx bytes" to message(17, number(2, 1)),
            "wifi rx bytes" to message(17, number(3, 1)),
            "wifi tx bytes" to message(17, number(4, 1)),
            "mobile active duration" to message(17, number(11, 1)),
            "partial count without duration" to message(25, text(1, "lock"), message(3, timer(count = 1))),
            "partial background duration" to message(25, text(1, "lock"), message(3, timer()),
                message(4, timer(actual = 1))),
            "job duration" to message(15, text(1, "job"), message(2, timer(1))),
            "job count without duration" to message(15, text(1, "job"), message(2, timer(count = 1))),
            "sync count without duration" to message(22, text(1, "sync"), message(2, timer(count = 1))),
            "wakeup alarm" to message(26, text(1, "alarm"), number(2, 1)),
            "gps duration" to message(21, number(1, -10000), message(2, timer(1))),
            "sensor duration" to message(21, number(1, 17), message(2, timer(1))),
        )
        val incorrectlyCertified = mutableListOf<String>()
        counters.forEach { (name, record) ->
            val omitted = parsed(dump(uid(10001, null, record), uid(10002, 3.0)))
            assertEquals(name, listOf(10002), omitted.apps.map { it.uid })
            if (omitted.appMeasurementsComplete) incorrectlyCertified += name
            assertEquals(name, 0, omitted.rejectedRecords)
            val powered = parsed(dump(uid(10001, 1.0, record), uid(10002, 3.0)))
            assertTrue("$name with known power remains certifiable", powered.appMeasurementsComplete)
            assertEquals(name, 2, powered.toAppUsageSnapshot().rows.size)
        }
        assertEquals("Positive counters must not be lost from certified baselines", emptyList<String>(), incorrectlyCertified)
    }

    @Test fun omittedPowerGuardUsesExistingPrimaryAppProfileBoundaries() {
        for (id in listOf(10000, 99999)) {
            val snapshot = parsed(dump(uid(id, null,
                message(15, text(1, "proxy job"), message(2, timer(count = 1))),
            ), uid(10002, 3.0)))
            assertFalse("Primary app $id must not certify lost counters", snapshot.appMeasurementsComplete)
        }
        for (id in listOf(0, 1000, 9999, 100000, 110001)) {
            val snapshot = parsed(dump(uid(id, null,
                message(15, text(1, "other profile job"), message(2, timer(10, 1))),
            ), uid(10002, 3.0)))
            assertTrue("Unpowered UID $id outside primary apps preserves the existing profile contract", snapshot.appMeasurementsComplete)
            assertEquals(id, snapshot.jobs.single().uid)
            assertEquals(listOf(10002), snapshot.apps.map { it.uid })
        }
    }

    @Test fun omittedPowerDetailOnlyCountersDoNotUncertifySessionMeasurements() {
        val snapshot = parsed(dump(uid(10001, null,
            message(8, timer(1)), message(9, timer(1)), message(10, timer(1)), message(14, timer(1)),
            message(6, message(1, timer(1)), message(3, timer(1))),
            message(20, number(1, 6), number(2, 1)),
            message(17, number(7, 1), number(12, 1)),
            message(22, text(1, "sync duration only"), message(2, timer(1))),
            message(25, text(1, "partial duration only"), message(3, timer(1))),
            message(21, number(1, 17), message(2, timer(count = 1))),
        ), uid(10002, 3.0)))
        assertTrue(snapshot.appMeasurementsComplete)
        assertEquals(1L, snapshot.wakelocks.single().totalTimeMs)
        assertEquals(1L, snapshot.syncs.single().totalTimeMs)
        assertEquals(1, snapshot.sensors.single().count)
        assertEquals(listOf(10002), snapshot.toAppUsageSnapshot().rows.map { it.uid })
    }

    @Test fun omittedPowerWithOnlyZeroSessionCountersRemainsCompleteAndBrowsable() {
        val snapshot = parsed(dump(uid(10001, null,
            message(7), message(24), message(11), message(12), message(17),
            message(20), message(20, number(1, 3)),
            message(15, text(1, "job\ncontinuation")), message(22, text(1, "sync")),
            message(25, text(1, "lock"), message(3, timer())),
            message(26, text(1, "alarm\rcontinuation")),
            message(21, number(1, -10000)), message(21, number(1, 17)),
        ), uid(10002, 3.0)))
        assertTrue(snapshot.appMeasurementsComplete)
        assertEquals(listOf(10002), snapshot.apps.map { it.uid })
        assertEquals("job\ncontinuation", snapshot.jobs.single().jobName)
        assertEquals(0, snapshot.jobs.single().count)
        assertEquals("alarm\rcontinuation", snapshot.alarms.single().tag)
        assertEquals(0, snapshot.rejectedRecords)
    }

    @Test fun producerRetainedZeroPowerConsumerWithCountersRemainsComplete() {
        // SHOULD_HIDE keeps the optional pwi present even when computed charge is zero.
        val snapshot = parsed(dump(uid(10001, null,
            message(18, number(2, 1)), message(15, text(1, "job"), message(2, timer(count = 1))),
        )))
        assertTrue(snapshot.appMeasurementsComplete)
        assertEquals(0.0, snapshot.apps.single().powerMah, 0.0)
        assertEquals(1L, snapshot.toAppUsageSnapshot().rows.single().jobCount)
    }

    @Test fun omittedPowerMalformedCounterRetainsExistingRejectionPolicy() {
        val snapshot = parsed(dump(uid(10001, null,
            message(15, text(1, "invalid job"), message(2, timer(count = -1))),
        ), uid(10002, 3.0)))
        assertFalse(snapshot.appMeasurementsComplete)
        assertEquals(1, snapshot.rejectedRecords)
        assertEquals(0, snapshot.rejectedAppPowerRecords)
        assertTrue(snapshot.jobs.isEmpty())
        assertEquals(listOf(10002), snapshot.apps.map { it.uid })
    }

    @Test fun partialNonfinitePowerRejectsOnlyThatConsumerAndCannotCertifyCapture() {
        val snapshot = parsed(dump(uid(10001, Double.NaN), uid(10002, 3.0)))
        assertEquals(listOf(10002), snapshot.apps.map { it.uid })
        assertEquals(2, snapshot.appPowerRecords)
        assertEquals(1, snapshot.rejectedAppPowerRecords)
        assertFalse(snapshot.appMeasurementsComplete)
        assertTrue(parsed(dump(uid(10001, Double.NaN))).hasOnlyRejectedAppPowerRecords)
    }

    @Test fun invalidNamedCounterCannotEraseAcceptedVictimOrCertifyZero() {
        val snapshot = parsed(dump(uid(10001, 2.0, message(26, text(1, "bad-alarm"), number(2, -1))), uid(10002, 3.0)))
        assertEquals(2, snapshot.apps.size)
        assertFalse(snapshot.appMeasurementsComplete)
        assertTrue(snapshot.rejectedRecords > 0)
        assertNull(snapshot.apps.first { it.uid == 10001 }.wakeupAlarmCount)
    }

    @Test fun knownCounterOverflowIsRejectedWithoutNegativeOrWrappedMeasurements() {
        val snapshot = parsed(dump(uid(10001, 2.0, message(7, number(1, Long.MAX_VALUE), number(2, 1)))))
        assertNull(snapshot.apps.single().cpuTimeMs)
        assertFalse(snapshot.appMeasurementsComplete)
    }

    @Test fun sharedUidIdentityIsDistinctSortedAndProducerOrderIndependent() {
        val record = message(5, number(1, 10001), message(2, text(1, "org.z")), message(2, text(1, "org.a")),
            message(2, text(1, "org.z")), message(18, double(1, 1.0)))
        assertEquals(listOf("org.a", "org.z"), parsed(dump(record)).apps.single().packages)
    }

    @Test fun unknownFieldsAreSkippedWithoutChangingKnownMetrics() {
        val snapshot = parsed(dump(uid(10001, 3.0, number(200, 3), double(201, 4.0), message(202, byteArrayOf(0))),
            moreSystem = number(300, 9)) + number(400, 3))
        assertEquals(3.0, snapshot.apps.single().powerMah, 0.0)
    }

    @Test fun emptyValidWindowAndResetComparisonsRemainTruthful() {
        assertTrue(parsed(dump()).apps.isEmpty())
        val baseline = parsed(dump(uid(power = 2.0))).copy(capturedAt = 100).toAppUsageSnapshot()
        val same = parsed(dump(uid(power = 3.0))).copy(capturedAt = 200).toAppUsageSnapshot()
        val reset = parsed(dump(uid(power = 3.0), start = 1700000000001L)).copy(capturedAt = 200).toAppUsageSnapshot()
        assertEquals(AppUsageBasis.DELTA, AppUsageDelta.compute(baseline, same).basis)
        assertEquals(AppUsageBasis.WINDOW_RESET, AppUsageDelta.compute(baseline, reset).basis)
    }

    @Test fun malformedWireTruncationSuffixAndMissingAnchorsAreUnavailable() {
        val valid = dump(uid())
        val cases = listOf(byteArrayOf(), "Permission Denial: dump".toByteArray(), message(1, number(1, 36)),
            valid.copyOf(valid.size - 1), valid + "\nDUMP TIMEOUT".toByteArray(), byteArrayOf(0),
            valid + byteArrayOf(11), valid + byteArrayOf(16) + ByteArray(9) { 0x80.toByte() } + byteArrayOf(2),
            message(1, number(1, 36), message(6, message(1, number(1, 1700000000000L), number(5, 1), number(6, 2)))),
        )
        cases.forEach { assertNull("Malformed bytes accepted: ${it.size}", BatteryStatsProtoParser.parse(it)) }
    }

    @Test fun duplicatesWrongWireAndInvalidUtf8AreUnavailable() {
        val cases = listOf(dump(uid(), uid()), dump(uid()) + dump(uid(10002)),
            dump(message(5, number(1, 10001), number(1, 10002), message(18, double(1, 1.0)))),
            dump(message(5, number(1, 10001), message(18, double(1, 1.0), double(1, 2.0)))),
            dump(uid(more = arrayOf(message(26, message(1, byteArrayOf(0xc3.toByte(), 0x28)), number(2, 1))))),
            dump(uid(more = arrayOf(number(17, 1)))),
        )
        cases.forEach { assertNull(BatteryStatsProtoParser.parse(it)) }
    }

    @Test fun inputSizeLimitIsAppliedBeforeAllocatingFields() {
        assertNull(BatteryStatsProtoParser.parse(ByteArray(8 * 1024 * 1024 + 1)))
    }

    @Test fun invalidNumericFieldsCannotHideDuplicateTypedFieldsOrInvalidNameBytes() {
        val cases = listOf(
            dump(message(5, number(1, 10001), message(18, double(1, Double.NaN), double(3, 1.0), double(3, 2.0)))),
            dump(uid(more = arrayOf(message(26, number(2, -1), message(1, byteArrayOf(0xc3.toByte(), 0x28)))))),
            dump(uid(more = arrayOf(message(15, message(2, timer(-1)), message(3, number(1, 1), number(1, 2)))))),
        )
        cases.forEach { assertNull(BatteryStatsProtoParser.parse(it)) }
    }

    @Test fun olderProducerMultipleUserComponentsFollowExistingFirstRecordPolicy() {
        val system = join(message(17, number(1, 8), number(2, 100000), double(3, 2.0)),
            message(17, number(1, 8), number(2, 200000), double(3, 3.0)))
        assertEquals(2.0, parsed(dump(moreSystem = system)).componentEstimatesMah["user"] ?: 0.0, 0.0)
    }

    private fun learnedCapacityDump(minimum: Long, maximum: Long): ByteArray = message(1,
        number(1, 36),
        uid(10001, 3.25, message(7, number(1, 100), number(2, 25)),
            message(11, timer(200, 1)), message(26, text(1, "ordinary-alarm"), number(2, 3))),
        message(6, message(1, number(1, 1700000000000L), number(5, 12000), number(6, 7000),
            number(7, 9000), number(11, minimum), number(12, maximum))),
    )

    @Test fun producerUnknownLearnedCapacitySentinelsKeepAppBaselineEligible() {
        // BatteryStatsImpl initializes/resets both capacities to -1; proto writes the int64s.
        val snapshot = parsed(learnedCapacityDump(-1, -1))
        assertNull(snapshot.learnedMinCapacityUah)
        assertNull(snapshot.learnedMaxCapacityUah)
        assertTrue(snapshot.hasValidWindow)
        assertTrue(snapshot.appMeasurementsComplete)
        assertEquals(0, snapshot.rejectedRecords)
        assertEquals(0, snapshot.rejectedAppPowerRecords)
        assertEquals(12000L, snapshot.batteryRealtimeMs)
        assertEquals(9000L, snapshot.screenOffTimeMs)
        val baseline = snapshot.toAppUsageSnapshot()
        assertEquals(1700000000000L, baseline.windowStartedAt)
        assertEquals(0L, baseline.windowStartCount)
        val app = baseline.rows.single()
        assertEquals(3.25, app.powerMah, 0.0)
        assertEquals(125L, app.cpuTimeMs)
        assertEquals(200L, app.foregroundTimeMs)
        assertEquals(3L, app.wakeupAlarms)
    }

    @Test fun oneKnownLearnedCapacityIsPreservedWhenOtherIsUnknown() {
        val knownMinimum = parsed(learnedCapacityDump(4300000, -1))
        assertEquals(4300000L, knownMinimum.learnedMinCapacityUah)
        assertNull(knownMinimum.learnedMaxCapacityUah)
        val knownMaximum = parsed(learnedCapacityDump(-1, 4500000))
        assertNull(knownMaximum.learnedMinCapacityUah)
        assertEquals(4500000L, knownMaximum.learnedMaxCapacityUah)
        for (snapshot in listOf(knownMinimum, knownMaximum)) {
            assertTrue(snapshot.hasValidWindow)
            assertTrue(snapshot.appMeasurementsComplete)
            assertEquals(3.25, snapshot.apps.single().powerMah, 0.0)
            assertEquals(125L, snapshot.apps.single().cpuTimeMs)
            assertEquals(3L, snapshot.apps.single().wakeupAlarmCount)
        }
    }

    @Test fun learnedCapacityAllowsOnlyOfficialUnknownSentinelAndNonnegativeValues() {
        assertNull(BatteryStatsProtoParser.parse(learnedCapacityDump(-2, 4500000)))
        assertNull(BatteryStatsProtoParser.parse(learnedCapacityDump(4300000, -2)))
        val snapshot = parsed(learnedCapacityDump(0, 0))
        assertNull(snapshot.learnedMinCapacityUah)
        assertNull(snapshot.learnedMaxCapacityUah)
        assertTrue(snapshot.appMeasurementsComplete)
    }

    private fun assertNetworkOverflowRejected(rxField: Int, txField: Int) {
        val snapshot = parsed(dump(
            uid(10001, 3.25, message(17, number(rxField, Long.MAX_VALUE), number(txField, 1)),
                message(7, number(1, 100), number(2, 25)), message(11, timer(200, 1))),
            uid(10002, 2.0, message(17, number(1, 3), number(2, 4), number(3, 5), number(4, 6))),
        ))
        assertTrue(snapshot.hasValidWindow)
        assertFalse(snapshot.appMeasurementsComplete)
        assertEquals(1, snapshot.rejectedRecords)
        assertEquals(0, snapshot.rejectedAppPowerRecords)
        val app = snapshot.apps.first { it.uid == 10001 }
        assertEquals(3.25, app.powerMah, 0.0)
        assertEquals(125L, app.cpuTimeMs)
        assertEquals(200L, app.foregroundTimeMs)
        assertNull(app.mobileRxBytes)
        assertNull(app.mobileTxBytes)
        assertNull(app.wifiRxBytes)
        assertNull(app.wifiTxBytes)
        assertEquals(listOf(10002), snapshot.network.map { it.uid })
        val rows = snapshot.toAppUsageSnapshot().rows
        val rejected = rows.first { it.uid == 10001 }
        assertNull(rejected.mobileBytes)
        assertNull(rejected.wifiBytes)
        val accepted = rows.first { it.uid == 10002 }
        assertEquals(7L, accepted.mobileBytes)
        assertEquals(11L, accepted.wifiBytes)
        assertTrue(rows.all { (it.mobileBytes ?: 0) >= 0 && (it.wifiBytes ?: 0) >= 0 })
    }

    @Test fun individuallyValidMobileByteCountersCannotOverflowSnapshotTotal() {
        assertNetworkOverflowRejected(1, 2)
    }

    @Test fun individuallyValidWifiByteCountersCannotOverflowSnapshotTotal() {
        assertNetworkOverflowRejected(3, 4)
    }

    @Test fun networkTotalsAtLargestSupportedValueRemainValidMeasurements() {
        val snapshot = parsed(dump(uid(10001, 3.25, message(17,
            number(1, Long.MAX_VALUE - 1), number(2, 1), number(3, Long.MAX_VALUE - 2), number(4, 2)))))
        assertTrue(snapshot.appMeasurementsComplete)
        assertEquals(0, snapshot.rejectedRecords)
        val row = snapshot.toAppUsageSnapshot().rows.single()
        assertEquals(Long.MAX_VALUE, row.mobileBytes)
        assertEquals(Long.MAX_VALUE, row.wifiBytes)
    }
}
