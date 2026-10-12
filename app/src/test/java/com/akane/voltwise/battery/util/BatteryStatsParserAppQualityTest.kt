package com.akane.voltwise.battery.util

import org.junit.Assert.*
import org.junit.Test

/** Synthetic checkin fixtures validate session app coverage, not device measurements. */
class BatteryStatsParserAppQualityTest {
    private val power = "9,10001,l,pwi,uid,1.0\n9,10002,l,pwi,uid,2.0"

    @Test fun malformedConsumedFieldsCannotCertifyAppMeasurementCoverage() {
        val records = listOf(
            "9,10001,l,wua,tag,NaN",
            "9,10001,l,fgs,NaN",
            "9,10001,l,fg,NaN",
            "9,10001,l,awl,NaN",
            "9,10001,l,cpu,NaN,0",
            "9,10001,l,cpu,0,NaN",
            "9,10001,l,cpu,9223372036854775807,1",
            "9,10001,l,st,NaN,0,0,0,0,0,0",
            "9,10001,l,st,0,0,0,NaN,0,0,0",
            "9,10001,l,jb,job,NaN,0,0,0",
            "9,10001,l,jb,job,0,NaN,0,0",
            "9,10001,l,sy,sync,0,NaN,0,0",
            "9,10001,l,sy,sync,NaN,0,0,0",
            "9,10001,l,wl,lock,NaN,p,0,0,0,0",
            "9,10001,l,wl,lock,0,p,NaN,0,0,0",
            "9,10001,l,wl,lock,0,p,0,0,0,0,NaN,bp,0,0,0,0",
            "9,10001,l,sr,-10000,NaN,0",
            "9,10001,l,sr,1,NaN,0",
            "9,10001,l,sr,NaN,0,0",
            "9,10001,l,sr,1,0,NaN",
            "9,10001,l,nt,NaN,0,0,0",
            "9,10001,l,nt,0,NaN,0,0",
            "9,10001,l,nt,0,0,NaN,0",
            "9,10001,l,nt,0,0,0,NaN",
            "9,10001,l,nt,0,0,0,0,0,0,0,0,NaN",
            "9,10001,l,nt,9223372036854775807,1,0,0",
        )
        val incorrectlyComplete = mutableListOf<String>()
        for (record in records) {
            val snapshot = BatteryStatsParser.parseCheckin("$power\n$record")
            if (snapshot.appMeasurementsComplete) incorrectlyComplete += record
            assertEquals("Counter quality does not reject accepted power rows", 2, snapshot.apps.size)
        }
        assertEquals(emptyList<String>(), incorrectlyComplete)
    }

    @Test fun absentKnownZeroAndUnconsumedFieldsKeepCoverageComplete() {
        val records = listOf(
            "", "9,10001,l,wua,tag,0", "9,10001,l,fgs,0,NaN", "9,10001,l,fg,0,NaN",
            "9,10001,l,awl,0", "9,10001,l,cpu,0,0,NaN",
            "9,10001,l,st,0,NaN,NaN,0,NaN,NaN,NaN",
            "9,10001,l,jb,job,0,0,NaN,NaN", "9,10001,l,sy,sync,0,0,NaN,NaN",
            "9,10001,l,wl,lock,0,p,0,0,NaN,0", "9,10001,l,sr,-10000,0,0",
            "9,10001,l,sr,1,0,0", "9,10001,l,nt,0,0,0,0",
            "9,10001,l,nt,0,0,0,0,NaN,NaN,NaN,NaN,0,NaN,NaN,NaN",
            "9,10001,l,blem,NaN", "9,10001,l,cam,NaN",
        )
        for (record in records) assertTrue(record, BatteryStatsParser.parseCheckin("$power\n$record").appMeasurementsComplete)
        assertTrue(BatteryStatsParser.FullSnapshot().appMeasurementsComplete)
        assertTrue(BatteryStatsParser.parseCheckin("9,10001,l,pwi,uid,1.0,0,NaN,NaN").appMeasurementsComplete)
    }

