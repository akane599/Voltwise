package com.akane.voltwise.battery.actions

sealed interface Readback<out T> {
    data class Recognized<T>(val value: T) : Readback<T>
    data object Unrecognized : Readback<Nothing>
}

enum class WhitelistKind { USER, SYSTEM }
data class WhitelistEntry(val packageName: String, val uid: Int, val kind: WhitelistKind)

sealed interface WhitelistChange {
    data class Added(val packageName: String) : WhitelistChange
    data class Removed(val packageName: String) : WhitelistChange
    data class UnknownPackage(val packageName: String) : WhitelistChange
}

/** Unknown or ambiguous output is never interpreted as the requested state. */
object ActionReadback {
    private val appOp = Regex(
        "(RUN_ANY_IN_BACKGROUND|RUN_IN_BACKGROUND): (?:mode: )?" +
            "(allow|ignore|default|deny|foreground)(; (?:time|rejectTime|duration)=[^;\\r\\n]+)*",
    )

    fun standbyBucket(output: String): Readback<StandbyBucket> {
        val value = output.trim()
        val bucket = StandbyBucket.entries.singleOrNull {
            it.code.toString() == value || it.token.equals(value, ignoreCase = true)
        }
        return bucket?.let { Readback.Recognized(it) } ?: Readback.Unrecognized
    }

    private val noOperations = Regex("No operations\\.(?:\\r?\\nDefault mode: (allow|ignore|deny|foreground))?")

    /**
     * Reads only `cmd appops get --user 0 <pkg> <op>` for [op], not an all-op dump.
     * Exactly "No operations." reads the effective ALLOW, or the one accompanying Default mode
     * value. ALLOW restores via `set allow`; MODE_DEFAULT is not the op's effective default.
     * Wrong-op, multiple/conflicting records and unexpected text remain unrecognized.
     */
    fun backgroundOp(output: String, op: BackgroundOp): Readback<AppOpMode> {
        val value = output.trim()
        noOperations.matchEntire(value)?.let { match ->
            val mode = if (match.groupValues[1].isEmpty()) AppOpMode.ALLOW
                else AppOpMode.entries.single { it.token == match.groupValues[1] }
            return Readback.Recognized(mode)
        }
        val match = appOp.matchEntire(value) ?: return Readback.Unrecognized
        if (match.groupValues[1] != op.name) return Readback.Unrecognized
        return Readback.Recognized(AppOpMode.entries.single { it.token == match.groupValues[2] })
    }

    fun dozeWhitelist(output: String): Readback<List<WhitelistEntry>> {
        val entries = mutableListOf<WhitelistEntry>()
        for (line in output.lineSequence().filter { it.isNotBlank() }) {
            val fields = line.trim().split(',')
            if (fields.size != 3) return Readback.Unrecognized
            val kind = when (fields[0]) {
                "user" -> WhitelistKind.USER
                "system", "system-excidle" -> WhitelistKind.SYSTEM
                else -> return Readback.Unrecognized
            }
            if (!CommandPolicy.isPackageName(fields[1]) && fields[1] != "android") return Readback.Unrecognized
            if (fields[2].isEmpty() || fields[2].any { it !in '0'..'9' }) return Readback.Unrecognized
            val uid = fields[2].toIntOrNull() ?: return Readback.Unrecognized
            entries += WhitelistEntry(fields[1], uid, kind)
        }
        // Real lists contain system entries; empty transport output cannot establish membership.
        return if (entries.isEmpty()) Readback.Unrecognized else Readback.Recognized(entries)
    }

    /**
     * Parses acknowledgements only; confirm membership with [dozeWhitelist] after every mutation.
     * UnknownPackage can also mean already whitelisted, not proof the package is uninstalled;
     * a silent removal is unrecognized here, not evidence that membership changed.
     */
    fun whitelistChange(output: String): Readback<WhitelistChange> {
        val value = output.trim()
        val prefix = listOf("Added: ", "Removed: ", "Unknown package: ").firstOrNull(value::startsWith)
            ?: return Readback.Unrecognized
        val pkg = value.removePrefix(prefix)
        if (!CommandPolicy.isPackageName(pkg)) return Readback.Unrecognized
        val change = when (prefix) {
            "Added: " -> WhitelistChange.Added(pkg)
            "Removed: " -> WhitelistChange.Removed(pkg)
            else -> WhitelistChange.UnknownPackage(pkg)
        }
        return Readback.Recognized(change)
    }
}
