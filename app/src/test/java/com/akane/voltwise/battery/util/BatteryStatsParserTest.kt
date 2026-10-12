package com.akane.voltwise.battery.util

import org.junit.Assert.*
import org.junit.Test

/** Synthetic records follow android16-release BatteryStats.java; they are not device measurements. */
class BatteryStatsParserTest {
    @Test fun capacitiesKeepFractionsAndDischargeUsesIntegerPercentagePoints() {
        val small = BatteryStatsParser.parseCheckin("9,0,l,pws,0.75\n9,0,l,dc,0,0,120,0")
        assertEquals(0.75, small.estimatedCapacityMah!!, 0.0)
        assertEquals(120, small.screenOnDischargePercent)
        assertEquals(0, small.screenOffDischargePercent)
        for (value in listOf("1e300", "NaN", "Infinity", "-1", "0.5")) {
            val snapshot = BatteryStatsParser.parseCheckin("9,0,l,dc,0,0,$value,$value")
            assertNull(snapshot.screenOnDischargePercent)
            assertNull(snapshot.screenOffDischargePercent)
        }
        for (value in listOf("0", "-1", "200001", "Infinity", "NaN")) {
            assertNull(BatteryStatsParser.parseCheckin("9,0,l,pws,$value").estimatedCapacityMah)
        }
    }
    @Test fun jobAndSyncTimePrecedeCountAndKeepBackgroundValues() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,10001,l,jb,"job,with,commas",12345,7,4321,3
            9,10001,l,sy,authority,7654,2,1234,1
        """.trimIndent())
        val job = snapshot.jobs.single()
        assertEquals("job,with,commas", job.jobName)
        assertEquals(12345L, job.totalTimeMs)
        assertEquals(7, job.count)
        assertEquals(4321L, job.backgroundTimeMs)
        assertEquals(3, job.backgroundCount)
        assertEquals(7654L, snapshot.syncs.single().totalTimeMs)
        assertEquals(2, snapshot.syncs.single().count)
    }
    @Test fun zeroDozeIsAReportedZeroAndIdlingIsNotMaintenance() {
        val zero = BatteryStatsParser.parseCheckin("9,0,l,m," + List(21) { "0" }.joinToString(",")).doze
        assertNotNull(zero)
        assertEquals(0L, zero!!.deepIdleTimeMs)
        val values = MutableList(21) { "0" }
        values[9] = "12000"; values[10] = "2"; values[11] = "20000"; values[12] = "4"
        values[15] = "3000"; values[16] = "1"
        val doze = BatteryStatsParser.parseCheckin("9,0,l,m," + values.joinToString(",")).doze!!
        assertEquals(12000L, doze.deepIdleTimeMs)
        assertEquals(3000L, doze.lightIdleTimeMs)
        assertNull("Idling is not a maintenance-window duration", doze.maintenanceTimeMs)
    }
    @Test fun globalBluetoothDoesNotUsePerUidControllerOrScanRows() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,0,l,gble,100,20,2,1,30
            9,10001,l,ble,1000,2000,20,1,3000
            9,10001,l,blem,120,4,2,140,10,3,1,30,10,20,10
        """.trimIndent())
        val bt = snapshot.bluetooth!!
        assertEquals(100L, bt.idleTimeMs)
        assertEquals(20L, bt.rxTimeMs)
        assertEquals(30L, bt.txTimeMs)
        assertEquals(2.0, bt.powerMah, 0.0)
    }
    @Test fun uidCountersPopulateOnlyReportedActivity() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,0,i,uid,10001,example.app
            9,10001,l,pwi,uid,1.5,0,0.5,2.0
            9,10001,l,cpu,100,200,0
            9,10001,l,fg,1500,2
            9,10001,l,fgs,2000,1
            9,10001,l,st,1000,2000,3000,4000,5000,6000,7000
            9,10001,l,nt,10,20,30,40,1,2,3,4,2000000,5,60,70
        """.trimIndent())
        val app = snapshot.apps.single()
        assertEquals(300L, app.cpuTimeMs)
        assertEquals(1500L, app.foregroundTimeMs)
        assertEquals(2000L, app.foregroundServiceTimeMs)
        assertEquals(4000L, app.backgroundTimeMs)
        assertEquals(7000L, app.cachedTimeMs)
        assertEquals(10L, app.mobileRxBytes)
        assertNotNull(app.screenPowerMah)
        assertEquals(0.5, app.screenPowerMah!!, 0.0)
        assertEquals(2000L, snapshot.network.single().mobileActiveTimeMs)
        assertEquals(60L, snapshot.network.single().btRxBytes)
    }
    @Test fun oreoProcessStatesUseBackgroundAndCachedInsteadOfForeground() {
        // android-8.1.0_r1: TOP, FGS, TOP_SLEEPING, FOREGROUND, BACKGROUND, CACHED.
        val raw = """
            9,0,i,vers,16,167,OPM1.171019.011,OPM1.171019.011
            9,10001,l,pwi,uid,1.5,0,0,0
            9,10001,l,st,1000,2000,3000,4000,5000,6000
        """.trimIndent()
        for (sdkInt in listOf(27, 26)) {
            val snapshot = BatteryStatsParser.parseCheckin(raw, sdkInt)
            val app = snapshot.apps.single()
            assertEquals(1000L, app.topTimeMs)
            assertEquals("Oreo background is the fifth process state", 5000L, app.backgroundTimeMs)
            assertEquals("Oreo cached is the sixth process state", 6000L, app.cachedTimeMs)
            assertEquals(
                snapshot.copy(capturedAt = 0),
                BatteryStatsParser.parseCheckin(raw.lineSequence().constrainOnce(), sdkInt).copy(capturedAt = 0),
            )
        }
    }
    @Test fun pieAndLaterProcessStatesKeepModernOffsets() {
        val raw = """
            9,0,i,vers,16,177,PKQ1.180522.001,PKQ1.180522.001
            9,10001,l,pwi,uid,1.5,0,0,0
            9,10001,l,st,1000,2000,3000,4000,5000,6000,7000
        """.trimIndent()
        for (sdkInt in listOf(28, 36)) {
            val app = BatteryStatsParser.parseCheckin(raw, sdkInt).apps.single()
            assertEquals(1000L, app.topTimeMs)
            assertEquals(4000L, app.backgroundTimeMs)
            assertEquals(7000L, app.cachedTimeMs)
        }
    }
    @Test fun sdkSelectsProcessStatesWithoutGuessingFromVersionMetadataOrRowLength() {
        val states = "9,10001,l,pwi,uid,1.5,0,0,0\n9,10001,l,st,1000,2000,3000,4000,5000,6000"
        for (metadata in listOf("", "9,0,i,vers,16,177,8.1.0,8.1.0\n", "9,0,i,vers\n")) {
            val oreo = BatteryStatsParser.parseCheckin(metadata + states, sdkInt = 27).apps.single()
            assertEquals(5000L, oreo.backgroundTimeMs)
            assertEquals(6000L, oreo.cachedTimeMs)
            val pie = BatteryStatsParser.parseCheckin(metadata + states, sdkInt = 28).apps.single()
            assertEquals(4000L, pie.backgroundTimeMs)
            assertNull("A truncated modern cached field stays unavailable", pie.cachedTimeMs)
        }
    }
    @Test fun mappingsCanFollowUsageAndPreserveSharedIdentities() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,10001,l,pwi,uid,3.0,0,0,0
            9,0,i,uid,10001,example.one
            9,0,i,uid,10001,example.two
        """.trimIndent())
        assertEquals(listOf("example.one", "example.two"), snapshot.apps.single().packages)
        assertTrue(snapshot.apps.single().packageName.contains("10001"))
    }
    @Test fun secondaryProfileUsesAppIdMappingWithoutLosingUid() {
        val snapshot = BatteryStatsParser.parseCheckin("9,0,i,uid,10001,example.app\n9,110001,l,pwi,uid,1.0,0,0,0")
        assertEquals(110001, snapshot.apps.single().uid)
        assertEquals(listOf("example.app"), snapshot.apps.single().packages)
    }
    @Test fun packagePrefixesDoNotDetermineWhetherAnUidIsAnApplication() {
        assertTrue(BatteryStatsParser.isUserApp(10001, listOf("com.google.android.gm")))
        assertFalse(BatteryStatsParser.isUserApp(1000, listOf("android")))
    }
    @Test fun invalidEnergyIsUnavailableInsteadOfNaNOrZero() {
        for (value in listOf("NaN", "Infinity", "-1", "broken")) {
            val snapshot = BatteryStatsParser.parseCheckin("9,10001,l,pwi,uid,$value,0,0,0")
            assertTrue("Invalid estimate $value must not create a ranked row", snapshot.apps.isEmpty())
        }
    }
    @Test fun alarmRecordIsAWakeupCountNotAZeroDuration() {
        val alarm = BatteryStatsParser.parseCheckin("9,10001,l,wua,tag,8").alarms.single()
        assertEquals(8, alarm.wakeups)
        assertNull(alarm.totalTimeMs)
    }
    @Test fun wakeupReasonsAndKernelWakelocksKeepCheckinTimeBeforeCount() {
        val raw = """
            9,0,l,wr,abort_suspend_"quoted",12345,7
            9,0,l,wr,rtc_alarm,0,0
            9,0,l,kwl,"PowerManagerService",7654,2
            9,0,l,kwl,eventpoll,400,3
        """.trimIndent()
        val snapshot = BatteryStatsParser.parseCheckin(raw)
        val reason = snapshot.wakeupReasons.single { it.name == "abort_suspend_\"quoted\"" }
        assertEquals(12345L, reason.totalTimeMs)
        assertEquals(7, reason.count)
        val zero = snapshot.wakeupReasons.single { it.name == "rtc_alarm" }
        assertEquals(0L, zero.totalTimeMs)
        assertEquals(0, zero.count)
        assertEquals(listOf("PowerManagerService", "eventpoll"), snapshot.kernelWakelocks.map { it.name })
        assertEquals(listOf(7654L, 400L), snapshot.kernelWakelocks.map { it.totalTimeMs })
        assertEquals(listOf(2, 3), snapshot.kernelWakelocks.map { it.count })
        assertTrue(snapshot.reportedTags.containsAll(listOf("wr", "kwl")))
        assertEquals(0, snapshot.rejectedRecords)
        assertEquals(
            snapshot.copy(capturedAt = 0),
            BatteryStatsParser.parseCheckin(raw.lineSequence().constrainOnce()).copy(capturedAt = 0),
        )
    }
    @Test fun malformedWakeupReasonsAndKernelWakelocksAreRejected() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,0,l,wr
            9,0,l,wr,short,123
            9,0,l,wr,unknown_time,unknown,2
            9,0,l,wr,negative_time,-1,2
            9,0,l,wr,unknown_count,123,unknown
            9,0,l,wr,negative_count,123,-1
            9,0,l,wr,oversized_count,123,2147483648
            9,0,l,kwl,short,123
            9,0,l,kwl,invalid,unknown,2
        """.trimIndent())
        assertTrue(snapshot.wakeupReasons.isEmpty())
        assertTrue(snapshot.kernelWakelocks.isEmpty())
        assertEquals(9, snapshot.rejectedRecords)
        assertTrue(snapshot.reportedTags.containsAll(listOf("wr", "kwl")))
    }
    @Test fun fullPartialAndWindowWakelocksRemainDistinct() {
        val snapshot = BatteryStatsParser.parseCheckin("9,10001,l,wl,tag,100,f,2,0,60,100,200,p,3,0,100,200,50,bp,1,0,50,50,300,w,4,0,100,300")
        assertEquals(3, snapshot.wakelocks.size)
        assertEquals(100L, snapshot.wakelocks.single { it.type == BatteryStatsParser.WakelockType.FULL }.totalTimeMs)
        assertEquals(200L, snapshot.wakelocks.single { it.type == BatteryStatsParser.WakelockType.PARTIAL }.totalTimeMs)
        assertEquals(300L, snapshot.wakelocks.single { it.type == BatteryStatsParser.WakelockType.WINDOW }.totalTimeMs)
    }
    @Test fun missingPowerStateIsNotAnOffScreenOrAnEmptyBattery() {
        val power = BatteryStatsParser.parsePowerManager("Wake Locks: size=0")
        assertNull(power.isScreenOn)
        assertNull(power.batteryLevel)
    }
    @Test fun pluggedDoesNotMeanChargingAndBooleansAreParsedIndependently() {
        val power = BatteryStatsParser.parsePowerManager("mIsPowered=true")
        assertFalse(power.batteryStatus.equals("Charging", ignoreCase = true))
        val idle = BatteryStatsParser.parseDeviceIdle("mDeepEnabled=false mLightEnabled=true")
        assertEquals(false, idle.deepEnabled)
        assertEquals(true, idle.lightEnabled)
    }
    @Test fun coreWindowMustHaveValidEpochAndComparableDurations() {
        val good = BatteryStatsParser.parseCheckin("9,0,l,bt,2,60000,50000,100000,80000,1700000000000,30000,20000,4000,3800000,3900000,10000")
        assertTrue(good.hasValidWindow)
        assertEquals(1700000000000L, good.startedAt)
        assertEquals(30000L, good.screenOffTimeMs)
        assertFalse(BatteryStatsParser.parseCheckin("9,0,l,bt,2,60000").hasValidWindow)
        assertFalse(BatteryStatsParser.parseCheckin("9,0,l,bt,2,100,200,100,200,1700000000000").hasValidWindow)
    }
    @Test fun unsupportedComponentAndActivityDoNotBecomeZero() {
        val app = BatteryStatsParser.parseCheckin("9,10001,l,pwi,uid,0,0,0,0").apps.single()
        assertEquals(0.0, app.powerMah, 0.0)
        assertNull(app.cpuTimeMs)
        assertNull(app.cpuPowerMah)
        assertNull(app.foregroundTimeMs)
    }
    @Test fun duplicatePowerRowsDoNotDoubleCountAndFrequencyStatesAreNotSummedTwice() {
        val s = BatteryStatsParser.parseCheckin("""
            9,10001,l,pwi,uid,1.5,0,0,0
            9,10001,l,pwi,uid,1.5,0,0,0
            9,0,l,gcf,100000,200000
            9,10001,l,ctf,A,2,100,200,50,100
            9,10001,l,ctf,T,2,50,100,20,40
        """.trimIndent())
        assertEquals(1.5, s.apps.single().powerMah, 0.0)
        assertEquals(1, s.rejectedRecords)
        assertEquals(listOf(100L, 200L), s.cpuFrequency.map { it.timeMs })
    }
    @Test fun wrongVersionOtherWindowsAndUnclosedQuotesAreRejected() {
        val s = BatteryStatsParser.parseCheckin("8,10001,l,pwi,uid,10\n9,10001,u,pwi,uid,20\n9,10001,l,jb,\"unfinished,100,2")
        assertTrue(s.apps.isEmpty())
        assertTrue(s.jobs.isEmpty())
        assertEquals(1, s.rejectedRecords)
        assertFalse(s.hasValidWindow)
    }

    @Test fun allRelevantPowerRowsRejectedFlagsAnOtherwiseValidWindow() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            $VALID_WINDOW
            9,10001,l,pwi,uid,NaN
            9,10002,l,pwi,uid,-1
            9,10003,l,pwi,uid
            9,broken,l,pwi,uid,1.0
        """.trimIndent())
        assertTrue(snapshot.hasValidWindow)
        assertTrue(snapshot.apps.isEmpty())
        assertEquals(4, snapshot.appPowerRecords)
        assertEquals(4, snapshot.rejectedAppPowerRecords)
        assertTrue(snapshot.hasOnlyRejectedAppPowerRecords)
    }
    @Test fun validWindowWithoutAppPowerRowsIsGenuinelyEmpty() {
        val snapshot = BatteryStatsParser.parseCheckin(VALID_WINDOW)
        assertTrue(snapshot.hasValidWindow)
        assertTrue(snapshot.apps.isEmpty())
        assertEquals(0, snapshot.appPowerRecords)
        assertEquals(0, snapshot.rejectedAppPowerRecords)
        assertFalse(snapshot.hasOnlyRejectedAppPowerRecords)
    }
    @Test fun unrelatedRejectedRecordsDoNotFlagAppPowerData() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            $VALID_WINDOW
            9,0,l,pwi,screen,NaN
            9,10001,l,jb,job,broken,2
            9,10001,u,pwi,uid,NaN
        """.trimIndent())
        assertTrue(snapshot.hasValidWindow)
        assertTrue(snapshot.apps.isEmpty())
        assertEquals(2, snapshot.rejectedRecords)
        assertEquals(0, snapshot.appPowerRecords)
        assertEquals(0, snapshot.rejectedAppPowerRecords)
        assertFalse(snapshot.hasOnlyRejectedAppPowerRecords)
    }
    @Test fun partialAndDuplicateRejectionKeepAcceptedAppPowerRows() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            $VALID_WINDOW
            9,10001,l,pwi,uid,NaN
            9,10002,l,pwi,uid,0
            9,10002,l,pwi,uid,2.0
        """.trimIndent())
        assertTrue(snapshot.hasValidWindow)
        assertEquals(10002, snapshot.apps.single().uid)
        assertEquals(0.0, snapshot.apps.single().powerMah, 0.0)
        assertEquals(3, snapshot.appPowerRecords)
        assertEquals(2, snapshot.rejectedAppPowerRecords)
        assertFalse(snapshot.hasOnlyRejectedAppPowerRecords)
    }

    private companion object {
        const val VALID_WINDOW = "9,0,l,bt,2,60000,50000,100000,80000,1700000000000,30000,20000,4000,3800000,3900000,10000"
    }

    private val streamingFixtures = listOf(
        "9,0,l,pws,0.75\n9,0,l,dc,0,0,120,0",
        "9,0,l,dc,0,0,1e300,1e300",
        """
            9,10001,l,jb,"job,with,commas",12345,7,4321,3
            9,10001,l,sy,authority,7654,2,1234,1
        """.trimIndent(),
        "9,0,l,m," + List(21) { "0" }.joinToString(","),
        """
            9,0,l,gble,100,20,2,1,30
            9,10001,l,ble,1000,2000,20,1,3000
            9,10001,l,blem,120,4,2,140,10,3,1,30,10,20,10
        """.trimIndent(),
        """
            9,0,i,uid,10001,example.app
            9,10001,l,pwi,uid,1.5,0,0.5,2.0
            9,10001,l,cpu,100,200,0
            9,10001,l,fg,1500,2
            9,10001,l,fgs,2000,1
            9,10001,l,st,1000,2000,3000,4000,5000,6000,7000
            9,10001,l,nt,10,20,30,40,1,2,3,4,2000000,5,60,70
        """.trimIndent(),
        """
            9,10001,l,pwi,uid,3.0,0,0,0
            9,0,i,uid,10001,example.one
            9,0,i,uid,10001,example.two
        """.trimIndent(),
        "9,0,i,uid,10001,example.app\n9,110001,l,pwi,uid,1.0,0,0,0",
        "9,10001,l,wua,tag,8",
        "9,10001,l,wl,tag,100,f,2,0,60,100,200,p,3,0,100,200,50,bp,1,0,50,50,300,w,4,0,100,300",
        "9,0,l,bt,2,60000,50000,100000,80000,1700000000000,30000,20000,4000,3800000,3900000,10000",
        "9,0,l,bt,2,60000",
        "9,0,l,bt,2,100,200,100,200,1700000000000",
        "9,10001,l,pwi,uid,0,0,0,0",
        """
            9,10001,l,pwi,uid,1.5,0,0,0
            9,10001,l,pwi,uid,1.5,0,0,0
            9,0,l,gcf,100000,200000
            9,10001,l,ctf,A,2,100,200,50,100
            9,10001,l,ctf,T,2,50,100,20,40
        """.trimIndent(),
        "8,10001,l,pwi,uid,10\n9,10001,u,pwi,uid,20\n9,10001,l,jb,\"unfinished,100,2",
        // A real "9,h," history line: dropped by the `startsWith("9,h,")` filter itself, not by
        // accident (an earlier fixture here mistakenly had an extra leading field, so it was only
        // skipped because "h" isn't a recognized record type — not because it looked like history).
        "9,h,1234:B|100,i,uid,10001,example.app\n9,10001,l,pwi,uid,1.0,0,0,0",
    )

    @Test fun streamingAndStringParsesAgreeOnEveryFixture() {
        streamingFixtures.forEach { raw ->
            // capturedAt defaults to the wall-clock time of each call; normalize it before comparing.
            val fromString = BatteryStatsParser.parseCheckin(raw).copy(capturedAt = 0)
            val fromSequence = BatteryStatsParser.parseCheckin(raw.lineSequence()).copy(capturedAt = 0)
            assertEquals("Mismatch for fixture: $raw", fromString, fromSequence)
        }
    }
}
