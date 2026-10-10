package com.akane.voltwise.battery.insights

import android.os.Build
import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.resolveFullUah
import com.akane.voltwise.battery.data.storedFullUah
import com.akane.voltwise.battery.data.sampling.KeyValueStore
import com.akane.voltwise.battery.insights.engine.InsightEngine
import com.akane.voltwise.battery.insights.engine.findingOrder
import com.akane.voltwise.battery.insights.model.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** One writer for analysis and feedback; never writes app snapshots or an open session's baseline. */
class InsightRepository(
    private val sessionDao: SessionDao,
    private val dailyDao: DailySummaryDao,
    private val appUsageDao: AppUsageDao,
    private val insightDao: InsightDao,
    scope: CoroutineScope,
    private val clock: Clock,
    private val dozeWhitelist: suspend () -> Set<String>?,
    private val privileged: () -> Boolean,
    private val store: KeyValueStore,
    private val maintenance: HistoryMaintenance,
    /** Latest charge counter and level, when available; FullCapacity also uses stored estimates. */
    private val capacityReading: () -> Pair<Long?, Int?> = { null to null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val analyzeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val analyze: (InsightInputs) -> InsightReport = { InsightEngine.analyze(it, Build.VERSION.SDK_INT) },
    private val highBatteryAlertEnabled: suspend () -> Boolean = { false },
    /** Production resolves the live device zone; explicit clocks retain their fixture zone by default. */
    private val currentZone: () -> ZoneId = { clock.zone },
) {
    private val mutex = Mutex()
    private val publishLock = Mutex()
    private val mutableReport = MutableStateFlow<InsightReport?>(null)
    val report: StateFlow<InsightReport?> = mutableReport.asStateFlow()
    private val mutableLastAnalyzedAt = MutableStateFlow<Long?>(null)
    val lastAnalyzedAt: StateFlow<Long?> = mutableLastAnalyzedAt.asStateFlow()
    private val mutableSuccessfulAnalysisRevision = MutableStateFlow(0L)
    /** In-process completed publications; persisted timestamps and feedback do not advance this. */
    val successfulAnalysisRevision: StateFlow<Long> = mutableSuccessfulAnalysisRevision.asStateFlow()
    private val initialization: Deferred<Unit>

    init {
        initialization = scope.async(ioDispatcher) {
            mutableLastAnalyzedAt.value = store.getString(LAST_ANALYZED_AT)?.toLongOrNull()
        }
        scope.launch(ioDispatcher) {
            initialization.await()
            insightDao.findings().collect { publish() }
        }
    }

    /** Startup decisions must distinguish an unloaded timestamp from an absent one. */
    suspend fun awaitLastAnalyzedAt(): Long? {
        initialization.await()
        return lastAnalyzedAt.value
    }

    suspend fun refresh() = mutex.withLock {
        // Load before advancing the timestamp; initialization never waits for analysis.
        initialization.await()
        val generation = maintenance.generation
        if (maintenance.isClearing) return@withLock
        val inputs = withContext(ioDispatcher) {
            val now = clock.millis()
            val zone = currentZone()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
            val sessions = sessionDao.closedSessionsBetween(now - InsightInputsBuilder.HISTORY_MS, now)
                .filter { it.endTime != null }
            val ids = sessions.map { it.sessionId }
            val rows = ids.chunked(QUERY_CHUNK).flatMap { appUsageDao.usageRowsForSessions(it) }
            val wakers = ids.chunked(QUERY_CHUNK).flatMap { appUsageDao.sessionWakers(it) }
            val (counter, level) = capacityReading()
            InsightInputsBuilder.build(
                now, today, resolveFullUah(counter, level, storedFullUah(sessions)), privileged(), sessions,
                dailyDao.range(today - InsightInputsBuilder.HISTORY_DAYS, today), rows, wakers,
                sessionDao.capacityEstimates(Int.MAX_VALUE).first(), dozeWhitelist(),
                insightDao.actionsOnce(), insightDao.findingsOnce(), zone = zone,
            ).copy(highBatteryAlertEnabled = highBatteryAlertEnabled())
        }
        val analyzed = withContext(analyzeDispatcher) { analyze(inputs) }
        withContext(ioDispatcher) {
            maintenance.mutations.withLock write@ {
                if (maintenance.isClearing || maintenance.generation != generation) return@write
                val existing = insightDao.findingsOnce().associateBy { it.key }
                val produced = analyzed.findings.map { it.key }.toSet()
                val updates = analyzed.findings.map { finding ->
                    val old = existing[finding.key]
                    val oldSeverity = enumName<Severity>(old?.severity)
                    val status = if (old?.status == InsightFindingStatus.DISMISSED &&
                        (oldSeverity == null || finding.severity.ordinal <= oldSeverity.ordinal)) {
                        InsightFindingStatus.DISMISSED
                    } else InsightFindingStatus.ACTIVE
                    val persisted = if (status == InsightFindingStatus.DISMISSED && oldSeverity != null) {
                        finding.copy(severity = maxOf(oldSeverity, finding.severity))
                    } else finding
                    FindingCodec.encode(persisted, old?.firstSeenAt ?: inputs.nowMs, inputs.nowMs, status,
                        old?.feedbackMultiplier ?: 1.0)
                } + existing.values.filter {
                    it.status == InsightFindingStatus.ACTIVE && it.key !in produced &&
                        // Unknown membership cannot establish that a whitelist finding has resolved.
                        (inputs.dozeUserWhitelist != null || it.type != FindingType.DOZE_WHITELISTED_DRAINER.name)
                }.map { it.copy(status = InsightFindingStatus.RESOLVED) }
                currentCoroutineContext().ensureActive()
                // Once rows can commit, their timestamp and publication must finish with them.
                withContext(NonCancellable) {
                    insightDao.upsertFindings(updates)
                    store.edit(mapOf(LAST_ANALYZED_AT to inputs.nowMs.toString()))
                    mutableLastAnalyzedAt.value = inputs.nowMs
                    publish()
                    mutableSuccessfulAnalysisRevision.value = mutableSuccessfulAnalysisRevision.value + 1
                }
            }
        }
    }

    suspend fun dismiss(key: String) = mutex.withLock {
        val generation = maintenance.generation
        if (maintenance.isClearing) return@withLock
        withContext(ioDispatcher) {
            maintenance.mutations.withLock write@ {
                if (maintenance.isClearing || maintenance.generation != generation) return@write
                insightDao.setStatus(key, InsightFindingStatus.DISMISSED)
                publish()
            }
        }
    }

    suspend fun notAProblem(key: String) = mutex.withLock {
        val generation = maintenance.generation
        if (maintenance.isClearing) return@withLock
        withContext(ioDispatcher) {
            maintenance.mutations.withLock write@ {
                if (maintenance.isClearing || maintenance.generation != generation) return@write
                val old = insightDao.findingsOnce().firstOrNull { it.key == key } ?: return@write
                if (old.status != InsightFindingStatus.ACTIVE) return@write
                if (maintenance.isClearing || maintenance.generation != generation) return@write
                insightDao.upsertFindings(listOf(old.copy(status = InsightFindingStatus.DISMISSED,
                    feedbackMultiplier = (old.feedbackMultiplier * 1.5).coerceAtMost(4.0))))
                publish()
            }
        }
    }

    private suspend fun publish() = publishLock.withLock {
        // Re-read inside the publish lock so buffered emissions cannot overwrite newer feedback.
        val rows = insightDao.findingsOnce()
        val active = rows.filter { it.status == InsightFindingStatus.ACTIVE }.mapNotNull(FindingCodec::decode)
            .sortedWith(findingOrder)
        mutableReport.value = InsightReport(mutableLastAnalyzedAt.value ?: rows.maxOfOrNull { it.lastSeenAt } ?: 0,
            active, active.firstOrNull { it.severity != Severity.INFO })
    }

    companion object {
        const val LAST_ANALYZED_AT = "insights.lastAnalyzedAt"
        private const val QUERY_CHUNK = 900
    }
}
