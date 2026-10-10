package com.akane.voltwise.battery.data

import android.util.Log
import androidx.room.withTransaction
import com.akane.voltwise.battery.apps.SessionSnapshotCollector
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.sampling.DailySummaryReplay
import com.akane.voltwise.battery.data.sampling.KeyValueStore
import com.akane.voltwise.battery.data.sampling.SamplerCapture
import com.akane.voltwise.battery.data.sampling.SamplerSink
import com.akane.voltwise.battery.data.sampling.SamplerState
import com.akane.voltwise.battery.data.sampling.SamplingController
import com.akane.voltwise.battery.data.sampling.SessionExtremes
import com.akane.voltwise.battery.data.sampling.SessionReport
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.diagnostics.DiagnosticStore
import com.akane.voltwise.battery.measurement.*
import com.akane.voltwise.settings.AppSettings
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Persisted Stop identity, also used by the application-owned insights refresh listener. */
internal const val MONITORING_STOPPED_CLOSE_REASON = "Monitoring stopped"

/** Local history reference frozen at monitoring start, before the generation's first write. */
internal suspend fun BatteryDao.retentionReferenceWallMs(): Long? = lastLocalSample()?.timestamp

/** Whether the writer's current session can be reset by a user action. */
internal fun resetApplies(open: ChargeSession?): Boolean = open?.type == SessionType.DISCHARGE

/** The repository's FIFO writer and acknowledged deletion seam, also runnable without Android. */
internal class HistoryWriter<E>(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val maintenance: HistoryMaintenance,
    private val openSessionId: () -> String?,
    private val deleteRow: suspend (String) -> Boolean,
    private val handleEvent: suspend (E) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private sealed interface Message<out E> {
        data class Event<E>(val value: E) : Message<E>
        data class Delete(val id: String, val result: CompletableDeferred<Boolean>) : Message<Nothing>
    }

    private val queue = Channel<Message<E>>(Channel.UNLIMITED)
    private val job = scope.launch(dispatcher) {
        for (message in queue) {
            try {
                when (message) {
                    is Message.Event -> handleEvent(message.value)
                    is Message.Delete -> message.result.complete(
                        message.id != openSessionId() && deleteRow(message.id),
                    )
                }
            } catch (e: Exception) {
                if (message is Message.Delete) message.result.completeExceptionally(e)
                // A storage call can be cancelled independently of the writer's owning scope.
                currentCoroutineContext().ensureActive()
                if (message is Message.Event) onFailure(e)
            }
        }
    }.also { writer -> writer.invokeOnCompletion { queue.close(it) } }

    fun trySend(event: E) = queue.trySend(Message.Event(event))
    suspend fun send(event: E) = queue.send(Message.Event(event))

    suspend fun deleteSession(id: String): Boolean {
        if (maintenance.isClearing) return false
        // Clear holds this same mutex while waiting for its queued event. Never take it on the writer.
        return maintenance.mutations.withLock {
            if (maintenance.isClearing) return@withLock false
            // Once enqueued, retain the mutation lock until the write finishes, even if the caller leaves.
            withContext(NonCancellable) { requestDeletion(id) }
        }
    }

    private suspend fun requestDeletion(id: String): Boolean {
        val result = CompletableDeferred<Boolean>()
        // Covers queued requests, in-flight requests, and a writer that already terminated.
        val completion = job.invokeOnCompletion { cause ->
            result.completeExceptionally(cause ?: IllegalStateException("History writer stopped"))
        }
        try {
            queue.send(Message.Delete(id, result))
            return result.await()
        } finally {
            completion.dispose()
        }
    }
}

/**
 * History and the observation model. Captures arrive from [SamplingController]'s thread; one
 * writer coroutine (the event loop below) owns the observation engines, the ETAs, the open session
 * and every write, in capture order. [PersistPolicy] picks which captures become rows; each row
 * commits in one transaction with its session row (and a session it closes) and the local day
 * rows its interval touches. The database keeps raw current; [realtimeFlow] is calibrated.
 */
