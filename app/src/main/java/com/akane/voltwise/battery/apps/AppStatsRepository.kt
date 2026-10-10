package com.akane.voltwise.battery.apps

import android.os.SystemClock
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.util.BatteryStatsBinaryOutput
import com.akane.voltwise.battery.util.BatteryStatsParser
import com.akane.voltwise.battery.util.BatteryStatsProtoParser
import com.akane.voltwise.battery.util.DumpOutput
import com.akane.voltwise.battery.util.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The privileged shell calls [AppStatsRepository] makes: [ShellRunner] on device, a fake in unit tests. */
interface StatsShell {
    /** The access mode found by the last probe, without probing again. */
    val mode: ShellRunner.Mode
    suspend fun detectMode(forceRefresh: Boolean): ShellRunner.Mode
    suspend fun exec(command: String): ShellRunner.Outcome
}

class ShellRunnerStatsShell(private val shell: ShellRunner) : StatsShell {
    override val mode: ShellRunner.Mode get() = shell.access.value
    override suspend fun detectMode(forceRefresh: Boolean) = shell.detectMode(forceRefresh)
    override suspend fun exec(command: String) = shell.exec(command)
}

/**
 * The one structured `dumpsys batterystats --proto --charged` reader. Concurrent callers join the dump in flight (a
 * [Mutex] guards it and the cache); the dump runs in [scope], so a caller that goes away does not
 * cancel it for the others. The last good snapshot answers for [TTL_MS] unless `force`, and only while
 * the access mode it was read with is still current. Parsing runs on [parseDispatcher]. Callers never
 * see an exception: no access is [AppStatsResult.NoAccess], anything else [AppStatsResult.Failed].
 * Nothing here polls: dumps happen only when a caller asks.
 */
class AppStatsRepository(
    private val shell: StatsShell,
    private val scope: CoroutineScope,
    private val onDiagnostic: (DiagnosticCode) -> Unit = {},
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) : AppStatsSource {
    private class Cached(val snapshot: BatteryStatsParser.FullSnapshot, val mode: ShellRunner.Mode, val atElapsedMs: Long)

    private val lock = Mutex()
    // Guarded by [lock].
    private var inFlight: Deferred<AppStatsResult>? = null
    private var cachedDump: Cached? = null
    private var generation = 0L

    private val _cached = MutableStateFlow<BatteryStatsParser.FullSnapshot?>(null)

    /**
     * The last successful dump, kept past the TTL (its `capturedAt` says how old it is). Reading it never
     * starts a dump: surfaces that must not trigger privileged work (Now) show this.
     */
    val cached: StateFlow<BatteryStatsParser.FullSnapshot?> = _cached.asStateFlow()

    override suspend fun snapshot(force: Boolean): AppStatsResult {
        val dump = lock.withLock {
            val fresh = cachedDump?.takeIf { entry ->
                val age = elapsedMs() - entry.atElapsedMs
                !force && age in 0 until TTL_MS && entry.mode == shell.mode
            }
            if (fresh != null) return AppStatsResult.Ready(fresh.snapshot)
            inFlight?.takeIf { it.isActive } ?: generation.let { started ->
                scope.async { dump(force, started) }.also { inFlight = it }
            }
        }
        return dump.await()
    }

    /** Drops the cache and detaches the dump in flight (its callers still get it); after `batterystats --reset`. */
    suspend fun invalidate() = lock.withLock {
        generation++
        cachedDump = null
        inFlight = null
        _cached.value = null
    }

    private suspend fun dump(force: Boolean, started: Long): AppStatsResult {
        val (result, mode) = try {
            read(force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onDiagnostic(DiagnosticCode.ADVANCED_READ_FAILED)
            AppStatsResult.Failed("Collection failed: ${e.javaClass.simpleName}") to null
        }
        lock.withLock {
            // An invalidated dump still answers its own callers but is neither cached nor current.
            if (started == generation) {
                if (result is AppStatsResult.Ready && mode != null) {
                    cachedDump = Cached(result.snapshot, mode, elapsedMs())
                    _cached.value = result.snapshot
                }
                inFlight = null
            }
        }
        return result
    }

    private suspend fun read(force: Boolean): Pair<AppStatsResult, ShellRunner.Mode?> {
        // A forced read (pull-to-refresh, plug/unplug) re-probes access; otherwise exec uses the cached mode.
        if (force && shell.detectMode(forceRefresh = true) == ShellRunner.Mode.NONE) return AppStatsResult.NoAccess to null
        return when (val outcome = shell.exec(COMMAND)) {
            is ShellRunner.Outcome.NoAccess -> AppStatsResult.NoAccess to null
            is ShellRunner.Outcome.Failure -> {
                // No mode at all, or a mode Android refuses this dump to (ADB grants on Android 16): no access.
                // ShellRunner.lastError keeps the refusal text (DumpOutput.REFUSED_CROSS_USER) for the UI.
                if (outcome.mode == ShellRunner.Mode.NONE || DumpOutput.isRefusal(outcome.message)) {
                    return AppStatsResult.NoAccess to null
                }
                onDiagnostic(DiagnosticCode.ADVANCED_READ_FAILED)
                AppStatsResult.Failed(describe(outcome)) to null
            }
            is ShellRunner.Outcome.Success -> {
                val parsed = withContext(parseDispatcher) {
                    BatteryStatsBinaryOutput.decode(outcome.output)?.let(BatteryStatsProtoParser::parse)
                }
                // Partial rejection stays READY with accepted rows; only total relevant rejection is a format failure.
                if (parsed == null || !parsed.hasValidWindow || parsed.hasOnlyRejectedAppPowerRecords) {
                    onDiagnostic(DiagnosticCode.ADVANCED_FORMAT_INVALID)
                    AppStatsResult.Failed(FORMAT_UNAVAILABLE) to null
                } else {
                    AppStatsResult.Ready(parsed.copy(source = "Android batterystats · ${outcome.mode.name}")) to outcome.mode
                }
            }
        }
    }

    private fun describe(failure: ShellRunner.Outcome.Failure): String = when (failure.mode) {
        ShellRunner.Mode.NONE -> failure.message // Not reached: read() returns NoAccess for a NONE failure.
        ShellRunner.Mode.SHIZUKU ->
            "Shizuku is connected but the dump failed: ${failure.message}. Try again, or restart Shizuku."
        ShellRunner.Mode.ROOT ->
            "Root is available but the dump failed: ${failure.message}."
        ShellRunner.Mode.ADB ->
            "DUMP and usage-stat access were detected but the dump failed: ${failure.message}."
    }

    companion object {
        const val COMMAND = BatteryStatsBinaryOutput.COMMAND
        const val TTL_MS = 60_000L
        const val FORMAT_UNAVAILABLE = "Battery statistics format unavailable or incomplete"
    }
}
