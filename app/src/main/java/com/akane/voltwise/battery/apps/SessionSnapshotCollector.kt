package com.akane.voltwise.battery.apps

import android.util.Log
import com.akane.voltwise.battery.data.PowerTransition
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.measurement.PowerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-discharge-session app breakdowns from two privileged dumps, run for the monitoring service's lifetime
 * ([run]). There is no periodic collection: a dump happens only here (plug-in, a new discharge session) and
 * when a screen asks [AppStatsSource].
 *
 * - **Baseline:** whenever the open session becomes a DISCHARGE session with no BASELINE (an unplug, monitoring
 *   start, a gap or reset that reopened it), wait [BASELINE_DEBOUNCE_MS] and store one. A change of open session
 *   before then cancels it. Keyed to the committed open-session row, so the row always exists first.
 * - **End:** at a plug-in that ended a DISCHARGE session ([PowerTransition]), wait [END_DEBOUNCE_MS] (the next
 *   transition cancels it), take a forced dump, store it as the END snapshot and the [AppUsageDelta] against the
 *   BASELINE as the session's breakdown (READY + basis); [AppStatsResult.NoAccess] → NO_ACCESS,
 *   [AppStatsResult.Failed] → FAILED. Once the debounce has passed, a later transition no longer cancels it.
 * - **Abandoned:** closed DISCHARGE sessions still PENDING that no end is being taken for (closed by Stop,
 *   process death, a gap, a Reset, or a plug-in cancelled by an unplug) are marked FAILED: at every transition,
 *   [SWEEP_DEBOUNCE_MS] after every change of open session (a Reset or gap reopens one without a transition), and
 *   [STARTUP_SWEEP_DELAY_MS] after start (after the repository has closed a session left open by a dead process).
 *   A plug-in's ended session is reserved from the moment its transition arrives, so a sweep never fails it while
 *   its end waits out the debounce.
 */
