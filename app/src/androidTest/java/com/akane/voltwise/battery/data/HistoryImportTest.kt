package com.akane.voltwise.battery.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageRow
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.data.db.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HistoryImportTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun sample(time: Long = 1500) = BatterySample(timestamp = time, levelPercent = 60, status = 3, plugged = 0,
        currentNowUa = -1000, chargeCounterUah = 2_000_000, voltageMv = 4000, temperatureDeciC = 250, health = 2,
        screenOn = true, elapsedMs = time, uptimeMs = time, sessionId = "session", observationId = "observation", source = "BatteryManager")
    private fun session() = ChargeSession("session", SessionType.DISCHARGE, 1000, null, 60, 59, 1000, -1000, null,
        lastSampleTime = 2000, observationId = "observation", observedMs = 1000, counterCoveredMs = 1000,
        screenOnMs = 1000, screenOnUah = 1000, source = "BatteryManager observed interval")
    private fun database() = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()

    private inline fun <T> BatteryDatabase.useDatabase(block: (BatteryDatabase) -> T): T = try { block(this) } finally { close() }

    @Test fun repeatedImportDoesNotDuplicateOrRestoreAnActiveSession() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            val payload = BatteryExport(listOf(sample()), listOf(session()))
            assertEquals(HistoryImportResult(1, 1, 0, 0), manager.importPayload(payload))
            assertEquals(HistoryImportResult(0, 0, 0, 2), manager.importPayload(payload))
            assertNull(db.sessionDao().active())
            assertEquals(2000L, db.sessionDao().byId("import:session")!!.endTime)
            assertEquals(1, db.batteryDao().count())
        }
    }
    @Test fun nativeWriteAfterImportTrimsOnlyOverflowAcrossTiedTimestamps() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            val points = (1..10).map { index ->
                sample(if (index <= 6) 1500 else 1500 + index.toLong()).copy(
                    elapsedMs = 2000 + index.toLong(), uptimeMs = 0, sessionId = null,
                )
            }
            assertEquals(HistoryImportResult(10, 0, 0, 0), manager.importPayload(BatteryExport(samples = points)))
            val nativeId = db.batteryDao().insertSample(sample(3000).copy(sessionId = null))
            val before = db.batteryDao().samplesBetween(0, 4000).first()
            val expected = before.sortedWith(compareByDescending<BatterySample> { it.timestamp }.thenByDescending { it.id }).take(7)
            db.batteryDao().boundStorage(7)
            val kept = db.batteryDao().samplesBetween(0, 4000).first()
            assertEquals("Tied cutoff must delete only four overflow readings", 7, db.batteryDao().count())
            assertEquals(expected.map { it.id }.toSet(), kept.map { it.id }.toSet())
            assertEquals(2, db.batteryDao().atTimestamp(1500).size)
            assertNotNull(db.batteryDao().byId(nativeId))
        }
    }

    @Test fun sampleTrimPreservesBelowTargetAndOrdersTimestampTiesById() = runBlocking {
        database().useDatabase { db ->
            for (id in listOf(-7L, 2L, -2L)) {
                db.batteryDao().insertSample(sample().copy(id = id, elapsedMs = 2000 + id, uptimeMs = 0, sessionId = null))
            }
            val before = db.batteryDao().samplesBetween(0, 3000).first().toSet()
            db.batteryDao().boundStorage(4)
            assertEquals("Below-target trim must preserve every reading", before, db.batteryDao().samplesBetween(0, 3000).first().toSet())
            db.batteryDao().boundStorage(3)
            assertEquals("At-target trim must preserve every reading", before, db.batteryDao().samplesBetween(0, 3000).first().toSet())
            for (id in listOf(7L, 1L)) {
                db.batteryDao().insertSample(sample().copy(id = id, elapsedMs = 2000 + id, uptimeMs = 0, sessionId = null))
            }
            db.batteryDao().boundStorage(3)
            assertEquals("All-tied trim must keep the highest IDs", setOf(7L, 2L, 1L), db.batteryDao().atTimestamp(1500).map { it.id }.toSet())
        }
    }

    @Test fun allSessionExportAfterFullImportAndBoundaryKeepsNewestCapWithoutDeletingHistory() = runBlocking {
        val file = File.createTempFile("history-session-cap", ".json", context.cacheDir)
        try {
            database().useDatabase { db ->
                val manager = ExportImportManager(context, db, HistoryMaintenance())
                val sessions = (1..HistoryLimits.MAX_SESSIONS).map { index ->
                    ChargeSession("s%05d".format(index), SessionType.DISCHARGE, maxOf(2, index).toLong(),
                        maxOf(2, index).toLong() + 1, null, null, null, null, null, activeKey = null)
                }
                assertEquals(HistoryLimits.MAX_SESSIONS, manager.importPayload(BatteryExport(sessions = sessions)).sessionsAdded)
                val boundary = ChargeSession("native-boundary", SessionType.CHARGE, 10001, null,
                    null, null, null, null, null, appCaptureStartMs = 10001)
                db.sessionDao().insert(boundary)
                val expected = sessions.drop(1).map { "import:${it.sessionId}" } + boundary.sessionId
                val payload = manager.snapshot(0, 10001, false, true)
                assertEquals("ALL snapshot keeps newest 10000 with deterministic cutoff tie", expected, payload.sessions.map { it.sessionId })
                assertEquals(boundary, payload.sessions.last())
                manager.exportJson(Uri.fromFile(file), 0, 10001, false, true)
                val encoded = file.readText()
                val exported = Json.parseToJsonElement(encoded).jsonObject.getValue("sessions").jsonArray
                assertEquals(expected, exported.map { it.jsonObject.getValue("sessionId").jsonPrimitive.content })
                assertFalse(encoded.contains("appCaptureStartMs"))
                assertFalse(encoded.contains("appCaptureEndMs"))
                try {
                    manager.snapshot(1, 10001, false, true)
                    fail("Explicit session range overflow must be rejected, not truncated")
                } catch (error: IllegalArgumentException) {
                    assertEquals("Use a smaller export date range", error.message)
                }
                assertEquals("Neither successful ALL nor rejected range export may delete history", 10001, db.sessionDao().count())
                assertNotNull(db.sessionDao().byId("import:s00001"))
            }
        } finally { file.delete() }
    }

    @Test fun boundedSessionExportPreservesInclusiveOverlapAndEqualTimeOrdering() = runBlocking {
        database().useDatabase { db ->
            val dao = db.sessionDao()
            fun row(id: String, start: Long, end: Long?, last: Long? = null) =
                ChargeSession(id, SessionType.DISCHARGE, start, end, null, null, null, null, null,
                    activeKey = null, lastSampleTime = last)
            val rows = listOf(row("after", 201, 250), row("b", 200, 220), row("a", 200, 220),
                row("ends-at-from", 50, 100), row("last-sample-at-from", 60, null, 100),
                row("before", 10, 99), row("no-end", 150, null))
            rows.forEach { dao.insert(it) }
            val overlapping = listOf("ends-at-from", "last-sample-at-from", "no-end", "a", "b")
            assertEquals(overlapping, dao.latestSessionsBetween(100, 200, HistoryLimits.MAX_SESSIONS).map { it.sessionId })
            assertEquals(overlapping, dao.exportSessions(100, 200).map { it.sessionId })
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            assertEquals(listOf("before") + overlapping, manager.snapshot(0, 200, false, true).sessions.map { it.sessionId })
            assertEquals(7, dao.count())
        }
    }

    @Test fun ownExportPreservesTheLocalActiveSessionAndCanRestoreAPrunedPoint() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            db.sessionDao().insert(session())
            db.batteryDao().insertSample(sample())
            val payload = manager.snapshot(0, 3000, true, true)
            assertEquals(HistoryImportResult(0, 0, 0, 2), manager.importPayload(payload))
            assertEquals(session(), db.sessionDao().active())
            db.batteryDao().clearAll()
            assertEquals(HistoryImportResult(1, 0, 0, 1), manager.importPayload(payload))
            assertEquals("session", db.batteryDao().lastSample()!!.sessionId)
            assertEquals(1, db.batteryDao().samplesForSession("session").first().size)
            assertEquals(HistoryImportResult(0, 0, 0, 2), manager.importPayload(payload))
            assertEquals(session(), db.sessionDao().active())
        }
    }
    @Test fun conflictingPointRollsBackTheEntireImportIncludingEarlierSessionInserts() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            db.batteryDao().insertSample(sample())
            try {
                manager.importPayload(BatteryExport(listOf(sample().copy(currentNowUa = -2000)), listOf(session())))
                fail("Conflicting observed point must fail")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, db.sessionDao().count())
            assertEquals(1, db.batteryDao().count())
            assertEquals(-1000L, db.batteryDao().lastSample()!!.currentNowUa)
        }
    }
    @Test fun invalidFinalRecordCannotLeaveAPartialImport() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            try {
                manager.importPayload(BatteryExport(listOf(sample(), sample(1700).copy(voltageMv = 4_000_000)), listOf(closedSession())))
                fail("Invalid voltage must fail")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, db.batteryDao().count())
            assertEquals(0, db.sessionDao().count())
        }
    }
    @Test fun invalidSessionCannotLeaveOtherPortableHistory() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            try {
                manager.importPayload(BatteryExport(listOf(sample()), listOf(closedSession(),
                    closedSession().copy(sessionId = "invalid", startLevel = 101))))
                fail("Invalid session level must fail")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, db.batteryDao().count())
            assertEquals(0, db.sessionDao().count())
        }
    }
    @Test fun exportRangeSelectsOverlappingSessionsWithoutRewritingTheirTotals() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            db.sessionDao().insert(session().copy(endTime = 2000, activeKey = null))
            db.sessionDao().insert(session().copy(sessionId = "outside", startTime = 3000, endTime = 4000, lastSampleTime = 4000, activeKey = null))
            db.batteryDao().insertSample(sample()); db.batteryDao().insertSample(sample(1900))
            val payload = manager.snapshot(1400, 1600, true, true)
            assertEquals(1, payload.samples.size); assertEquals(1, payload.sessions.size)
            assertEquals(1000L, payload.sessions.single().startTime)
            assertEquals(2000L, payload.sessions.single().endTime)
            assertEquals(1000L, payload.sessions.single().deltaUah)
            assertEquals(1400L, payload.fromEpochMs); assertEquals(1600L, payload.toEpochMs)
            assertTrue(payload.units["chargeCounterUah"]!!.contains("µAh"))
        }
    }
    @Test fun jsonFileRoundTripRetainsNullableValuesAndDeduplicates() = runBlocking {
        val file = File.createTempFile("history-test", ".json", context.cacheDir)
        try {
            database().useDatabase { source ->
                val point = sample().copy(sessionId = null, chargeCounterUah = null, temperatureDeciC = 0)
                source.batteryDao().insertSample(point)
                ExportImportManager(context, source, HistoryMaintenance()).exportJson(Uri.fromFile(file), 0, 3000, true, false)
                database().useDatabase { dest ->
                    val manager = ExportImportManager(context, dest, HistoryMaintenance())
                    assertEquals(1, manager.importJson(Uri.fromFile(file)).samplesAdded)
                    assertEquals(1, manager.importJson(Uri.fromFile(file)).unchanged)
                    assertNull(dest.batteryDao().lastSample()!!.chargeCounterUah)
                    assertEquals(0, dest.batteryDao().lastSample()!!.temperatureDeciC)
                }
            }
        } finally { file.delete() }
    }
    @Test fun localChartsDoNotMixImportedOrLegacyDeviceReadings() = runBlocking {
        database().useDatabase { db ->
            db.batteryDao().insertSample(sample())
            db.batteryDao().insertSample(sample(1600).copy(source = "import:BatteryManager", observationId = "import:foreign"))
            db.batteryDao().insertSample(sample(1700).copy(source = "legacy", observationId = null))
            val chart = db.batteryDao().chartSamples(0, 3000, 1).first()
            assertEquals(listOf(1500L), chart.map { it.timestamp })
            assertEquals(3, db.batteryDao().samplesBetween(0, 3000).first().size)
        }
    }
    private fun closedSession() = session().copy(endTime = 2000, activeKey = null, energyNwh = 3_900_000, peakTemperatureDeciC = 310,
        screenOffSuspendMs = 0, capacityEstimateMah = 4800, capacityConfidence = "LOW", capacityBasis = "COUNTER_SPAN",
        appUsageStatus = AppUsageStatus.READY, appUsageBasis = AppUsageBasis.DELTA)
    private val appRows = listOf(AppUsageRow(10_123, "com.example.video", 12.5, cpuTimeMs = 60_000, foregroundTimeMs = 900_000, wifiBytes = 4096),
        AppUsageRow(1000, "android", 3.25), AppUsageRow(-1, "", 0.75, isOthers = true))

    @Test fun formatTwoFileWithoutV5FieldsImportsWithThemEmpty() = runBlocking {
        val file = File.createTempFile("history-test", ".json", context.cacheDir)
        try {
            file.writeText("""{"sessions":[{"sessionId":"old","type":"DISCHARGE","startTime":1000,"endTime":2000,"startLevel":60,"endLevel":59,""" +
                """"deltaUah":null,"avgCurrentUa":null,"estCapacityMah":3000,"activeKey":null,"observationId":null,"lastSampleTime":null,""" +
                """"observedMs":0,"counterCoveredMs":0,"screenOnMs":0,"screenOffMs":0,"screenOnUah":null,"screenOffUah":null,""" +
                """"cpuSuspendMs":null,"closeReason":"Power state changed","source":"BatteryManager observed interval"}],"formatVersion":2}""")
            database().useDatabase { db ->
                assertEquals(HistoryImportResult(0, 1, 0, 0), ExportImportManager(context, db, HistoryMaintenance()).importJson(Uri.fromFile(file)))
                val imported = db.sessionDao().byId("import:old")!!
                assertEquals(3000, imported.estCapacityMah)
                assertTrue(listOf(imported.chargerType, imported.energyNwh, imported.peakPowerMw, imported.peakTemperatureDeciC,
                    imported.screenOffSuspendMs, imported.capacityEstimateMah, imported.capacityConfidence, imported.capacityBasis,
                    imported.appUsageStatus, imported.appUsageBasis).all { it == null })
                assertEquals(emptyList<SessionAppUsage>(), db.appUsageDao().sessionUsage("import:old").first())
            }
        } finally { file.delete() }
    }
    @Test fun formatFiveRoundTripExcludesAppEvidenceAndPreservesLocalBreakdowns() = runBlocking {
        val file = File.createTempFile("history-test", ".json", context.cacheDir)
        try {
            database().useDatabase { source ->
                val local = closedSession().copy(appCaptureStartMs = 1100, appCaptureEndMs = 1900)
                source.sessionDao().insert(local)
                source.batteryDao().insertSample(sample())
                source.appUsageDao().replaceSessionUsage("session", AppUsageBasis.DELTA, appRows)
                val localRows = source.appUsageDao().sessionUsage("session").first()
                val localPoint = source.batteryDao().lastSample()
                val sourceManager = ExportImportManager(context, source, HistoryMaintenance())
                val payload = sourceManager.snapshot(0, 3000, true, true)
                assertEquals(HISTORY_FORMAT_VERSION, payload.formatVersion)
                sourceManager.exportJson(Uri.fromFile(file), 0, 3000, true, true)
                val encoded = file.readText()
                val exported = Json.parseToJsonElement(encoded).jsonObject
                assertFalse(exported.containsKey("appUsage"))
                assertFalse(exported.containsKey("appRows"))
                assertFalse(encoded.contains("com.example.video"))
                val exportedSession = exported.getValue("sessions").jsonArray.single().jsonObject
                assertFalse(exportedSession.containsKey("appCaptureStartMs"))
                assertFalse(exportedSession.containsKey("appCaptureEndMs"))
                database().useDatabase { dest ->
                    val manager = ExportImportManager(context, dest, HistoryMaintenance())
                    assertEquals(HistoryImportResult(1, 1, 0, 0), manager.importJson(Uri.fromFile(file)))
                    val imported = dest.sessionDao().byId("import:session")!!
                    assertEquals(local.copy(sessionId = "import:session", observationId = "import:observation",
                        source = "import:BatteryManager observed interval", appUsageStatus = null, appUsageBasis = null,
                        appCaptureStartMs = null, appCaptureEndMs = null), imported)
                    val importedPoint = dest.batteryDao().lastSample()!!
                    assertEquals(sample().copy(id = importedPoint.id, sessionId = "import:session",
                        observationId = "import:observation", source = "import:BatteryManager"), importedPoint)
                    assertNull(dest.sessionDao().active())
                    assertTrue(dest.appUsageDao().sessionUsage("import:session").first().isEmpty())
                    assertEquals(HistoryImportResult(0, 0, 0, 2), manager.importJson(Uri.fromFile(file)))
                    val legacy = JsonObject(exported + mapOf(
                        "formatVersion" to JsonPrimitive(4),
                        "appUsage" to Json.encodeToJsonElement(localRows.map { it.copy(powerMah = 99.0) }),
                    ))
                    file.writeText(legacy.toString())
                    assertEquals(HistoryImportResult(0, 0, 0, 2), manager.importJson(Uri.fromFile(file)))
                    assertEquals(imported, dest.sessionDao().byId("import:session"))
                    assertTrue(dest.appUsageDao().usageForSessionsBetween(0, 3000).isEmpty())
                    assertEquals(1, dest.batteryDao().count())
                }
                // A legacy copy cannot replace the device's native session or local breakdown.
                assertEquals(HistoryImportResult(0, 0, 0, 2), sourceManager.importJson(Uri.fromFile(file)))
                assertEquals(local, source.sessionDao().byId("session"))
                assertEquals(localRows, source.appUsageDao().sessionUsage("session").first())
                assertEquals(localPoint, source.batteryDao().lastSample())
            }
        } finally { file.delete() }
    }
    @Test fun staleParentsAndReimportsPreserveExistingBreakdowns() = runBlocking {
        database().useDatabase { db ->
            val manager = ExportImportManager(context, db, HistoryMaintenance())
            val local = closedSession().copy(sessionId = "local", observationId = "local-observation")
            db.sessionDao().insert(local)
            db.appUsageDao().replaceSessionUsage("local", AppUsageBasis.DELTA, appRows)
            val localRows = db.appUsageDao().sessionUsage("local").first()
            val newer = BatteryExport(sessions = listOf(closedSession()), formatVersion = 4)
            val older = newer.copy(sessions = listOf(closedSession().copy(
                endTime = 1500, lastSampleTime = 1500, observedMs = 500, counterCoveredMs = 500,
                screenOnMs = 500, deltaUah = 500, screenOnUah = 500,
            )))
            assertEquals(HistoryImportResult(0, 1, 0, 0), manager.importPayload(newer))
            // Simulate a breakdown already present from an older version of the app.
            db.appUsageDao().replaceSessionUsage("import:session", AppUsageBasis.DELTA, appRows)
            val retained = db.sessionDao().byId("import:session")
            val retainedRows = db.appUsageDao().sessionUsage("import:session").first()
            assertEquals(HistoryImportResult(0, 0, 0, 1), manager.importPayload(older))
            assertEquals(retained, db.sessionDao().byId("import:session"))
            assertEquals(retainedRows, db.appUsageDao().sessionUsage("import:session").first())
            assertEquals(HistoryImportResult(0, 0, 0, 1), manager.importPayload(newer))
            assertEquals(retained, db.sessionDao().byId("import:session"))
            assertEquals(retainedRows, db.appUsageDao().sessionUsage("import:session").first())
            assertEquals(local, db.sessionDao().byId("local"))
            assertEquals(localRows, db.appUsageDao().sessionUsage("local").first())
        }
    }

    @Test fun malformedLegacyAppUsageIsIgnoredWhilePortableHistoryImports() = runBlocking {
        val file = File.createTempFile("history-test", ".json", context.cacheDir)
        try {
            val portable = Json.encodeToJsonElement(BatteryExport.serializer(),
                BatteryExport(listOf(sample()), listOf(closedSession()), formatVersion = 4)).jsonObject
            val legacy = JsonObject(portable + ("appUsage" to Json.parseToJsonElement(
                """[{"sessionId":"ghost","rank":"invalid","powerMah":"NaN"}]""")))
            file.writeText(legacy.toString())
            database().useDatabase { db ->
                val manager = ExportImportManager(context, db, HistoryMaintenance())
                assertEquals(HistoryImportResult(1, 1, 0, 0), manager.importJson(Uri.fromFile(file)))
                assertEquals(1, db.batteryDao().count())
                assertEquals(1, db.sessionDao().count())
                val imported = db.sessionDao().byId("import:session")!!
                assertEquals(1000L, imported.deltaUah)
                assertNull(imported.appUsageStatus)
                assertNull(imported.appUsageBasis)
                assertTrue(db.appUsageDao().usageForSessionsBetween(0, 3000).isEmpty())
                assertTrue(db.appUsageDao().sessionUsage("import:ghost").first().isEmpty())
                assertTrue(db.appUsageDao().snapshots().isEmpty())
                assertEquals(HistoryImportResult(0, 0, 0, 2), manager.importJson(Uri.fromFile(file)))
            }
        } finally { file.delete() }
    }

    @Test fun legacyAppUsageCsvIsIgnoredAndPreservesStoredHistory() = runBlocking {
        val file = File.createTempFile("history-test", ".csv", context.cacheDir)
        try {
            database().useDatabase { db ->
                val manager = ExportImportManager(context, db, HistoryMaintenance())
                assertEquals(HistoryImportResult(0, 1, 0, 0), manager.importPayload(BatteryExport(sessions = listOf(closedSession()))))
                db.appUsageDao().replaceSessionUsage("import:session", AppUsageBasis.DELTA, appRows)
                val local = closedSession().copy(sessionId = "local", observationId = "local-observation")
                db.sessionDao().insert(local)
                db.batteryDao().insertSample(sample().copy(sessionId = "local", observationId = "local-observation"))
                db.appUsageDao().replaceSessionUsage("local", AppUsageBasis.DELTA, appRows)
                val retainedSessions = db.sessionDao().sessionsBetween(0, 3000)
                val retainedRows = db.appUsageDao().usageForSessionsBetween(0, 3000)
                val retainedPoint = db.batteryDao().lastSample()
                file.writeText("sessionId,rank,uid,packageName,powerMah,cpuTimeMs,foregroundTimeMs,backgroundTimeMs,wakelockTimeMs,mobileBytes,wifiBytes,isOthers,basis,exportedAtEpochMs\n" +
                    "session,0,10123,com.example.video,99.0,60000,900000,,,,4096,false,DELTA,1\n" +
                    "session,1,1000,android,3.25,,,,,,,false,DELTA,1\n" +
                    "session,2,-1,,0.75,,,,,,,true,DELTA,1\n")
                assertEquals(HistoryImportResult(0, 0, 0, 0), manager.importCsv(Uri.fromFile(file)))
                assertEquals(retainedSessions, db.sessionDao().sessionsBetween(0, 3000))
                assertEquals(retainedRows, db.appUsageDao().usageForSessionsBetween(0, 3000))
                assertEquals(retainedPoint, db.batteryDao().lastSample())
                assertEquals(HistoryImportResult(0, 0, 0, 0), manager.importCsv(Uri.fromFile(file)))
                assertEquals(retainedSessions, db.sessionDao().sessionsBetween(0, 3000))
                assertEquals(retainedRows, db.appUsageDao().usageForSessionsBetween(0, 3000))
                assertEquals(retainedPoint, db.batteryDao().lastSample())
                assertEquals(1, db.batteryDao().count())
            }
        } finally { file.delete() }
    }
    @Test fun legacyCsvIsAcceptedAndMalformedLaterRowsLeaveNoHistory() = runBlocking {
        val file = File.createTempFile("history-test", ".csv", context.cacheDir)
        val header = "timestamp,levelPercent,status,plugged,currentNowUa,chargeCounterUah,voltageMv,temperatureDeciC,health,screenOn\n"
        val row = "1000,0,3,0,0,,4000,0,2,true\n"
        try {
            database().useDatabase { db ->
                val manager = ExportImportManager(context, db, HistoryMaintenance())
                file.writeText(header + row + "malformed\n")
                try { manager.importCsv(Uri.fromFile(file)); fail("Whole file must fail") } catch (_: IllegalArgumentException) { }
                assertEquals(0, db.batteryDao().count())
                file.writeText(header + row)
                assertEquals(1, manager.importCsv(Uri.fromFile(file)).samplesAdded)
                assertEquals(1, manager.importCsv(Uri.fromFile(file)).unchanged)
                assertNull(db.batteryDao().lastSample()!!.chargeCounterUah)
            }
        } finally { file.delete() }
    }
}