    @Test fun rejectedPowerKeepsAcceptedRowsButWithholdsCoverage() {
        for (record in listOf("9,10003,l,pwi,uid,NaN", "9,broken,l,pwi,uid,1.0", "9,10001,l,pwi,uid,1.0")) {
            val snapshot = BatteryStatsParser.parseCheckin("$power\n$record")
            assertFalse(record, snapshot.appMeasurementsComplete)
            assertEquals(2, snapshot.apps.size)
            assertEquals(1, snapshot.rejectedAppPowerRecords)
        }
    }

    @Test fun invalidUidAndTruncatedAppRecordsCannotCertifyCoverage() {
        for (record in listOf("9,broken,l,wua,tag,0", "9,broken,l,fgs,0", "9,10001,l,jb,job,1")) {
            assertFalse(record, BatteryStatsParser.parseCheckin("$power\n$record").appMeasurementsComplete)
        }
    }

    @Test fun positivePrimaryAppOrphansWithholdCoverageWithoutBeingRejected() {
        for ((uid, count, complete) in listOf(
            Triple(10001, 150, false), Triple(10001, 0, true), Triple(1000, 150, true),
            Triple(0, 150, true), Triple(110001, 150, true),
        )) {
            val snapshot = BatteryStatsParser.parseCheckin("9,10002,l,pwi,uid,2.0\n9,$uid,l,wua,tag,$count")
            assertEquals("UID $uid, count $count", complete, snapshot.appMeasurementsComplete)
            assertEquals(0, snapshot.rejectedRecords)
            assertEquals(0, snapshot.rejectedAppPowerRecords)
            assertEquals(10002, snapshot.apps.single().uid)
            assertEquals(count, snapshot.alarms.single().count)
        }
    }

    @Test fun unrelatedMalformedDeviceRecordsDoNotDiscardAppCoverage() {
        for (record in listOf("9,0,l,kwl,lock,NaN,1", "9,0,l,wr,reason,0,NaN",
            "9,0,l,pwi,screen,NaN", "9,0,l,gble,NaN,0,0,0,0")) {
            assertTrue(record, BatteryStatsParser.parseCheckin("$power\n$record").appMeasurementsComplete)
        }
    }

    @Test fun acceptedSystemNativeAndProfileRowsValidateTheirConsumedFields() {
        for (uid in listOf(0, 1000, 110001)) {
            val snapshot = BatteryStatsParser.parseCheckin("9,$uid,l,pwi,uid,1.0\n9,$uid,l,fgs,NaN")
            assertFalse("accepted UID $uid", snapshot.appMeasurementsComplete)
            assertEquals(uid, snapshot.apps.single().uid)
            assertEquals(0, snapshot.rejectedRecords)
        }
        assertTrue(BatteryStatsParser.parseCheckin("$power\n9,1000,l,fgs,NaN").appMeasurementsComplete)
    }

    @Test fun everyConsumedFamilyDetectsPositivePrimaryOrphanEvidence() {
        val records = listOf(
            "wua,tag,1", "fg,1", "fgs,1", "awl,1", "cpu,1,0", "st,1,0,0,0,0,0,0",
            "st,0,0,0,1,0,0,0", "nt,1,0,0,0", "nt,0,0,0,0,0,0,0,0,1000",
            "wl,lock,1,p,0,0,0,0", "wl,lock,0,p,1,0,0,0", "sr,-10000,1,0", "sr,1,1,0",
            "jb,job,1,0,0,0", "sy,sync,0,1,0,0",
        )
        val missed = records.filter { record ->
            BatteryStatsParser.parseCheckin("9,10002,l,pwi,uid,2.0\n9,10001,l,$record").appMeasurementsComplete
        }
        assertEquals(emptyList<String>(), missed)
        // A sync's valid duration is required for admission, but only count feeds session metrics.
        assertTrue(BatteryStatsParser.parseCheckin("9,10002,l,pwi,uid,2.0\n9,10001,l,sy,sync,1,0,0,0").appMeasurementsComplete)
    }

