package com.akane.voltwise.battery.actions

import com.akane.voltwise.battery.actions.PrivilegedCommand.*
import org.junit.Assert.*
import org.junit.Test

class CommandPolicyTest {
    private val pkg = "com.example_app.A1"

    @Test fun onlyTheFixedChargedProtoDiagnosticIsAllowed() {
        assertTrue(CommandPolicy.allows(listOf("dumpsys", "batterystats", "--proto", "--charged")))
        for (command in listOf(
            "dumpsys batterystats --proto", "dumpsys batterystats --charged --proto",
            "dumpsys batterystats --proto --charged --history", "dumpsys battery --proto --charged",
            "dumpsys batterystats --proto --charged;id", "dumpsys batterystats --proto --charged\n",
            " dumpsys batterystats --proto --charged", "dumpsys  batterystats --proto --charged",
        )) assertFalse(command, CommandPolicy.allows(command.split(' ')))
    }

    @Test fun policyRejectsChargedCheckinDiagnostic() {
        assertFalse(CommandPolicy.allows(listOf("dumpsys", "batterystats", "-c", "--charged")))
    }

    @Test fun policyRejectsBatteryDiagnostic() {
        assertFalse(CommandPolicy.allows(listOf("dumpsys", "battery")))
    }

    @Test fun policyRejectsDeviceIdleDiagnostic() {
        assertFalse(CommandPolicy.allows(listOf("dumpsys", "deviceidle")))
    }

    @Test fun everyTemplateRoundTripsWithExactTokens() {
        assertEquals(listOf("active", "working_set", "frequent", "rare", "restricted"), StandbyBucket.supported(30).map { it.token })
        assertEquals(listOf("allow", "ignore", "default", "deny", "foreground"), AppOpMode.entries.map { it.token })
        val commands = mutableListOf<Pair<PrivilegedCommand, String>>(
            GetStandbyBucket(pkg) to "am get-standby-bucket --user 0 $pkg",
            ListDozeWhitelist to "cmd deviceidle whitelist",
            RemoveDozeWhitelist(pkg) to "cmd deviceidle whitelist -$pkg",
            AddDozeWhitelist(pkg) to "cmd deviceidle whitelist +$pkg",
            ForceStop(pkg) to "am force-stop --user 0 $pkg",
        )
        for (bucket in StandbyBucket.supported(30)) {
            commands += SetStandbyBucket(pkg, bucket) to "am set-standby-bucket --user 0 $pkg ${bucket.token}"
        }
        for (op in BackgroundOp.entries) {
            commands += GetBackgroundOp(pkg, op) to "cmd appops get --user 0 $pkg ${op.name}"
            for (mode in listOf(AppOpMode.ALLOW, AppOpMode.IGNORE)) {
                commands += SetBackgroundOp(pkg, op, mode) to "cmd appops set --user 0 $pkg ${op.name} ${mode.token}"
            }
        }
        for ((command, expected) in commands) {
            assertEquals(expected.split(' '), command.argv)
            assertTrue(expected, CommandPolicy.allows(command.argv))
            assertTrue(expected, CommandPolicy.allows(command.argv.joinToString(" ").split(' ')))
            assertFalse(CommandPolicy.allows(command.argv + "extra"))
            for (index in command.argv.indices) {
                assertFalse(CommandPolicy.allows(command.argv.toMutableList().apply { this[index] = "" }))
            }
        }
    }

    @Test fun invalidPackagesAreRejectedByEveryConstructorAndEveryTemplate() {
        val invalid = listOf(
            "", "android", ".com.example", "com..example", "com.example.", "1com.example", "com.1example",
            "com.example;id", "com.example&&id", "com.example$(id)", "com.example`id`",
            "com.example\nid", "com.example\n", "com.example\rid", "com.example\tid", "com.example id",
            "com.exаmple", "com．example", "com.example\u0000", "com.example --user 1",
            "a." + "b".repeat(254),
        )
        val factories: List<(String) -> PrivilegedCommand> = listOf(
            ::GetStandbyBucket,
            { SetStandbyBucket(it, StandbyBucket.RARE) },
            { GetBackgroundOp(it, BackgroundOp.RUN_ANY_IN_BACKGROUND) },
            { SetBackgroundOp(it, BackgroundOp.RUN_IN_BACKGROUND, AppOpMode.IGNORE) },
            ::RemoveDozeWhitelist, ::AddDozeWhitelist, ::ForceStop,
        )
        for (bad in invalid) {
            assertFalse(bad, CommandPolicy.isPackageName(bad))
            for (factory in factories) {
                assertThrows(IllegalArgumentException::class.java) { factory(bad) }
                val argv = factory(pkg).argv.map { it.replace(pkg, bad) }
                assertFalse(argv.toString(), CommandPolicy.allows(argv))
                assertFalse(CommandPolicy.allows(argv.joinToString(" ").split(' ')))
            }
        }
        assertTrue(CommandPolicy.isPackageName("a." + "b".repeat(253)))
    }

