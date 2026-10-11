package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.actions.ActionReadback
import com.akane.voltwise.battery.actions.PrivilegedCommand
import com.akane.voltwise.battery.actions.Readback
import com.akane.voltwise.battery.actions.WhitelistKind
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.util.ShellRunner

/** Unknown access or readback is distinct from a known empty user whitelist. */
internal suspend fun readUserDozeWhitelist(
    shell: ShellRunner,
    onDiagnostic: (DiagnosticCode) -> Unit,
): Set<String>? {
    val mode = shell.detectMode()
    if (mode != ShellRunner.Mode.SHIZUKU && mode != ShellRunner.Mode.ROOT) return null
    return when (val outcome = shell.execAction(PrivilegedCommand.ListDozeWhitelist)) {
        is ShellRunner.Outcome.NoAccess -> null
        is ShellRunner.Outcome.Failure -> {
            onDiagnostic(DiagnosticCode.DOZE_WHITELIST_READ_FAILED)
            null
        }
        is ShellRunner.Outcome.Success -> when (val readback = ActionReadback.dozeWhitelist(outcome.output)) {
            is Readback.Recognized -> readback.value.filter { it.kind == WhitelistKind.USER }
                .map { it.packageName }.toSet()
            else -> {
                onDiagnostic(DiagnosticCode.DOZE_WHITELIST_FORMAT_INVALID)
                null
            }
        }
    }
}
