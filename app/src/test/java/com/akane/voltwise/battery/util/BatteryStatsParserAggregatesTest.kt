package com.akane.voltwise.battery.util

import org.junit.Assert.*
import org.junit.Test

/** Synthetic checkin records, not measurements from a device. */
class BatteryStatsParserAggregatesTest {
    @Test fun aggregatesSumParsedRowsForEachUidAndOnlyPartialWakelocks() {
        val snapshot = BatteryStatsParser.parseCheckin(FIXTURE)
        val first = snapshot.apps.single { it.uid == 10001 }
        assertEquals(10L, first.wakeupAlarmCount)
        assertEquals(8L, first.partialWakelockCount)
        assertEquals(500L, first.partialWakelockTimeMs)
        assertEquals(90L, first.partialWakelockBgTimeMs)
        assertEquals(24L, first.jobCount)
        assertEquals(1000L, first.jobTimeMs)
        assertEquals(5L, first.syncCount)
        assertEquals(1000L, first.topTimeMs)
        assertEquals(999L, first.wakeLockTimeMs)

        val second = snapshot.apps.single { it.uid == 10002 }
        assertEquals(0L, second.wakeupAlarmCount)
        assertEquals(0L, second.partialWakelockCount)
        assertEquals(40L, second.partialWakelockTimeMs)
        assertNull(second.partialWakelockBgTimeMs)
        assertNull(second.jobCount)
        assertNull(second.jobTimeMs)
        assertEquals(0L, second.syncCount)
        assertEquals(2000L, second.topTimeMs)
        assertNull(second.wakeLockTimeMs)

        assertEquals("kernel_lock", snapshot.kernelWakelocks.single().name)
        assertEquals(17, snapshot.kernelWakelocks.single().count)
        assertEquals(4567L, snapshot.kernelWakelocks.single().totalTimeMs)
        assertEquals("alarm_rtc", snapshot.wakeupReasons.single().name)
        assertEquals(19, snapshot.wakeupReasons.single().count)
        assertEquals(890L, snapshot.wakeupReasons.single().totalTimeMs)
        assertEquals(0, snapshot.rejectedRecords)
        assertTrue(snapshot.reportedTags.containsAll(listOf("wl", "wua", "jb", "sy", "kwl", "wr")))
        assertEquals(
            snapshot.copy(capturedAt = 0),
            BatteryStatsParser.parseCheckin(FIXTURE.lineSequence().constrainOnce()).copy(capturedAt = 0),
        )
    }

    @Test fun absentSectionsAndDefaultModelsKeepAggregatesNull() {
        val parsed = BatteryStatsParser.parseCheckin("9,10001,l,pwi,uid,1.0").apps.single()
        val default = BatteryStatsParser.AppPowerStats(10001, "example.app", 1.0)
        for (app in listOf(parsed, default)) {
            assertNull(app.wakeupAlarmCount)
            assertNull(app.partialWakelockCount)
            assertNull(app.partialWakelockTimeMs)
            assertNull(app.partialWakelockBgTimeMs)
            assertNull(app.jobCount)
            assertNull(app.jobTimeMs)
            assertNull(app.syncCount)
            assertNull(app.topTimeMs)
        }
        assertTrue(BatteryStatsParser.FullSnapshot().wakeupReasons.isEmpty())
    }

    @Test fun fullAndWindowLocksDoNotReportPartialActivity() {
        val app = BatteryStatsParser.parseCheckin("""
            9,10001,l,pwi,uid,1.0
            9,10001,l,wl,full,100,f,2,0,60,100,300,w,4,0,100,300
        """.trimIndent()).apps.single()
        assertNull(app.partialWakelockCount)
        assertNull(app.partialWakelockTimeMs)
        assertNull(app.partialWakelockBgTimeMs)
    }

    @Test fun missingBackgroundOnAnyPartialLockDoesNotProduceAnIncompleteTotal() {
        val app = BatteryStatsParser.parseCheckin("""
            9,10001,l,pwi,uid,1.0
            9,10001,l,wl,reported,200,p,3,0,100,200,0,bp,0,0,0,0
            9,10001,l,wl,unreported,300,p,5,0,200,300
        """.trimIndent()).apps.single()
        assertEquals(8L, app.partialWakelockCount)
        assertEquals(500L, app.partialWakelockTimeMs)
        assertNull(app.partialWakelockBgTimeMs)
    }

