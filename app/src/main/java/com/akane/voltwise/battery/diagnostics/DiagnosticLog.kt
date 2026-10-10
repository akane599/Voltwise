package com.akane.voltwise.battery.diagnostics

/** Only fixed codes are accepted: commands, package names and exception messages cannot enter the log. */
enum class DiagnosticCode {
    MONITORING_STARTED, MONITORING_STOPPED, START_FAILED, BATTERY_UNAVAILABLE, BATTERY_READ_FAILED,
    STATE_EVENTS_UNAVAILABLE, HISTORY_WRITE_FAILED, OBSERVATION_GAP, CHARGE_UNAVAILABLE,
    ACCESS_NONE, ACCESS_SHIZUKU, ACCESS_ROOT, ACCESS_ADB, ADVANCED_READ_FAILED, ADVANCED_FORMAT_INVALID,
    ADVANCED_INTERRUPTED, ADVANCED_RECOVERED, SYSTEM_STATS_RESET, ALERT_FAILED, NOTIFICATION_FAILED, LOG_READ_FAILED,
    APP_SCOPE_FAILED, ADVANCED_INCOMPLETE,
}

data class DiagnosticEvent(val code: DiagnosticCode, val firstAt: Long, val lastAt: Long, val count: Int = 1)

/** Single owner. Oldest events are discarded; adjacent repeated conditions share one entry. */
class DiagnosticLog(restored: List<DiagnosticEvent> = emptyList()) {
    companion object {
        const val MAX_EVENTS = 60
        const val MAX_BYTES = 32 * 1024
        private const val HEADER = "BatStatsDiagnostics1"
        fun decode(text: String): List<DiagnosticEvent> {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val lines = text.lineSequence().toList()
            require(lines.firstOrNull() == HEADER && lines.size <= MAX_EVENTS + 1)
            return lines.drop(1).map { row ->
                val columns = row.split('\t')
                require(columns.size == 4)
                DiagnosticEvent(DiagnosticCode.valueOf(columns[0]), columns[1].toLong(), columns[2].toLong(), columns[3].toInt()).also {
                    require(it.firstAt >= 0 && it.lastAt >= it.firstAt && it.count in 1..9999)
                }
            }
        }
        fun encode(events: List<DiagnosticEvent>): String = buildString {
            append(HEADER)
            events.takeLast(MAX_EVENTS).forEach { append("\n${it.code.name}\t${it.firstAt}\t${it.lastAt}\t${it.count}") }
        }
    }
    private val events = ArrayDeque(restored.takeLast(MAX_EVENTS))
    fun record(code: DiagnosticCode, timestamp: Long) {
        if (timestamp < 0) return
        val previous = events.lastOrNull()
        if (previous?.code == code && timestamp >= previous.lastAt) {
            events.removeLast()
            events.addLast(previous.copy(lastAt = timestamp, count = (previous.count + 1).coerceAtMost(9999)))
        } else {
            if (events.size == MAX_EVENTS) events.removeFirst()
            events.addLast(DiagnosticEvent(code, timestamp, timestamp))
        }
    }
    fun snapshot(): List<DiagnosticEvent> = events.toList()
}