    @Test fun explicitMalformedHeadersAndConsumedNumericValuesAreIncomplete() {
        val records = listOf(
            "9,10001,,wua,tag,0", "9,10001,broken,fgs,0", "9,-1,l,fgs,0",
            "9,10001,l,wua,tag,2147483648", "9,10001,l,fgs,-1", "9,10001,l,fgs,Infinity",
            "9,10001,l,nt,0,0,9223372036854775807,1",
        )
        val missed = records.filter { BatteryStatsParser.parseCheckin("$power\n$it").appMeasurementsComplete }
        assertEquals(emptyList<String>(), missed)
        assertTrue(BatteryStatsParser.parseCheckin("$power\n9,10001,u,fgs,NaN").appMeasurementsComplete)
    }

    @Test fun processStateValidationFollowsTheSdkAndIgnoresUnusedStates() {
        val oreoBackground = "$power\n9,10001,l,st,0,0,0,0,NaN,0"
        assertFalse(BatteryStatsParser.parseCheckin(oreoBackground, 27).appMeasurementsComplete)
        assertTrue(BatteryStatsParser.parseCheckin(oreoBackground, 28).appMeasurementsComplete)
        val pieBackground = "$power\n9,10001,l,st,0,0,0,NaN,0,0"
        assertTrue(BatteryStatsParser.parseCheckin(pieBackground, 27).appMeasurementsComplete)
        assertFalse(BatteryStatsParser.parseCheckin(pieBackground, 28).appMeasurementsComplete)
    }

    @Test fun consumedAggregateOverflowsCannotCertifyCoverage() {
        for (records in listOf(
            "9,10001,l,sr,1,9223372036854775807,0\n9,10001,l,sr,2,1,0",
            "9,10001,l,jb,one,9223372036854775807,0,0,0\n9,10001,l,jb,two,1,0,0,0",
            "9,10001,l,wl,one,0,p,0,0,0,0,9223372036854775807,bp,0,0,0,0\n9,10001,l,wl,two,0,p,0,0,0,0,1,bp,0,0,0,0",
        )) {
            val snapshot = BatteryStatsParser.parseCheckin("$power\n$records")
            assertFalse(records, snapshot.appMeasurementsComplete)
            assertEquals(0, snapshot.rejectedRecords)
            assertEquals(2, snapshot.apps.size)
        }
    }

    @Test fun qualityIsEqualForStringAndSinglePassSequenceInputs() {
        val raw = "$power\n9,10003,l,wua,tag,150\n9,10001,l,fgs,NaN"
        assertEquals(BatteryStatsParser.parseCheckin(raw).copy(capturedAt = 0),
            BatteryStatsParser.parseCheckin(raw.lineSequence().constrainOnce()).copy(capturedAt = 0))
    }

    @Test fun rejectedRequiredRecordShapesCannotCertifyAppCoverage() {
        val records = listOf(
            "wua", "wua,tag",
            "sr", "sr,-10000", "sr,-10000,100", "sr,1,0",
            "nt", "nt,0", "nt,0,0", "nt,1,2,3",
            "wl", "wl,tag", "wl,tag,100", "wl,tag,100,p", "wl,tag,100,unknown,0", "wl,tag,100,bp,0",
            "jb", "jb,job,100", "jb,job,100,0", "sy", "sy,sync,100", "sy,sync,100,0",
            "pwi,uid",
        )
        val incorrectlyComplete = mutableListOf<String>()
        for (record in records) {
            val snapshot = BatteryStatsParser.parseCheckin("$power\n9,10001,l,$record")
            if (snapshot.appMeasurementsComplete) incorrectlyComplete += record
            assertEquals(record, 2, snapshot.apps.size)
            assertEquals("Existing admission rejects $record", 1, snapshot.rejectedRecords)
            assertEquals(record, if (record.startsWith("pwi")) 1 else 0, snapshot.rejectedAppPowerRecords)
        }
        assertEquals(emptyList<String>(), incorrectlyComplete)
    }

