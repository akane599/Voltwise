package com.akane.voltwise.battery.actions

import com.akane.voltwise.battery.util.ExecutionPolicy

enum class StandbyBucket(val token: String, val code: Int) {
    EXEMPTED("exempted", 5),
    ACTIVE("active", 10),
    WORKING_SET("working_set", 20),
    FREQUENT("frequent", 30),
    RARE("rare", 40),
    RESTRICTED("restricted", 45),
    NEVER("never", 50);

    val writable: Boolean get() = this != EXEMPTED && this != NEVER

    companion object {
        /** Buckets this app may set; EXEMPTED and NEVER are readback-only. */
        fun supported(sdk: Int): List<StandbyBucket> = entries.filter {
            sdk >= 28 && it.writable && (it != RESTRICTED || sdk >= 30)
        }
    }
}

enum class BackgroundOp {
    RUN_ANY_IN_BACKGROUND, RUN_IN_BACKGROUND;

    companion object {
        fun forSdk(sdk: Int): BackgroundOp = if (sdk >= 28) RUN_ANY_IN_BACKGROUND else RUN_IN_BACKGROUND
    }
}

enum class AppOpMode(val token: String) {
    ALLOW("allow"), IGNORE("ignore"), DEFAULT("default"), DENY("deny"), FOREGROUND("foreground");

    /** DEFAULT, DENY and FOREGROUND are readback-only. */
    val writable: Boolean get() = this == ALLOW || this == IGNORE
}

/** No caller-supplied command text crosses the action boundary. */
sealed interface PrivilegedCommand {
    val argv: List<String>
    val executionPolicy: ExecutionPolicy get() = when (this) {
        is GetStandbyBucket, is GetBackgroundOp, ListDozeWhitelist -> ExecutionPolicy.READ_ONLY
        is SetStandbyBucket, is SetBackgroundOp, is RemoveDozeWhitelist, is AddDozeWhitelist,
        is ForceStop -> ExecutionPolicy.MUTATION
    }

    data class GetStandbyBucket(val pkg: String) : PrivilegedCommand {
        init { requirePackage(pkg) }
        override val argv get() = listOf("am", "get-standby-bucket", "--user", "0", pkg)
    }

    data class SetStandbyBucket(val pkg: String, val bucket: StandbyBucket) : PrivilegedCommand {
        init {
            requirePackage(pkg)
            require(bucket.writable) { "Unsupported standby bucket" }
        }
        override val argv get() = listOf("am", "set-standby-bucket", "--user", "0", pkg, bucket.token)
    }

    data class GetBackgroundOp(val pkg: String, val op: BackgroundOp) : PrivilegedCommand {
        init { requirePackage(pkg) }
        override val argv get() = listOf("cmd", "appops", "get", "--user", "0", pkg, op.name)
    }

    data class SetBackgroundOp(val pkg: String, val op: BackgroundOp, val mode: AppOpMode) : PrivilegedCommand {
        init {
            requirePackage(pkg)
            require(mode.writable) { "Unsupported app-op mode" }
        }
        override val argv get() = listOf("cmd", "appops", "set", "--user", "0", pkg, op.name, mode.token)
    }

    data object ListDozeWhitelist : PrivilegedCommand {
        override val argv get() = listOf("cmd", "deviceidle", "whitelist")
    }

    data class RemoveDozeWhitelist(val pkg: String) : PrivilegedCommand {
        init { requirePackage(pkg) }
        override val argv get() = listOf("cmd", "deviceidle", "whitelist", "-$pkg")
    }

    data class AddDozeWhitelist(val pkg: String) : PrivilegedCommand {
        init { requirePackage(pkg) }
        override val argv get() = listOf("cmd", "deviceidle", "whitelist", "+$pkg")
    }

    data class ForceStop(val pkg: String) : PrivilegedCommand {
        init { requirePackage(pkg) }
        override val argv get() = listOf("am", "force-stop", "--user", "0", pkg)
    }
}

private fun requirePackage(pkg: String) {
    require(CommandPolicy.isPackageName(pkg)) { "Invalid package name" }
}
