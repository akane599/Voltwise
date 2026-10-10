package com.akane.voltwise.viewmodel

import com.akane.voltwise.battery.data.db.InsightActionEntity
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.data.db.MESSAGE_CHANGED_EXTERNALLY
import com.akane.voltwise.battery.data.db.SessionDao
import com.akane.voltwise.battery.insights.InsightInputsBuilder
import com.akane.voltwise.battery.insights.InsightRepository
import com.akane.voltwise.battery.insights.actions.ActionResult
import com.akane.voltwise.battery.insights.actions.InsightActionRepository
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.*
import com.akane.voltwise.battery.util.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Screen seam. Observation never analyzes or executes an action. */
interface InsightsRepository {
    val report: StateFlow<InsightReport?>
    val actions: Flow<List<InsightActionEntity>>
    /** Null until access detection completes; false means detection found no Shizuku/root. */
    val privileged: Flow<Boolean?>
    val lastAnalyzedAt: Flow<Long?>
    val successfulAnalysisRevision: StateFlow<Long>
    val eligibleSessionCount: Flow<Int>
    suspend fun analyzeNow()
    suspend fun dismiss(key: String)
    suspend fun notAProblem(key: String)
    suspend fun apply(finding: Finding, recommendation: Recommendation): ActionResult
    suspend fun undo(actionId: Long): ActionResult
}

class DefaultInsightsRepository(
    private val insights: InsightRepository,
    private val actionRepository: InsightActionRepository,
    private val shellRunner: ShellRunner,
    sessionDao: SessionDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : InsightsRepository {
    override val report = insights.report
    override val actions = actionRepository.actions
    override val lastAnalyzedAt = insights.lastAnalyzedAt
    override val successfulAnalysisRevision = insights.successfulAnalysisRevision
    override val privileged: Flow<Boolean?> = flow {
        // NONE is also ShellRunner's initial value; keep pending applies unknown during detection.
        if (shellRunner.access.value == ShellRunner.Mode.NONE) {
            emit(null)
            shellRunner.detectMode()
        }
        emitAll(shellRunner.access.map { it == ShellRunner.Mode.SHIZUKU || it == ShellRunner.Mode.ROOT })
    }
    override val eligibleSessionCount = sessionDao.filteredSessions(null, "", Int.MAX_VALUE).map { sessions ->
        val inputs = InsightInputsBuilder.build(
            clock(), 0, null, sessions, emptyList(), emptyList(), emptyList(), emptyList(),
            null, emptyList(), emptyList(),
        )
        AppWindows.select(inputs).size
    }.flowOn(Dispatchers.Default)
    override suspend fun analyzeNow() {
        shellRunner.detectMode(forceRefresh = true)
        insights.refresh()
    }
    override suspend fun dismiss(key: String) = insights.dismiss(key)
    override suspend fun notAProblem(key: String) = insights.notAProblem(key)
    override suspend fun apply(finding: Finding, recommendation: Recommendation) = actionRepository.apply(finding, recommendation)
    override suspend fun undo(actionId: Long) = actionRepository.undo(actionId)
}

internal fun InsightActionEntity.undoable(): Boolean =
    type != ActionType.FORCE_STOP.name && (status == InsightActionStatus.APPLIED || status == InsightActionStatus.UNKNOWN)

internal fun Finding.toInsightState(
    privileged: Boolean,
    actions: List<InsightActionEntity>,
    generatedAtMs: Long,
): InsightFindingState = InsightFindingState(
    key, type, severity, confidence, score, subject, direction,
    evidence.insightSnapshot(), series.insightSnapshot(),
    recommendations.map { rec ->
        val applied = actions.any { row ->
            row.type == rec.action.name &&
                (row.undoable() ||
                    (subject == Subject.Device && rec.action == ActionType.ENABLE_HIGH_BATTERY_ALERT &&
                        row.status == InsightActionStatus.ONE_SHOT &&
                        (row.appliedAt ?: row.createdAt) >= generatedAtMs)) &&
                // Reoffering removal in a newer report proves live whitelist membership.
                !(rec.action == ActionType.REMOVE_DOZE_WHITELIST &&
                    (row.appliedAt ?: row.createdAt) < generatedAtMs) &&
                when (val subject = subject) {
                    is Subject.App -> row.packageName == subject.packageName && row.uid == subject.uid
                    Subject.Device -> rec.action == ActionType.ENABLE_HIGH_BATTERY_ALERT || row.findingKey == key
                }
        }
        RecommendationState(rec.action, rec.reversible, rec.requiresPrivilege, (!rec.requiresPrivilege || privileged) && !applied, applied)
    }.insightSnapshot(),
    attributions.insightSnapshot(),
)

internal fun actionStates(
    actions: List<InsightActionEntity>,
    findings: List<InsightFindingState>,
): List<AppliedInsightAction> = actions.map { row ->
    AppliedInsightAction(
        row.id, row.findingKey, ActionType.entries.firstOrNull { it.name == row.type }, row.packageName,
        row.status, row.appliedAt, row.undoable(),
        findings.firstOrNull { it.type == FindingType.ACTION_EFFECT && it.key.endsWith(":${row.id}") &&
            (it.subject as? Subject.App)?.packageName == row.packageName },
        changedExternally = row.status == InsightActionStatus.REVERTED && row.message == MESSAGE_CHANGED_EXTERNALLY,
        targetState = row.targetState,
    )
}.insightSnapshot()
