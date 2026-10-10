package com.akane.voltwise.battery.shizuku

import com.akane.voltwise.battery.shizuku.ShizukuBridge.Failure
import com.akane.voltwise.battery.shizuku.ShizukuBridge.RunResult
import com.akane.voltwise.battery.util.CommandOutput
import com.akane.voltwise.battery.util.ExecutionCertainty
import com.akane.voltwise.battery.util.ExecutionPolicy
import com.akane.voltwise.battery.util.CommandProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ShizukuMutationPolicyTest {
    @Test fun lostMutationResponseDispatchesExactlyOnceWhileDiagnosticReadRetries() = runTest {
        for ((policy, expectedDispatches) in listOf(ExecutionPolicy.MUTATION to 1, ExecutionPolicy.READ_ONLY to 2)) {
            var dispatched = 0
            val first = RunResult.Error("response pipe lost", Failure.TRANSPORT, ExecutionCertainty.UNKNOWN)
            val result = retryAfterTransportFailure({ dispatched++; first }, policy) {
                dispatched++
                RunResult.Success("confirmed retry")
            }
            assertEquals("$policy must obey its replay policy", expectedDispatches, dispatched)
            if (policy == ExecutionPolicy.MUTATION) assertEquals(first, result)
            else assertEquals(RunResult.Success("confirmed retry"), result)
        }
    }

    @Test fun refusedMutationTransactionRemainsKnownNotDispatchedAndDoesNotRetry() = runTest {
        var dispatches = 0
        val result = retryAfterTransportFailure({
            dispatches++
            classifyAfterRead(true, true, readPipeResult(false) { error("Refusal must not read a response") },
                ExecutionPolicy.MUTATION)
        }, ExecutionPolicy.MUTATION) { error("A refused mutation must not replay") } as RunResult.Error
        assertEquals(Failure.COMMAND, result.reason)
        assertEquals(ExecutionCertainty.CONFIRMED, result.certainty)
        assertEquals(1, dispatches)
    }

    @Test fun completeMutationResponseRemainsConfirmedAfterAccessDisappears() {
        for ((running, permitted) in listOf(false to false, true to false)) {
            assertEquals(RunResult.Success(""), classifyAfterRead(running, permitted,
                CommandOutput.Result(""), ExecutionPolicy.MUTATION))
        }
    }

    @Test fun missingMutationResponseAfterAccessLossIsUncertainRatherThanRefused() {
        for ((running, permitted) in listOf(false to false, true to false, true to true)) {
            val result = classifyAfterRead(running, permitted, null, ExecutionPolicy.MUTATION) as RunResult.Error
            assertEquals(ExecutionCertainty.UNKNOWN, result.certainty)
        }
    }

    @Test fun helperNotStartedErrorsRemainConfirmedAcrossPipeProtocol() {
        assertEquals("NOT_STARTED:UNSUPPORTED_COMMAND", ShellUserService.NOT_STARTED_UNSUPPORTED)
        assertEquals("NOT_STARTED:HELPER_BUSY", ShellUserService.NOT_STARTED_BUSY)
        for (error in listOf("NOT_STARTED:UNSUPPORTED_COMMAND", "NOT_STARTED:HELPER_BUSY")) {
            val output = ByteArrayOutputStream()
            CommandProtocol.write(output, CommandOutput.Result(error = error))
            val response = readPipeResult(true) { CommandProtocol.read(ByteArrayInputStream(output.toByteArray())) }
            assertEquals(error, response.error)
            assertEquals(ExecutionCertainty.CONFIRMED, response.certainty)
            for ((running, permitted) in listOf(true to true, true to false, false to false)) {
                val result = classifyAfterRead(running, permitted, response, ExecutionPolicy.MUTATION) as RunResult.Error
                assertEquals(ExecutionCertainty.CONFIRMED, result.certainty)
            }
        }
    }

    @Test fun unsuccessfulMutationResponseDoesNotClaimConfirmedFailure() {
        for (error in listOf("response unavailable", "Unsupported command", "Helper busy; retry later")) {
            val result = classifyAfterRead(true, true, readPipeResult(true) { CommandOutput.Result(error = error) },
                ExecutionPolicy.MUTATION) as RunResult.Error
            assertEquals(ExecutionCertainty.UNKNOWN, result.certainty)
        }
    }
}
