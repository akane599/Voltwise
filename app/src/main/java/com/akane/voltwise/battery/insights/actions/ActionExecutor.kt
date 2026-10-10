package com.akane.voltwise.battery.insights.actions

import com.akane.voltwise.battery.actions.PrivilegedCommand
import com.akane.voltwise.battery.util.ShellRunner

fun interface ActionExecutor {
    suspend fun run(command: PrivilegedCommand): ShellRunner.Outcome
}

class ShellRunnerActionExecutor(private val shellRunner: ShellRunner) : ActionExecutor {
    override suspend fun run(command: PrivilegedCommand): ShellRunner.Outcome = shellRunner.execAction(command)
}

/** Pure navigation data. packageName is the package URI subject, not an Intent component. */
data class IntentSpec(val action: String, val packageName: String? = null)

enum class RefusalCode {
    NOT_PRIVILEGED, PROTECTED, NOT_INSTALLED, UID_MISMATCH, SHARED_UID, ROLE_HOLDER,
    UNSUPPORTED_SDK, INVALID_SUBJECT, INVALID_PACKAGE, UNRESTORABLE_PRIOR, INSPECTION_FAILED, ALREADY_AT_TARGET,
}
/**
 * NOT_APPLIED: the setting is still at its prior value and no Undo remains. STATE_MISMATCH: an undo's restore
 * left the target in place, so Undo stays available. Journal rows keep the persisted STATE_MISMATCH message for both.
 */
enum class FailureCode { READ_FAILED, EXECUTION_FAILED, NOT_APPLIED, STATE_MISMATCH, NOT_UNDOABLE, INVALID_JOURNAL }
enum class FallbackCode { RESTRICTED_TO_RARE }

sealed interface ActionResult {
    data class Applied(val actionId: Long) : ActionResult
    data class AppliedWithFallback(val actionId: Long, val note: FallbackCode) : ActionResult
    data class Failed(val code: FailureCode) : ActionResult
    data object Unknown : ActionResult
    data object Reverted : ActionResult
    data class ChangedExternally(val current: String?) : ActionResult
    data class Refused(val reason: RefusalCode) : ActionResult
    data class OneShot(val actionId: Long, val notificationsBlocked: Boolean = false) : ActionResult
    data class OpenSettings(val spec: IntentSpec) : ActionResult
}