class BatteryRepository(
    private val db: BatteryDatabase,
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val scope: CoroutineScope,
    private val maintenance: HistoryMaintenance,
    private val diagnostics: DiagnosticStore,
    private val sampler: SamplingController,
    private val calibration: CalibrationStore,
    private val retention: HistoryRetention,
    samplerPreferences: KeyValueStore,
) {
    private val batteryDao = db.batteryDao()
    val sessionDao = db.sessionDao()
    private val persistDao = db.persistDao()
    private val dailySummaryDao = db.dailySummaryDao()
    private val samplerState = SamplerState(samplerPreferences)
    // Lifecycle events are never dropped; samples are bounded by [queuedSamples] instead.
    private val events: HistoryWriter<Event>
    private val queuedSamples = AtomicInteger()
    private val lifecycle = Any()
    val isClearingHistory: Boolean get() = maintenance.isClearing

    // Confined to the writer coroutine.
    private val engine = ObservationEngine()
    private val sessionEngine = ObservationEngine()
    private val dischargeEta = DischargeEta()
    private var chargeEta = ChargeEta()
    private var savedTapers: Map<Int, Long>? = null // Loaded at the first start, on the writer rather than the caller's thread.
    private var generation: String? = null
    private var retentionReferenceWallMs: Long? = null
    private var session: ChargeSession? = null
    private var sessionExtremes = SessionExtremes()
    private var needsSessionRecovery = false
    private var lastPersisted: PersistPolicy.State? = null
    private var summaryAtLastPersist = ObservationSummary()
    private var lastCleanupElapsed = Long.MIN_VALUE
    private var samplesSinceCleanup = 0

    private val _realtime = MutableStateFlow(Realtime())
    val realtimeFlow = _realtime.asStateFlow()
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoringFlow = _isMonitoring.asStateFlow()
    private val _observation = MutableStateFlow(ObservationSummary())
    val observation = _observation.asStateFlow()
    private val _persisted = MutableSharedFlow<BatterySample>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every saved sample (with its row id), emitted after its transaction commits. */
    val persisted: SharedFlow<BatterySample> = _persisted.asSharedFlow()
    private val _closedSessions = MutableSharedFlow<ChargeSession>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** All newly closed session rows, emitted only after their writes commit, including same-power gaps. */
    val closedSessions: SharedFlow<ChargeSession> = _closedSessions.asSharedFlow()
    private val _powerTransitions = MutableSharedFlow<PowerTransition>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Plug-ins (DISCHARGING → CHARGING/PLUGGED) and unplugs (back to DISCHARGING) that closed a session,
     * emitted after the closed and the new session rows committed. Not replayed to late collectors.
     */
    val powerTransitions: SharedFlow<PowerTransition> = _powerTransitions.asSharedFlow()
    private enum class FailureSource { BATTERY, STATE_EVENTS, HISTORY, RETENTION }
    private val failures = MutableStateFlow<Map<FailureSource, String>>(emptyMap())
    val error = failures.map { values ->
        values.toSortedMap().values.joinToString("\n").ifEmpty { null }
    }.stateIn(scope, SharingStarted.Eagerly, null)
    private fun failure(source: FailureSource, message: String? = null) {
        failures.update { if (message == null) it - source else it + (source to message) }
    }
    val activeSessionFlow: Flow<ChargeSession?> = sessionDao.activeFlow()
    val settingsFlow = settingsRepository.flow

    private sealed interface Event {
        data class Start(val generation: String) : Event
        data class Sample(val capture: SamplerCapture) : Event
        data object Stop : Event
        data object Reset : Event
        data object Backfill : Event
        data class Clear(val result: CompletableDeferred<Unit>) : Event
    }

    private val sink = object : SamplerSink {
        override fun onReading(sample: BatterySample, expectedIntervalMs: Long) {
            // update, not a plain set: a concurrent calibration change retries with the new value.
            _realtime.update { Realtime(sample, calibration.state.value.effective, expectedIntervalMs) }
        }

        override fun onObserved(capture: SamplerCapture): Boolean {
            if (queuedSamples.incrementAndGet() > MAX_QUEUED_SAMPLES) {
                queuedSamples.decrementAndGet()
                return false
            }
            return events.trySend(Event.Sample(capture)).isSuccess
        }

        override fun onBatteryIssue(message: String?) = failure(FailureSource.BATTERY, message)
        override fun onStateEventsIssue(message: String?) = failure(FailureSource.STATE_EVENTS, message)
    }

    init {
        sampler.attach(sink)
        scope.launch {
            calibration.state.collect { state -> _realtime.update { it.copy(calibration = state.effective) } }
        }
        events = HistoryWriter(
            scope = scope,
            dispatcher = Dispatchers.IO,
            maintenance = maintenance,
            openSessionId = { session?.sessionId },
            deleteRow = { sessionDao.deleteSession(it, generation) },
            handleEvent = { event ->
                if (event is Event.Sample) queuedSamples.decrementAndGet()
                when (event) {
                    is Event.Start -> start(event.generation)
                    // A capture of a stopped generation may still be in flight; it is not observed.
                    is Event.Sample -> if (event.capture.point.generation == generation) process(event.capture)
                    Event.Stop -> {
                        generation = null
                        engine.stop()
                        _observation.value = engine.summary
                        finishSession(MONITORING_STOPPED_CLOSE_REASON)
                    }
                    Event.Reset -> if (resetApplies(session)) {
                        resetObservationState()
                        finishSession("Observation reset by user")
                        seedDischargeEta()
                        sampler.restartSequence()
                    }
                    // With monitoring on, its start already ran it, before this generation's first capture.
                    Event.Backfill -> if (generation == null) backfillDailySummaries()
                    is Event.Clear -> clear(event.result)
                }
            },
            onFailure = { e ->
                diagnostics.record(DiagnosticCode.HISTORY_WRITE_FAILED)
                failure(FailureSource.HISTORY, "History collection failed (${e.javaClass.simpleName}); live battery readings remain available")
                sampler.markGap()
            },
        )
    }

    fun startSampling() {
        synchronized(lifecycle) {
            if (isClearingHistory || !_isMonitoring.compareAndSet(expect = false, update = true)) return
            diagnostics.record(DiagnosticCode.MONITORING_STARTED)
            val generation = UUID.randomUUID().toString()
            // Queued before the sampler can capture under this generation.
            events.trySend(Event.Start(generation))
            sampler.startMonitoring(generation)
        }
    }

    fun stopSampling() {
        synchronized(lifecycle) {
            if (_isMonitoring.value) diagnostics.record(DiagnosticCode.MONITORING_STOPPED)
            _isMonitoring.value = false
            sampler.stopMonitoring()
            // Captures still in flight for the old generation are dropped by the writer after this.
            events.trySend(Event.Stop)
        }
    }

    /** Activity resume/manual refresh gives ordinary information without starting background work. */
    fun refreshNow(onComplete: suspend (Realtime) -> Unit = {}) {
        // The capture runs on the sampler thread; callers (e.g. WidgetUpdater) expect the callback on main.
        scope.launch(Dispatchers.Main.immediate) {
            try { sampler.refresh() }
            finally { onComplete(_realtime.value) }
        }
    }

    /**
     * One fresh reading for widgets: never observed or saved, works with monitoring off. Falls back
     * to the last realtime value when Android supplies no reading.
     */
    suspend fun readOnce(): Realtime {
        val sample = sampler.readOnce() ?: return _realtime.value
        return Realtime(sample, calibration.state.value.effective)
    }

    /**
     * The once-per-install daily-summary backfill at app start as well as at monitoring start, so an upgrade with
     * monitoring off still fills History › Days. Runs on the writer; a no-op once done.
     */
    fun backfillDailySummariesOnce() {
        events.trySend(Event.Backfill)
    }

    /** Resets only an open discharge window, as decided when the writer processes the request. */
    fun resetObservation() {
        events.trySend(Event.Reset)
    }

    suspend fun startSession(type: SessionType) {
        require(_isMonitoring.value) { "Start monitoring before creating an observation" }
        val actual = SessionReport.sessionType(realtimeFlow.value.powerState)
        require(type == actual) { "Session type must match the observed charging state" }
        resetObservation()
    }

    suspend fun endCurrentSession() = resetObservation()

    /** Decided against writer-owned state after every earlier sample/Stop, even with monitoring off. */
    suspend fun deleteSession(id: String): Boolean = events.deleteSession(id)

    /** Serialized with imports and the sample writer; no queued sample can survive deletion. */
    suspend fun clearHistory(stopService: () -> Unit) = maintenance.clear(
        stopMonitoring = {
            withContext(Dispatchers.Main.immediate) { stopService() }
            stopSampling()
        },
        delete = {
            val result = CompletableDeferred<Unit>()
            events.send(Event.Clear(result))
            result.await()
        }
    )

    private suspend fun start(generation: String) {
        this.generation = generation
        // Freeze the reference before any sample under this generation can be saved.
        retentionReferenceWallMs = batteryDao.retentionReferenceWallMs()
        session = null
        if (savedTapers == null) savedTapers = samplerState.loadTapers().also { chargeEta = ChargeEta(it) }
        resetObservationState()
        needsSessionRecovery = true
        recoverInterruptedSession()
        backfillDailySummaries()
        seedDischargeEta()
    }

    private suspend fun clear(result: CompletableDeferred<Unit>) {
        try {
            db.withTransaction {
                batteryDao.clearAll(); sessionDao.clearAll(); dailySummaryDao.clearAll(); db.appUsageDao().clearSnapshots()
                // Actions are Undo authority for real system changes, not disposable history.
                db.insightDao().clearFindings()
            }
            // Calibration and learned charge tapers describe the device, not history: kept.
            session = null
            needsSessionRecovery = false
            failure(FailureSource.HISTORY); failure(FailureSource.RETENTION)
            resetObservationState()
            result.complete(Unit)
        } catch (e: Exception) { result.completeExceptionally(e); throw e }
    }

    private fun resetObservationState() {
        engine.reset(); sessionEngine.reset(); dischargeEta.reset(); chargeEta.reset()
        sessionExtremes = SessionExtremes()
        lastPersisted = null
        summaryAtLastPersist = ObservationSummary()
        _observation.value = engine.summary
    }

    private suspend fun process(capture: SamplerCapture) {
        recoverInterruptedSession()
        val point = capture.point
        val raw = capture.sample
        val old = engine.summary
        val summary = engine.accept(point)
        val changed = old.latest?.power != point.power
        val gap = old.gaps != summary.gaps
        if (gap) diagnostics.record(DiagnosticCode.OBSERVATION_GAP)
        if (old.counterGaps != summary.counterGaps) diagnostics.record(DiagnosticCode.CHARGE_UNAVAILABLE)

        // A power change or gap closes the open session; the closed row commits in the same
        // transaction as the new one. [session] changes only after that commit, so a failed write
        // leaves the old session open and the next capture closes it again: its type no longer
        // matches the power state (and the failure made that capture a GAP).
        val open = session
        val mismatch = open != null && open.type != SessionReport.sessionType(point.power)
        val ended = if (open != null && (changed || gap || mismatch)) {
            val closing = if (changed && !gap) SessionReport.reportPowerBoundary(open, raw, point, sessionEngine, sessionExtremes) else open
            closing.copy(endTime = closing.lastSampleTime ?: closing.startTime, activeKey = null,
                closeReason = if (gap) summary.lastIssue ?: "Observation gap" else "Power state changed")
        } else null
        val current = if (open == null || ended != null) {
            sessionEngine.reset()
            sessionExtremes = SessionExtremes()
            // On the in-memory row, since every save rewrites it: PENDING for discharge, NOT_APPLICABLE otherwise.
            SessionReport.open(point, raw).let { it.copy(appUsageStatus = SessionSnapshotCollector.initialStatus(it.type)) }
        } else open
        val sessionSummary = sessionEngine.accept(point)
        val calibratedUa = BatteryReading.calibratedUa(point.currentUa, calibration.state.value.effective)
        sessionExtremes = sessionExtremes.plus(BatteryReading.powerMw(calibratedUa, point.voltageMv), raw.temperatureDeciC)
        calibration.accept(point, raw.plugged) // RAW current: detection must never see its own output.

        // Both estimators see every observation in order; at most one has an estimate.
        val dischargeEstimate = dischargeEta.accept(point)
        val chargeEstimate = chargeEta.accept(point, raw.plugged, capture.chargeRemainingMs)
        saveLearnedTapers()
        val eta = dischargeEstimate ?: chargeEstimate
        val sample = raw.copy(sessionId = current.sessionId, etaMs = eta?.remainingMs, etaBasis = eta?.basis?.name,
            boundaryReason = if (gap) summary.lastIssue else null)
        _observation.value = summary
        _realtime.update { it.copy(sample = mergePersistedReading(it.sample, sample)) }

        val state = PersistPolicy.State(point.elapsedMs, raw.status, raw.plugged, raw.levelPercent, point.generation)
        // An engine gap is a GAP boundary whatever the capture's label. A session boundary always
        // saves, whatever the policy says: the closed row and the new one must reach the database.
        val reason = PersistPolicy.decide(lastPersisted, state, if (gap) Boundary.GAP else point.boundary,
            point.interactive, capture.poll) ?: when {
            ended != null -> if (gap) PersistReason.GAP else PersistReason.POWER
            open == null -> PersistReason.FIRST
            else -> return
        }
        val updated = SessionReport.report(current, sample, sessionSummary, sessionExtremes)
        val interval = DailySummaryAggregator.interval(summaryAtLastPersist, summary, sample.temperatureDeciC)
        val zone = ZoneId.systemDefault()
        val rowId = try {
            db.withTransaction {
                // Update-or-insert only: REPLACE on charge_sessions would cascade-delete its app usage.
                ended?.let { if (persistDao.updateSession(it) == 0) persistDao.insertSession(it) }
                val days = interval?.let { added ->
                    val rows = DailySummaryAggregator.days(added, zone).mapNotNull { dailySummaryDao.byDay(it) }
                    DailySummaryAggregator.apply(rows.associateBy { it.epochDay }, added, zone, sample.timestamp)
                }.orEmpty()
                persistDao.persistSample(sample, updated, days)
            }
        } catch (e: Exception) {
            // Rolled back: [session] is still the open row. Recovery closes any active row before the
            // next write; that write re-opens or properly closes it.
            needsSessionRecovery = true
            throw e
        }
        session = updated
        lastPersisted = state
        summaryAtLastPersist = summary
        failure(FailureSource.HISTORY)
        Log.d(SamplingController.LOG_TAG, "save reason=$reason level=${sample.levelPercent} power=${point.power} " +
            "screen=${if (point.interactive) "on" else "off"} boundary=${point.boundary}")
        if (rowId != -1L) {
            _persisted.tryEmit(sample.copy(id = rowId))
            samplesSinceCleanup++
        }
        if (ended != null) {
            _closedSessions.tryEmit(ended)
            emitTransition(ended, point, updated)
        }
        if (lastCleanupElapsed == Long.MIN_VALUE || samplesSinceCleanup >= HistoryLimits.CLEANUP_SAMPLE_INTERVAL || point.elapsedMs - lastCleanupElapsed >= 86_400_000) {
            lastCleanupElapsed = point.elapsedMs
            samplesSinceCleanup = 0
            try { cleanup(point); failure(FailureSource.RETENTION) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                diagnostics.record(DiagnosticCode.HISTORY_WRITE_FAILED)
                failure(FailureSource.RETENTION, "History retention failed (${e.javaClass.simpleName}); retrying on the next maintenance interval")
            }
        }
    }

    /** From the ended session's type, so a boundary retried after a failed write still reports its origin. */
    private fun emitTransition(ended: ChargeSession, point: Observation, started: ChargeSession) {
        val from = when (ended.type) {
            SessionType.CHARGE -> PowerState.CHARGING
            SessionType.DISCHARGE -> PowerState.DISCHARGING
            SessionType.PLUGGED -> PowerState.PLUGGED
            SessionType.UNKNOWN -> PowerState.UNKNOWN
        }
        val powered = setOf(PowerState.CHARGING, PowerState.PLUGGED)
        val plugIn = from == PowerState.DISCHARGING && point.power in powered
        val unplug = from in powered && point.power == PowerState.DISCHARGING
        if (!(plugIn || unplug)) return
        _powerTransitions.tryEmit(PowerTransition(from, point.power, point.wallMs, point.elapsedMs, ended.sessionId, started.sessionId))
    }

    private fun saveLearnedTapers() {
        val learned = chargeEta.learnedTaperMsPerPercent
        if (learned == savedTapers) return
        samplerState.saveTapers(learned)
        savedTapers = learned
    }

    private suspend fun finishSession(reason: String) {
        val current = session
        session = null; sessionEngine.reset()
        if (current != null) {
            try {
                // Update-or-insert, never REPLACE (see process).
                val closed = current.copy(endTime = current.lastSampleTime ?: current.startTime,
                    activeKey = null, closeReason = reason)
                sessionDao.upsert(closed)
                _closedSessions.tryEmit(closed)
            } catch (e: Exception) {
                needsSessionRecovery = true
                throw e
            }
        }
    }

    private suspend fun recoverInterruptedSession() {
        if (!needsSessionRecovery) return
        sessionDao.closeInterrupted("Process stopped; interval ended at last stored reading")
        needsSessionRecovery = false
    }

    /**
     * Once per install: rebuilds `daily_summaries` from the stored samples, one UTC day of rows at a
     * time. It runs in the writer at app start with no generation running, or before a generation's first
     * capture, so it never races the live day rows; it replaces the rows it computes, so an interrupted run
     * simply repeats. A failure leaves the flag unset and retries at the next app or monitoring start.
     */
    private suspend fun backfillDailySummaries() {
        if (samplerState.backfillDone) return
        try {
            val replay = DailySummaryReplay(ZoneId.systemDefault(), System.currentTimeMillis())
            // One representative row per UTC day that holds this app's own samples.
            for (day in batteryDao.chartSamples(0, Long.MAX_VALUE, DAY_MS).first()) {
                val start = day.timestamp / DAY_MS * DAY_MS
                batteryDao.samplesBetween(start, start + DAY_MS - 1).first().forEach(replay::add)
            }
            val rows = replay.result
            if (rows.isNotEmpty()) dailySummaryDao.upsertAll(rows)
            samplerState.backfillDone = true
            Log.d(SamplingController.LOG_TAG, "daily summaries backfilled days=${rows.size}")
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(SamplingController.LOG_TAG, "Daily summary backfill failed (${e.javaClass.simpleName}); retrying at the next start")
        }
    }

    /** The 7-day typical drain seeds the discharge ETA until live data dominates. */
    private suspend fun seedDischargeEta() {
        try {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val from = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(6)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val sessions = sessionDao.closedDischargeSessionsBetween(from, now)
            dischargeEta.seed(TypicalDischargeSeed.rateUa(sessions, from, now))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // No seed: the estimate waits for live data.
        }
    }

    private suspend fun cleanup(point: Observation) {
        // Waits for the settings migration (v2 "auto-cleanup off" becomes Forever); throws if it failed.
        val purgeFailure = try {
            retention.cutoff(point.wallMs, point.elapsedMs, retentionReferenceWallMs)?.let { cutoff -> HistoryPolicy.purgeExpired(db, cutoff) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        // The size bound never waits on the retention setting: a failed cutoff still trims.
        sessionDao.boundStorage()
        batteryDao.boundStorage() // Both bounds reserve headroom for the next cleanup interval.
        purgeFailure?.let { throw it }
    }

    fun samplesBetween(start: Long, end: Long) = batteryDao.samplesBetween(start, end)
    fun samplesForSession(sessionId: String) = batteryDao.samplesForSession(sessionId)
    suspend fun getSettings(): AppSettings = settingsRepository.flow.first()

    /**
     * The latest capture. [sample] holds raw BatteryManager values as stored; [currentUa], [currentMa]
     * and [powerMw] apply [calibration]. [expectedIntervalMs] is the cadence the capture was taken at.
     */
    data class Realtime(
        val sample: BatterySample? = null,
        val calibration: CurrentCalibration = CurrentCalibration.IDENTITY,
        val expectedIntervalMs: Long = SamplingPolicy.SCREEN_ON_INTERVAL_MS,
    ) {
        val level: Int? get() = sample?.levelPercent
        val plugged: Int? get() = sample?.plugged
        val currentUa: Long? get() = BatteryReading.calibratedUa(sample?.currentNowUa, calibration)
        val currentMa: Double? get() = currentUa?.div(1000.0)
        val voltageMv: Int? get() = sample?.voltageMv
        val powerMw: Double? get() = BatteryReading.powerMw(currentUa, sample?.voltageMv)
        val temperatureC: Float? get() = sample?.temperatureDeciC?.div(10f)
        val powerState: PowerState get() = BatteryReading.powerState(sample?.status ?: 1, sample?.plugged)
    }

    private companion object {
        const val MAX_QUEUED_SAMPLES = 64
        const val DAY_MS = 86_400_000L
    }
}
