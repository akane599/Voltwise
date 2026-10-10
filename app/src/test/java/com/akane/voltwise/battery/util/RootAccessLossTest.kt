package com.akane.voltwise.battery.util

import com.akane.voltwise.battery.util.ShellRunner.Mode
import com.akane.voltwise.battery.util.ShellRunner.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RootAccessLossTest {
    @Test fun deniedRootInvalidatesCacheWithoutRetryingTheFailedCommand() = runTest {
        for (fallback in listOf(Mode.SHIZUKU, Mode.ADB, Mode.NONE)) {
            var probes = 0
            var commands = 0
            val runner = ShellRunner(
                probeMode = { if (++probes == 1) Mode.ROOT else fallback },
                runShizuku = { _, _, _ -> error("Must not retry through Shizuku") },
                shizukuRunning = { false },
                elapsedMs = { 0L },
                runRoot = { _, _ ->
                    commands++
                    CommandOutput.run(listOf("sh", "-c", "printf 'su: permission denied\\n' >&2; exit 1"), 1000)
                },
            )
            assertEquals(Mode.ROOT, runner.detectMode())
            assertEquals(Outcome.NoAccess(Mode.ROOT, "Root access unavailable"), runner.exec("dump"))
            assertEquals("No same-command fallback", 1, probes)
            assertEquals(1, commands)
            assertEquals(Mode.NONE, runner.access.value)
            assertEquals("Root access unavailable", runner.lastError.value)
            assertEquals("Must probe before the 10-second TTL", fallback, runner.detectMode())
            assertEquals(2, probes)
            assertEquals(fallback, runner.access.value)
        }
    }

    @Test fun ordinaryRootCommandFailureKeepsTheCachedMode() = runTest {
        var probes = 0
        var commands = 0
        val runner = ShellRunner(
            probeMode = { probes++; Mode.ROOT },
            runShizuku = { _, _, _ -> error("Must not retry through Shizuku") },
            shizukuRunning = { false },
            elapsedMs = { 0L },
            runRoot = { _, timeoutMs ->
                assertEquals(25_000L, timeoutMs)
                commands++
                CommandOutput.run(listOf("sh", "-c", "printf 'dumpsys: permission denied\\n' >&2; exit 1"), 1000)
            },
        )
        repeat(2) {
            assertEquals(Outcome.Failure(Mode.ROOT, "Command exited with status 1"), runner.exec("dump"))
        }
        assertEquals(1, probes)
        assertEquals(2, commands)
        assertEquals(Mode.ROOT, runner.access.value)
    }

    @Test fun unavailableSuInvalidatesTheCache() = runTest {
        var probes = 0
        val runner = ShellRunner(
            probeMode = { if (++probes == 1) Mode.ROOT else Mode.NONE },
            runShizuku = { _, _, _ -> error("Must not retry through Shizuku") },
            shizukuRunning = { false },
            elapsedMs = { 0L },
            runRoot = { _, _ -> CommandOutput.run(listOf("/batstats-missing-su-SQ64"), 1000) },
        )
        assertEquals(Mode.ROOT, runner.detectMode())
        assertEquals(Outcome.NoAccess(Mode.ROOT, "Root access unavailable"), runner.exec("dump"))
        assertEquals(1, probes)
        assertEquals(Mode.NONE, runner.access.value)
        assertEquals(Mode.NONE, runner.detectMode())
        assertEquals(2, probes)
    }

    @Test fun modeCacheExpiresAtTenSeconds() = runTest {
        var now = 0L
        var probes = 0
        val runner = ShellRunner(
            probeMode = { if (++probes == 1) Mode.ROOT else Mode.ADB },
            runShizuku = { _, _, _ -> error("No command expected") },
            shizukuRunning = { false },
            elapsedMs = { now },
        )
        assertEquals(Mode.ROOT, runner.detectMode())
        now = 9_999L
        assertEquals(Mode.ROOT, runner.detectMode())
        assertEquals(1, probes)
        now = 10_000L
        assertEquals(Mode.ADB, runner.detectMode())
        assertEquals(2, probes)
    }
}
