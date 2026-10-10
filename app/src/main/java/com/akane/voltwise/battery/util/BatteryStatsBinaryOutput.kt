package com.akane.voltwise.battery.util

import java.util.Base64

/** Internal envelope for the one structured, read-only batterystats command. */
object BatteryStatsBinaryOutput {
    const val COMMAND = "dumpsys batterystats --proto --charged"
    private const val PREFIX = "VWBSP1:"
    internal const val MAX_RAW_BYTES = (CommandOutput.MAX_BYTES - PREFIX.length) / 4 * 3
    val ARGV: List<String> get() = listOf("dumpsys", "batterystats", "--proto", "--charged")
    internal fun matches(arguments: List<String>): Boolean =
        arguments == ARGV || arguments == listOf("su", "-c", COMMAND)

    internal fun rawLimit(encodedLimit: Int): Int = ((encodedLimit - PREFIX.length).coerceAtLeast(0) / 4) * 3

    fun encode(bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_RAW_BYTES) { "Invalid binary output length" }
        return PREFIX + Base64.getEncoder().encodeToString(bytes)
    }

    /** Fail closed: text dumps and noncanonical/truncated envelopes are never structured evidence. */
    fun decode(output: String): ByteArray? {
        if (output.length > CommandOutput.MAX_BYTES || !output.startsWith(PREFIX)) return null
        val encoded = output.substring(PREFIX.length)
        if (encoded.isEmpty() || encoded.length % 4 != 0) return null
        return try {
            val bytes = Base64.getDecoder().decode(encoded)
            bytes.takeIf { it.isNotEmpty() && it.size <= MAX_RAW_BYTES &&
                Base64.getEncoder().encodeToString(it) == encoded }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
