package com.akane.voltwise.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akane.voltwise.battery.apps.AppInfo
import com.akane.voltwise.battery.apps.AppInfoSource
import com.akane.voltwise.battery.apps.AppLabel
import com.akane.voltwise.battery.apps.AppUsageSnapshot
import com.akane.voltwise.battery.apps.TopApps
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.DailySummary
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.resolveFullUah
import com.akane.voltwise.battery.data.uah
import com.akane.voltwise.battery.insights.engine.detectors.app.AppContext
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.battery.measurement.CalibrationState
import com.akane.voltwise.battery.measurement.DailySummaryAggregator
import com.akane.voltwise.battery.measurement.EtaHold
import com.akane.voltwise.battery.measurement.HealthSummary
import com.akane.voltwise.battery.service.MonitoringControl
import com.akane.voltwise.settings.useFahrenheit
import com.akane.voltwise.ui.components.chart.ChartMath
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/**
 * Now: the hero (level, state, ETA, Start/Stop), the current trace, live readouts, the on-battery window's drain,
 * and the Today / Health / Top apps cards. Everything is derived from flows while the screen collects [state]
 * (stopped 5 s after it leaves), on [computeDispatcher]; the trace is downsampled there too. The mapping rules are
 * in [NowMapping]; the only UI-package types used are the chart's data points, since ChartMath downsamples them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowViewModel(
    private val source: NowRepository,
    private val monitoring: MonitoringControl,
    private val appInfo: AppInfoSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val range = savedStateHandle.getStateFlow(KEY_RANGE, TraceRange.LIVE.name)
        .map { name -> TraceRange.entries.firstOrNull { it.name == name } ?: TraceRange.LIVE }
    private val startBlocked = MutableStateFlow(false)

    private class Live(val hero: HeroState, val readouts: Readouts, val nowMs: Long, val counterUah: Long?, val levelPct: Int?)
    private class Rows(val endMs: Long, val samples: List<BatterySample>)
    private class Now(val live: Live, val trace: TraceState, val fahrenheit: Boolean, val calibration: CalibrationState)
    private class Cards(val today: DailySummary?, val health: HealthSummary?, val topApps: TopAppsState, val onBattery: ChargeSession?)

    private val live: Flow<Live> = combine(
        source.realtime.scan(EtaHold.Reading()) { previous, reading -> EtaHold.next(previous, reading) },
        monitoring.isMonitoring.onEach { active -> if (active) startBlocked.value = false },
        startBlocked,
    ) { reading, on, blocked ->
        Live(
            hero = NowMapping.hero(reading, on, blocked),
            readouts = NowMapping.readouts(reading.reading),
            nowMs = reading.reading.sample?.timestamp ?: clock(),
            counterUah = reading.reading.sample?.chargeCounterUah,
            levelPct = reading.reading.level,
        )
    }

    private val trace: Flow<TraceState> = range.flatMapLatest { selected ->
        val rows = if (selected == TraceRange.LIVE) liveRows() else historyRows(selected)
        combine(rows, source.calibration.map { it.effective }.distinctUntilChanged()) { window, calibration ->
            NowMapping.trace(selected, window.samples, window.endMs, calibration)
        }.mapLatest { trace ->
            if (trace.points.size <= NowMapping.DOWNSAMPLE_ABOVE) trace
            else trace.copy(points = ChartMath.downsampleMinMaxAsync(trace.points, NowMapping.DOWNSAMPLE_BUCKETS, computeDispatcher))
        }
    }

    // Re-evaluated at every reading (2 s while visible), so the card moves to the new day after midnight.
    private val today: Flow<DailySummary?> = source.realtime
        .map { DailySummaryAggregator.epochDay(clock(), zone()) }
        .distinctUntilChanged()
        .flatMapLatest { day -> source.day(day) }

    private val health: Flow<HealthSummary?> = combine(
        source.recentSessions(HealthSummary.SESSIONS),
        source.design.map { it.uah }.distinctUntilChanged(),
    ) { sessions, designUah -> NowMapping.healthSummary(sessions, designUah) }

    private val topApps: Flow<TopAppsState> = combine(
        source.cachedAppUsage,
        source.activeSession.map { session -> session?.takeIf { it.type == SessionType.DISCHARGE }?.sessionId }.distinctUntilChanged(),
    ) { usage, sessionId -> usage to sessionId }
        .mapLatest { (usage, sessionId) -> topApps(usage, sessionId) }

    val state: StateFlow<NowUiState> = combine(
        combine(live, trace, source.settings.map { it.useFahrenheit }.distinctUntilChanged(), source.calibration, ::Now),
        combine(today, health, topApps, source.dischargeSessions(1).map { it.firstOrNull() }, ::Cards),
        source.insights,
        source.lastAnalyzedAt,
        source.eligibleSessionCount.distinctUntilChanged(),
    ) { now, cards, report, lastAnalyzedAt, eligibleSessions ->
        val fullUah = resolveFullUah(now.live.counterUah, now.live.levelPct, cards.health?.estimate?.fullUah)
        NowUiState(
            nowMs = now.live.nowMs,
            hero = now.live.hero,
            readouts = now.live.readouts,
            trace = now.trace,
            sinceUnplug = NowMapping.sinceUnplug(cards.onBattery, now.live.hero.monitoring, fullUah),
            today = cards.today?.let { NowMapping.today(it, fullUah) },
            health = cards.health?.let(NowMapping::health),
            topApps = cards.topApps,
            insightsSummary = report?.takeIf { lastAnalyzedAt != null }?.let {
                val active = it.findings.count { finding -> finding.severity != Severity.INFO }
                // What Insights lists under Changes: informational trends and the capacity decline.
                val changes = it.findings.count { finding -> isInsightChange(finding.type, finding.severity, finding.direction) }
                InsightsSummary(
                    headline = it.headline?.let { finding ->
                        InsightHeadline(finding.key, finding.type, finding.severity, (finding.subject as? Subject.App)?.packageName)
                    },
                    activeFindingCount = active,
                    changeCount = changes,
                    // Insights' rule: findings and changes win, then too few comparable sessions is "still learning".
                    learning = active == 0 && changes == 0 && eligibleSessions < AppContext.MIN_ELIGIBLE_WINDOWS,
                )
            },
            calibrationNotice = NowMapping.notice(now.calibration),
            useFahrenheit = now.fahrenheit,
        )
    }
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NowUiState())

    /** State changes; navigation events are the screen's. */
    fun onEvent(event: NowEvent) {
        when (event) {
            NowEvent.ToggleMonitoring -> toggleMonitoring()
            is NowEvent.SelectRange -> savedStateHandle[KEY_RANGE] = event.range.name
            // Only the current on-battery window can be reset: a Reset racing a plug-in must not end the CHARGE session.
            NowEvent.ResetObservation -> if (state.value.sinceUnplug?.current == true) source.resetObservation()
            NowEvent.UndoCalibration -> source.undoCalibration()
            NowEvent.KeepCalibration -> source.dismissCalibrationNotice()
            NowEvent.OpenHistory, NowEvent.OpenHealth, NowEvent.OpenApps, is NowEvent.OpenApp -> Unit
        }
    }

    private fun toggleMonitoring() {
        if (monitoring.isMonitoring.value) {
            startBlocked.value = false
            monitoring.stop()
        } else {
            // BLOCKED: Android refused the foreground-service start (MonitoringControl); say so and keep the button.
            startBlocked.value = monitoring.start() == MonitoringControl.StartResult.BLOCKED
        }
    }

    /** Trailing [TraceRange.LIVE] window: stored rows seed it, then every realtime reading is appended. */
    private fun liveRows(): Flow<Rows> = flow {
        val buffer = ArrayList<BatterySample>()
        source.samplesSince(clock() - TraceRange.LIVE.spanMs).first().forEach { NowMapping.appendLive(buffer, it) }
        emit(Rows(buffer.lastOrNull()?.timestamp ?: clock(), buffer.toList()))
        source.realtime.collect { reading ->
            val sample = reading.sample ?: return@collect
            if (NowMapping.appendLive(buffer, sample)) emit(Rows(sample.timestamp, buffer.toList()))
        }
    }

    /** Stored rows for [selected]; the window ends at the time of each emission (a save, ≥ 30 s apart). */
    private fun historyRows(selected: TraceRange): Flow<Rows> = flow {
        emitAll(source.samplesSince(clock() - selected.spanMs).map { rows -> Rows(clock(), rows) })
    }

    private suspend fun topApps(usage: AppUsageSnapshot?, dischargeSessionId: String?): TopAppsState {
        if (usage == null) return TopAppsState.Empty
        val top = TopApps.of(usage, dischargeSessionId?.let { source.baseline(it) }) ?: return TopAppsState.Empty
        return TopAppsState.Ready(
            rows = top.rows.map { row ->
                TopApp(row.uid, row.packageName, AppLabel.of(row.uid, row.packageName, info(row.packageName)), row.powerMah, row.share)
            },
            basis = top.basis,
            capturedAtMs = top.capturedAt,
        )
    }

    private suspend fun info(packageName: String): AppInfo? = try {
        appInfo.info(packageName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val KEY_RANGE = "now.range"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