    @Test fun reportedZeroBackgroundRemainsZero() {
        val app = BatteryStatsParser.parseCheckin("""
            9,10001,l,pwi,uid,1.0
            9,10001,l,wl,reported,200,p,3,0,100,200,0,bp,0,0,0,0
        """.trimIndent()).apps.single()
        assertEquals(0L, app.partialWakelockBgTimeMs)
    }

    @Test fun malformedRowsAreRejectedAndNeverInventAggregateZeroes() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,10001,l,pwi,uid,1.0
            9,10001,l,wl,bad,unknown,p,3,0,0,0
            9,10001,l,wua,bad,unknown
            9,10001,l,jb,bad,unknown,2,0,0
            9,10001,l,sy,bad,100,unknown,0,0
            9,0,l,kwl,bad,unknown,1
            9,0,l,wr,bad,100,unknown
        """.trimIndent())
        assertEquals(6, snapshot.rejectedRecords)
        val app = snapshot.apps.single()
        assertNull(app.wakeupAlarmCount)
        assertNull(app.partialWakelockCount)
        assertNull(app.partialWakelockTimeMs)
        assertNull(app.partialWakelockBgTimeMs)
        assertNull(app.jobCount)
        assertNull(app.jobTimeMs)
        assertNull(app.syncCount)
    }

    @Test fun aggregateCountsUseLongAndOverflowingDurationsStayUnavailable() {
        val app = BatteryStatsParser.parseCheckin("""
            9,10001,l,pwi,uid,1.0
            9,10001,l,wua,one,2147483647
            9,10001,l,wua,two,2147483647
            9,10001,l,wl,one,9223372036854775807,p,2147483647,0,0,0,9223372036854775807,bp,0,0,0,0
            9,10001,l,wl,two,1,p,2147483647,0,0,0,1,bp,0,0,0,0
            9,10001,l,jb,one,9223372036854775807,2147483647,0,0
            9,10001,l,jb,two,1,2147483647,0,0
            9,10001,l,sy,one,1,2147483647,0,0
            9,10001,l,sy,two,1,2147483647,0,0
        """.trimIndent()).apps.single()
        assertEquals(4294967294L, app.wakeupAlarmCount)
        assertEquals(4294967294L, app.partialWakelockCount)
        assertEquals(4294967294L, app.jobCount)
        assertEquals(4294967294L, app.syncCount)
        assertNull(app.partialWakelockTimeMs)
        assertNull(app.partialWakelockBgTimeMs)
        assertNull(app.jobTimeMs)
    }

    private companion object {
        val FIXTURE = """
            9,10001,l,wl,first_tag,100,f,2,0,60,100,200,p,3,0,100,200,50,bp,1,0,50,50,300,w,4,0,100,300
            9,10001,l,wl,second_tag,300,p,5,0,200,300,40,bp,2,0,30,40
            9,10002,l,wl,other_tag,500,f,9,0,100,500,40,p,0,0,40,40,600,w,8,0,100,600
            9,10001,l,wua,alarm_one,4
            9,10001,l,wua,alarm_two,6
            9,10002,l,wua,zero_alarm,0
            9,10001,l,jb,"job,one",300,11,100,3
            9,10001,l,jb,job_two,700,13,200,4
            9,10001,l,sy,authority_one,100,2,10,1
            9,10001,l,sy,authority_two,200,3,20,1
            9,10002,l,sy,zero_authority,0,0,0,0
            9,10001,l,st,1000,2000,3000,4000,5000,6000,7000
            9,10002,l,st,2000,3000,4000,5000,6000,7000,8000
            9,10001,l,awl,999
            9,0,l,kwl,"kernel_lock",4567,17
            9,0,l,wr,alarm_rtc,890,19
            9,10001,l,pwi,uid,2.0
            9,10002,l,pwi,uid,1.0
            9,0,i,uid,10001,example.one
            9,0,i,uid,10002,example.two
        """.trimIndent()
    }
}
