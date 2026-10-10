package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.actions.ActionReadback
import com.akane.voltwise.battery.actions.PrivilegedCommand
import com.akane.voltwise.battery.actions.Readback
import com.akane.voltwise.battery.actions.WhitelistKind
import com.akane.voltwise.battery.util.ShellRunner

/** Unknown access or readback is distinct from a known empty user whitelist. */
internal suspend fun readUserDozeWhitelist(shell: ShellRunner): Set<String>? {
    val mode = shell.detectMode()
    if (mode != ShellRunner.Mode.SHIZUKU && mode != ShellRunner.Mode.ROOT) return null
    val outcome = shell.execAction(PrivilegedCommand.ListDozeWhitelist)
    val readback = (outcome as? ShellRunner.Outcome.Success)?.let { ActionReadback.dozeWhitelist(it.output) }
    return when (readback) {
        is Readback.Recognized -> readback.value.filter { it.kind == WhitelistKind.USER }
            .map { it.packageName }.toSet()
        else -> null
    }
}