    @Test fun admittedOptionalTailsAndUnconsumedWakelockSectionsRemainComplete() {
        val records = listOf(
            "wua,tag,0", "sr,-10000,0,0", "sr,1,0,0", "nt,0,0,0,0", "wl,tag,0,p,0",
            "wl,tag,0,f,0", "wl,tag,0,w,0", "wl,tag,NaN,f,NaN", "wl,tag,NaN,w,NaN",
            "wl,tag,0,f,0,0,0,0,0,p,0", "wl,tag,0,p,0,0,NaN,0",
            "jb,job,0,0,,", "sy,sync,0,0,,",
            "fg,0", "fgs,0", "awl,0", "cpu,0,0", "st,0,0,0,0",
        )
        val incorrectlyIncomplete = records.filter {
            !BatteryStatsParser.parseCheckin("$power\n9,10001,l,$it").appMeasurementsComplete
        }
        assertEquals(emptyList<String>(), incorrectlyIncomplete)
        assertTrue(BatteryStatsParser.parseCheckin(power).appMeasurementsComplete)
    }

    @Test fun requiredShapeValidationIncludesAcceptedNativeButIgnoresNativeOrphans() {
        for (uid in listOf(0, 1000, 110001)) {
            for (record in listOf("wua,tag", "sr,1,0", "nt,0,0,0", "wl,tag,0,p")) {
                val accepted = BatteryStatsParser.parseCheckin("9,$uid,l,pwi,uid,1.0\n9,$uid,l,$record")
                assertFalse("accepted $uid $record", accepted.appMeasurementsComplete)
                assertEquals(1, accepted.rejectedRecords)
                val orphan = BatteryStatsParser.parseCheckin("$power\n9,$uid,l,$record")
                assertTrue("orphan $uid $record", orphan.appMeasurementsComplete)
                assertEquals(1, orphan.rejectedRecords)
            }
        }
    }

    @Test fun presentDirectCoreRecordsRequireTheirConsumedValues() {
        val records = listOf(
            "fg", "fgs", "awl", "cpu", "cpu,0", "st", "st,0", "st,0,0", "st,0,0,0",
        )
        val incorrectlyComplete = mutableListOf<String>()
        for (record in records) {
            val snapshot = BatteryStatsParser.parseCheckin("$power\n9,10001,l,$record")
            if (snapshot.appMeasurementsComplete) incorrectlyComplete += record
            assertEquals(record, 2, snapshot.apps.size)
            assertEquals("Direct enrichment does not reject a power row", 0, snapshot.rejectedRecords)
            assertEquals(record, 0, snapshot.rejectedAppPowerRecords)
        }
        assertEquals(emptyList<String>(), incorrectlyComplete)
    }

    @Test fun requiredStateShapeUsesSdkBackgroundWithoutRequiringUnusedTail() {
        val pieMinimum = "$power\n9,10001,l,st,0,,,0"
        assertTrue(BatteryStatsParser.parseCheckin(pieMinimum, 28).appMeasurementsComplete)
        assertFalse(BatteryStatsParser.parseCheckin(pieMinimum, 27).appMeasurementsComplete)
        val oreoMinimum = "$power\n9,10001,l,st,0,,,,0"
        assertTrue(BatteryStatsParser.parseCheckin(oreoMinimum, 27).appMeasurementsComplete)
        assertFalse(BatteryStatsParser.parseCheckin(oreoMinimum, 28).appMeasurementsComplete)
    }

