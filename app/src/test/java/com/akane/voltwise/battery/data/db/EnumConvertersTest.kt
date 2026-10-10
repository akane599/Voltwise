package com.akane.voltwise.battery.data.db

import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageStatus
import org.junit.Assert.*
import org.junit.Test

class EnumConvertersTest {
    private val converters = EnumConverters()

    @Test fun everyKnownNameRoundTrips() {
        InsightFindingStatus.entries.forEach { assertEquals(it, converters.toInsightFindingStatus(converters.fromInsightFindingStatus(it))) }
        InsightActionStatus.entries.forEach { assertEquals(it, converters.toInsightActionStatus(converters.fromInsightActionStatus(it))) }
        SessionType.entries.forEach { assertEquals(it, converters.toSessionType(converters.fromSessionType(it))) }
        AppSnapshotKind.entries.forEach { assertEquals(it, converters.toSnapshotKind(converters.fromSnapshotKind(it))) }
        AppUsageStatus.entries.forEach { assertEquals(it, converters.toAppUsageStatus(converters.fromAppUsageStatus(it))) }
        AppUsageBasis.entries.forEach { assertEquals(it, converters.toAppUsageBasis(converters.fromAppUsageBasis(it))) }
    }

    @Test fun unknownNamesInNullableColumnsReadAsNull() {
        listOf("SOMETHING_NEW", "ready", "", " READY").forEach { junk ->
            assertNull(junk, converters.toAppUsageStatus(junk))
            assertNull(junk, converters.toAppUsageBasis(junk))
        }
        assertNull(converters.toAppUsageStatus(null))
        assertNull(converters.toAppUsageBasis(null))
    }

    @Test fun unknownInsightStatusesNeverBecomeActiveOrApplied() {
        listOf("FUTURE", "active", "applied", "", " APPLIED").forEach { junk ->
            assertEquals(InsightFindingStatus.RESOLVED, converters.toInsightFindingStatus(junk))
            assertEquals(InsightActionStatus.UNKNOWN, converters.toInsightActionStatus(junk))
        }
        assertNull(converters.toInsightFindingStatus(null))
        assertNull(converters.toInsightActionStatus(null))
        assertNull(converters.fromInsightFindingStatus(null))
        assertNull(converters.fromInsightActionStatus(null))
    }

    @Test fun unknownNamesInNotNullColumnsReadAsTheSafeFallback() {
        assertEquals(SessionType.UNKNOWN, converters.toSessionType("HYBRID"))
        assertEquals(SessionType.UNKNOWN, converters.toSessionType("discharge"))
        assertEquals("Never a protected baseline", AppSnapshotKind.END, converters.toSnapshotKind("MIDPOINT"))
        assertNull(converters.toSessionType(null))
        assertNull(converters.toSnapshotKind(null))
    }
}
