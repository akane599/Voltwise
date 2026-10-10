package com.akane.voltwise.battery.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class BatteryStatsBinaryOutputTest {
    @Test fun binaryBytesSurviveEveryBackendArgumentFormAndTheExistingPipeProtocol() {
        val bytes = byteArrayOf(0, 10, 13, -1, -2) + "9,h,\nSecurity exception: opaque name\n".toByteArray() +
            ByteArray(256) { it.toByte() }
        val script = bytes.joinToString("") { "\\%03o".format(it.toInt() and 255) }
        for (arguments in listOf(
            listOf("dumpsys", "batterystats", "--proto", "--charged"),
            listOf("su", "-c", "dumpsys batterystats --proto --charged"),
        )) {
            val result = CommandOutput.run(arguments, 1000, CommandOutput.MAX_BYTES) { received ->
                assertEquals(arguments, received)
                ProcessBuilder("sh", "-c", "printf '$script'").start()
            }
            assertTrue(result.error, result.successful)
            assertArrayEquals(bytes, BatteryStatsBinaryOutput.decode(result.output))
            val wire = ByteArrayOutputStream().also { CommandProtocol.write(it, result) }.toByteArray()
            val received = CommandProtocol.read(ByteArrayInputStream(wire))
            assertArrayEquals(bytes, BatteryStatsBinaryOutput.decode(received.output))
            assertThrows(Exception::class.java) { CommandProtocol.read(ByteArrayInputStream(wire.copyOf(wire.size - 1))) }
        }
    }

    @Test fun boundedRawBytesExactlyFitTheEncodedCapAndOverflowDiscardsAllData() {
        // Seven prefix bytes + four Base64 bytes can represent exactly three raw bytes.
        val fits = run("printf '\\000\\377\\012'", maxBytes = 11)
        assertTrue(fits.error, fits.successful)
        assertEquals(11, fits.output.toByteArray().size)
        assertArrayEquals(byteArrayOf(0, -1, 10), BatteryStatsBinaryOutput.decode(fits.output))
        val overflow = run("printf '\\000\\377\\012x'", maxBytes = 11)
        assertFalse(overflow.successful)
        assertEquals("", overflow.output)
    }

    @Test fun incompleteProcessReadsAndNonzeroExitsNeverReturnAnEnvelope() {
        for ((script, timeout) in listOf(
            "printf partial; exit 7" to 1000L,
            "printf partial; sleep 3" to 50L,
            "sleep 3 & printf partial; sleep 0.05" to 100L,
            "sleep 3 >&2 & printf partial; sleep 0.05" to 100L,
        )) {
            val start = System.nanoTime()
            val result = run(script, timeout)
            assertFalse(script, result.successful)
            assertEquals("", result.output)
            assertTrue("Caller stayed blocked for $script", (System.nanoTime() - start) / 1_000_000 < 1500)
        }
    }

    @Test fun stderrIsDrainedSeparatelyAndNeverMixedIntoSuccessfulBytes() {
        for (script in listOf(
            "printf '\\012\\001x'; printf warning >&2",
            "printf '\\012\\001x'; head -c 70000 /dev/zero >&2",
        )) {
            val result = run(script)
            assertFalse(result.successful)
            assertEquals("", result.output)
            assertNotEquals("Stderr must be drained before the deadline", "Command timed out", result.error)
        }
    }

    @Test fun platformRefusalAndNativeTimeoutAreRejectedEvenWithZeroExit() {
        for ((script, expected) in listOf(
            "printf 'Permission Denial: cannot dump'" to DumpOutput.REFUSED,
            "printf 'Security exception: MATCH_ANY_USER requires INTERACT_ACROSS_USERS'" to DumpOutput.REFUSED_CROSS_USER,
            "printf \"Can't find service: batterystats\"" to "Android service unavailable",
            "printf '\\012\\002\\010\\011\\n*** SERVICE '\\''batterystats'\\'' DUMP TIMEOUT (10000ms) EXPIRED ***\\n\\n'" to "Android service dump was incomplete",
        )) {
            val result = run(script)
            assertEquals(script, expected, result.error)
            assertEquals("", result.output)
        }
    }

    @Test fun denialOnTheSuProcessRetainsTypedAccessLoss() {
        val result = CommandOutput.run(
            listOf("su", "-c", "dumpsys batterystats --proto --charged"), 1000, CommandOutput.MAX_BYTES,
        ) { ProcessBuilder("sh", "-c", "printf 'su: permission denied\\n' >&2; exit 1").start() }
        assertFalse(result.successful)
        assertEquals(CommandOutput.AccessFailure.DENIED, result.accessFailure)
        assertEquals("", result.output)
    }

    @Test fun denialOnTheSuProcessStdoutRetainsTypedAccessLoss() {
        val result = CommandOutput.run(
            listOf("su", "-c", "dumpsys batterystats --proto --charged"), 1000, CommandOutput.MAX_BYTES,
        ) {
            ProcessBuilder("sh", "-c", "printf 'su: permission denied\\n'; exit 1").start().also {
                assertEquals("Denial must come from stdout only", -1, it.errorStream.read())
            }
        }
        assertEquals(CommandOutput.AccessFailure.DENIED, result.accessFailure)
        assertFalse(result.successful)
        assertEquals("", result.output)
    }

    @Test fun nonDenialStdoutOnTheSuProcessDoesNotBecomeAccessLoss() {
        val result = CommandOutput.run(
            listOf("su", "-c", "dumpsys batterystats --proto --charged"), 1000, CommandOutput.MAX_BYTES,
        ) {
            ProcessBuilder("sh", "-c", "printf 'Error: something else\\n'; exit 1").start().also {
                assertEquals("Control must come from stdout only", -1, it.errorStream.read())
            }
        }
        assertNull(result.accessFailure)
        assertFalse(result.successful)
        assertEquals("", result.output)
    }

    @Test fun embeddedProtobufNamesNeverBecomeAccessLossEvidence() {
        val result = CommandOutput.run(
            listOf("su", "-c", "dumpsys batterystats --proto --charged"), 1000, CommandOutput.MAX_BYTES,
        ) { ProcessBuilder("sh", "-c", "printf '\\012\\002xxpermission denied'; exit 1").start() }
        assertNull(result.accessFailure)
        assertFalse(result.successful)
        assertEquals("", result.output)
    }

    @Test fun failedBinaryLaunchRetainsTypedUnavailableAccess() {
        val result = CommandOutput.runBinary(BatteryStatsBinaryOutput.ARGV, 1000, CommandOutput.MAX_BYTES) {
            throw java.io.IOException("Cannot run program su: error=2, No such file or directory")
        }
        assertEquals(CommandOutput.AccessFailure.EXECUTABLE_UNAVAILABLE, result.accessFailure)
        assertEquals(ExecutionCertainty.CONFIRMED, result.certainty)
        assertEquals("", result.output)
    }

    @Test fun textCommandsKeepTheirExistingHistoryFilteringAndAreNotEnveloped() {
        val result = CommandOutput.run(listOf("dumpsys", "batterystats", "-c", "--charged"), 1000, 40) {
            ProcessBuilder("sh", "-c", "printf '9,h,discard\\nretained\\n'").start()
        }
        assertTrue(result.error, result.successful)
        assertEquals("retained", result.output)
        assertNull(BatteryStatsBinaryOutput.decode(result.output))
    }

    @Test fun interruptingBinaryReadReturnsPromptlyWithoutPartialSuccess() {
        val result = java.util.concurrent.atomic.AtomicReference<CommandOutput.Result>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            result.set(CommandOutput.runBinary(BatteryStatsBinaryOutput.ARGV, 15_000, CommandOutput.MAX_BYTES) {
                ProcessBuilder("sh", "-c", "printf partial; sleep 10").start().also { entered.countDown() }
            })
        }
        worker.start()
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        worker.interrupt()
        worker.join(1500)
        assertFalse(worker.isAlive)
        assertEquals("Command interrupted", result.get().error)
        assertEquals("", result.get().output)
        assertEquals(ExecutionCertainty.UNKNOWN, result.get().certainty)
    }

    @Test fun decoderRejectsTextMalformedAndOversizedEnvelopes() {
        for (output in listOf("", "9,10002,l,pwi,uid,3", "VWBSP1:", "VWBSP1:!!!!", "VWBSP1:AA", "VWBSP1:AB==")) {
            assertNull(output, BatteryStatsBinaryOutput.decode(output))
        }
        assertNull(BatteryStatsBinaryOutput.decode("VWBSP1:" + "A".repeat(CommandOutput.MAX_BYTES)))
        assertArrayEquals(byteArrayOf(0, -1, 10), BatteryStatsBinaryOutput.decode("VWBSP1:AP8K"))
        assertEquals(8_388_607, BatteryStatsBinaryOutput.encode(ByteArray(6_291_450)).length)
        assertThrows(IllegalArgumentException::class.java) { BatteryStatsBinaryOutput.encode(ByteArray(6_291_451)) }
    }

    @Test fun binaryLaunchRejectsAnyCommandOutsideTheTwoFixedBackendForms() {
        for (arguments in listOf(listOf("sh", "-c", "id"), BatteryStatsBinaryOutput.ARGV + "--history")) {
            assertThrows(IllegalArgumentException::class.java) {
                CommandOutput.runBinary(arguments, 1000, CommandOutput.MAX_BYTES) { error("Must never launch") }
            }
        }
    }

    private fun run(script: String, timeout: Long = 1000, maxBytes: Int = CommandOutput.MAX_BYTES): CommandOutput.Result =
        CommandOutput.runBinary(listOf("dumpsys", "batterystats", "--proto", "--charged"), timeout, maxBytes) {
            ProcessBuilder("sh", "-c", script).start()
        }
}
