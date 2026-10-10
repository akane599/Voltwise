package com.akane.voltwise.battery.insights.actions

import com.akane.voltwise.battery.actions.*
import com.akane.voltwise.battery.data.db.InsightActionEntity
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.data.db.InsightActionStatus.*
import com.akane.voltwise.battery.data.db.InsightDao
import com.akane.voltwise.battery.insights.model.*
import com.akane.voltwise.battery.util.ExecutionCertainty
import com.akane.voltwise.battery.util.ShellRunner.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit user actions only. The mutex covers journaled actions from validation through confirmation. */
class InsightActionRepository(
    private val dao: InsightDao,
    private val executor: ActionExecutor,
    private val inspector: TargetInspector,
    private val clock: () -> Long,
    private val alertEnabler: suspend () -> Unit,
    private val alertsPostable: () -> Boolean,
) {
    private val mutex = Mutex()
    val actions: Flow<List<InsightActionEntity>> = dao.actions()

    suspend fun apply(finding: Finding, rec: Recommendation): ActionResult {
        val app = finding.subject as? Subject.App
        when (rec.action) {
            ActionType.OPEN_APP_SETTINGS -> {
                if (app == null) return ActionResult.Refused(RefusalCode.INVALID_SUBJECT)
                if (!CommandPolicy.isPackageName(app.packageName)) return ActionResult.Refused(RefusalCode.INVALID_PACKAGE)
                return ActionResult.OpenSettings(IntentSpec("android.settings.APPLICATION_DETAILS_SETTINGS", app.packageName))
            }
            ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS -> return ActionResult.OpenSettings(
                IntentSpec("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
            )
            else -> Unit
        }
        return mutex.withLock {
            if (rec.action == ActionType.ENABLE_HIGH_BATTERY_ALERT) {
                alertEnabler()
                val id = dao.insertAction(row(finding, rec.action, ONE_SHOT).copy(appliedAt = clock()))
                return@withLock ActionResult.OneShot(id, notificationsBlocked = !alertsPostable())
            }
            if (app == null) return@withLock ActionResult.Refused(RefusalCode.INVALID_SUBJECT)
            validate(app.packageName, app.uid)?.let { return@withLock ActionResult.Refused(it) }
            if (rec.action == ActionType.FORCE_STOP) {
                // Force-stop has no trustworthy readback; interruptions must leave a durable attempt.
                val attempt = row(finding, rec.action, UNKNOWN)
                val saved = attempt.copy(id = dao.insertAction(attempt))
                val outcome = executor.run(PrivilegedCommand.ForceStop(app.packageName))
                val success = outcome is Outcome.Success
                val uncertain = outcome is Outcome.Failure && outcome.certainty == ExecutionCertainty.UNKNOWN
                if (uncertain) return@withLock ActionResult.Unknown
                dao.updateAction(saved.copy(
                    status = if (success) ONE_SHOT else FAILED,
                    appliedAt = if (success) clock() else null,
                    message = when {
                        success -> null
                        outcome is Outcome.NoAccess -> RefusalCode.NOT_PRIVILEGED.name
                        else -> FailureCode.EXECUTION_FAILED.name
                    },
                ))
                return@withLock when {
                    success -> ActionResult.OneShot(saved.id)
                    outcome is Outcome.NoAccess -> ActionResult.Refused(RefusalCode.NOT_PRIVILEGED)
                    else -> ActionResult.Failed(FailureCode.EXECUTION_FAILED)
                }
            }
            val operation = operation(rec.action) ?: return@withLock ActionResult.Refused(RefusalCode.UNSUPPORTED_SDK)
            val prior = read(operation, app.packageName)
            if (prior !is StateRead.Known) return@withLock initialReadFailure(prior)
            val canWrite = operation.restorable(prior.value, inspector.sdkInt) && !operation.atOrBeyondTarget(prior.value)
            // Retire stale Undo authority before a new write can reach the old target again.
            for (old in dao.actionsWithStatus(listOf(APPLIED, PREPARED, UNKNOWN))) {
                if (old.type != rec.action.name || old.packageName != app.packageName) continue
                if (old.uid != app.uid) {
                    dao.updateAction(old.copy(status = REVERTED, revertedAt = clock(), message = "CHANGED_EXTERNALLY"))
                    continue
                }
                if (old.status == APPLIED && old.targetState != prior.value) {
                    dao.updateAction(old.copy(status = REVERTED, revertedAt = clock(), message = "CHANGED_EXTERNALLY"))
                } else if (old.status in listOf(PREPARED, UNKNOWN) && old.priorState == prior.value) {
                    if (old.appliedAt != null) {
                        dao.updateAction(old.copy(status = REVERTED, revertedAt = clock(), message = null))
                    } else {
                        dao.updateAction(old.copy(status = FAILED, message = FailureCode.STATE_MISMATCH.name))
                    }
                } else if (old.status in listOf(PREPARED, UNKNOWN) && old.targetState != prior.value && canWrite) {
                    // A third live state is the new prior; the old row cannot own a later matching target.
                    dao.updateAction(old.copy(status = REVERTED, revertedAt = clock(), message = "CHANGED_EXTERNALLY"))
                }
            }
            if (!operation.restorable(prior.value, inspector.sdkInt)) {
                return@withLock ActionResult.Refused(RefusalCode.UNRESTORABLE_PRIOR)
            }
            if (operation.atOrBeyondTarget(prior.value)) {
                return@withLock ActionResult.Refused(RefusalCode.ALREADY_AT_TARGET)
            }
            val prepared = row(finding, rec.action, PREPARED).copy(priorState = prior.value, targetState = operation.target)
            val saved = prepared.copy(id = dao.insertAction(prepared))
            val outcome = executor.run(operation.write(app.packageName, operation.target))
            if (outcome is Outcome.NoAccess) {
                dao.updateAction(saved.copy(status = FAILED, message = RefusalCode.NOT_PRIVILEGED.name))
                return@withLock ActionResult.Refused(RefusalCode.NOT_PRIVILEGED)
            }
            val confirmed = read(operation, app.packageName)
            if (confirmed !is StateRead.Known) return@withLock unknown(saved)
            if (confirmed.value != operation.target) {
                if (confirmed.value != prior.value ||
                    outcome is Outcome.Failure && outcome.certainty == ExecutionCertainty.UNKNOWN
                ) return@withLock unknown(saved)
                dao.updateAction(saved.copy(status = FAILED, message = FailureCode.STATE_MISMATCH.name))
                return@withLock ActionResult.Failed(FailureCode.NOT_APPLIED)
            }
            dao.updateAction(saved.copy(status = APPLIED, appliedAt = clock()))
            if (rec.action == ActionType.STANDBY_BUCKET_RESTRICTED && inspector.sdkInt < 30) {
                ActionResult.AppliedWithFallback(saved.id, FallbackCode.RESTRICTED_TO_RARE)
            } else ActionResult.Applied(saved.id)
        }
    }

    /** Shell-restored buckets are user-forced by Android until the app is next used. */
    suspend fun undo(actionId: Long): ActionResult = mutex.withLock {
        val row = dao.actionsOnce().firstOrNull { it.id == actionId }
            ?: return@withLock ActionResult.Failed(FailureCode.NOT_UNDOABLE)
        if (row.type == ActionType.FORCE_STOP.name || row.status !in listOf(APPLIED, UNKNOWN)) {
            return@withLock ActionResult.Failed(FailureCode.NOT_UNDOABLE)
        }
        val operation = journalOperation(row) ?: return@withLock ActionResult.Failed(FailureCode.INVALID_JOURNAL)
        val pkg = row.packageName ?: return@withLock ActionResult.Failed(FailureCode.INVALID_JOURNAL)
        val uid = row.uid ?: return@withLock ActionResult.Failed(FailureCode.INVALID_JOURNAL)
        val refusal = validate(pkg, uid, restoring = true)
        if (refusal == RefusalCode.NOT_INSTALLED || refusal == RefusalCode.UID_MISMATCH) {
            dao.updateAction(row.copy(status = REVERTED, revertedAt = clock(), message = "CHANGED_EXTERNALLY"))
            return@withLock ActionResult.ChangedExternally(null)
        }
        if (refusal != null) return@withLock ActionResult.Refused(refusal)
        val current = read(operation, pkg)
        if (current !is StateRead.Known) return@withLock initialReadFailure(current)
        if (row.status == UNKNOWN && current.value == row.priorState) {
            if (row.appliedAt != null) {
                dao.updateAction(row.copy(status = REVERTED, revertedAt = clock(), message = null))
                return@withLock ActionResult.Reverted
            }
            dao.updateAction(row.copy(status = FAILED, message = FailureCode.STATE_MISMATCH.name))
            return@withLock ActionResult.Failed(FailureCode.NOT_APPLIED)
        }
        if (current.value != row.targetState) {
            dao.updateAction(row.copy(status = REVERTED, revertedAt = clock(), message = "CHANGED_EXTERNALLY"))
            return@withLock ActionResult.ChangedExternally(current.value)
        }
        // The target read proves application; preserve it if restoration is interrupted.
        val restoring = row.copy(status = UNKNOWN, appliedAt = row.appliedAt ?: row.createdAt)
        dao.updateAction(restoring)
        val outcome = executor.run(operation.write(pkg, requireNotNull(restoring.priorState)))
        if (outcome is Outcome.NoAccess) {
            dao.updateAction(restoring.copy(status = APPLIED, message = RefusalCode.NOT_PRIVILEGED.name))
            return@withLock ActionResult.Refused(RefusalCode.NOT_PRIVILEGED)
        }
        val confirmed = read(operation, pkg)
        if (confirmed !is StateRead.Known) return@withLock unknown(restoring)
        if (confirmed.value == restoring.priorState) {
            dao.updateAction(restoring.copy(status = REVERTED, revertedAt = clock(), message = null))
            ActionResult.Reverted
        } else if (confirmed.value == restoring.targetState) {
            if (outcome is Outcome.Failure && outcome.certainty == ExecutionCertainty.UNKNOWN) {
                return@withLock unknown(restoring)
            }
            dao.updateAction(restoring.copy(status = APPLIED, message = FailureCode.STATE_MISMATCH.name))
            ActionResult.Failed(FailureCode.STATE_MISMATCH)
        } else unknown(restoring)
    }

    /** Read-only recovery: never replay a command whose execution was interrupted. */
    suspend fun reconcile() = mutex.withLock {
        for (row in dao.actionsWithStatus(listOf(PREPARED, UNKNOWN))) {
            // A one-shot has no restorable state/readback; preserve its uncertainty without replay.
            if (row.type == ActionType.FORCE_STOP.name) continue
            val operation = journalOperation(row)
            val pkg = row.packageName
            val uid = row.uid
            if (operation == null || pkg == null || uid == null) {
                unknown(row)
                continue
            }
            val refusal = validate(pkg, uid, restoring = true)
            if (refusal == RefusalCode.NOT_INSTALLED || refusal == RefusalCode.UID_MISMATCH) {
                dao.updateAction(row.copy(status = REVERTED, revertedAt = clock(), message = "CHANGED_EXTERNALLY"))
                continue
            }
            if (refusal != null) {
                unknown(row)
                continue
            }
            val current = read(operation, pkg)
            val status = when {
                current !is StateRead.Known -> UNKNOWN
                current.value == row.targetState -> APPLIED
                current.value == row.priorState -> if (row.appliedAt != null) REVERTED else FAILED
                else -> UNKNOWN
            }
            dao.updateAction(row.copy(
                status = status,
                appliedAt = if (status == APPLIED) row.appliedAt ?: row.createdAt else row.appliedAt,
                revertedAt = if (status == REVERTED) clock() else row.revertedAt,
                message = null,
            ))
        }
    }

    private fun validate(pkg: String, uid: Int, restoring: Boolean = false): RefusalCode? {
        if (CommandPolicy.isProtected(pkg, uid)) return RefusalCode.PROTECTED
        if (!CommandPolicy.isPackageName(pkg)) return RefusalCode.INVALID_PACKAGE
        return try {
            val installed = inspector.installedUid(pkg, 0) ?: return RefusalCode.NOT_INSTALLED
            when {
                installed != uid -> RefusalCode.UID_MISMATCH
                !restoring && inspector.packagesForUid(uid) != listOf(pkg) -> RefusalCode.SHARED_UID
                !restoring && pkg in inspector.roleHolders() -> RefusalCode.ROLE_HOLDER
                else -> null
            }
        } catch (_: SecurityException) {
            RefusalCode.INSPECTION_FAILED
        }
    }

    private fun row(finding: Finding, type: ActionType, status: InsightActionStatus): InsightActionEntity {
        val app = finding.subject as? Subject.App
        return InsightActionEntity(
            findingKey = finding.key, type = type.name, packageName = app?.packageName, uid = app?.uid,
            userId = 0, status = status, priorStateVersion = 1, createdAt = clock(),
            metric = finding.evidence.firstOrNull()?.metric?.name,
        )
    }

    private suspend fun unknown(row: InsightActionEntity): ActionResult {
        dao.updateAction(row.copy(status = UNKNOWN, message = null))
        return ActionResult.Unknown
    }

    private fun initialReadFailure(read: StateRead): ActionResult = when (read) {
        StateRead.NoAccess -> ActionResult.Refused(RefusalCode.NOT_PRIVILEGED)
        StateRead.Failed, StateRead.Unknown -> ActionResult.Failed(FailureCode.READ_FAILED)
        else -> ActionResult.Unknown
    }

    private fun operation(type: ActionType): Operation? = when (type) {
        ActionType.RESTRICT_BACKGROUND ->
            if (inspector.sdkInt >= 28) Operation(type, BackgroundOp.forSdk(inspector.sdkInt)) else null
        ActionType.STANDBY_BUCKET_RARE, ActionType.STANDBY_BUCKET_RESTRICTED ->
            if (inspector.sdkInt >= 28) Operation(type, restricted = inspector.sdkInt >= 30) else null
        ActionType.REMOVE_DOZE_WHITELIST -> Operation(type)
        else -> null
    }

    private fun journalOperation(row: InsightActionEntity): Operation? {
        if (row.priorStateVersion != 1 || row.userId != 0) return null
        val type = ActionType.entries.firstOrNull { it.name == row.type } ?: return null
        // Persist the exact app-op in both states, so an OS upgrade cannot redirect Undo.
        // Legacy rows remain restorable even when new restrictions are unsupported.
        val op = if (type == ActionType.RESTRICT_BACKGROUND) {
            val name = row.targetState?.substringBefore(':')
            Operation(type, op = BackgroundOp.entries.firstOrNull { it.name == name } ?: return null)
        } else operation(type) ?: return null
        if (!op.restorable(row.priorState, inspector.sdkInt) || !op.restorable(row.targetState, inspector.sdkInt)) return null
        return op
    }

    private suspend fun read(operation: Operation, pkg: String): StateRead = when (val result = executor.run(operation.read(pkg))) {
        is Outcome.NoAccess -> StateRead.NoAccess
        is Outcome.Failure -> StateRead.Failed
        is Outcome.Success -> operation.parse(result.output, pkg)?.let(StateRead::Known) ?: StateRead.Unknown
    }

    private sealed interface StateRead {
        data class Known(val value: String) : StateRead
        data object Unknown : StateRead
        data object NoAccess : StateRead
        data object Failed : StateRead
    }

    /** Version 1 states: bucket enum name, OP:MODE, or user whitelist membership PRESENT/ABSENT. */
    private data class Operation(val type: ActionType, val op: BackgroundOp? = null, val restricted: Boolean = false) {
        val target: String get() = when (type) {
            ActionType.RESTRICT_BACKGROUND -> "${requireNotNull(op).name}:IGNORE"
            ActionType.REMOVE_DOZE_WHITELIST -> "ABSENT"
            ActionType.STANDBY_BUCKET_RESTRICTED -> if (restricted) "RESTRICTED" else "RARE"
            else -> "RARE"
        }

        fun read(pkg: String): PrivilegedCommand = when (type) {
            ActionType.RESTRICT_BACKGROUND -> PrivilegedCommand.GetBackgroundOp(pkg, requireNotNull(op))
            ActionType.REMOVE_DOZE_WHITELIST -> PrivilegedCommand.ListDozeWhitelist
            else -> PrivilegedCommand.GetStandbyBucket(pkg)
        }

        fun parse(output: String, pkg: String): String? = when (type) {
            ActionType.RESTRICT_BACKGROUND -> (ActionReadback.backgroundOp(output, requireNotNull(op)) as? Readback.Recognized)
                ?.value?.let { "${op.name}:${it.name}" }
            ActionType.REMOVE_DOZE_WHITELIST -> (ActionReadback.dozeWhitelist(output) as? Readback.Recognized)
                ?.value?.let { entries -> if (entries.any { it.packageName == pkg && it.kind == WhitelistKind.USER }) "PRESENT" else "ABSENT" }
            else -> (ActionReadback.standbyBucket(output) as? Readback.Recognized)?.value?.name
        }

        fun restorable(state: String?, sdk: Int): Boolean = when (type) {
            ActionType.RESTRICT_BACKGROUND -> AppOpMode.entries.any { it.writable && state == "${op?.name}:${it.name}" }
            ActionType.REMOVE_DOZE_WHITELIST -> state == "PRESENT" || state == "ABSENT"
            else -> StandbyBucket.supported(sdk).any { it.name == state }
        }

        // Only called after the prior is known to be restorable.
        fun atOrBeyondTarget(state: String): Boolean = when (type) {
            ActionType.STANDBY_BUCKET_RARE, ActionType.STANDBY_BUCKET_RESTRICTED ->
                StandbyBucket.valueOf(state).code >= StandbyBucket.valueOf(target).code
            else -> state == target
        }

        fun write(pkg: String, state: String): PrivilegedCommand = when (type) {
            ActionType.RESTRICT_BACKGROUND -> PrivilegedCommand.SetBackgroundOp(pkg, requireNotNull(op), AppOpMode.valueOf(state.substringAfter(':')))
            ActionType.REMOVE_DOZE_WHITELIST -> if (state == "PRESENT") PrivilegedCommand.AddDozeWhitelist(pkg) else PrivilegedCommand.RemoveDozeWhitelist(pkg)
            else -> PrivilegedCommand.SetStandbyBucket(pkg, StandbyBucket.valueOf(state))
        }
    }
}
