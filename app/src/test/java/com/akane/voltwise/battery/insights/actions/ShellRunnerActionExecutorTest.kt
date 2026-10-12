package com.akane.voltwise.battery.insights.actions

import com.akane.voltwise.battery.actions.PrivilegedCommand
import com.akane.voltwise.battery.util.CommandOutput
import com.akane.voltwise.battery.util.ShellRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ShellRunnerActionExecutorTest {
    @Test fun delegatesToActionBoundaryNotDiagnosticAccess() = runTest {
        val runner = ShellRunner(
            probeMode = { ShellRunner.Mode.ADB },
            runShizuku = { _, _, _ -> error("must not execute") },
            runRoot = { _, _ -> error("must not execute") },
            shizukuRunning = { false }, elapsedMs = { 0L },
        )
        assertTrue(ShellRunnerActionExecutor(runner).run(PrivilegedCommand.ForceStop("com.example.app")) is ShellRunner.Outcome.NoAccess)
    }

    @Test fun delegatesValidatedCommandAndPreservesEmptySuccess() = runTest {
        val calls = mutableListOf<String>()
        val runner = ShellRunner(
            probeMode = { ShellRunner.Mode.ROOT },
            runShizuku = { _, _, _ -> error("must not execute") },
            runRoot = { command, _ -> calls += command; CommandOutput.Result("") },
            shizukuRunning = { false }, elapsedMs = { 0L },
        )
        val command = PrivilegedCommand.ForceStop("com.example.app")
        assertEquals(ShellRunner.Outcome.Success("", ShellRunner.Mode.ROOT), ShellRunnerActionExecutor(runner).run(command))
        assertEquals(listOf(command.argv.joinToString(" ")), calls)
    }
}
