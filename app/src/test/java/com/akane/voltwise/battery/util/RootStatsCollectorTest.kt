package com.akane.voltwise.battery.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RootStatsCollectorTest {
    @Before fun clearCacheBeforeTest() = RootStatsCollector.invalidateRootCache()
    @After fun clearCacheAfterTest() = RootStatsCollector.invalidateRootCache()

    @Test fun timedOutProbeIsRetriedWithoutWaitingForCacheExpiry() = runTest {
        var probes = 0
        val probe: suspend (Long) -> CommandOutput.Result = {
            probes++
            if (probes == 1) CommandOutput.Result(
                error = "Command timed out", certainty = ExecutionCertainty.UNKNOWN,
            ) else CommandOutput.Result(output = "uid=0(root) gid=0(root)")
        }

        assertFalse(RootStatsCollector.isRootAvailable({ 1_000L }, probe))
        assertTrue("A timeout must not hide a subsequent root grant", RootStatsCollector.isRootAvailable({ 1_001L }, probe))
        assertEquals("The next call must probe again after a timeout", 2, probes)
    }

    @Test fun definiteDenialIsCachedForSixtySeconds() = runTest {
        var probes = 0
        val probe: suspend (Long) -> CommandOutput.Result = {
            probes++
            CommandOutput.Result(error = "Command exited with status 1", accessFailure = CommandOutput.AccessFailure.DENIED)
        }

        assertFalse(RootStatsCollector.isRootAvailable({ 1_000L }, probe))
        assertFalse(RootStatsCollector.isRootAvailable({ 60_999L }, probe))
        assertEquals("Explicit denial is reused within the cache lifetime", 1, probes)
        assertFalse(RootStatsCollector.isRootAvailable({ 61_000L }, probe))
        assertEquals("Explicit denial expires after sixty seconds", 2, probes)
    }
    @Test fun missingSuIsCached() = runTest {
        var probes = 0
        val probe: suspend (Long) -> CommandOutput.Result = {
            probes++
            CommandOutput.Result(error = "IOException", accessFailure = CommandOutput.AccessFailure.EXECUTABLE_UNAVAILABLE)
        }

        assertFalse(RootStatsCollector.isRootAvailable({ 1_000L }, probe))
        assertFalse(RootStatsCollector.isRootAvailable({ 1_001L }, probe))
        assertEquals("Missing su is a definite result", 1, probes)
    }

    @Test fun probeAllowsFifteenSecondsForTheGrantPromptAndCachesRoot() = runTest {
        var probes = 0
        val probe: suspend (Long) -> CommandOutput.Result = { timeoutMs ->
            probes++
            assertEquals("The root grant prompt needs more than four seconds", 15_000L, timeoutMs)
            CommandOutput.Result(output = "uid=0(root) gid=0(root)")
        }

        assertTrue(RootStatsCollector.isRootAvailable({ 1_000L }, probe))
        assertTrue(RootStatsCollector.isRootAvailable({ 1_001L }, probe))
        assertEquals("A confirmed root grant is reused", 1, probes)
    }

    @Test fun unclassifiedFailuresAndNonRootOutputAreNotCached() = runTest {
        val results = listOf(
            CommandOutput.Result(error = "Command output could not be read", certainty = ExecutionCertainty.UNKNOWN),
            CommandOutput.Result(error = "Command exited with status 1"),
            CommandOutput.Result(output = "uid=2000(shell) gid=2000(shell)"),
            CommandOutput.Result(output = "uid=01(not-root)"),
            CommandOutput.Result(output = "uid=0(root) gid=0(root)"),
        )
        var probes = 0
        val probe: suspend (Long) -> CommandOutput.Result = { results[probes++] }

        repeat(4) { assertFalse(RootStatsCollector.isRootAvailable({ 1_000L }, probe)) }
        assertTrue(RootStatsCollector.isRootAvailable({ 1_000L }, probe))
        assertEquals("Only definite access evidence may suppress another probe", 5, probes)
    }

    @Test fun concurrentCallersShareOneCompletedProbe() = runTest {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<CommandOutput.Result>()
        var probes = 0
        val probe: suspend (Long) -> CommandOutput.Result = {
            probes++
            started.complete(Unit)
            result.await()
        }
        val first = async { RootStatsCollector.isRootAvailable({ 1_000L }, probe) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            RootStatsCollector.isRootAvailable({ 1_000L }, probe)
        }

        assertEquals("Concurrent probes are serialized", 1, probes)
        result.complete(CommandOutput.Result(output = "uid=0(root) gid=0(root)"))
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals("Waiting callers reuse the definite result", 1, probes)
    }

    @Test fun designCapacityIsParsedFromABatteryUeventInAbiUnits() {
        val raw = "POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_CHARGE_FULL_DESIGN=4000000\nPOWER_SUPPLY_CHARGE_FULL=3600000"
        assertEquals(4_000_000L, RootStatsCollector.parseChargeFullDesignUah(raw))
    }

    @Test fun missingOrInvalidFieldsAreNotGuessed() {
        assertNull("Not a battery uevent", RootStatsCollector.parseChargeFullDesignUah("POWER_SUPPLY_TYPE=Mains\nPOWER_SUPPLY_CHARGE_FULL_DESIGN=4000000"))
        assertNull("No CHARGE_FULL_DESIGN field", RootStatsCollector.parseChargeFullDesignUah("POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_CHARGE_FULL=3600000"))
        assertNull("Out of the plausible ABI range", RootStatsCollector.parseChargeFullDesignUah("POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_CHARGE_FULL_DESIGN=0"))
        assertNull("A permission failure is not a value", RootStatsCollector.parseChargeFullDesignUah("Permission denied"))
    }
}
