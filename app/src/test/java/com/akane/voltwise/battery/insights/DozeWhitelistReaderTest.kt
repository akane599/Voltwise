package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.actions.PrivilegedCommand
import com.akane.voltwise.battery.shizuku.ShizukuBridge
import com.akane.voltwise.battery.util.CommandOutput
import com.akane.voltwise.battery.util.ShellRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DozeWhitelistReaderTest {
    @Test fun noneAndAdbMeanUnknownWithoutExecutingACommand() = runTest {
        for (mode in listOf(ShellRunner.Mode.NONE, ShellRunner.Mode.ADB)) {
            val shell = ShellRunner(
                probeMode = { mode }, runShizuku = { _, _, _ -> error("Must not execute") },
                runRoot = { _, _ -> error("Must not execute") },
                shizukuRunning = { error("Must not probe a fallback") }, elapsedMs = { 0L },
            )
            assertNull("$mode cannot establish whitelist membership", readUserDozeWhitelist(shell))
        }
    }

    @Test fun shizukuErrorMeansUnknown() = runTest {
        assertNull(readUserDozeWhitelist(shizuku(ShizukuBridge.RunResult.Error("command failed", ShizukuBridge.Failure.TRANSPORT))))
    }

    @Test fun unrecognizedOutputMeansUnknown() = runTest {
        assertNull(readUserDozeWhitelist(shizuku(ShizukuBridge.RunResult.Success("garbage"))))
    }

    @Test fun mixedWhitelistContainsOnlyUserEntries() = runTest {
        val output = "system,android,1000\nuser,com.example.app,10123\nsystem-excidle,com.google.android.gms,10050"
        assertEquals(setOf("com.example.app"), readUserDozeWhitelist(shizuku(ShizukuBridge.RunResult.Success(output))))
    }

    @Test fun onlySystemEntriesMeanKnownEmptyUserWhitelist() = runTest {
        val output = "system,android,1000\nsystem-excidle,com.google.android.gms,10050"
        assertEquals(emptySet<String>(), readUserDozeWhitelist(shizuku(ShizukuBridge.RunResult.Success(output))))
    }

    @Test fun rootUsesTheSameReadbackAndKeepsErrorsUnknown() = runTest {
        for ((output, expected) in listOf(
            CommandOutput.Result("user,com.example.app,10123") to setOf("com.example.app"),
            CommandOutput.Result(error = "command failed") to null,
        )) {
            val shell = ShellRunner(
                probeMode = { ShellRunner.Mode.ROOT }, runShizuku = { _, _, _ -> error("No fallback") },
                runRoot = { command, _ ->
                    assertEquals(PrivilegedCommand.ListDozeWhitelist.argv.joinToString(" "), command)
                    output
                }, shizukuRunning = { false }, elapsedMs = { 0L },
            )
            assertEquals(expected, readUserDozeWhitelist(shell))
        }
    }

    private fun shizuku(result: ShizukuBridge.RunResult) = ShellRunner(
        probeMode = { ShellRunner.Mode.SHIZUKU },
        runShizuku = { command, _, _ ->
            assertEquals(PrivilegedCommand.ListDozeWhitelist.argv.joinToString(" "), command)
            result
        }, shizukuRunning = { true }, elapsedMs = { 0L },
        runRoot = { _, _ -> error("No fallback") },
    )
}
