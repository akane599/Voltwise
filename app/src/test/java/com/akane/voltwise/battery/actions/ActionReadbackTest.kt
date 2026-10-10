package com.akane.voltwise.battery.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionReadbackTest {
    @Test fun standbyNumericAndNamedFixtures() {
        val fixtures = mapOf(
            "5" to StandbyBucket.EXEMPTED, "10" to StandbyBucket.ACTIVE, "20" to StandbyBucket.WORKING_SET,
            "30" to StandbyBucket.FREQUENT, "40" to StandbyBucket.RARE, "45" to StandbyBucket.RESTRICTED,
            "50" to StandbyBucket.NEVER,
        )
        for ((number, bucket) in fixtures) {
            for (text in listOf(number, bucket.token, bucket.name, "  $number\n")) {
                assertEquals(Readback.Recognized(bucket), ActionReadback.standbyBucket(text))
            }
        }
        for (text in listOf("", "0", "41", "010", "com.example: 40", "bucket=rare", "10\n40", "Error: active")) {
            assertEquals(text, Readback.Unrecognized, ActionReadback.standbyBucket(text))
        }
    }

    @Test fun appOpsKnownRecordsIncludingReadOnlyDeny() {
        for (op in BackgroundOp.entries) for (mode in AppOpMode.entries) {
            for (text in listOf(
                "${op.name}: mode: ${mode.token}",
                "${op.name}: ${mode.token}; time=+1h2m ago; rejectTime=+2h ago; duration=+1s",
            )) assertEquals(text, Readback.Recognized(mode), ActionReadback.backgroundOp(text, op))
        }
    }

    @Test fun appOpsRequireTheQueriedOpRecord() {
        for (op in BackgroundOp.entries) {
            val other = BackgroundOp.entries.single { it != op }
            for (text in listOf("mode: allow", "${other.name}: allow", "Uid mode: ${other.name}: ignore")) {
                assertEquals(text, Readback.Unrecognized, ActionReadback.backgroundOp(text, op))
            }
        }
    }

    @Test fun appOpsNoOperationsIsEffectiveAllow() {
        for (op in BackgroundOp.entries) for (text in listOf("No operations.", "  No operations.\n")) {
            assertEquals(text, Readback.Recognized(AppOpMode.ALLOW), ActionReadback.backgroundOp(text, op))
        }
    }

    @Test fun appOpsNoOperationsReadsOneNamedDefaultMode() {
        for (op in BackgroundOp.entries) for (mode in listOf("allow", "ignore", "deny", "foreground")) {
            for (separator in listOf("\n", "\r\n")) {
                val text = "No operations.${separator}Default mode: $mode"
                val readback = ActionReadback.backgroundOp(text, op)
                assertEquals(text, mode, (readback as? Readback.Recognized)?.value?.token)
            }
        }
        for (mode in listOf("default", "unknown", "allowed", "", "ALLOW")) {
            assertEquals(mode, Readback.Unrecognized, ActionReadback.backgroundOp(
                "No operations.\nDefault mode: $mode", BackgroundOp.RUN_ANY_IN_BACKGROUND,
            ))
        }
    }

    @Test fun appOpsLoneUidModeIsUnrecognized() {
        for (op in BackgroundOp.entries) for (mode in AppOpMode.entries) {
            for (prefix in listOf("", "mode: ")) {
                assertEquals(Readback.Unrecognized, ActionReadback.backgroundOp("Uid mode: ${op.name}: $prefix${mode.token}", op))
            }
        }
    }

    @Test fun restorePriorAllowUsesExactAllowArgv() {
        for (op in BackgroundOp.entries) {
            val absentPrior = ActionReadback.backgroundOp("No operations.", op)
            val explicitPrior = ActionReadback.backgroundOp("${op.name}: allow", op)
            for (prior in listOf(Readback.Recognized(AppOpMode.ALLOW), absentPrior, explicitPrior)) {
                val mode = (prior as Readback.Recognized).value
                assertEquals(
                    listOf("cmd", "appops", "set", "--user", "0", "com.example", op.name, "allow"),
                    PrivilegedCommand.SetBackgroundOp("com.example", op, mode).argv,
                )
            }
        }
    }

    @Test fun appOpsUnknownAmbiguousAndOemOutputDoesNotImplyDefaultOrSuccess() {
        for (text in listOf(
            "", "Default mode: default", "allow", "mode: allowed", "mode: foreground",
            "No operations.\nDefault mode: mystery", "No operations.\nDefault mode: allow\nError: denied",
            "No operations.\nDefault mode: allow\nDefault mode: ignore", "No operations.\nRUN_ANY_IN_BACKGROUND: allow",
            "Uid mode: RUN_ANY_IN_BACKGROUND: ignore\nNo operations.",
            "RUN_ANY_IN_BACKGROUND: allow OEM custom", "MODE_ALLOWED", "CAMERA: allow", "mode=allow",
            "RUN_ANY_IN_BACKGROUND: allow\nRUN_ANY_IN_BACKGROUND: ignore", "mode: allow\nError: denied",
            "Uid mode: RUN_ANY_IN_BACKGROUND: ignore\nRUN_ANY_IN_BACKGROUND: allow", "mode: allow; vendor=1",
        )) assertEquals(text, Readback.Unrecognized, ActionReadback.backgroundOp(text, BackgroundOp.RUN_ANY_IN_BACKGROUND))
    }

    @Test fun whitelistKeepsUserAndSystemEntriesDistinct() {
        assertEquals(
            Readback.Recognized(listOf(
                WhitelistEntry("com.example", 10001, WhitelistKind.USER),
                WhitelistEntry("android", 1000, WhitelistKind.SYSTEM),
                WhitelistEntry("com.android.phone", 1001, WhitelistKind.SYSTEM),
            )),
            ActionReadback.dozeWhitelist("user,com.example,10001\nsystem,android,1000\nsystem-excidle,com.android.phone,1001\n"),
        )
        assertEquals(
            Readback.Recognized(listOf(WhitelistEntry("android", 1000, WhitelistKind.SYSTEM))),
            ActionReadback.dozeWhitelist("system,android,1000\n"),
        )
        for (text in listOf(
            "user,com.example,-1", "user,com.example,+10001", "user,com.example,999999999999",
            "user,com.example,", "user,com.example", "vendor,com.example,10001", "user,com.exаmple,10001",
            "Whitelist: com.example", "user,com.example,10001\nError: no access",
        )) assertEquals(text, Readback.Unrecognized, ActionReadback.dozeWhitelist(text))
    }

    @Test fun whitelistWithoutEntriesIsUnrecognized() {
        for (text in listOf("", "\n", " \t\n", "Whitelist: com.example", "Error: no access")) {
            assertEquals(text, Readback.Unrecognized, ActionReadback.dozeWhitelist(text))
        }
    }

    @Test fun whitelistMutationAcknowledgementIsTypedAndUnknownIsNotSuccess() {
        assertEquals(Readback.Recognized(WhitelistChange.Added("com.example")), ActionReadback.whitelistChange("Added: com.example\n"))
        assertEquals(Readback.Recognized(WhitelistChange.Removed("com.example")), ActionReadback.whitelistChange("Removed: com.example"))
        assertEquals(Readback.Recognized(WhitelistChange.UnknownPackage("com.example")), ActionReadback.whitelistChange("Unknown package: com.example"))
        for (text in listOf("", "Success", "Added:", "Added: com.example extra", "Removed com.example", "Added: com.example\nUnknown package: com.other")) {
            assertEquals(text, Readback.Unrecognized, ActionReadback.whitelistChange(text))
        }
    }
}
