package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.actions.PrivilegedCommand
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
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
            val diagnostics = mutableListOf<DiagnosticCode>()
            val shell = ShellRunner(
                probeMode = { mode }, runShizuku = { _, _, _ -> error("Must not execute") },
                runRoot = { _, _ -> error("Must not execute") },
                shizukuRunning = { error("Must not probe a fallback") }, elapsedMs = { 0L },
            )
            assertNull("$mode cannot establish whitelist membership", readUserDozeWhitelist(shell, diagnostics::add))
            assertEquals(emptyList<DiagnosticCode>(), diagnostics)
        }
    }

    @Test fun shizukuErrorMeansUnknownAndRecordsReadFailure() = runTest {
        val diagnostics = mutableListOf<DiagnosticCode>()
        val shell = shizuku(ShizukuBridge.RunResult.Error("command failed", ShizukuBridge.Failure.TRANSPORT))
        assertNull(readUserDozeWhitelist(shell, diagnostics::add))
        assertEquals(listOf(DiagnosticCode.DOZE_WHITELIST_READ_FAILED), diagnostics)
    }

    @Test fun unrecognizedOutputMeansUnknownAndRecordsInvalidFormat() = runTest {
        for (output in listOf("garbage", "vendor,com.example.app,10123", "")) {
            val diagnostics = mutableListOf<DiagnosticCode>()
            assertNull(readUserDozeWhitelist(shizuku(ShizukuBridge.RunResult.Success(output)), diagnostics::add))
            assertEquals(listOf(DiagnosticCode.DOZE_WHITELIST_FORMAT_INVALID), diagnostics)
        }
    }

    @Test fun mixedWhitelistContainsOnlyUserEntries() = runTest {
        val diagnostics = mutableListOf<DiagnosticCode>()
        val output = "system,android,1000\nuser,com.example.app,10123\nsystem-excidle,com.google.android.gms,10050"
        assertEquals(setOf("com.example.app"), readUserDozeWhitelist(shizuku(ShizukuBridge.RunResult.Success(output)), diagnostics::add))
        assertEquals(emptyList<DiagnosticCode>(), diagnostics)
    }

    @Test fun onlySystemEntriesMeanKnownEmptyUserWhitelist() = runTest {
        val diagnostics = mutableListOf<DiagnosticCode>()
        val output = "system,android,1000\nsystem-excidle,com.google.android.gms,10050"
        assertEquals(emptySet<String>(), readUserDozeWhitelist(shizuku(ShizukuBridge.RunResult.Success(output)), diagnostics::add))
        assertEquals(emptyList<DiagnosticCode>(), diagnostics)
    }

    @Test fun lostShizukuAccessMeansUnknownWithoutDiagnostics() = runTest {
        for (reason in listOf(ShizukuBridge.Failure.NOT_RUNNING, ShizukuBridge.Failure.NO_PERMISSION)) {
            val diagnostics = mutableListOf<DiagnosticCode>()
            assertNull(readUserDozeWhitelist(shizuku(ShizukuBridge.RunResult.Error("access lost", reason)), diagnostics::add))
            assertEquals(emptyList<DiagnosticCode>(), diagnostics)
        }
    }

    @Test fun shizukuAndroidRefusalMeansUnknownAndRecordsDozeReadFailure() = runTest {
        val diagnostics = mutableListOf<DiagnosticCode>()
        val shell = shizuku(ShizukuBridge.RunResult.Success("Permission Denial: whitelist requires privileged access"))
        assertNull(readUserDozeWhitelist(shell, diagnostics::add))
        assertEquals(listOf(DiagnosticCode.DOZE_WHITELIST_READ_FAILED), diagnostics)
    }

    @Test fun rootAndroidRefusalMeansUnknownAndRecordsDozeReadFailure() = runTest {
        val diagnostics = mutableListOf<DiagnosticCode>()
        val shell = ShellRunner(
            probeMode = { ShellRunner.Mode.ROOT }, runShizuku = { _, _, _ -> error("No fallback") },
            runRoot = { command, _ ->
                assertEquals(PrivilegedCommand.ListDozeWhitelist.argv.joinToString(" "), command)
                CommandOutput.Result("Permission Denial: whitelist requires privileged access")
            }, shizukuRunning = { false }, elapsedMs = { 0L },
        )
        assertNull(readUserDozeWhitelist(shell, diagnostics::add))
        assertEquals(listOf(DiagnosticCode.DOZE_WHITELIST_READ_FAILED), diagnostics)
    }

    @Test fun rootUsesTheSameReadbackAndKeepsErrorsUnknown() = runTest {
        for ((output, expected, expectedDiagnostics) in listOf(
            Triple(CommandOutput.Result("user,com.example.app,10123"), setOf("com.example.app"), emptyList()),
            Triple(CommandOutput.Result(error = "command failed"), null, listOf(DiagnosticCode.DOZE_WHITELIST_READ_FAILED)),
            Triple(CommandOutput.Result("garbage"), null, listOf(DiagnosticCode.DOZE_WHITELIST_FORMAT_INVALID)),
            Triple(CommandOutput.Result(error = "access lost", accessFailure = CommandOutput.AccessFailure.DENIED), null, emptyList()),
        )) {
            val diagnostics = mutableListOf<DiagnosticCode>()
            val shell = ShellRunner(
                probeMode = { ShellRunner.Mode.ROOT }, runShizuku = { _, _, _ -> error("No fallback") },
                runRoot = { command, _ ->
                    assertEquals(PrivilegedCommand.ListDozeWhitelist.argv.joinToString(" "), command)
                    output
                }, shizukuRunning = { false }, elapsedMs = { 0L },
            )
            assertEquals(expected, readUserDozeWhitelist(shell, diagnostics::add))
            assertEquals(expectedDiagnostics, diagnostics)
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
