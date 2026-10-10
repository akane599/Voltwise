package com.akane.voltwise.battery.util

import com.akane.voltwise.battery.actions.PrivilegedCommand
import com.akane.voltwise.battery.shizuku.ShizukuBridge
import com.akane.voltwise.battery.util.ShellRunner.Mode
import com.akane.voltwise.battery.util.ShellRunner.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ShellRunnerActionTest {
    private val command = PrivilegedCommand.ForceStop("com.example")

    @Test fun adbAndNoneNeverExecuteActions() = runTest {
        for (mode in listOf(Mode.ADB, Mode.NONE)) {
            val runner = ShellRunner(
                probeMode = { mode },
                runShizuku = { _, _, _ -> error("Shizuku must not execute") },
                runRoot = { _, _ -> error("Root must not execute") },
                shizukuRunning = { error("No fallback probe") },
                elapsedMs = { 0L },
            )
            assertEquals(Outcome.NoAccess(mode, "Actions require Shizuku or root"), runner.execAction(command))
            assertNull(runner.lastError.value)
        }
    }

    @Test fun rootReceivesExactJoinedTokensAndEmptyOutputSucceeds() = runTest {
        val calls = mutableListOf<Pair<String, Long>>()
        val runner = ShellRunner(
            probeMode = { Mode.ROOT },
            runShizuku = { _, _, _ -> error("No fallback") },
            runRoot = { text, timeout -> calls += text to timeout; CommandOutput.Result("") },
            shizukuRunning = { false },
            elapsedMs = { 0L },
        )
        for (action in listOf(command, PrivilegedCommand.AddDozeWhitelist("com.example"), PrivilegedCommand.RemoveDozeWhitelist("com.example"))) {
            assertEquals(Outcome.Success("", Mode.ROOT), runner.execAction(action))
            assertEquals(action.argv.joinToString(" ") to 25_000L, calls.last())
        }
        assertEquals(3, calls.size)
        assertEquals(Outcome.Failure(Mode.ROOT, "Command returned no data"), runner.exec("dumpsys battery"))
    }

    @Test fun shizukuReceivesExactJoinedTokensAndEmptyOutputSucceeds() = runTest {
        val calls = mutableListOf<Pair<String, Long>>()
        val runner = ShellRunner(
            probeMode = { Mode.SHIZUKU },
            runShizuku = { text, timeout, _ -> calls += text to timeout; ShizukuBridge.RunResult.Success("") },
            runRoot = { _, _ -> error("No fallback") },
            shizukuRunning = { true },
            elapsedMs = { 0L },
        )
        assertEquals(Outcome.Success("", Mode.SHIZUKU), runner.execAction(command))
        assertEquals(listOf(command.argv.joinToString(" ") to 25_000L), calls)
    }

    @Test fun typedMutationPolicyAndUncertaintyCrossTheProductionShellBoundary() = runTest {
        val policies = mutableListOf<ExecutionPolicy>()
        val runner = ShellRunner(probeMode = { Mode.SHIZUKU },
            runShizuku = { _, _, policy ->
                policies += policy
                ShizukuBridge.RunResult.Error("response lost", ShizukuBridge.Failure.TRANSPORT, ExecutionCertainty.UNKNOWN)
            }, shizukuRunning = { true }, elapsedMs = { 0L })
        val result = runner.execAction(command) as Outcome.Failure
        assertEquals(ExecutionCertainty.UNKNOWN, result.certainty)
        assertEquals(listOf(ExecutionPolicy.MUTATION), policies)
        runner.execAction(PrivilegedCommand.GetStandbyBucket("com.example"))
        runner.exec("dumpsys battery")
        assertEquals(listOf(ExecutionPolicy.MUTATION, ExecutionPolicy.READ_ONLY, ExecutionPolicy.READ_ONLY), policies)
    }

    @Test fun postDispatchAccessLossRemainsUncertainWhilePreflightDenialRemainsRefusal() = runTest {
        for (certainty in ExecutionCertainty.entries) {
            val runner = ShellRunner(probeMode = { Mode.SHIZUKU },
                runShizuku = { _, _, _ -> ShizukuBridge.RunResult.Error("access lost",
                    ShizukuBridge.Failure.NO_PERMISSION, certainty) },
                shizukuRunning = { true }, elapsedMs = { 0L })
            val result = runner.execAction(command)
            if (certainty == ExecutionCertainty.UNKNOWN) {
                assertTrue(result is Outcome.Failure)
                assertEquals(ExecutionCertainty.UNKNOWN, (result as Outcome.Failure).certainty)
            } else assertTrue(result is Outcome.NoAccess)
            assertEquals(Mode.NONE, runner.access.value)
        }
    }

    @Test fun rootLostResponseRetainsItsUncertainty() = runTest {
        val runner = ShellRunner(probeMode = { Mode.ROOT },
            runShizuku = { _, _, _ -> error("No fallback") },
            runRoot = { _, _ -> CommandOutput.Result(error = "output lost", certainty = ExecutionCertainty.UNKNOWN) },
            shizukuRunning = { false }, elapsedMs = { 0L })
        assertEquals(ExecutionCertainty.UNKNOWN, (runner.execAction(command) as Outcome.Failure).certainty)
    }

    @Test fun actionsAndDiagnosticsShareOneLockInBothDirections() = runTest {
        for (actionFirst in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val runner = ShellRunner(
                probeMode = { Mode.ROOT },
                runShizuku = { _, _, _ -> error("No fallback") },
                runRoot = { text, _ ->
                    calls += text
                    if (calls.size == 1) { entered.complete(Unit); release.await() }
                    CommandOutput.Result("ok")
                },
                shizukuRunning = { false },
                elapsedMs = { 0L },
            )
            val first = async { if (actionFirst) runner.execAction(command) else runner.exec("dumpsys battery") }
            entered.await()
            val second = async {
                secondStarted.complete(Unit)
                if (actionFirst) runner.exec("dumpsys battery") else runner.execAction(command)
            }
            secondStarted.await()
            assertEquals(1, calls.size)
            release.complete(Unit)
            assertTrue(first.await() is Outcome.Success)
            assertTrue(second.await() is Outcome.Success)
            val expected = listOf(command.argv.joinToString(" "), "dumpsys battery")
            assertEquals(if (actionFirst) expected else expected.reversed(), calls)
        }
    }

    @Test fun actionOutcomesPreserveDiagnosticErrorUntilAccessLoss() = runTest {
        for (mode in listOf(Mode.ROOT, Mode.SHIZUKU)) {
            var result = CommandOutput.Result(error = "Diagnostic failed")
            val runner = ShellRunner(
                probeMode = { mode },
                runShizuku = { _, _, _ ->
                    if (result.error == null) ShizukuBridge.RunResult.Success(result.output)
                    else ShizukuBridge.RunResult.Error(
                        result.error.orEmpty(),
                        if (result.accessFailure == null) ShizukuBridge.Failure.COMMAND else ShizukuBridge.Failure.NO_PERMISSION,
                    )
                },
                runRoot = { _, _ -> result },
                shizukuRunning = { true },
                elapsedMs = { 0L },
            )
            assertEquals(Outcome.Failure(mode, "Diagnostic failed"), runner.exec("dumpsys battery"))
            result = CommandOutput.Result("")
            assertEquals(Outcome.Success("", mode), runner.execAction(command))
            assertEquals("Diagnostic failed", runner.lastError.value)
            result = CommandOutput.Result(error = "Action failed")
            assertEquals(Outcome.Failure(mode, "Action failed"), runner.execAction(command))
            assertEquals("Diagnostic failed", runner.lastError.value)
            result = CommandOutput.Result(error = "Access lost", accessFailure = CommandOutput.AccessFailure.DENIED)
            assertTrue(runner.execAction(command) is Outcome.NoAccess)
            assertEquals(Mode.NONE, runner.access.value)
            assertEquals(if (mode == Mode.ROOT) "Root access unavailable" else "Access lost", runner.lastError.value)
        }
    }

    @Test fun actionFailureDoesNotFallBackOrTurnEmptyErrorIntoSuccess() = runTest {
        val runner = ShellRunner(
            probeMode = { Mode.SHIZUKU },
            runShizuku = { _, _, _ -> ShizukuBridge.RunResult.Error("Helper timeout", ShizukuBridge.Failure.COMMAND) },
            runRoot = { _, _ -> error("No fallback") },
            shizukuRunning = { true },
            elapsedMs = { 0L },
        )
        assertEquals(Outcome.Failure(Mode.SHIZUKU, "Helper timeout"), runner.execAction(command))
        assertEquals(Mode.SHIZUKU, runner.access.value)
    }
}
