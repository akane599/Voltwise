package com.akane.voltwise.battery.shizuku

import com.akane.voltwise.battery.actions.PrivilegedCommand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import rikka.shizuku.ShizukuApiConstants

class ShellUserServiceTest {
    @Test fun helperUsesStrictSpaceDelimitedPolicyAndKeepsDiagnostics() {
        for (command in listOf(
            "dumpsys batterystats --proto --charged",
            PrivilegedCommand.ForceStop("com.example").argv.joinToString(" "),
        )) {
            assertTrue(ShellUserService.allows(command))
            for (bad in listOf(" $command", "$command ", command.replace(" ", "  "), "$command\n", "$command;id", "$command && id")) {
                assertFalse(bad, ShellUserService.allows(bad))
            }
        }
        for (command in listOf(
            "am force-stop --user 1 com.example", "cmd deviceidle force-idle", "sh -c id", "dumpsys battery reset",
            "dumpsys batterystats --proto", "dumpsys batterystats --proto --charged --history",
            "dumpsys batterystats --proto --charged --checkin",
        )) {
            assertFalse(ShellUserService.allows(command))
        }
    }

    @Test fun helperRejectsChargedCheckinDiagnostic() {
        assertFalse(ShellUserService.allows("dumpsys batterystats -c --charged"))
    }

    @Test fun helperRejectsBatteryDiagnostic() {
        assertFalse(ShellUserService.allows("dumpsys battery"))
    }

    @Test fun helperRejectsDeviceIdleDiagnostic() {
        assertFalse(ShellUserService.allows("dumpsys deviceidle"))
    }

    @Test fun helperRejectsDefaultAppOpSetMode() {
        for (op in listOf("RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND")) {
            assertTrue(ShellUserService.allows("cmd appops set --user 0 com.example $op allow"))
            assertFalse(ShellUserService.allows("cmd appops set --user 0 com.example $op default"))
        }
    }

    @Test
    fun destroyTransactionMatchesShizukuConstant() {
        assertEquals(ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy, ShellUserService.TRANSACTION_DESTROY)
    }
}
