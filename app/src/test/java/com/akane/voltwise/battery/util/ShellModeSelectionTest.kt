package com.akane.voltwise.battery.util

import com.akane.voltwise.battery.shizuku.ShizukuBridge
import com.akane.voltwise.battery.util.ShellRunner.Mode
import com.akane.voltwise.battery.util.ShellRunner.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellModeSelectionTest {
    @Test fun typedAccessFailureInvalidatesCachedShizukuAndTheNextProbeUsesFallbackOrder() = runTest {
        for (reason in listOf(ShizukuBridge.Failure.NOT_RUNNING, ShizukuBridge.Failure.NO_PERMISSION)) {
            for (fallback in listOf(Mode.ROOT, Mode.ADB)) {
                var running = true
                var authorized = true
                val probes = mutableListOf<String>()
                val runner = ShellRunner(
                    probeMode = {
                        selectShellMode(
                            shizukuRunning = { probes += "shizuku"; running },
                            shizukuAuthorized = { authorized },
                            rootAvailable = { probes += "root"; fallback == Mode.ROOT },
                            adbAvailable = { probes += "adb"; true },
                        )
                    },
                    runShizuku = { _, _, _ -> ShizukuBridge.RunResult.Error("original reason", reason) },
                    shizukuRunning = { running },
                    elapsedMs = { 0L },
                )
                assertEquals(Mode.SHIZUKU, runner.detectMode())
                running = reason != ShizukuBridge.Failure.NOT_RUNNING
                authorized = false
                assertEquals(Outcome.NoAccess(Mode.SHIZUKU, "original reason"), runner.exec("dump"))
                assertEquals("No same-command fallback", listOf("shizuku"), probes)
                assertEquals("original reason", runner.lastError.value)
                assertEquals(fallback, runner.detectMode())
                val expected = if (fallback == Mode.ROOT) listOf("shizuku", "shizuku", "root")
                    else listOf("shizuku", "shizuku", "root", "adb")
                assertEquals(expected, probes)
                assertEquals(fallback, runner.access.value)
            }
        }
    }

    @Test fun commandAndHelperFailuresKeepCachedShizukuEvenWithAccessLikeMessages() = runTest {
        for (reason in listOf(
            ShizukuBridge.Failure.COMMAND,
            ShizukuBridge.Failure.BIND_FAILED,
            ShizukuBridge.Failure.TRANSPORT,
        )) {
            var probes = 0
            var commands = 0
            val runner = ShellRunner(
                probeMode = { probes++; Mode.SHIZUKU },
                runShizuku = { _, _, _ ->
                    commands++
                    ShizukuBridge.RunResult.Error("not running / permission denied", reason)
                },
                shizukuRunning = { false },
                elapsedMs = { 0L },
            )
            repeat(2) {
                assertEquals(Outcome.Failure(Mode.SHIZUKU, "not running / permission denied"), runner.exec("dump"))
            }
            assertEquals(1, probes)
            assertEquals(2, commands)
            assertEquals(Mode.SHIZUKU, runner.access.value)
            assertEquals("not running / permission denied", runner.lastError.value)
        }
    }

    @Test fun runningAuthorizedShizukuWinsWithoutProbingRootOrAdb() = runTest {
        assertEquals(
            Mode.SHIZUKU,
            selectShellMode(
                shizukuRunning = { true },
                shizukuAuthorized = { true },
                rootAvailable = { error("Root must not be probed") },
                adbAvailable = { error("ADB must not be probed") },
            ),
        )
    }

    @Test fun runningDeniedShizukuFallsBackToRootBeforeAdb() = runTest {
        assertEquals(
            Mode.ROOT,
            selectShellMode(
                shizukuRunning = { true },
                shizukuAuthorized = { false },
                rootAvailable = { true },
                adbAvailable = { error("ADB must not be probed when root is available") },
            ),
        )
    }

    @Test fun runningDeniedShizukuFallsBackToAdbWhenRootIsUnavailable() = runTest {
        val probes = mutableListOf<String>()
        assertEquals(
            Mode.ADB,
            selectShellMode(
                shizukuRunning = { true },
                shizukuAuthorized = { false },
                rootAvailable = { probes += "root"; false },
                adbAvailable = { probes += "adb"; true },
            ),
        )
        assertEquals(listOf("root", "adb"), probes)
    }

    @Test fun stoppedShizukuFallsBackToRootWithoutCheckingShizukuPermission() = runTest {
        assertEquals(
            Mode.ROOT,
            selectShellMode(
                shizukuRunning = { false },
                shizukuAuthorized = { error("Stopped Shizuku must not be checked for permission") },
                rootAvailable = { true },
                adbAvailable = { error("ADB must not be probed when root is available") },
            ),
        )
    }

    @Test fun stoppedShizukuFallsBackToAdbWhenRootIsUnavailable() = runTest {
        assertEquals(
            Mode.ADB,
            selectShellMode(
                shizukuRunning = { false },
                shizukuAuthorized = { true },
                rootAvailable = { false },
                adbAvailable = { true },
            ),
        )
    }

    @Test fun deniedShizukuWithNoOtherBackendReturnsNone() = runTest {
        assertEquals(
            Mode.NONE,
            selectShellMode(
                shizukuRunning = { true },
                shizukuAuthorized = { false },
                rootAvailable = { false },
                adbAvailable = { false },
            ),
        )
    }

    @Test fun noAvailableBackendReturnsNone() = runTest {
        assertEquals(
            Mode.NONE,
            selectShellMode(
                shizukuRunning = { false },
                shizukuAuthorized = { false },
                rootAvailable = { false },
                adbAvailable = { false },
            ),
        )
    }
}
