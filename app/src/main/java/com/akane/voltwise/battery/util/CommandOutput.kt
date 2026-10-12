package com.akane.voltwise.battery.util

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A command succeeds only after a complete, bounded read and a zero exit code. */
object CommandOutput {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_ERROR_BYTES = 64 * 1024

    private val nativeProtoTimeout = Regex(
        """\n\*\*\* SERVICE 'batterystats' DUMP TIMEOUT \([0-9]+ms\) EXPIRED \*\*\*\r?\n\r?\n$""",
    )

    /** `9,h,` battery-history lines are the bulk of a checkin dump and the parser ignores them;
     * dropping them while reading (instead of after) keeps large dumps under [MAX_BYTES]. */
    private const val HISTORY_LINE_PREFIX = "9,h,"

    /** Fixed access-loss evidence, separate from successful data and safe to log. */
    enum class AccessFailure { DENIED, EXECUTABLE_UNAVAILABLE }

    private val denial = Regex(
        """(?im)^\h*(?:su:\h*[^\r\n]*\b)?(?:permission denied|access denied|request rejected|not allowed)\h*[.!]?\h*$""",
    )
    private val unavailable = Regex("""(?im)^\h*su:[^\r\n]*(?:not found|no such file or directory)\h*$""")
    private val launchError = Regex("""(?:error=|error:\h*)(2|13)(?:,|\h+\()""")

    data class Result(
        val output: String = "",
        val error: String? = null,
        val accessFailure: AccessFailure? = null,
        val certainty: ExecutionCertainty = ExecutionCertainty.CONFIRMED,
    ) {
        val successful: Boolean get() = error == null

        /** The (already history-filtered) output as a line sequence, for streaming parse. */
        fun lineSequence(): Sequence<String> = output.lineSequence()
    }

    fun run(arguments: List<String>, timeoutMs: Long, maxBytes: Int = MAX_BYTES): Result =
        run(arguments, timeoutMs, maxBytes) {
            ProcessBuilder(it).redirectErrorStream(!BatteryStatsBinaryOutput.matches(it)).start()
        }

    internal fun run(
        arguments: List<String>, timeoutMs: Long, maxBytes: Int, startProcess: (List<String>) -> Process,
    ): Result = if (BatteryStatsBinaryOutput.matches(arguments)) {
        runBinary(arguments, timeoutMs, maxBytes, startProcess)
    } else runText(arguments, timeoutMs, maxBytes, startProcess)

    fun runBinary(arguments: List<String>, timeoutMs: Long, maxBytes: Int = MAX_BYTES): Result =
        runBinary(arguments, timeoutMs, maxBytes) { ProcessBuilder(it).redirectErrorStream(false).start() }

    internal fun runBinary(
        arguments: List<String>, timeoutMs: Long, maxBytes: Int, startProcess: (List<String>) -> Process,
    ): Result {
        require(BatteryStatsBinaryOutput.matches(arguments)) { "Unsupported binary command" }
        require(timeoutMs > 0 && maxBytes in 1..MAX_BYTES)
        val rawLimit = BatteryStatsBinaryOutput.rawLimit(maxBytes)
        if (rawLimit == 0) return Result(error = "Output limit exceeded")
        var process: Process? = null
        var stdout: FutureTask<ByteArray>? = null
        var stderr: FutureTask<ByteArray>? = null
        return try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val child = startProcess(arguments)
            process = child
            child.outputStream.close()
            val outputRead = binaryReader(child.inputStream, rawLimit, "batstats-binary-reader")
            stdout = outputRead
            val errorRead = binaryReader(child.errorStream, MAX_ERROR_BYTES, "batstats-error-reader")
            stderr = errorRead
            val bytes = outputRead.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
            val errorBytes = errorRead.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
            val errors = ascii(errorBytes)
            val platformFailure = binaryFailure(bytes)
            when {
                !child.waitFor((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) ->
                    Result(error = "Command timed out", certainty = ExecutionCertainty.UNKNOWN)
                child.exitValue() != 0 -> Result(
                    error = "Command exited with status ${child.exitValue()}",
                    accessFailure = classifyAccessFailure(errors) ?: binaryAccessFailure(bytes),
                )
                platformFailure != null -> Result(error = platformFailure)
                errorBytes.isNotEmpty() -> Result(
                    error = binaryFailure(errorBytes) ?: "Command reported an error",
                    accessFailure = classifyAccessFailure(errors),
                )
                bytes.isEmpty() -> Result(error = "Command returned no data")
                else -> Result(output = BatteryStatsBinaryOutput.encode(bytes))
            }
        } catch (_: TimeoutException) {
            Result(error = "Command timed out", certainty = ExecutionCertainty.UNKNOWN)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result(error = "Command interrupted", certainty = ExecutionCertainty.UNKNOWN)
        } catch (_: ExecutionException) {
            Result(error = "Command output could not be read", certainty = ExecutionCertainty.UNKNOWN)
        } catch (e: IOException) {
            Result(
                error = "IOException",
                accessFailure = if (process == null) classifyLaunchFailure(e.message.orEmpty()) else null,
                certainty = if (process == null) ExecutionCertainty.CONFIRMED else ExecutionCertainty.UNKNOWN,
            )
        } catch (e: Exception) {
            Result(
                error = e.javaClass.simpleName,
                certainty = if (process == null) ExecutionCertainty.CONFIRMED else ExecutionCertainty.UNKNOWN,
            )
        } finally {
            stdout?.cancel(true)
            stderr?.cancel(true)
            process?.let { child ->
                Thread({ runCatching { child.destroyForcibly() } }, "batstats-command-cleanup")
                    .apply { isDaemon = true; start() }
            }
        }
    }

