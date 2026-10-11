package com.akane.voltwise.battery.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticLogTest {
    @Test fun legacyDiagnosticNamesStillDecodeUnchanged() {
        val legacyNames = listOf(
            "MONITORING_STARTED", "MONITORING_STOPPED", "START_FAILED", "BATTERY_UNAVAILABLE", "BATTERY_READ_FAILED",
            "STATE_EVENTS_UNAVAILABLE", "HISTORY_WRITE_FAILED", "OBSERVATION_GAP", "CHARGE_UNAVAILABLE",
            "ACCESS_NONE", "ACCESS_SHIZUKU", "ACCESS_ROOT", "ACCESS_ADB", "ADVANCED_READ_FAILED", "ADVANCED_FORMAT_INVALID",
            "ADVANCED_INTERRUPTED", "ADVANCED_RECOVERED", "SYSTEM_STATS_RESET", "ALERT_FAILED", "NOTIFICATION_FAILED",
            "LOG_READ_FAILED", "APP_SCOPE_FAILED", "ADVANCED_INCOMPLETE",
        )
        val restored = DiagnosticLog.decode("BatStatsDiagnostics1" + legacyNames.joinToString("") { "\n$it\t100\t200\t2" })
        assertEquals(legacyNames, restored.map { it.code.name })
        assertEquals(legacyNames, DiagnosticCode.entries.take(legacyNames.size).map { it.name })
        assertTrue(restored.all { it.firstAt == 100L && it.lastAt == 200L && it.count == 2 })
    }

    @Test fun dozeWhitelistCodesPersistAndAppearInDiagnosticReport() {
        val codes = listOf(DiagnosticCode.DOZE_WHITELIST_READ_FAILED, DiagnosticCode.DOZE_WHITELIST_FORMAT_INVALID)
        val log = DiagnosticLog()
        codes.forEach { log.record(it, 100); log.record(it, 200) }
        val restored = DiagnosticLog.decode(DiagnosticLog.encode(log.snapshot()))
        assertEquals(codes.map { DiagnosticEvent(it, 100, 200, count = 2) }, restored)
        codes.forEach { assertTrue(DiagnosticReport.events(restored).contains("${it.name} · 2")) }
    }

    @Test fun advancedIncompletePersistsAndAppearsInDiagnosticReport() {
        val log = DiagnosticLog()
        log.record(DiagnosticCode.ADVANCED_INCOMPLETE, 100)
        log.record(DiagnosticCode.ADVANCED_INCOMPLETE, 200)
        val restored = DiagnosticLog.decode(DiagnosticLog.encode(log.snapshot()))
        assertEquals(listOf(DiagnosticEvent(DiagnosticCode.ADVANCED_INCOMPLETE, 100, 200, count = 2)), restored)
        assertTrue("The report includes the fixed incomplete code and occurrence count",
            DiagnosticReport.events(restored).contains("ADVANCED_INCOMPLETE · 2"))
    }
    @Test fun repeatedFailuresCoalesceAndCountSaturatesWithoutGrowingStorage() {
        val log = DiagnosticLog()
        repeat(20_000) { log.record(DiagnosticCode.ADVANCED_READ_FAILED, it.toLong()) }
        val event = log.snapshot().single()
        assertEquals(9999, event.count)
        assertEquals(0L, event.firstAt)
        assertEquals(19_999L, event.lastAt)
    }
    @Test fun boundRetainsLatestEventsAndRoundTripsExactTimestamps() {
        val log = DiagnosticLog()
        repeat(100) { log.record(DiagnosticCode.entries[it % DiagnosticCode.entries.size], it.toLong()) }
        assertEquals(60, log.snapshot().size)
        assertEquals(40L, log.snapshot().first().firstAt)
        assertEquals(log.snapshot(), DiagnosticLog.decode(DiagnosticLog.encode(log.snapshot())))
    }
    @Test fun clockRollbackStartsSeparateEventInsteadOfNegativeInterval() {
        val log = DiagnosticLog()
        log.record(DiagnosticCode.OBSERVATION_GAP, 100)
        log.record(DiagnosticCode.OBSERVATION_GAP, 50)
        assertEquals(listOf(100L, 50L), log.snapshot().map { it.firstAt })
    }
    @Test fun invalidOrPrivatePayloadsAreRejectedWithoutLoggingThem() {
        val bad = listOf("raw package payload", "BatStatsDiagnostics1\nprivate.package\t1\t2\t1",
            "BatStatsDiagnostics1\nACCESS_NONE\t20\t10\t1", "BatStatsDiagnostics1\nACCESS_NONE\t0\t10\t-1",
            "BatStatsDiagnostics1" + "\nACCESS_NONE\t0\t10\t1".repeat(61), "x".repeat(DiagnosticLog.MAX_BYTES + 1))
        bad.forEach { text -> assertThrows(IllegalArgumentException::class.java) { DiagnosticLog.decode(text) } }
    }
}
