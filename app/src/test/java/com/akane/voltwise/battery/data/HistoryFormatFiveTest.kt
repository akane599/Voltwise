package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionAppUsage
import com.akane.voltwise.battery.data.db.SessionType
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class HistoryFormatFiveTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun session() = ChargeSession(
        "doze", SessionType.DISCHARGE, 1000, 11_000, 90, 89, null, null, null,
        observedMs = 10_000, screenOnMs = 2000, screenOffMs = 8000,
        dozeMs = 7000, screenOffDozeMs = 6000,
    )

    @Test fun formatFiveRoundTripPreservesNullableDozeAndOmitsCaptureWindows() {
        assertEquals(5, HISTORY_FORMAT_VERSION)
        val original = session().copy(appCaptureStartMs = 1000, appCaptureEndMs = 11_000)
        val payload = BatteryExport(sessions = listOf(original), formatVersion = HISTORY_FORMAT_VERSION)
        val encoded = json.encodeToString(BatteryExport.serializer(), payload)
        assertTrue(encoded.contains("\"dozeMs\":7000"))
        assertTrue(encoded.contains("\"screenOffDozeMs\":6000"))
        assertFalse(encoded.contains("appCaptureStartMs"))
        assertFalse(encoded.contains("appCaptureEndMs"))
        val restored = json.decodeFromString(BatteryExport.serializer(), encoded).sessions.single()
        assertEquals(original.copy(appCaptureStartMs = null, appCaptureEndMs = null), restored)
        assertEquals(7000L, HistoryPolicy.session(restored).dozeMs)
        assertEquals(6000L, HistoryPolicy.session(restored).screenOffDozeMs)
        val unknown = session().copy(dozeMs = null, screenOffDozeMs = null)
        val nullPayload = BatteryExport(sessions = listOf(unknown), formatVersion = 5)
        assertEquals(unknown, json.decodeFromString(BatteryExport.serializer(),
            json.encodeToString(BatteryExport.serializer(), nullPayload)).sessions.single())
    }

    @Test fun formatsOneThroughFourImportDozeAsUnknownEvenIfHandMadeFieldsExist() {
        for (format in 1..4) {
            val payload = BatteryExport(sessions = listOf(session()), formatVersion = format)
            val restored = json.decodeFromString(BatteryExport.serializer(),
                json.encodeToString(BatteryExport.serializer(), payload))
            val imported = HistoryPolicy.session(restored.sessions.single(), restored.formatVersion)
            assertNull(imported.dozeMs)
            assertNull(imported.screenOffDozeMs)
            val plan = HistoryPolicy.planSessionImport(null, restored.sessions.single(),
                formatVersion = restored.formatVersion)
            assertNull(plan.session.dozeMs)
            assertNull(plan.session.screenOffDozeMs)
            val missing = session().copy(dozeMs = null, screenOffDozeMs = null)
            assertNull(HistoryPolicy.session(missing, format).dozeMs)
        }
    }

    @Test fun formatFourWithoutNewColumnsDecodesAndImportsUnknownDoze() {
        val record = JsonObject(json.encodeToJsonElement(HistorySessionSerializer, session()).jsonObject
            .filterKeys { it != "dozeMs" && it != "screenOffDozeMs" })
        val payload = json.decodeFromJsonElement(BatteryExport.serializer(), buildJsonObject {
            put("formatVersion", 4)
            put("sessions", JsonArray(listOf(record)))
        })
        val imported = HistoryPolicy.planSessionImport(null, payload.sessions.single(),
            formatVersion = payload.formatVersion).session
        assertNull(imported.dozeMs)
        assertNull(imported.screenOffDozeMs)
    }

    @Test fun formatFiveEnrichesLegacyDozeAndLegacyReimportKeepsKnownEvidence() {
        val old = HistoryPolicy.session(session(), 4)
        val enriched = HistoryPolicy.planSessionImport(old, session())
        assertEquals(ImportSessionDisposition.UPDATED, enriched.disposition)
        assertEquals(7000L, enriched.session.dozeMs)
        assertEquals(6000L, enriched.session.screenOffDozeMs)
        val repeated = HistoryPolicy.planSessionImport(enriched.session, session(),
            formatVersion = 4)
        assertEquals(ImportSessionDisposition.UNCHANGED, repeated.disposition)
        assertEquals(enriched.session, repeated.session)
        assertThrows(IllegalArgumentException::class.java) {
            HistoryPolicy.planSessionImport(enriched.session, session().copy(dozeMs = 6999))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistoryPolicy.planSessionImport(enriched.session, session().copy(screenOffDozeMs = 5999))
        }
    }

    @Test fun dozeFromAnOlderWindowIsNotCopiedIntoAnExtendedUnknownWindow() {
        val previous = HistoryPolicy.session(session())
        val incoming = session().copy(endTime = 12_000, observedMs = 11_000,
            screenOffMs = 9000, dozeMs = null, screenOffDozeMs = null)
        val updated = HistoryPolicy.planSessionImport(previous, incoming)
        assertEquals(ImportSessionDisposition.UPDATED, updated.disposition)
        assertNull(updated.session.dozeMs)
        assertNull(updated.session.screenOffDozeMs)
    }

    @Test fun importClampsDozeToObservedAndScreenOffIntervalsAndDropsNegatives() {
        val oversized = HistoryPolicy.session(session().copy(dozeMs = Long.MAX_VALUE, screenOffDozeMs = Long.MAX_VALUE))
        assertEquals(10_000L, oversized.dozeMs)
        assertEquals(8000L, oversized.screenOffDozeMs)
        assertEquals(oversized, HistoryPolicy.session(oversized))
        for (negative in listOf(-1L, Long.MIN_VALUE)) {
            val row = HistoryPolicy.session(session().copy(dozeMs = negative, screenOffDozeMs = negative))
            assertNull(row.dozeMs)
            assertNull(row.screenOffDozeMs)
        }
        val zero = HistoryPolicy.session(session().copy(dozeMs = 0, screenOffDozeMs = 0))
        assertEquals(0L, zero.dozeMs)
        assertEquals(0L, zero.screenOffDozeMs)
        val unobserved = HistoryPolicy.session(session().copy(observedMs = 0, screenOnMs = 0, screenOffMs = 0))
        assertEquals(0L, unobserved.dozeMs)
        assertEquals(0L, unobserved.screenOffDozeMs)
    }

    @Test fun clockCorrectionScalesDozeWithCoverageAndKeepsItWithinNormalizedSpan() {
        val normalized = HistoryPolicy.session(session().copy(endTime = 10_000))
        assertEquals(9000L, normalized.observedMs)
        assertEquals(6300L, normalized.dozeMs)
        assertEquals(5400L, normalized.screenOffDozeMs)
        assertTrue(checkNotNull(normalized.screenOffDozeMs) <= normalized.screenOffMs)
        assertEquals(normalized, HistoryPolicy.session(normalized))
        val empty = HistoryPolicy.session(session().copy(endTime = 1000,
            observedMs = 1000, screenOnMs = 0, screenOffMs = 1000))
        assertEquals(0L, empty.dozeMs)
        assertEquals(0L, empty.screenOffDozeMs)
    }

    @Test fun legacyUsageTagsNeverEnterJsonOrSessionCsvAndRoundTripAsAbsent() {
        val malicious = SessionAppUsage("doze", 0, 10_001, "example.app", 1.0,
            basis = AppUsageBasis.DELTA, topWakelockTag = "=1+1", topAlarmTag = "+1+1", topJobName = "@SUM(1)")
        val payload = BatteryExport(sessions = listOf(session()), formatVersion = 5)
        val encoded = json.encodeToString(BatteryExport.serializer(), payload)
        assertFalse(encoded.contains("appUsage\":"))
        assertFalse(encoded.contains("=1+1"))
        assertFalse(encoded.contains("topWakelockTag"))
        val restored = json.decodeFromString(BatteryExport.serializer(), encoded)
        assertEquals(payload, restored)
        // CSV uses this exact session serializer, and emits no per-app file.
        val record = json.encodeToJsonElement(HistorySessionSerializer, payload.sessions.single()).jsonObject
        val csv = StringWriter().also { writer ->
            HistoryCsv.writeRow(writer, record.keys.toList())
            HistoryCsv.writeRow(writer, record.values.map { it.jsonPrimitive.content })
        }.toString()
        assertFalse(csv.contains("=1+1"))
        assertFalse(csv.contains("topWakelockTag"))
        assertFalse(csv.contains("appCapture"))
        val legacy = JsonObject(json.parseToJsonElement(encoded).jsonObject +
            ("appUsage" to json.encodeToJsonElement(listOf(malicious))))
        assertEquals(payload, json.decodeFromJsonElement(BatteryExport.serializer(), legacy))
    }

    @Test fun legacyFormatThreeAndFourUsageKeysAreIgnoredAndReadyClaimsAreStripped() {
        val ready = session().copy(appUsageStatus = AppUsageStatus.READY,
            appUsageBasis = AppUsageBasis.DELTA)
        for (format in 3..4) {
            val legacy = buildJsonObject {
                put("formatVersion", format)
                put("sessions", JsonArray(listOf(json.encodeToJsonElement(HistorySessionSerializer, ready))))
                // Unknown legacy evidence is skipped rather than validated or restored.
                put("appUsage", JsonArray(listOf(buildJsonObject { put("rank", "invalid") })))
            }
            val payload = json.decodeFromJsonElement(BatteryExport.serializer(), legacy)
            assertEquals(listOf(ready), payload.sessions)
            val plan = HistoryPolicy.planSessionImport(null, payload.sessions.single(),
                formatVersion = payload.formatVersion)
            assertEquals(ImportSessionDisposition.ADDED, plan.disposition)
            assertEquals("import:doze", plan.session.sessionId)
            assertNull(plan.session.appUsageStatus)
            assertNull(plan.session.appUsageBasis)
        }
    }

    @Test fun handMadeCaptureWindowsAreIgnoredWithoutValidationOrImport() {
        val record = JsonObject(json.encodeToJsonElement(HistorySessionSerializer, session()).jsonObject + mapOf(
            "appCaptureStartMs" to JsonPrimitive("invalid"),
            "appCaptureEndMs" to JsonPrimitive(-1),
        ))
        val decoded = json.decodeFromJsonElement(HistorySessionSerializer, record)
        assertNull(decoded.appCaptureStartMs)
        assertNull(decoded.appCaptureEndMs)
        val programmatic = HistoryPolicy.session(session().copy(appCaptureStartMs = -1, appCaptureEndMs = Long.MAX_VALUE))
        assertNull(programmatic.appCaptureStartMs)
        assertNull(programmatic.appCaptureEndMs)
    }
}