    private fun binaryReader(input: InputStream, limit: Int, name: String): FutureTask<ByteArray> =
        FutureTask {
            input.use { stream ->
                val output = ByteArrayOutputStream(minOf(limit, 8192))
                val buffer = ByteArray(8192)
                while (true) {
                    val size = stream.read(buffer)
                    if (size == -1) break
                    if (size > limit - output.size()) throw IOException("Output limit exceeded")
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
        }.also { Thread(it, name).apply { isDaemon = true; start() } }

    /** Examine fixed diagnostic boundaries only; names inside a proto are opaque bytes. */
    private fun binaryFailure(bytes: ByteArray): String? {
        val prefix = ascii(bytes, end = minOf(bytes.size, 2048)).trimStart(' ', '\t', '\r', '\n')
        return when {
            prefix.startsWith("Security exception", true) ->
                if (prefix.contains("INTERACT_ACROSS_USERS")) DumpOutput.REFUSED_CROSS_USER else DumpOutput.REFUSED
            prefix.startsWith("Permission Denial", true) || prefix.startsWith("java.lang.SecurityException") -> DumpOutput.REFUSED
            prefix.startsWith("Can't find service:", true) -> "Android service unavailable"
            prefix.startsWith("Error dumping service info", true) || prefix.startsWith("ERROR:") -> "Android service dump failed"
            nativeProtoTimeout.containsMatchIn(ascii(bytes, start = (bytes.size - 256).coerceAtLeast(0))) ->
                "Android service dump was incomplete"
            else -> null
        }
    }

    private fun binaryAccessFailure(bytes: ByteArray): AccessFailure? {
        val prefix = ascii(bytes, end = minOf(bytes.size, 2048)).trimStart()
        // Never search embedded protobuf names for access-loss evidence.
        return if (prefix.startsWith("su:") || prefix.startsWith("permission denied", true) ||
            prefix.startsWith("access denied", true) || prefix.startsWith("request rejected", true) ||
            prefix.startsWith("not allowed", true)
        ) classifyAccessFailure(prefix) else null
    }

    private fun ascii(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): String = buildString(end - start) {
        for (index in start until end) append((bytes[index].toInt() and 255).toChar())
    }

    private fun runText(
        arguments: List<String>, timeoutMs: Long, maxBytes: Int, startProcess: (List<String>) -> Process,
    ): Result {
        require(timeoutMs > 0 && maxBytes in 1..MAX_BYTES)
        var process: Process? = null
        var reader: FutureTask<String>? = null
        return try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val child = startProcess(arguments)
            process = child
            child.outputStream.close()
            val read = FutureTask {
                val text = StringBuilder()
                var totalBytes = 0
                var firstLine = true
                BufferedReader(InputStreamReader(child.inputStream, Charsets.UTF_8)).use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.startsWith(HISTORY_LINE_PREFIX)) continue
                        val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
                        totalBytes += lineBytes
                        if (totalBytes > maxBytes) throw IOException("Output limit exceeded")
                        if (!firstLine) text.append('\n')
                        firstLine = false
                        text.append(line)
                    }
                }
                text.toString()
            }
            reader = read
            Thread(read, "batstats-command-reader").apply { isDaemon = true; start() }
            // Bound the read itself: a descendant can hold stdout after the parent has exited.
            val text = read.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
            when {
                !child.waitFor((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) ->
                    Result(error = "Command timed out", certainty = ExecutionCertainty.UNKNOWN)
                child.exitValue() != 0 -> Result(
                    error = "Command exited with status ${child.exitValue()}",
                    accessFailure = classifyAccessFailure(text),
                )
                else -> Result(output = text)
            }
        } catch (_: TimeoutException) {
            Result(error = "Command timed out", certainty = ExecutionCertainty.UNKNOWN)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result(error = "Command interrupted", certainty = ExecutionCertainty.UNKNOWN)
        } catch (_: ExecutionException) {
            Result(error = "Command output could not be read", certainty = ExecutionCertainty.UNKNOWN)
        } catch (e: IOException) {
            Result(
                error = "IOException",
                accessFailure = if (process == null) classifyLaunchFailure(e.message.orEmpty()) else null,
                certainty = if (process == null) ExecutionCertainty.CONFIRMED else ExecutionCertainty.UNKNOWN,
            )
        } catch (e: Exception) {
            Result(
                error = e.javaClass.simpleName,
                certainty = if (process == null) ExecutionCertainty.CONFIRMED else ExecutionCertainty.UNKNOWN,
            )
        } finally {
            reader?.cancel(true)
            // Some JVM pipe implementations block close behind an inherited reader.
            // Cleanup must not turn a timed-out read into a blocking caller.
            process?.let { child ->
                Thread({ runCatching { child.destroyForcibly() } }, "batstats-command-cleanup")
                    .apply { isDaemon = true; start() }
            }
        }
    }

    internal fun classifyAccessFailure(text: String): AccessFailure? = when {
        denial.containsMatchIn(text) -> AccessFailure.DENIED
        unavailable.containsMatchIn(text) -> AccessFailure.EXECUTABLE_UNAVAILABLE
        else -> null
    }

    internal fun classifyLaunchFailure(detail: String): AccessFailure? = when (launchError.find(detail)?.groupValues?.get(1)) {
        "2" -> AccessFailure.EXECUTABLE_UNAVAILABLE
        "13" -> AccessFailure.DENIED
        else -> null
    }
}