    @Test fun validWakelockSectionsCannotMaskAnUnclassifiableStartedSection() {
        val records = listOf(
            "0,f,0,0,0,0,100,unknown,1", "0,f,0,0,0,0,100,,1", "0,f,0,0,0,0,100",
            "100,unknown,1,0,0,0,0,f,0", "100,,1,0,0,0,0,f,0",
            "0,f,0,0,0,0,100,unknown,1,0,0,0,0,p,0",
        )
        val incorrectlyComplete = mutableListOf<String>()
        for (record in records) {
            val snapshot = BatteryStatsParser.parseCheckin("$power\n9,10001,l,wl,tag,$record")
            if (snapshot.appMeasurementsComplete) incorrectlyComplete += record
            assertEquals(record, 2, snapshot.apps.size)
            assertEquals("Ignored unknown sections do not alter rejection accounting", 0, snapshot.rejectedRecords)
            assertEquals(record, 0, snapshot.rejectedAppPowerRecords)
        }
        assertEquals(emptyList<String>(), incorrectlyComplete)
    }

    @Test fun knownWakelockTypesInEveryOrderKeepCoverageComplete() {
        fun permutations(types: List<String>): List<List<String>> =
            if (types.isEmpty()) listOf(emptyList()) else types.flatMap { first ->
                permutations(types - first).map { listOf(first) + it }
            }
        val incorrectlyIncomplete = mutableListOf<String>()
        for (types in permutations(listOf("f", "w", "p", "bp"))) {
            val sections = types.joinToString(",") { "0,$it,0,NaN,NaN,NaN" }
            val snapshot = BatteryStatsParser.parseCheckin("$power\n9,10001,l,wl,tag,$sections")
            if (!snapshot.appMeasurementsComplete) incorrectlyIncomplete += types.joinToString(",")
            assertEquals(3, snapshot.wakelocks.size)
            assertEquals(0, snapshot.rejectedRecords)
            assertEquals(0L, snapshot.apps.first { it.uid == 10001 }.partialWakelockBgTimeMs)
        }
        assertEquals(emptyList<String>(), incorrectlyIncomplete)
    }

    @Test fun wakelockUnusedTailsAreOptionalAndBackgroundCountsAreUnconsumed() {
        val records = listOf(
            "0,p,0", "0,p,0,NaN", "0,p,0,NaN,NaN", "0,p,0,NaN,NaN,NaN",
            "NaN,f,NaN", "NaN,w,NaN", "0,p,0,0,0,0,0,bp", "0,p,0,0,0,0,0,bp,NaN",
            "0,p,0,0,0,0,0,bp,NaN,NaN,NaN,NaN", "0,f,0,0,0,0,0,bp,0",
        )
        val incorrectlyIncomplete = records.filter {
            !BatteryStatsParser.parseCheckin("$power\n9,10001,l,wl,tag,$it").appMeasurementsComplete
        }
        assertEquals(emptyList<String>(), incorrectlyIncomplete)
    }

    @Test fun backgroundSectionsCannotLosePositiveDurationWithoutSameRecordPartial() {
        val records = listOf(
            "0,f,0,0,0,0,100,bp,1", "100,bp,1,0,0,0,0,w,0",
            "0,f,0,0,0,0,NaN,bp,0", "0,w,0,0,0,0,,bp,0",
        )
        val incorrectlyComplete = mutableListOf<String>()
        for (record in records) {
            // A partial timer on another tag must not legitimize an unattached background section.
            val snapshot = BatteryStatsParser.parseCheckin("$power\n9,10001,l,wl,other,0,p,0\n9,10001,l,wl,tag,$record")
            if (snapshot.appMeasurementsComplete) incorrectlyComplete += record
            assertEquals(record, 2, snapshot.apps.size)
            assertEquals(record, 0, snapshot.rejectedRecords)
            assertNull(snapshot.apps.first { it.uid == 10001 }.partialWakelockBgTimeMs)
        }
        assertEquals(emptyList<String>(), incorrectlyComplete)
        val valid = BatteryStatsParser.parseCheckin("$power\n9,10001,l,wl,tag,0,p,0,0,0,0,100,bp,NaN")
        assertTrue(valid.appMeasurementsComplete)
        assertEquals(100L, valid.apps.first { it.uid == 10001 }.partialWakelockBgTimeMs)
    }
}
