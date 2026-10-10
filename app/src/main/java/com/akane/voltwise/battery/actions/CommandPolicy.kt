package com.akane.voltwise.battery.actions

import com.akane.voltwise.battery.util.BatteryStatsBinaryOutput

/** Shared, fail-closed argv allow-list for the app and the privileged helper process. */
object CommandPolicy {
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val protectedPackages = setOf(
        "com.akane.voltwise", "com.akane.voltwise.debug", "com.akane.voltwise.preview",
        "moe.shizuku.privileged.api", "android", "com.android.systemui", "com.android.phone",
        "com.google.android.gms",
    )

    fun isPackageName(s: String): Boolean = s.length <= 255 && packageName.matches(s)

    fun isProtected(packageName: String, uid: Int): Boolean =
        uid < 10_000 || uid / 100_000 != 0 || packageName in protectedPackages

    fun allows(argv: List<String>): Boolean {
        if (argv.isEmpty() || argv.any { it.isEmpty() }) return false
        return when (argv.first()) {
            // whittle: only the charged proto diagnostic; extend only for an approved live caller.
            "dumpsys" -> argv == BatteryStatsBinaryOutput.ARGV
            "am" -> allowsAm(argv)
            "cmd" -> allowsAppOps(argv) || allowsWhitelist(argv)
            else -> false
        }
    }

    private fun allowsAm(argv: List<String>): Boolean {
        if (argv.size !in 5..6 || argv[2] != "--user" || argv[3] != "0" || !isPackageName(argv[4])) {
            return false
        }
        return when (argv[1]) {
            "get-standby-bucket" -> argv.size == 5
            "force-stop" -> argv.size == 5 && argv[4] !in protectedPackages
            "set-standby-bucket" -> argv.size == 6 && argv[4] !in protectedPackages &&
                StandbyBucket.entries.any { it.writable && it.token == argv[5] }
            else -> false
        }
    }

    private fun allowsAppOps(argv: List<String>): Boolean {
        if (argv.size !in 7..8 || argv[1] != "appops" || argv[3] != "--user" || argv[4] != "0" ||
            !isPackageName(argv[5]) || BackgroundOp.entries.none { it.name == argv[6] }
        ) return false
        return when (argv[2]) {
            "get" -> argv.size == 7
            "set" -> argv.size == 8 && argv[5] !in protectedPackages &&
                AppOpMode.entries.any { it.writable && it.token == argv[7] }
            else -> false
        }
    }

    private fun allowsWhitelist(argv: List<String>): Boolean {
        if (argv.size !in 3..4 || argv[1] != "deviceidle" || argv[2] != "whitelist") return false
        return argv.size == 3 ||
            (argv[3].first() in "+-" && isPackageName(argv[3].drop(1)) && argv[3].drop(1) !in protectedPackages)
    }
}