class SessionSnapshotCollector(
    private val stats: AppStatsSource,
    private val store: SessionSnapshotStore,
    private val transitions: Flow<PowerTransition>,
    private val log: (String) -> Unit = { Log.d(LOG_TAG, it) },
    private val warn: (String) -> Unit = { Log.w(LOG_TAG, it) },
    private val onDiagnostic: (DiagnosticCode) -> Unit = {},
) {
    private val writes = Mutex()
    private val finalized = mutableSetOf<String>()
    private val _finalizedSessions = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Notification only: consumers re-read the committed session; slow subscribers never block capture. */
    val finalizedSessions: SharedFlow<String> = _finalizedSessions.asSharedFlow()
    // Plug-in ends waiting out their debounce or being taken; every sweep leaves them alone.
    private val endsInProgress: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Runs until cancelled; the service cancels it when monitoring stops. */
    suspend fun run(): Unit = coroutineScope {
        launch { baselines() }
        // null = start: the sweep waits for the repository to close any session a dead process left open.
        merge(flowOf(null), transitions).collectLatest { transition ->
            guarded("transition") { onTransition(transition, this@coroutineScope) }
        }
    }

    // A query error in the flow itself is retried after [OPEN_SESSION_RETRY_MS]; the guard is a last resort
    // that keeps anything else from reaching the service.
    private suspend fun baselines() = guarded("open session") {
        store.openSession().retryWhen { cause, _ ->
            warn("open session query failed (${cause.javaClass.simpleName}: ${cause.message}); retrying in ${OPEN_SESSION_RETRY_MS / 1000} s")
            delay(OPEN_SESSION_RETRY_MS)
            true
        }.distinctUntilChanged().withIndex().collectLatest { (index, open) -> // distinct: a retry re-emits the same session
            coroutineScope {
                // The first emission is the start, which has its own sweep (after the repository's recovery).
                if (index > 0) launch {
                    delay(SWEEP_DEBOUNCE_MS)
                    guarded("sweep") { failAbandoned() }
                }
                if (open?.type == SessionType.DISCHARGE) guarded("baseline") { baseline(open.sessionId) }
            }
        }
    }

    private suspend fun baseline(sessionId: String) {
        if (store.hasBaseline(sessionId)) return
        delay(BASELINE_DEBOUNCE_MS)
        when (val result = stats.snapshot(force = true)) {
            is AppStatsResult.Ready -> {
                if (!result.snapshot.appMeasurementsComplete || result.snapshot.rejectedAppPowerRecords > 0) {
                    onDiagnostic(DiagnosticCode.ADVANCED_INCOMPLETE)
                    log("baseline skipped: incomplete app measurements")
                    return
                }
                val saved = writes.withLock { store.saveBaseline(sessionId, result.snapshot.toAppUsageSnapshot()) }
                if (saved) log("baseline stored session=$sessionId apps=${result.snapshot.apps.size}")
            }
            // Nothing stored: the session ends ABSOLUTE if a dump works at plug-in, else NO_ACCESS/FAILED.
            AppStatsResult.NoAccess -> log("baseline skipped: no privileged access")
            is AppStatsResult.Failed -> log("baseline failed: ${result.message}")
        }
    }

    private suspend fun onTransition(transition: PowerTransition?, scope: CoroutineScope) {
        if (transition == null) {
            delay(STARTUP_SWEEP_DELAY_MS)
            failAbandoned()
            return
        }
        val ended = transition.endedSessionId?.takeIf { transition.isPlugIn }
        if (ended == null) {
            failAbandoned()
            return
        }
        // Reserved at once: the open-session sweep runs [SWEEP_DEBOUNCE_MS] after the same plug-in.
        endsInProgress += ended
        var handedOff = false
        try {
            failAbandoned()
            delay(END_DEBOUNCE_MS)
            // Past the debounce: a later transition must not cancel the dump, but stopping the service does.
            scope.launch {
                try { guarded("end") { finishEnded(ended) } }
                finally { endsInProgress -= ended }
            }
            handedOff = true
        } finally {
            // Cancelled by the next transition (or a sweep error): that transition's sweep may now fail it.
            if (!handedOff) endsInProgress -= ended
        }
    }

    private suspend fun finishEnded(sessionId: String) {
        val result = stats.snapshot(force = true)
        writes.withLock {
            when (result) {
                is AppStatsResult.Ready -> {
                    val end = result.snapshot.toAppUsageSnapshot()
                    val computed = AppUsageDelta.compute(store.baseline(sessionId), end)
                    // Incomplete app evidence may include UIDs absent from the baseline, so matching UIDs is insufficient.
                    val delta = if (!result.snapshot.appMeasurementsComplete || result.snapshot.rejectedAppPowerRecords > 0) {
                        onDiagnostic(DiagnosticCode.ADVANCED_INCOMPLETE)
                        computed.copy(captureStartMs = null)
                    } else computed
                    if (store.saveEnd(sessionId, end, delta)) {
                        emitFinalized(sessionId)
                        log("end stored session=$sessionId basis=${delta.basis} rows=${delta.rows.size}")
                    }
                }
                AppStatsResult.NoAccess -> {
                    if (store.setStatus(sessionId, AppUsageStatus.NO_ACCESS)) emitFinalized(sessionId)
                }
                is AppStatsResult.Failed -> {
                    if (store.setStatus(sessionId, AppUsageStatus.FAILED)) emitFinalized(sessionId)
                }
            }
        }
        if (result !is AppStatsResult.Ready) log("end not captured session=$sessionId result=$result")
    }

    private suspend fun failAbandoned() = writes.withLock {
        store.pendingClosedDischarges()
            .filter { it !in endsInProgress }
            .forEach { sessionId ->
                if (store.setStatus(sessionId, AppUsageStatus.FAILED)) emitFinalized(sessionId)
                log("breakdown not captured session=$sessionId")
            }
    }

    private fun emitFinalized(sessionId: String) {
        if (finalized.add(sessionId)) _finalizedSessions.tryEmit(sessionId)
    }

    /** A storage or dump error must never take the monitoring service down; it leaves the session PENDING. */
    private suspend fun guarded(step: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("$step failed (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    private val PowerTransition.isPlugIn: Boolean
        get() = from == PowerState.DISCHARGING && to in setOf(PowerState.CHARGING, PowerState.PLUGGED)

    companion object {
        const val LOG_TAG = "BatStatsApps"
        const val END_DEBOUNCE_MS = 10_000L
        const val BASELINE_DEBOUNCE_MS = 30_000L
        const val STARTUP_SWEEP_DELAY_MS = 30_000L
        const val SWEEP_DEBOUNCE_MS = 5_000L
        const val OPEN_SESSION_RETRY_MS = 60_000L

        /** Set on the in-memory open session at creation: a breakdown is only ever taken for discharge sessions. */
        fun initialStatus(type: SessionType): AppUsageStatus =
            if (type == SessionType.DISCHARGE) AppUsageStatus.PENDING else AppUsageStatus.NOT_APPLICABLE
    }
}