    @Test fun policyRejectsNonTemplatesAndReadOnlyValues() {
        val badCommands = listOf(
            "am force-stop --user 1 $pkg", "am force-stop --user all $pkg",
            "am force-stop $pkg", "am force-stop --user 0 $pkg extra",
            "am set-standby-bucket --user 0 $pkg 40", "am set-standby-bucket --user 0 $pkg never",
            "am set-standby-bucket --user 0 $pkg exempted", "cmd appops set --user 0 $pkg CAMERA allow",
            "cmd appops set --user 0 $pkg RUN_IN_BACKGROUND deny", "cmd appops get --user 1 $pkg RUN_IN_BACKGROUND",
            "cmd appops set --user 0 $pkg RUN_IN_BACKGROUND foreground",
            "cmd deviceidle whitelist $pkg", "cmd deviceidle whitelist +", "cmd deviceidle whitelist -",
            "cmd deviceidle whitelist reset", "cmd deviceidle force-idle", "dumpsys battery reset",
            "dumpsys batterystats --reset", "sh -c id", "", " ", "cmd", "am", "dumpsys",
        )
        for (command in badCommands) assertFalse(command, CommandPolicy.allows(command.split(' ')))
        assertFalse(CommandPolicy.allows(emptyList()))
        for (bucket in listOf(StandbyBucket.EXEMPTED, StandbyBucket.NEVER)) {
            assertThrows(IllegalArgumentException::class.java) { SetStandbyBucket(pkg, bucket) }
        }
        for (mode in listOf(AppOpMode.DENY, AppOpMode.FOREGROUND)) {
            assertThrows(IllegalArgumentException::class.java) {
                SetBackgroundOp(pkg, BackgroundOp.RUN_IN_BACKGROUND, mode)
            }
        }
    }

    @Test fun policyRejectsDefaultAppOpSetMode() {
        for (op in BackgroundOp.entries) {
            assertFalse(CommandPolicy.allows(listOf("cmd", "appops", "set", "--user", "0", pkg, op.name, "default")))
        }
    }

    @Test fun constructorsRejectDefaultAppOpSetMode() {
        for (op in BackgroundOp.entries) {
            assertThrows(IllegalArgumentException::class.java) { SetBackgroundOp(pkg, op, AppOpMode.DEFAULT) }
        }
    }

    @Test fun protectedPackagesAndUidBoundaries() {
        for (name in listOf(
            "com.akane.voltwise", "com.akane.voltwise.debug", "com.akane.voltwise.preview",
            "moe.shizuku.privileged.api", "android", "com.android.systemui", "com.android.phone", "com.google.android.gms",
        )) assertTrue(name, CommandPolicy.isProtected(name, 10_000))
        for (uid in listOf(-1, 0, 1000, 9999, 100_000, 110_000, Int.MAX_VALUE)) {
            assertTrue(CommandPolicy.isProtected(pkg, uid))
        }
        for (uid in listOf(10_000, 99_999)) assertFalse(CommandPolicy.isProtected(pkg, uid))
        assertFalse(CommandPolicy.isProtected("com.google.android.gms.other", 10_000))
    }

    @Test fun policyRejectsEveryProtectedPackageMutation() {
        val protected = listOf(
            "com.akane.voltwise", "com.akane.voltwise.debug", "com.akane.voltwise.preview",
            "moe.shizuku.privileged.api", "android", "com.android.systemui", "com.android.phone", "com.google.android.gms",
        )
        val mutations = mutableListOf<PrivilegedCommand>(ForceStop(pkg), AddDozeWhitelist(pkg), RemoveDozeWhitelist(pkg))
        mutations += StandbyBucket.supported(30).map { SetStandbyBucket(pkg, it) }
        for (op in BackgroundOp.entries) {
            mutations += listOf(AppOpMode.ALLOW, AppOpMode.IGNORE).map { SetBackgroundOp(pkg, op, it) }
        }
        for (target in protected) {
            for (command in mutations) {
                val argv = command.argv.map { it.replace(pkg, target) }
                assertFalse(argv.toString(), CommandPolicy.allows(argv))
                assertFalse(argv.toString(), CommandPolicy.allows(argv.joinToString(" ").split(' ')))
            }
        }
        for (target in listOf(pkg, "com.google.android.gms.other", "com.akane.voltwise.other")) {
            for (command in mutations) {
                val argv = command.argv.map { it.replace(pkg, target) }
                assertTrue(argv.toString(), CommandPolicy.allows(argv))
            }
        }
    }

    @Test fun policyPreservesReadOnlyQueriesForProtectedPackages() {
        for (target in listOf(
            "com.akane.voltwise", "com.akane.voltwise.debug", "com.akane.voltwise.preview",
            "moe.shizuku.privileged.api", "com.android.systemui", "com.android.phone", "com.google.android.gms",
        )) {
            assertTrue(CommandPolicy.allows(GetStandbyBucket(target).argv))
            for (op in BackgroundOp.entries) assertTrue(CommandPolicy.allows(GetBackgroundOp(target, op).argv))
        }
        assertTrue(CommandPolicy.allows(ListDozeWhitelist.argv))
    }

    @Test fun sdkGatesOnlyWritableBucketsAndChoosesBackgroundOp() {
        for (sdk in listOf(26, 27)) {
            assertEquals(emptyList<StandbyBucket>(), StandbyBucket.supported(sdk))
            assertEquals(BackgroundOp.RUN_IN_BACKGROUND, BackgroundOp.forSdk(sdk))
        }
        val base = listOf(StandbyBucket.ACTIVE, StandbyBucket.WORKING_SET, StandbyBucket.FREQUENT, StandbyBucket.RARE)
        for (sdk in listOf(28, 29)) {
            assertEquals(base, StandbyBucket.supported(sdk))
            assertEquals(BackgroundOp.RUN_ANY_IN_BACKGROUND, BackgroundOp.forSdk(sdk))
        }
        assertEquals(base + StandbyBucket.RESTRICTED, StandbyBucket.supported(30))
        assertEquals(base + StandbyBucket.RESTRICTED, StandbyBucket.supported(37))
    }
}
