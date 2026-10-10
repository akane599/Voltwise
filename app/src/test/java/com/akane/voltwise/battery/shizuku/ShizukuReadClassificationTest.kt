package com.akane.voltwise.battery.shizuku

import com.akane.voltwise.battery.shizuku.ShizukuBridge.Failure
import com.akane.voltwise.battery.shizuku.ShizukuBridge.RunResult
import com.akane.voltwise.battery.util.CommandOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ShizukuReadClassificationTest {
    @Test fun stoppedAfterReadIsNotRunningEvenWithOutputOrProtocolFailure() {
        for (result in listOf(CommandOutput.Result("dump"), CommandOutput.Result(error = "command failed"), null)) {
            for (permitted in listOf(false, true)) {
                val failure = classifyAfterRead(false, permitted, result) as RunResult.Error
                assertEquals(Failure.NOT_RUNNING, failure.reason)
            }
        }
    }

    @Test fun permissionRevokedAfterReadIsNoPermissionEvenWithOutputOrProtocolFailure() {
        for (result in listOf(CommandOutput.Result("dump"), CommandOutput.Result(error = "command failed"), null)) {
            val failure = classifyAfterRead(true, false, result) as RunResult.Error
            assertEquals(Failure.NO_PERMISSION, failure.reason)
        }
    }

    @Test fun authorizedReadPreservesProtocolCommandAndSuccessResults() {
        assertEquals(
            RunResult.Error("Helper protocol unavailable", Failure.TRANSPORT),
            classifyAfterRead(true, true, null),
        )
        assertEquals(
            RunResult.Error("command failed", Failure.COMMAND),
            classifyAfterRead(true, true, CommandOutput.Result(error = "command failed")),
        )
        assertEquals(RunResult.Success("dump"), classifyAfterRead(true, true, CommandOutput.Result("dump")))
    }

    @Test fun refusedTransactionIsCommandFailureNotTimeoutAndDoesNotRetry() = runTest {
        val pipeResult = readPipeResult(false) { error("Refused transaction must not read the pipe") }
        val first = classifyAfterRead(true, true, pipeResult)
        assertEquals(RunResult.Error("Helper command refused", Failure.COMMAND), first)
        var retries = 0
        val result = retryAfterTransportFailure({ first }) {
            retries++
            RunResult.Success("retried dump")
        }
        assertSame("Refusal must return the first result", first, result)
        assertEquals("Refusal must not retry the dump", 0, retries)
    }

    @Test fun unsupportedCommandResponseIsCommandFailureAndDoesNotRetry() = runTest {
        val first = classifyAfterRead(true, true, readPipeResult(true) {
            CommandOutput.Result(error = "Unsupported command")
        })
        assertEquals(RunResult.Error("Unsupported command", Failure.COMMAND), first)
        assertSame(first, retryAfterTransportFailure({ first }) { error("Unsupported command must not retry") })
    }

    @Test fun acceptedTransactionReadsAndPreservesItsResult() {
        val expected = CommandOutput.Result("dump")
        var reads = 0
        assertSame(expected, readPipeResult(true) {
            reads++
            expected
        })
        assertEquals("Accepted transaction reads exactly once", 1, reads)
    }

    @Test fun postReadAccessLossDoesNotRebindOrRepeatTheDump() = runTest {
        for ((running, permitted) in listOf(false to false, true to false)) {
            val first = classifyAfterRead(running, permitted, CommandOutput.Result("partial dump"))
            var retries = 0
            val result = retryAfterTransportFailure({ first }) {
                retries++
                RunResult.Success("retried dump")
            }
            assertSame("Access loss must return the first result", first, result)
            assertEquals("Access loss must not retry the dump", 0, retries)
        }
    }

    @Test fun onlyTransportErrorsInvokeTheRetry() = runTest {
        for (reason in Failure.entries.filter { it != Failure.TRANSPORT }) {
            val first = RunResult.Error("original failure", reason)
            assertSame(first, retryAfterTransportFailure({ first }) { error("Must not rebind for $reason") })
        }
        val success = RunResult.Success("dump")
        assertSame(success, retryAfterTransportFailure({ success }) { error("Must not retry success") })

        val transport = RunResult.Error("dead binder", Failure.TRANSPORT)
        val second = RunResult.Error("still dead", Failure.TRANSPORT)
        var retries = 0
        assertSame(second, retryAfterTransportFailure({ transport }) { failure ->
            assertSame(transport, failure)
            retries++
            second
        })
        assertEquals("Transport failure retries exactly once", 1, retries)
        assertSame(transport, retryAfterTransportFailure({ transport }) { null })
    }

    @Test fun retryCancellationPropagates() = runTest {
        val cancellation = CancellationException("cancelled dump")
        try {
            retryAfterTransportFailure({ RunResult.Error("dead binder", Failure.TRANSPORT) }) { throw cancellation }
            throw AssertionError("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
