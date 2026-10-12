package com.akane.voltwise.battery.util

import android.content.Context
import android.os.SystemClock
import com.akane.voltwise.battery.actions.CommandPolicy
import com.akane.voltwise.battery.actions.PrivilegedCommand
import com.akane.voltwise.battery.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.TimeUnit

class ShellRunner internal constructor(
    private val probeMode: suspend () -> Mode,
    private val runShizuku: suspend (String, Long, ExecutionPolicy) -> ShizukuBridge.RunResult,
    private val shizukuRunning: () -> Boolean,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val runRoot: suspend (String, Long) -> CommandOutput.Result = { cmd, timeoutMs ->
        runInterruptible { CommandOutput.run(listOf("su", "-c", cmd), timeoutMs) }
    },
) {
    constructor(context: Context, shizuku: ShizukuBridge) : this(
        probeMode = {
            selectShellMode(
                shizukuRunning = shizuku::ping,
                shizukuAuthorized = shizuku::hasPermission,
                rootAvailable = RootStatsCollector::isRootAvailable,
                adbAvailable = { PrivilegeChecker.hasAdvancedViaAdb(context) },
            )
        },
        runShizuku = shizuku::run,
        shizukuRunning = shizuku::ping,
    )
    companion object {
        private const val CMD_TIMEOUT_SEC = 25L

        private const val MODE_CACHE_MS = 10_000L
    }

    enum class Mode { ROOT, SHIZUKU, ADB, NONE }

    sealed class Outcome {
        data class Success(val output: String, val mode: Mode) : Outcome()

        data class Failure(
            val mode: Mode,
            val message: String,
            val certainty: ExecutionCertainty = ExecutionCertainty.CONFIRMED,
        ) : Outcome()

        data class NoAccess(val mode: Mode, val message: String) : Outcome()
    }

    private val modeLock = Mutex()
    private val commandLock = Mutex()
    private val _access = MutableStateFlow(Mode.NONE)
    val access = _access.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()


    @Volatile
    private var cachedMode: Mode? = null

    @Volatile
    private var cachedModeAt = 0L

    suspend fun exec(cmd: String, allowEmpty: Boolean = false): Outcome = execute(cmd, allowEmpty, action = false)

    suspend fun execAction(command: PrivilegedCommand): Outcome {
        val argv = command.argv.toList()
        require(CommandPolicy.allows(argv)) { "Unsupported action command" }
        // su -c interprets shell text. These validated tokens contain no shell syntax or whitespace;
        // single-space joining is lossless and needs no quoting. Keep this invariant executable.
        check(argv.all { token -> token.isNotEmpty() && token.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_.+-"
        } }) { "Unsafe action token" }
        return execute(argv.joinToString(" "), allowEmpty = true, action = true, policy = command.executionPolicy)
    }

    private suspend fun execute(
        cmd: String,
        allowEmpty: Boolean,
        action: Boolean,
        policy: ExecutionPolicy = ExecutionPolicy.READ_ONLY,
    ): Outcome = commandLock.withLock {
        withContext(Dispatchers.IO) {
            // Select one backend for this read. A failure never falls through to another source.
            // Use the cached mode (detectMode() probes only when nothing is cached yet); callers
            // that need a fresh probe use detectMode(forceRefresh = true) explicitly.
            val mode = detectMode()
            if (action && mode != Mode.SHIZUKU && mode != Mode.ROOT) {
                val message = "Actions require Shizuku or root"
                return@withContext Outcome.NoAccess(mode, message)
            }
            val result = when (mode) {
                Mode.SHIZUKU -> when (val result = runShizuku(cmd, TimeUnit.SECONDS.toMillis(CMD_TIMEOUT_SEC), policy)) {
                    is ShizukuBridge.RunResult.Success -> CommandOutput.Result(result.output)
                    is ShizukuBridge.RunResult.Error -> {
                        currentCoroutineContext().ensureActive()
                        if (result.reason == ShizukuBridge.Failure.NOT_RUNNING ||
                            result.reason == ShizukuBridge.Failure.NO_PERMISSION
                        ) {
                            invalidateMode()
                            _access.value = Mode.NONE
                            _lastError.value = result.message
                            if (policy == ExecutionPolicy.MUTATION && result.certainty == ExecutionCertainty.UNKNOWN) {
                                return@withContext Outcome.Failure(mode, result.message, result.certainty)
                            }
                            return@withContext Outcome.NoAccess(mode, result.message)
                        }
                        CommandOutput.Result(error = result.message, certainty = result.certainty)
                    }
                }
                Mode.ROOT -> runRoot(cmd, CMD_TIMEOUT_SEC * 1000)
                Mode.ADB -> runInterruptible { CommandOutput.run(cmd.split(' '), CMD_TIMEOUT_SEC * 1000) }
                Mode.NONE -> CommandOutput.Result(error = if (shizukuRunning())
                    "Shizuku authorization required" else "Privileged access unavailable")
            }
            currentCoroutineContext().ensureActive()
            if (mode == Mode.ROOT && rootAccessLost(result)) {
                invalidateMode()
                _access.value = Mode.NONE
                _lastError.value = "Root access unavailable"
                if (policy == ExecutionPolicy.MUTATION && result.certainty == ExecutionCertainty.UNKNOWN) {
                    return@withContext Outcome.Failure(mode, "Root access unavailable", result.certainty)
                }
                return@withContext Outcome.NoAccess(mode, "Root access unavailable")
            }
            val error = result.error ?: when {
                !allowEmpty && result.output.isBlank() -> "Command returned no data"
                DumpOutput.failure(result.output) != null -> DumpOutput.failure(result.output)
                else -> null
            }
            // Action outcomes belong to their journal; preserve diagnostic errors unless access is lost.
            if (!action) _lastError.value = error
            if (error == null) Outcome.Success(result.output, mode) else Outcome.Failure(
                mode, error, if (policy == ExecutionPolicy.MUTATION) result.certainty else ExecutionCertainty.CONFIRMED,
            )
        }
    }

    /** Reuses the selected backend for 10 seconds unless access loss invalidates it. */
    suspend fun detectMode(forceRefresh: Boolean = false): Mode {
        if (!forceRefresh) {
            cachedMode?.let {
                if (elapsedMs() - cachedModeAt < MODE_CACHE_MS) return it
            }
        }
        return modeLock.withLock {
            if (!forceRefresh) {
                cachedMode?.let {
                    if (elapsedMs() - cachedModeAt < MODE_CACHE_MS) {
                        return@withLock it
                    }
                }
            }
            val mode = withContext(Dispatchers.IO) { probeMode() }
            _access.value = mode
            cachedMode = mode
            cachedModeAt = elapsedMs()
            mode
        }
    }

    fun invalidateMode() {
        cachedMode = null
        cachedModeAt = 0L
        RootStatsCollector.invalidateRootCache()
    }
}

/** Only typed su denial/unavailability ends root access; command failures retain the backend. */
internal fun rootAccessLost(result: CommandOutput.Result): Boolean =
    !result.successful && result.accessFailure != null

/** Probes only as far as the first authorized backend; this does not retry failed commands. */
internal suspend fun selectShellMode(
    shizukuRunning: () -> Boolean,
    shizukuAuthorized: () -> Boolean,
    rootAvailable: suspend () -> Boolean,
    adbAvailable: () -> Boolean,
): ShellRunner.Mode = when {
    shizukuRunning() && shizukuAuthorized() -> ShellRunner.Mode.SHIZUKU
    rootAvailable() -> ShellRunner.Mode.ROOT
    adbAvailable() -> ShellRunner.Mode.ADB
    else -> ShellRunner.Mode.NONE
}
