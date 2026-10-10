package com.akane.voltwise.battery.util

import com.akane.voltwise.battery.apps.toAppUsageSnapshot
import org.junit.Assert.*
import org.junit.Test

/** Literal synthetic checkin rows; no device measurements. */
class BatteryStatsParserWakerQualityTest {
    private val app = "9,10001,l,pwi,uid,1.0"

    @Test fun rejectedConsumedWakersCannotCertifyAbsentBaselineNames() {
        val malformed = listOf(
            "9,0,l,kwl,worker,unknown,2", "9,0,l,wr,worker,100,unknown",
            "9,0,l,kwl,\"worker,100,2", "9,0,l,wr,\"worker,100,2",
            "9,broken,l,kwl,worker,100,2", "9,broken,l,wr,worker,100,2",
            "9,-1,l,kwl,worker,100,2", "9,2147483648,l,wr,worker,100,2",
            "9,0,,kwl,worker,100,2", "9,0,broken,wr,worker,100,2",
            "9,10001,broken,kwl,worker,100,2",
            "9,0,l,kwl", "9,0,l,wr,worker,100",
            "9,0,l,kwl,worker,-1,2", "9,0,l,wr,worker,100,2147483648",
            "9,0,l,kwl,worker,9223372036854775808,2",
            "9,0,l,wr,worker,Infinity,2",
        )
        val incorrectlyComplete = malformed.filter { row ->
            val parsed = BatteryStatsParser.parseCheckin("$app\n$row\n9,0,l,kwl,neighbor,40,3")
            assertTrue("Device quality must preserve accepted app coverage: $row", parsed.appMeasurementsComplete)
            assertEquals(1, parsed.apps.size)
            assertEquals("neighbor", parsed.kernelWakelocks.single().name)
            parsed.deviceWakersComplete || parsed.toAppUsageSnapshot().wakersComplete == true
        }
        assertEquals(emptyList<String>(), incorrectlyComplete)
    }

    @Test fun absentZeroOptionalAndUnconsumedRecordsKeepDeviceCoverage() {
        val harmless = listOf(
            "", "9,0,l,kwl,worker,0,0", "9,0,l,wr,worker,0,0",
            "9,10001,l,kwl,worker,0,0,NaN,NaN,NaN",
            "9,0,l,wr,worker,0,0,NaN", "9,10001,l,wr,worker,NaN,NaN",
            "9,10001,l,wr,\"unfinished", "9,10001,broken,wr,worker,NaN,NaN",
            "9,0,u,kwl,worker,NaN,NaN", "9,0,c,wr,\"unfinished",
            "9,0,i,kwl,\"unfinished", "9,0,l,gble,NaN", "9,0,l,pwi,screen,NaN",
            "9,10001,l,pr,worker,NaN", "9,10001,l,wua,alarm,NaN",
        )
        for (row in harmless) {
            val parsed = BatteryStatsParser.parseCheckin("$app\n$row")
            assertTrue(row, parsed.deviceWakersComplete)
            assertEquals(row, true, parsed.toAppUsageSnapshot().wakersComplete)
        }
    }

    @Test fun rejectedAppMetricsWithholdOnlyAppCoverage() {
        val parsed = BatteryStatsParser.parseCheckin("$app\n9,10001,l,fgs,NaN\n9,0,l,kwl,worker,40,3")
        assertFalse(parsed.appMeasurementsComplete)
        assertTrue(parsed.deviceWakersComplete)
        assertEquals(true, parsed.toAppUsageSnapshot().wakersComplete)
    }

    @Test fun selectionCapStillWithholdsCompletenessForCleanDeviceRecords() {
        val rows = (0..200).joinToString("\n") { "9,0,l,kwl,worker$it,100,1" }
        val parsed = BatteryStatsParser.parseCheckin("$app\n$rows")
        assertTrue(parsed.deviceWakersComplete)
        val snapshot = parsed.toAppUsageSnapshot()
        assertEquals(200, snapshot.deviceWakers.size)
        assertEquals(false, snapshot.wakersComplete)
    }

    @Test fun wakerQualityMatchesSinglePassSequenceParsing() {
        val raw = "$app\n9,0,l,kwl,\"unfinished\n9,0,l,wr,neighbor,40,3"
        assertEquals(BatteryStatsParser.parseCheckin(raw).copy(capturedAt = 0),
            BatteryStatsParser.parseCheckin(raw.lineSequence().constrainOnce()).copy(capturedAt = 0))
    }
}
