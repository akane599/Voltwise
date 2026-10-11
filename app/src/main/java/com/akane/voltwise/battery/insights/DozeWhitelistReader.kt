package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.actions.ActionReadback
import com.akane.voltwise.battery.actions.PrivilegedCommand
import com.akane.voltwise.battery.actions.Readback
import com.akane.voltwise.battery.actions.WhitelistKind
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.util.DumpOutput
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
            if (outcome.mode == ShellRunner.Mode.NONE || DumpOutput.isRefusal(outcome.message)) return null
            onDiagnostic(DiagnosticCode.ADVANCED_READ_FAILED)
            null
        }
        is ShellRunner.Outcome.Success -> when (val readback = ActionReadback.dozeWhitelist(outcome.output)) {
            is Readback.Recognized -> readback.value.filter { it.kind == WhitelistKind.USER }
                .map { it.packageName }.toSet()
            else -> {
                onDiagnostic(DiagnosticCode.ADVANCED_FORMAT_INVALID)
                null
            }
        }
    }
}
