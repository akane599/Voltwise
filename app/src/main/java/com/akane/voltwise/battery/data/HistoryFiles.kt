package com.akane.voltwise.battery.data

import java.io.FilterInputStream
import java.io.InputStream
import java.io.Reader
import java.io.Writer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Serialize imports and clearing, so an import cannot repopulate history after a completed clear. */
class HistoryMaintenance {
    val mutations = Mutex()
    private val clearing = AtomicBoolean()
    val isClearing: Boolean get() = clearing.get()
    private val clearGeneration = AtomicLong()
    /** Invalidates analysis that read history before a clear, even after that clear finishes. */
    val generation: Long get() = clearGeneration.get()

    /** A confirmed clear finishes even if the settings screen is closed. Block new starts first. */
    suspend fun clear(stopMonitoring: suspend () -> Unit, delete: suspend () -> Unit) {
        check(clearing.compareAndSet(false, true)) { "History is already being cleared" }
        clearGeneration.incrementAndGet()
        try {
            withContext(NonCancellable) {
                stopMonitoring()
                mutations.withLock { delete() }
            }
        } finally { clearing.set(false) }
    }
}

object HistoryLimits {
    const val MAX_BYTES = 64L * 1024 * 1024
    const val MAX_SAMPLES = 100_000
    const val MAX_SESSIONS = 10_000
    const val MAX_FIELD_CHARS = 2048
    internal const val CLEANUP_SAMPLE_INTERVAL = 200
    // Leave room for the next maintenance interval, including its final write before cleanup.
    internal const val SAMPLE_TRIM_TARGET = MAX_SAMPLES - CLEANUP_SAMPLE_INTERVAL
    internal const val SESSION_TRIM_TARGET = MAX_SESSIONS - CLEANUP_SAMPLE_INTERVAL

    // Existing excess history must not block imports that leave this store unchanged or smaller.
    internal fun importWithinLimit(before: Int, after: Int, limit: Int): Boolean = after <= limit || after <= before
}

class LimitedHistoryInput(input: InputStream, private val limit: Long = HistoryLimits.MAX_BYTES, private val json: Boolean = false) : FilterInputStream(input) {
    private var consumed = 0L
    private var quoted = false
    private var escaped = false
    private var stringBytes = 0
    private var depth = 0
    private fun scan(value: Int) {
        if (!json) return
        val ch = value.toChar()
        if (quoted) {
            stringBytes++
            require(stringBytes <= 8192) { "JSON string exceeds the size limit" }
            if (escaped) escaped = false
            else if (ch == '\\') escaped = true
            else if (ch == '"') quoted = false
        } else when (ch) {
            '"' -> { quoted = true; stringBytes = 0 }
            '{', '[' -> { depth++; require(depth <= 32) { "JSON nesting exceeds the limit" } }
            '}', ']' -> { depth--; require(depth >= 0) { "Malformed JSON nesting" } }
        }
    }
    private fun account(count: Int): Int {
        if (count > 0) { consumed += count; require(consumed <= limit) { "History file exceeds the size limit" } }
        return count
    }
    override fun read(): Int = `in`.read().also { if (it >= 0) { account(1); scan(it) } }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val count = account(`in`.read(bytes, offset, length))
        if (count > 0) for (i in offset until offset + count) scan(bytes[i].toInt() and 255)
        return count
    }
    override fun skip(n: Long): Long = throw UnsupportedOperationException("Use bounded reads")
}

/** RFC4180 quoting, including embedded newlines, with strict malformed-record and size checks. */
object HistoryCsv {
    fun writeRow(writer: Writer, values: List<String>) {
        writer.append(values.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }).append('\n')
    }
    fun rows(reader: Reader): Sequence<List<String>> = sequence {
        val input = java.io.PushbackReader(reader, 1)
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var started = false
        fun finishField() { fields.add(field.toString()); field.setLength(0); afterQuote = false; started = false; require(fields.size <= 64) { "Too many CSV columns" } }
        while (true) {
            val next = input.read()
            if (next < 0) {
                require(!quoted) { "Unclosed CSV quote" }
                if (fields.isNotEmpty() || started || afterQuote || field.isNotEmpty()) { finishField(); yield(fields) }
                break
            }
            val ch = next.toChar()
            when {
                quoted && ch == '"' -> {
                    val peek = input.read()
                    if (peek == '"'.code) field.append('"')
                    else { quoted = false; afterQuote = true; if (peek >= 0) input.unread(peek) }
                }
                quoted -> field.append(ch)
                ch == ',' -> finishField()
                ch == '\n' || ch == '\r' -> {
                    if (ch == '\r') { val peek = input.read(); if (peek >= 0 && peek != '\n'.code) input.unread(peek) }
                    if (fields.isNotEmpty() || started || afterQuote || field.isNotEmpty()) { finishField(); yield(fields) }
                    fields = mutableListOf(); field.setLength(0); afterQuote = false; started = false
                }
                afterQuote -> error("Unexpected text after CSV quote")
                ch == '"' -> { require(!started && field.isEmpty()) { "Quote inside unquoted CSV field" }; quoted = true; started = true }
                else -> { field.append(ch); started = true }
            }
            require(field.length <= HistoryLimits.MAX_FIELD_CHARS) { "CSV field exceeds the size limit" }
        }
    }
}
