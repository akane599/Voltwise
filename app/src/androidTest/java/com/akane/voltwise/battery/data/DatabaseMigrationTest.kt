package com.akane.voltwise.battery.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageRow
import com.akane.voltwise.battery.apps.AppUsageStatus
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.insights.FindingCodec
import com.akane.voltwise.battery.insights.InsightInputsBuilder
import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.AppWindowInput
import com.akane.voltwise.battery.insights.model.WindowBasis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset
import java.util.UUID

/** Seed the actual historical schemas; Room validates the migrated schema on opening. */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun versionOneKeepsHistory() = migrate(1)
    @Test fun versionTwoKeepsHistory() = migrate(2)
    @Test fun versionThreeKeepsHistoryAndRejectsDuplicateActiveSessions() = migrate(3)

    @Test fun versionFiveKeepsHistoryAndAddsUnknownCoverage() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { v5 ->
            v5.execSQL("CREATE TABLE IF NOT EXISTS `battery_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `levelPercent` INTEGER, `status` INTEGER NOT NULL, `plugged` INTEGER, `currentNowUa` INTEGER, `chargeCounterUah` INTEGER, `voltageMv` INTEGER, `temperatureDeciC` INTEGER, `health` INTEGER, `screenOn` INTEGER NOT NULL, `elapsedMs` INTEGER, `uptimeMs` INTEGER, `observationId` TEXT, `sessionId` TEXT, `currentAverageUa` INTEGER, `energyNwh` INTEGER, `cycleCount` INTEGER, `etaMs` INTEGER, `etaBasis` TEXT, `source` TEXT NOT NULL DEFAULT 'legacy', `boundaryReason` TEXT)")
            v5.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_timestamp` ON `battery_samples` (`timestamp`)")
            v5.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_sessionId` ON `battery_samples` (`sessionId`)")
            v5.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_battery_samples_observationId_elapsedMs` ON `battery_samples` (`observationId`, `elapsedMs`)")
            v5.execSQL("CREATE TABLE IF NOT EXISTS `charge_sessions` (`sessionId` TEXT NOT NULL, `type` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `startLevel` INTEGER, `endLevel` INTEGER, `deltaUah` INTEGER, `avgCurrentUa` INTEGER, `estCapacityMah` INTEGER, `activeKey` INTEGER, `observationId` TEXT, `lastSampleTime` INTEGER, `observedMs` INTEGER NOT NULL DEFAULT 0, `counterCoveredMs` INTEGER NOT NULL DEFAULT 0, `screenOnMs` INTEGER NOT NULL DEFAULT 0, `screenOffMs` INTEGER NOT NULL DEFAULT 0, `screenOnUah` INTEGER, `screenOffUah` INTEGER, `cpuSuspendMs` INTEGER, `closeReason` TEXT, `source` TEXT NOT NULL DEFAULT 'legacy', `chargerType` TEXT, `energyNwh` INTEGER, `peakPowerMw` INTEGER, `peakTemperatureDeciC` INTEGER, `screenOffSuspendMs` INTEGER, `capacityEstimateMah` INTEGER, `capacityConfidence` TEXT, `capacityBasis` TEXT, `appUsageStatus` TEXT, `appUsageBasis` TEXT, PRIMARY KEY(`sessionId`))")
            v5.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_startTime` ON `charge_sessions` (`startTime`)")
            v5.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_type` ON `charge_sessions` (`type`)")
            v5.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_charge_sessions_activeKey` ON `charge_sessions` (`activeKey`)")
            v5.execSQL("CREATE TABLE IF NOT EXISTS `daily_summaries` (`epochDay` INTEGER NOT NULL, `screenOnMs` INTEGER NOT NULL, `screenOffMs` INTEGER NOT NULL, `screenOnDischargeUah` INTEGER NOT NULL, `screenOffDischargeUah` INTEGER NOT NULL, `chargedUah` INTEGER NOT NULL, `cpuSuspendMs` INTEGER, `minLevel` INTEGER, `maxLevel` INTEGER, `peakTemperatureDeciC` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))")
            v5.execSQL("CREATE TABLE IF NOT EXISTS `app_snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT, `kind` TEXT NOT NULL, `capturedAt` INTEGER NOT NULL, `windowStartedAt` INTEGER, `windowStartCount` INTEGER)")
            v5.execSQL("CREATE INDEX IF NOT EXISTS `index_app_snapshots_sessionId` ON `app_snapshots` (`sessionId`)")
            v5.execSQL("CREATE TABLE IF NOT EXISTS `app_snapshot_uids` (`snapshotId` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `powerMah` REAL NOT NULL, `cpuTimeMs` INTEGER, `foregroundTimeMs` INTEGER, `backgroundTimeMs` INTEGER, `wakelockTimeMs` INTEGER, `mobileBytes` INTEGER, `wifiBytes` INTEGER, PRIMARY KEY(`snapshotId`, `uid`), FOREIGN KEY(`snapshotId`) REFERENCES `app_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            v5.execSQL("CREATE TABLE IF NOT EXISTS `session_app_usage` (`sessionId` TEXT NOT NULL, `rank` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `powerMah` REAL NOT NULL, `cpuTimeMs` INTEGER, `foregroundTimeMs` INTEGER, `backgroundTimeMs` INTEGER, `wakelockTimeMs` INTEGER, `mobileBytes` INTEGER, `wifiBytes` INTEGER, `isOthers` INTEGER NOT NULL, `basis` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `rank`), FOREIGN KEY(`sessionId`) REFERENCES `charge_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            v5.execSQL("CREATE INDEX IF NOT EXISTS `index_session_app_usage_sessionId` ON `session_app_usage` (`sessionId`)")
            v5.execSQL("INSERT INTO charge_sessions (sessionId,type,startTime,endTime,observedMs,counterCoveredMs,screenOnMs,screenOnUah) VALUES ('v5','DISCHARGE',0,120000,120000,120000,120000,20000)")
            v5.execSQL("INSERT INTO daily_summaries (epochDay,screenOnMs,screenOffMs,screenOnDischargeUah,screenOffDischargeUah,chargedUah,updatedAt) VALUES (20000,120000,0,20000,0,0,120000)")
            v5.version = 5
        }
        val db = open(name)
        try {
            val session = db.sessionDao().byId("v5")!!
            val day = db.dailySummaryDao().byDay(20_000)!!
            assertEquals(120_000L, session.screenOnMs)
            assertEquals(20_000L, session.screenOnUah)
            assertEquals(20_000L, day.screenOnDischargeUah)
            assertNull(session.screenOnCoveredMs); assertNull(session.screenOffCoveredMs)
            assertNull(day.screenOnCoveredMs); assertNull(day.screenOffCoveredMs)
            val coveredSession = session.copy(screenOnCoveredMs = 120_000, screenOffCoveredMs = 0)
            val coveredDay = day.copy(screenOnCoveredMs = 120_000, screenOffCoveredMs = 0)
            db.sessionDao().upsert(coveredSession)
            db.dailySummaryDao().upsert(coveredDay)
            assertEquals(coveredSession, db.sessionDao().byId("v5"))
            assertEquals(coveredDay, db.dailySummaryDao().byDay(20_000))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionSixKeepsHistoryAndAddsUnknownInsightMeasurements() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { v6 ->
            v6.execSQL("CREATE TABLE IF NOT EXISTS `battery_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `levelPercent` INTEGER, `status` INTEGER NOT NULL, `plugged` INTEGER, `currentNowUa` INTEGER, `chargeCounterUah` INTEGER, `voltageMv` INTEGER, `temperatureDeciC` INTEGER, `health` INTEGER, `screenOn` INTEGER NOT NULL, `elapsedMs` INTEGER, `uptimeMs` INTEGER, `observationId` TEXT, `sessionId` TEXT, `currentAverageUa` INTEGER, `energyNwh` INTEGER, `cycleCount` INTEGER, `etaMs` INTEGER, `etaBasis` TEXT, `source` TEXT NOT NULL DEFAULT 'legacy', `boundaryReason` TEXT)")
            v6.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_timestamp` ON `battery_samples` (`timestamp`)")
            v6.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_sessionId` ON `battery_samples` (`sessionId`)")
            v6.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_battery_samples_observationId_elapsedMs` ON `battery_samples` (`observationId`, `elapsedMs`)")
            v6.execSQL("INSERT INTO `battery_samples` (`id`, `timestamp`, `levelPercent`, `status`, `plugged`, `currentNowUa`, `chargeCounterUah`, `voltageMv`, `temperatureDeciC`, `health`, `screenOn`, `elapsedMs`, `uptimeMs`, `observationId`, `sessionId`, `currentAverageUa`, `energyNwh`, `cycleCount`, `etaMs`, `etaBasis`, `source`, `boundaryReason`) VALUES (1, 123, 123, 123, 123, 123, 123, 123, 123, 123, 123, 123, 123, 'seed-observationId', 'v6', 123, 123, 123, 123, 'seed-etaBasis', 'seed-source', 'seed-boundaryReason')")
            v6.execSQL("CREATE TABLE IF NOT EXISTS `charge_sessions` (`sessionId` TEXT NOT NULL, `type` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `startLevel` INTEGER, `endLevel` INTEGER, `deltaUah` INTEGER, `avgCurrentUa` INTEGER, `estCapacityMah` INTEGER, `activeKey` INTEGER, `observationId` TEXT, `lastSampleTime` INTEGER, `observedMs` INTEGER NOT NULL DEFAULT 0, `counterCoveredMs` INTEGER NOT NULL DEFAULT 0, `screenOnMs` INTEGER NOT NULL DEFAULT 0, `screenOffMs` INTEGER NOT NULL DEFAULT 0, `screenOnUah` INTEGER, `screenOffUah` INTEGER, `cpuSuspendMs` INTEGER, `closeReason` TEXT, `source` TEXT NOT NULL DEFAULT 'legacy', `chargerType` TEXT, `energyNwh` INTEGER, `peakPowerMw` INTEGER, `peakTemperatureDeciC` INTEGER, `screenOffSuspendMs` INTEGER, `capacityEstimateMah` INTEGER, `capacityConfidence` TEXT, `capacityBasis` TEXT, `appUsageStatus` TEXT, `appUsageBasis` TEXT, `screenOnCoveredMs` INTEGER, `screenOffCoveredMs` INTEGER, PRIMARY KEY(`sessionId`))")
            v6.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_startTime` ON `charge_sessions` (`startTime`)")
            v6.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_type` ON `charge_sessions` (`type`)")
            v6.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_charge_sessions_activeKey` ON `charge_sessions` (`activeKey`)")
            v6.execSQL("INSERT INTO `charge_sessions` (`sessionId`, `type`, `startTime`, `endTime`, `startLevel`, `endLevel`, `deltaUah`, `avgCurrentUa`, `estCapacityMah`, `activeKey`, `observationId`, `lastSampleTime`, `observedMs`, `counterCoveredMs`, `screenOnMs`, `screenOffMs`, `screenOnUah`, `screenOffUah`, `cpuSuspendMs`, `closeReason`, `source`, `chargerType`, `energyNwh`, `peakPowerMw`, `peakTemperatureDeciC`, `screenOffSuspendMs`, `capacityEstimateMah`, `capacityConfidence`, `capacityBasis`, `appUsageStatus`, `appUsageBasis`, `screenOnCoveredMs`, `screenOffCoveredMs`) VALUES ('v6', 'DISCHARGE', 123, 123, 123, 123, 123, 123, 123, NULL, 'seed-observationId', 123, 123, 123, 123, 123, 123, 123, 123, 'seed-closeReason', 'seed-source', 'seed-chargerType', 123, 123, 123, 123, 123, 'seed-capacityConfidence', 'seed-capacityBasis', 'READY', 'DELTA', 123, 123)")
            v6.execSQL("CREATE TABLE IF NOT EXISTS `daily_summaries` (`epochDay` INTEGER NOT NULL, `screenOnMs` INTEGER NOT NULL, `screenOffMs` INTEGER NOT NULL, `screenOnDischargeUah` INTEGER NOT NULL, `screenOffDischargeUah` INTEGER NOT NULL, `chargedUah` INTEGER NOT NULL, `cpuSuspendMs` INTEGER, `minLevel` INTEGER, `maxLevel` INTEGER, `peakTemperatureDeciC` INTEGER, `updatedAt` INTEGER NOT NULL, `screenOnCoveredMs` INTEGER, `screenOffCoveredMs` INTEGER, PRIMARY KEY(`epochDay`))")
            v6.execSQL("INSERT INTO `daily_summaries` (`epochDay`, `screenOnMs`, `screenOffMs`, `screenOnDischargeUah`, `screenOffDischargeUah`, `chargedUah`, `cpuSuspendMs`, `minLevel`, `maxLevel`, `peakTemperatureDeciC`, `updatedAt`, `screenOnCoveredMs`, `screenOffCoveredMs`) VALUES (20000, 123, 123, 123, 123, 123, 123, 123, 123, 123, 123, 123, 123)")
            v6.execSQL("CREATE TABLE IF NOT EXISTS `app_snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT, `kind` TEXT NOT NULL, `capturedAt` INTEGER NOT NULL, `windowStartedAt` INTEGER, `windowStartCount` INTEGER)")
            v6.execSQL("CREATE INDEX IF NOT EXISTS `index_app_snapshots_sessionId` ON `app_snapshots` (`sessionId`)")
            v6.execSQL("INSERT INTO `app_snapshots` (`id`, `sessionId`, `kind`, `capturedAt`, `windowStartedAt`, `windowStartCount`) VALUES (1, 'v6', 'END', 123, 123, 123)")
            v6.execSQL("CREATE TABLE IF NOT EXISTS `app_snapshot_uids` (`snapshotId` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `powerMah` REAL NOT NULL, `cpuTimeMs` INTEGER, `foregroundTimeMs` INTEGER, `backgroundTimeMs` INTEGER, `wakelockTimeMs` INTEGER, `mobileBytes` INTEGER, `wifiBytes` INTEGER, PRIMARY KEY(`snapshotId`, `uid`), FOREIGN KEY(`snapshotId`) REFERENCES `app_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            v6.execSQL("INSERT INTO `app_snapshot_uids` (`snapshotId`, `uid`, `packageName`, `powerMah`, `cpuTimeMs`, `foregroundTimeMs`, `backgroundTimeMs`, `wakelockTimeMs`, `mobileBytes`, `wifiBytes`) VALUES (1, 123, 'seed-packageName', 12.5, 123, 123, 123, 123, 123, 123)")
            v6.execSQL("CREATE TABLE IF NOT EXISTS `session_app_usage` (`sessionId` TEXT NOT NULL, `rank` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `powerMah` REAL NOT NULL, `cpuTimeMs` INTEGER, `foregroundTimeMs` INTEGER, `backgroundTimeMs` INTEGER, `wakelockTimeMs` INTEGER, `mobileBytes` INTEGER, `wifiBytes` INTEGER, `isOthers` INTEGER NOT NULL, `basis` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `rank`), FOREIGN KEY(`sessionId`) REFERENCES `charge_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            v6.execSQL("CREATE INDEX IF NOT EXISTS `index_session_app_usage_sessionId` ON `session_app_usage` (`sessionId`)")
            v6.execSQL("INSERT INTO `session_app_usage` (`sessionId`, `rank`, `uid`, `packageName`, `powerMah`, `cpuTimeMs`, `foregroundTimeMs`, `backgroundTimeMs`, `wakelockTimeMs`, `mobileBytes`, `wifiBytes`, `isOthers`, `basis`) VALUES ('v6', 0, 123, 'seed-packageName', 12.5, 123, 123, 123, 123, 123, 123, 0, 'DELTA')")
            v6.version = 6
        }
        val db = open(name)
        try {
            val session = db.sessionDao().byId("v6")!!
            assertEquals(123L, session.screenOnCoveredMs)
            assertEquals(123L, session.screenOffCoveredMs)
            assertNull(session.dozeMs); assertNull(session.screenOffDozeMs)
            assertNull(session.appCaptureStartMs); assertNull(session.appCaptureEndMs)
            val day = db.dailySummaryDao().byDay(20_000)!!
            assertEquals(123L, day.screenOnDischargeUah)
            assertNull(day.dozeMs); assertNull(day.screenOffDozeMs); assertNull(day.screenOffSuspendMs)
            val usage = db.appUsageDao()
            assertTrue(usage.snapshots().isEmpty())
            assertTrue(usage.snapshotUids(1).isEmpty())
            assertNull(usage.sessionUsage("v6").first().single().topJobName)
            val updated = session.copy(dozeMs = 20, screenOffDozeMs = 10, appCaptureStartMs = 1, appCaptureEndMs = 100)
            db.sessionDao().upsert(updated)
            assertEquals(updated, db.sessionDao().byId("v6"))
            val updatedDay = day.copy(dozeMs = 20, screenOffDozeMs = 10, screenOffSuspendMs = 30)
            db.dailySummaryDao().upsert(updatedDay)
            assertEquals(listOf(updatedDay), db.dailySummaryDao().range(20_000, 20_000))
            assertEquals(listOf(updated), db.sessionDao().closedSessionsBetween(123, 123))

            val waker = SessionDeviceWaker("v6", "WAKEUP_REASON", "alarm", 2, 20, 0)
            usage.insertSessionWakers("v6", listOf(waker))
            usage.insertSessionWakers("v6", listOf(waker.copy(name = "replacement")))
            assertEquals(listOf(waker.copy(name = "replacement")), usage.sessionWakers(listOf("v6")))
            try {
                usage.insertSessionWakers("v6", listOf(waker, waker))
                fail("Duplicate waker must roll back the whole replacement")
            } catch (_: SQLiteConstraintException) { }
            assertEquals(listOf(waker.copy(name = "replacement")), usage.sessionWakers(listOf("v6")))
            usage.insertSessionWakers("v6", emptyList())
            assertTrue(usage.sessionWakers(listOf("v6")).isEmpty())
            val freshSnapshot = usage.insertSnapshotHeader(AppSnapshot(
                sessionId = "v6", kind = AppSnapshotKind.END, capturedAt = 123,
                windowStartedAt = 123, windowStartCount = 1,
            ))
            usage.insertSnapshotWakers(listOf(SnapshotDeviceWaker(freshSnapshot, "WAKEUP_REASON", "alarm", 2, 20)))
            assertEquals(1, usage.snapshotWakers(freshSnapshot).size)
            usage.clearSnapshots()
            assertTrue(usage.snapshotWakers(freshSnapshot).isEmpty())

            val insights = db.insightDao()
            val finding = InsightFindingEntity("key", "TREND", severity = "LOW", confidence = "HIGH",
                score = 2.0, firstSeenAt = 1, lastSeenAt = 2, status = InsightFindingStatus.ACTIVE,
                evidenceVersion = 1, evidenceJson = "{}")
            insights.upsertFindings(listOf(finding))
            insights.upsertFindings(listOf(finding.copy(score = 3.0)))
            insights.setStatus("key", InsightFindingStatus.DISMISSED)
            insights.upsertFindings(insights.findingsOnce().map { it.copy(feedbackMultiplier = 2.0) })
            assertEquals(listOf(finding.copy(score = 3.0, status = InsightFindingStatus.DISMISSED, feedbackMultiplier = 2.0)), insights.findingsOnce())
            assertEquals(insights.findingsOnce(), insights.findings().first())
            val action = InsightActionEntity(findingKey = "key", type = "RESTRICT_BACKGROUND", userId = 0,
                status = InsightActionStatus.PREPARED, priorStateVersion = 1, createdAt = 1)
            val id = insights.insertAction(action)
            assertTrue(id > 0)
            val applied = action.copy(id = id, status = InsightActionStatus.APPLIED, appliedAt = 2)
            insights.updateAction(applied)
            assertEquals(listOf(applied), insights.actionsWithStatus(listOf(InsightActionStatus.APPLIED)))
            insights.clearFindings()
            assertTrue(insights.findingsOnce().isEmpty())
            db.sessionDao().clearAll()
            assertEquals(listOf(applied), insights.actionsOnce())
            assertEquals(insights.actionsOnce(), insights.actions().first())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionSevenKeepsJournalAndAddsNullableMetric() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val action = InsightActionEntity(
            id = 41, findingKey = "JOB_STORM:example.app", type = "RESTRICT_BACKGROUND",
            packageName = "example.app", uid = 10_001, userId = 0, status = InsightActionStatus.APPLIED,
            priorStateVersion = 1, priorState = "RUN_ANY_IN_BACKGROUND:ALLOW",
            targetState = "RUN_ANY_IN_BACKGROUND:IGNORE", createdAt = 100, appliedAt = 200,
            revertedAt = 300, message = "retained",
        )
        SQLiteDatabase.openOrCreateDatabase(path, null).use { v7 ->
            v7.execSQL("CREATE TABLE IF NOT EXISTS `battery_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `levelPercent` INTEGER, `status` INTEGER NOT NULL, `plugged` INTEGER, `currentNowUa` INTEGER, `chargeCounterUah` INTEGER, `voltageMv` INTEGER, `temperatureDeciC` INTEGER, `health` INTEGER, `screenOn` INTEGER NOT NULL, `elapsedMs` INTEGER, `uptimeMs` INTEGER, `observationId` TEXT, `sessionId` TEXT, `currentAverageUa` INTEGER, `energyNwh` INTEGER, `cycleCount` INTEGER, `etaMs` INTEGER, `etaBasis` TEXT, `source` TEXT NOT NULL DEFAULT 'legacy', `boundaryReason` TEXT)")
            v7.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_timestamp` ON `battery_samples` (`timestamp`)")
            v7.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_sessionId` ON `battery_samples` (`sessionId`)")
            v7.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_battery_samples_observationId_elapsedMs` ON `battery_samples` (`observationId`, `elapsedMs`)")
            v7.execSQL("CREATE TABLE IF NOT EXISTS `charge_sessions` (`sessionId` TEXT NOT NULL, `type` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `startLevel` INTEGER, `endLevel` INTEGER, `deltaUah` INTEGER, `avgCurrentUa` INTEGER, `estCapacityMah` INTEGER, `activeKey` INTEGER, `observationId` TEXT, `lastSampleTime` INTEGER, `observedMs` INTEGER NOT NULL DEFAULT 0, `counterCoveredMs` INTEGER NOT NULL DEFAULT 0, `screenOnMs` INTEGER NOT NULL DEFAULT 0, `screenOffMs` INTEGER NOT NULL DEFAULT 0, `screenOnUah` INTEGER, `screenOffUah` INTEGER, `cpuSuspendMs` INTEGER, `closeReason` TEXT, `source` TEXT NOT NULL DEFAULT 'legacy', `chargerType` TEXT, `energyNwh` INTEGER, `peakPowerMw` INTEGER, `peakTemperatureDeciC` INTEGER, `screenOffSuspendMs` INTEGER, `capacityEstimateMah` INTEGER, `capacityConfidence` TEXT, `capacityBasis` TEXT, `appUsageStatus` TEXT, `appUsageBasis` TEXT, `screenOnCoveredMs` INTEGER, `screenOffCoveredMs` INTEGER, `dozeMs` INTEGER, `screenOffDozeMs` INTEGER, `appCaptureStartMs` INTEGER, `appCaptureEndMs` INTEGER, PRIMARY KEY(`sessionId`))")
            v7.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_startTime` ON `charge_sessions` (`startTime`)")
            v7.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_type` ON `charge_sessions` (`type`)")
            v7.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_charge_sessions_activeKey` ON `charge_sessions` (`activeKey`)")
            v7.execSQL("CREATE TABLE IF NOT EXISTS `daily_summaries` (`epochDay` INTEGER NOT NULL, `screenOnMs` INTEGER NOT NULL, `screenOffMs` INTEGER NOT NULL, `screenOnDischargeUah` INTEGER NOT NULL, `screenOffDischargeUah` INTEGER NOT NULL, `chargedUah` INTEGER NOT NULL, `cpuSuspendMs` INTEGER, `minLevel` INTEGER, `maxLevel` INTEGER, `peakTemperatureDeciC` INTEGER, `updatedAt` INTEGER NOT NULL, `screenOnCoveredMs` INTEGER, `screenOffCoveredMs` INTEGER, `dozeMs` INTEGER, `screenOffDozeMs` INTEGER, `screenOffSuspendMs` INTEGER, PRIMARY KEY(`epochDay`))")
            v7.execSQL("CREATE TABLE IF NOT EXISTS `app_snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT, `kind` TEXT NOT NULL, `capturedAt` INTEGER NOT NULL, `windowStartedAt` INTEGER, `windowStartCount` INTEGER, `deepIdleMs` INTEGER, `deepIdleCount` INTEGER, `lightIdleMs` INTEGER, `lightIdleCount` INTEGER, `screenOffMs` INTEGER, `wakersComplete` INTEGER)")
            v7.execSQL("CREATE INDEX IF NOT EXISTS `index_app_snapshots_sessionId` ON `app_snapshots` (`sessionId`)")
            v7.execSQL("CREATE TABLE IF NOT EXISTS `app_snapshot_uids` (`snapshotId` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `powerMah` REAL NOT NULL, `cpuTimeMs` INTEGER, `foregroundTimeMs` INTEGER, `backgroundTimeMs` INTEGER, `wakelockTimeMs` INTEGER, `mobileBytes` INTEGER, `wifiBytes` INTEGER, `wakeupAlarms` INTEGER, `partialWakelockCount` INTEGER, `partialWakelockBgMs` INTEGER, `jobCount` INTEGER, `jobMs` INTEGER, `syncCount` INTEGER, `fgServiceMs` INTEGER, `topMs` INTEGER, `mobileActiveMs` INTEGER, `gpsMs` INTEGER, `sensorMs` INTEGER, PRIMARY KEY(`snapshotId`, `uid`), FOREIGN KEY(`snapshotId`) REFERENCES `app_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            v7.execSQL("CREATE TABLE IF NOT EXISTS `session_app_usage` (`sessionId` TEXT NOT NULL, `rank` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `powerMah` REAL NOT NULL, `cpuTimeMs` INTEGER, `foregroundTimeMs` INTEGER, `backgroundTimeMs` INTEGER, `wakelockTimeMs` INTEGER, `mobileBytes` INTEGER, `wifiBytes` INTEGER, `isOthers` INTEGER NOT NULL, `basis` TEXT NOT NULL, `wakeupAlarms` INTEGER, `partialWakelockCount` INTEGER, `partialWakelockBgMs` INTEGER, `jobCount` INTEGER, `jobMs` INTEGER, `syncCount` INTEGER, `fgServiceMs` INTEGER, `topMs` INTEGER, `mobileActiveMs` INTEGER, `gpsMs` INTEGER, `sensorMs` INTEGER, `topWakelockTag` TEXT, `topAlarmTag` TEXT, `topJobName` TEXT, PRIMARY KEY(`sessionId`, `rank`), FOREIGN KEY(`sessionId`) REFERENCES `charge_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            v7.execSQL("CREATE INDEX IF NOT EXISTS `index_session_app_usage_sessionId` ON `session_app_usage` (`sessionId`)")
            v7.execSQL("CREATE TABLE IF NOT EXISTS `snapshot_device_wakers` (`snapshotId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `count` INTEGER NOT NULL, `totalMs` INTEGER NOT NULL, PRIMARY KEY(`snapshotId`, `kind`, `name`), FOREIGN KEY(`snapshotId`) REFERENCES `app_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            v7.execSQL("CREATE TABLE IF NOT EXISTS `session_device_wakers` (`sessionId` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `count` INTEGER NOT NULL, `totalMs` INTEGER NOT NULL, `rank` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `kind`, `name`), FOREIGN KEY(`sessionId`) REFERENCES `charge_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            v7.execSQL("CREATE TABLE IF NOT EXISTS `insight_findings` (`key` TEXT NOT NULL, `type` TEXT NOT NULL, `uid` INTEGER, `packageName` TEXT, `severity` TEXT NOT NULL, `confidence` TEXT NOT NULL, `score` REAL NOT NULL, `firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `feedbackMultiplier` REAL NOT NULL DEFAULT 1.0, `evidenceVersion` INTEGER NOT NULL, `evidenceJson` TEXT NOT NULL, PRIMARY KEY(`key`))")
            v7.execSQL("CREATE INDEX IF NOT EXISTS `index_insight_findings_status` ON `insight_findings` (`status`)")
            v7.execSQL("CREATE TABLE IF NOT EXISTS `insight_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `findingKey` TEXT NOT NULL, `type` TEXT NOT NULL, `packageName` TEXT, `uid` INTEGER, `userId` INTEGER NOT NULL, `status` TEXT NOT NULL, `priorStateVersion` INTEGER NOT NULL, `priorState` TEXT, `targetState` TEXT, `createdAt` INTEGER NOT NULL, `appliedAt` INTEGER, `revertedAt` INTEGER, `message` TEXT)")
            v7.execSQL("CREATE INDEX IF NOT EXISTS `index_insight_actions_status` ON `insight_actions` (`status`)")
            v7.execSQL("INSERT INTO insight_actions (id,findingKey,type,packageName,uid,userId,status,priorStateVersion,priorState,targetState,createdAt,appliedAt,revertedAt,message) VALUES (41,'JOB_STORM:example.app','RESTRICT_BACKGROUND','example.app',10001,0,'APPLIED',1,'RUN_ANY_IN_BACKGROUND:ALLOW','RUN_ANY_IN_BACKGROUND:IGNORE',100,200,300,'retained')")
            v7.execSQL("INSERT INTO insight_findings (`key`,type,severity,confidence,score,firstSeenAt,lastSeenAt,status,evidenceVersion,evidenceJson) VALUES ('JOB_STORM:example.app','JOB_STORM','HIGH','HIGH',2.0,100,200,'DISMISSED',1,'{}')")
            v7.execSQL("INSERT INTO charge_sessions (sessionId,type,startTime,endTime) VALUES ('v7','DISCHARGE',100,200)")
            v7.version = 7
        }
        val db = open(name)
        try {
            assertEquals(listOf(action), db.insightDao().actionsOnce())
            assertNull(db.insightDao().actionsOnce().single().metric)
            assertEquals(InsightFindingStatus.DISMISSED, db.insightDao().findingsOnce().single().status)
            assertEquals(200L, db.sessionDao().byId("v7")!!.endTime)
            val updated = action.copy(metric = "JOBS_PER_H")
            db.insightDao().updateAction(updated)
            assertEquals(listOf(updated), db.insightDao().actionsOnce())
            val id = db.insightDao().insertAction(updated.copy(id = 0, metric = "FUTURE_METRIC"))
            assertTrue(id > action.id)
            assertEquals("FUTURE_METRIC", db.insightDao().actionsOnce().single { it.id == id }.metric)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionEightRemovesLegacyCertificationAndPreservesHistoryAndUndoAuthority() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { v8 ->
            v8.execSQL("CREATE TABLE IF NOT EXISTS `battery_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `levelPercent` INTEGER, `status` INTEGER NOT NULL, `plugged` INTEGER, `currentNowUa` INTEGER, `chargeCounterUah` INTEGER, `voltageMv` INTEGER, `temperatureDeciC` INTEGER, `health` INTEGER, `screenOn` INTEGER NOT NULL, `elapsedMs` INTEGER, `uptimeMs` INTEGER, `observationId` TEXT, `sessionId` TEXT, `currentAverageUa` INTEGER, `energyNwh` INTEGER, `cycleCount` INTEGER, `etaMs` INTEGER, `etaBasis` TEXT, `source` TEXT NOT NULL DEFAULT 'legacy', `boundaryReason` TEXT)")
            v8.execSQL("CREATE TABLE IF NOT EXISTS `charge_sessions` (`sessionId` TEXT NOT NULL, `type` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `startLevel` INTEGER, `endLevel` INTEGER, `deltaUah` INTEGER, `avgCurrentUa` INTEGER, `estCapacityMah` INTEGER, `activeKey` INTEGER, `observationId` TEXT, `lastSampleTime` INTEGER, `observedMs` INTEGER NOT NULL DEFAULT 0, `counterCoveredMs` INTEGER NOT NULL DEFAULT 0, `screenOnMs` INTEGER NOT NULL DEFAULT 0, `screenOffMs` INTEGER NOT NULL DEFAULT 0, `screenOnUah` INTEGER, `screenOffUah` INTEGER, `cpuSuspendMs` INTEGER, `closeReason` TEXT, `source` TEXT NOT NULL DEFAULT 'legacy', `chargerType` TEXT, `energyNwh` INTEGER, `peakPowerMw` INTEGER, `peakTemperatureDeciC` INTEGER, `screenOffSuspendMs` INTEGER, `capacityEstimateMah` INTEGER, `capacityConfidence` TEXT, `capacityBasis` TEXT, `appUsageStatus` TEXT, `appUsageBasis` TEXT, `screenOnCoveredMs` INTEGER, `screenOffCoveredMs` INTEGER, `dozeMs` INTEGER, `screenOffDozeMs` INTEGER, `appCaptureStartMs` INTEGER, `appCaptureEndMs` INTEGER, PRIMARY KEY(`sessionId`))")
            v8.execSQL("CREATE TABLE IF NOT EXISTS `daily_summaries` (`epochDay` INTEGER NOT NULL, `screenOnMs` INTEGER NOT NULL, `screenOffMs` INTEGER NOT NULL, `screenOnDischargeUah` INTEGER NOT NULL, `screenOffDischargeUah` INTEGER NOT NULL, `chargedUah` INTEGER NOT NULL, `cpuSuspendMs` INTEGER, `minLevel` INTEGER, `maxLevel` INTEGER, `peakTemperatureDeciC` INTEGER, `updatedAt` INTEGER NOT NULL, `screenOnCoveredMs` INTEGER, `screenOffCoveredMs` INTEGER, `dozeMs` INTEGER, `screenOffDozeMs` INTEGER, `screenOffSuspendMs` INTEGER, PRIMARY KEY(`epochDay`))")
            v8.execSQL("CREATE TABLE IF NOT EXISTS `app_snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT, `kind` TEXT NOT NULL, `capturedAt` INTEGER NOT NULL, `windowStartedAt` INTEGER, `windowStartCount` INTEGER, `deepIdleMs` INTEGER, `deepIdleCount` INTEGER, `lightIdleMs` INTEGER, `lightIdleCount` INTEGER, `screenOffMs` INTEGER, `wakersComplete` INTEGER)")
            v8.execSQL("CREATE TABLE IF NOT EXISTS `app_snapshot_uids` (`snapshotId` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `powerMah` REAL NOT NULL, `cpuTimeMs` INTEGER, `foregroundTimeMs` INTEGER, `backgroundTimeMs` INTEGER, `wakelockTimeMs` INTEGER, `mobileBytes` INTEGER, `wifiBytes` INTEGER, `wakeupAlarms` INTEGER, `partialWakelockCount` INTEGER, `partialWakelockBgMs` INTEGER, `jobCount` INTEGER, `jobMs` INTEGER, `syncCount` INTEGER, `fgServiceMs` INTEGER, `topMs` INTEGER, `mobileActiveMs` INTEGER, `gpsMs` INTEGER, `sensorMs` INTEGER, PRIMARY KEY(`snapshotId`, `uid`), FOREIGN KEY(`snapshotId`) REFERENCES `app_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            v8.execSQL("CREATE TABLE IF NOT EXISTS `session_app_usage` (`sessionId` TEXT NOT NULL, `rank` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `powerMah` REAL NOT NULL, `cpuTimeMs` INTEGER, `foregroundTimeMs` INTEGER, `backgroundTimeMs` INTEGER, `wakelockTimeMs` INTEGER, `mobileBytes` INTEGER, `wifiBytes` INTEGER, `isOthers` INTEGER NOT NULL, `basis` TEXT NOT NULL, `wakeupAlarms` INTEGER, `partialWakelockCount` INTEGER, `partialWakelockBgMs` INTEGER, `jobCount` INTEGER, `jobMs` INTEGER, `syncCount` INTEGER, `fgServiceMs` INTEGER, `topMs` INTEGER, `mobileActiveMs` INTEGER, `gpsMs` INTEGER, `sensorMs` INTEGER, `topWakelockTag` TEXT, `topAlarmTag` TEXT, `topJobName` TEXT, PRIMARY KEY(`sessionId`, `rank`), FOREIGN KEY(`sessionId`) REFERENCES `charge_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            v8.execSQL("CREATE TABLE IF NOT EXISTS `snapshot_device_wakers` (`snapshotId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `count` INTEGER NOT NULL, `totalMs` INTEGER NOT NULL, PRIMARY KEY(`snapshotId`, `kind`, `name`), FOREIGN KEY(`snapshotId`) REFERENCES `app_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            v8.execSQL("CREATE TABLE IF NOT EXISTS `session_device_wakers` (`sessionId` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `count` INTEGER NOT NULL, `totalMs` INTEGER NOT NULL, `rank` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `kind`, `name`), FOREIGN KEY(`sessionId`) REFERENCES `charge_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            v8.execSQL("CREATE TABLE IF NOT EXISTS `insight_findings` (`key` TEXT NOT NULL, `type` TEXT NOT NULL, `uid` INTEGER, `packageName` TEXT, `severity` TEXT NOT NULL, `confidence` TEXT NOT NULL, `score` REAL NOT NULL, `firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `feedbackMultiplier` REAL NOT NULL DEFAULT 1.0, `evidenceVersion` INTEGER NOT NULL, `evidenceJson` TEXT NOT NULL, PRIMARY KEY(`key`))")
            v8.execSQL("CREATE TABLE IF NOT EXISTS `insight_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `findingKey` TEXT NOT NULL, `type` TEXT NOT NULL, `packageName` TEXT, `uid` INTEGER, `userId` INTEGER NOT NULL, `status` TEXT NOT NULL, `priorStateVersion` INTEGER NOT NULL, `priorState` TEXT, `targetState` TEXT, `createdAt` INTEGER NOT NULL, `appliedAt` INTEGER, `revertedAt` INTEGER, `message` TEXT, `metric` TEXT)")
            v8.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_timestamp` ON `battery_samples` (`timestamp`)")
            v8.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_sessionId` ON `battery_samples` (`sessionId`)")
            v8.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_battery_samples_observationId_elapsedMs` ON `battery_samples` (`observationId`, `elapsedMs`)")
            v8.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_startTime` ON `charge_sessions` (`startTime`)")
            v8.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_type` ON `charge_sessions` (`type`)")
            v8.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_charge_sessions_activeKey` ON `charge_sessions` (`activeKey`)")
            v8.execSQL("CREATE INDEX IF NOT EXISTS `index_app_snapshots_sessionId` ON `app_snapshots` (`sessionId`)")
            v8.execSQL("CREATE INDEX IF NOT EXISTS `index_session_app_usage_sessionId` ON `session_app_usage` (`sessionId`)")
            v8.execSQL("CREATE INDEX IF NOT EXISTS `index_insight_findings_status` ON `insight_findings` (`status`)")
            v8.execSQL("CREATE INDEX IF NOT EXISTS `index_insight_actions_status` ON `insight_actions` (`status`)")
            v8.execSQL("INSERT INTO charge_sessions (sessionId,type,startTime,endTime,startLevel,endLevel,observedMs,counterCoveredMs,screenOffMs,screenOffUah,cpuSuspendMs,screenOffSuspendMs,source,appUsageStatus,appUsageBasis,screenOnCoveredMs,screenOffCoveredMs,dozeMs,screenOffDozeMs,appCaptureStartMs,appCaptureEndMs) VALUES ('v8','DISCHARGE',1000000,4600000,80,70,3600000,3600000,3600000,200000,3000000,3000000,'observed','READY','DELTA',0,3600000,2500000,2500000,1000000,4600000)")
            v8.execSQL("INSERT INTO charge_sessions (sessionId,type,startTime,endTime,activeKey,source,appUsageStatus) VALUES ('open-v8','DISCHARGE',4700000,NULL,1,'observed','PENDING')")
            v8.execSQL("INSERT INTO battery_samples (id,timestamp,levelPercent,status,plugged,currentNowUa,chargeCounterUah,voltageMv,temperatureDeciC,health,screenOn,sessionId,source) VALUES (1,4600000,70,3,0,-100000,2800000,4000,300,2,0,'v8','observed')")
            v8.execSQL("INSERT INTO daily_summaries (epochDay,screenOnMs,screenOffMs,screenOnDischargeUah,screenOffDischargeUah,chargedUah,cpuSuspendMs,updatedAt,screenOnCoveredMs,screenOffCoveredMs,dozeMs,screenOffDozeMs,screenOffSuspendMs) VALUES (0,0,3600000,0,200000,0,3000000,4600000,0,3600000,2500000,2500000,3000000)")
            v8.execSQL("INSERT INTO app_snapshots (id,sessionId,kind,capturedAt,windowStartedAt,windowStartCount,deepIdleMs,deepIdleCount,lightIdleMs,lightIdleCount,screenOffMs,wakersComplete) VALUES (1,'open-v8','BASELINE',4700000,500000,3,2500000,5,100000,2,3600000,1)")
            v8.execSQL("INSERT INTO app_snapshots (id,sessionId,kind,capturedAt,windowStartedAt,windowStartCount) VALUES (2,'v8','END',4600000,500000,3)")
            v8.execSQL("INSERT INTO app_snapshot_uids (snapshotId,uid,packageName,powerMah,cpuTimeMs,wakeupAlarms,jobCount,jobMs) VALUES (1,10002,'victim.app',25.0,1000,100000,500,600000)")
            v8.execSQL("INSERT INTO app_snapshot_uids (snapshotId,uid,packageName,powerMah,cpuTimeMs,wakeupAlarms) VALUES (2,10002,'victim.app',30.0,2000,100500)")
            v8.execSQL("INSERT INTO snapshot_device_wakers (snapshotId,kind,name,count,totalMs) VALUES (1,'WAKEUP_REASON','forged baseline',1000,100000)")
            v8.execSQL("INSERT INTO session_app_usage (sessionId,rank,uid,packageName,powerMah,cpuTimeMs,wakeupAlarms,jobCount,jobMs,isOthers,basis,topAlarmTag,topJobName) VALUES ('v8',0,10002,'victim.app',5.0,1000,100000,500,600000,0,'DELTA','forged alarm','forged job')")
            v8.execSQL("INSERT INTO session_device_wakers (sessionId,kind,name,count,totalMs,rank) VALUES ('v8','WAKEUP_REASON','forged reason',100000,600000,0)")
            v8.execSQL("INSERT INTO insight_findings (`key`,type,uid,packageName,severity,confidence,score,firstSeenAt,lastSeenAt,status,feedbackMultiplier,evidenceVersion,evidenceJson) VALUES ('app-active','WAKEUP_STORM',10002,'victim.app','HIGH','HIGH',2.0,100,200,'ACTIVE',1.5,2,'{\"recommendations\":[{\"action\":\"RESTRICT_BACKGROUND\",\"reversible\":true,\"requiresPrivilege\":true}],\"attributions\":[{\"kind\":\"APP\",\"name\":\"victim.app\",\"packageName\":\"victim.app\",\"value\":100000.0,\"unit\":\"COUNT_PER_H\",\"sessions\":1}]}')")
            v8.execSQL("INSERT INTO insight_findings (`key`,type,uid,packageName,severity,confidence,score,firstSeenAt,lastSeenAt,status,feedbackMultiplier,evidenceVersion,evidenceJson) VALUES ('device-active','SCREEN_OFF_DRAIN_HIGH',NULL,NULL,'HIGH','HIGH',2.0,100,200,'ACTIVE',2.0,2,'{\"recommendations\":[{\"action\":\"RESTRICT_BACKGROUND\",\"reversible\":true,\"requiresPrivilege\":true}],\"attributions\":[{\"kind\":\"APP\",\"name\":\"victim.app\",\"packageName\":\"victim.app\",\"value\":100000.0,\"unit\":\"COUNT_PER_H\",\"sessions\":1}]}')")
            v8.execSQL("INSERT INTO insight_findings (`key`,type,uid,packageName,severity,confidence,score,firstSeenAt,lastSeenAt,status,feedbackMultiplier,evidenceVersion,evidenceJson) VALUES ('app-dismissed','JOB_STORM',10002,'victim.app','HIGH','HIGH',2.0,100,200,'DISMISSED',3.0,2,'{\"recommendations\":[{\"action\":\"RESTRICT_BACKGROUND\",\"reversible\":true,\"requiresPrivilege\":true}],\"attributions\":[{\"kind\":\"APP\",\"name\":\"victim.app\",\"packageName\":\"victim.app\",\"value\":100000.0,\"unit\":\"COUNT_PER_H\",\"sessions\":1}]}')")
            v8.execSQL("INSERT INTO insight_findings (`key`,type,uid,packageName,severity,confidence,score,firstSeenAt,lastSeenAt,status,feedbackMultiplier,evidenceVersion,evidenceJson) VALUES ('app-resolved','JOB_STORM',10002,'victim.app','HIGH','HIGH',2.0,100,200,'RESOLVED',4.0,2,'{\"recommendations\":[{\"action\":\"RESTRICT_BACKGROUND\",\"reversible\":true,\"requiresPrivilege\":true}],\"attributions\":[{\"kind\":\"APP\",\"name\":\"victim.app\",\"packageName\":\"victim.app\",\"value\":100000.0,\"unit\":\"COUNT_PER_H\",\"sessions\":1}]}')")
            v8.execSQL("INSERT INTO insight_actions (id,findingKey,type,packageName,uid,userId,status,priorStateVersion,priorState,targetState,createdAt,appliedAt,revertedAt,message,metric) VALUES (41,'app-active','RESTRICT_BACKGROUND','victim.app',10002,0,'PREPARED',1,'RUN_ANY_IN_BACKGROUND:ALLOW','RUN_ANY_IN_BACKGROUND:IGNORE',100,200,300,'retained','WAKEUP_ALARMS_PER_H')")
            v8.execSQL("INSERT INTO insight_actions (id,findingKey,type,packageName,uid,userId,status,priorStateVersion,priorState,targetState,createdAt,appliedAt,revertedAt,message,metric) VALUES (42,'app-active','RESTRICT_BACKGROUND','victim.app',10002,0,'APPLIED',1,'RUN_ANY_IN_BACKGROUND:ALLOW','RUN_ANY_IN_BACKGROUND:IGNORE',100,200,300,'retained','WAKEUP_ALARMS_PER_H')")
            v8.execSQL("INSERT INTO insight_actions (id,findingKey,type,packageName,uid,userId,status,priorStateVersion,priorState,targetState,createdAt,appliedAt,revertedAt,message,metric) VALUES (43,'app-active','RESTRICT_BACKGROUND','victim.app',10002,0,'FAILED',1,'RUN_ANY_IN_BACKGROUND:ALLOW','RUN_ANY_IN_BACKGROUND:IGNORE',100,200,300,'retained','WAKEUP_ALARMS_PER_H')")
            v8.execSQL("INSERT INTO insight_actions (id,findingKey,type,packageName,uid,userId,status,priorStateVersion,priorState,targetState,createdAt,appliedAt,revertedAt,message,metric) VALUES (44,'app-active','RESTRICT_BACKGROUND','victim.app',10002,0,'UNKNOWN',1,'RUN_ANY_IN_BACKGROUND:ALLOW','RUN_ANY_IN_BACKGROUND:IGNORE',100,200,300,'retained','WAKEUP_ALARMS_PER_H')")
            v8.execSQL("INSERT INTO insight_actions (id,findingKey,type,packageName,uid,userId,status,priorStateVersion,priorState,targetState,createdAt,appliedAt,revertedAt,message,metric) VALUES (45,'app-active','RESTRICT_BACKGROUND','victim.app',10002,0,'REVERTED',1,'RUN_ANY_IN_BACKGROUND:ALLOW','RUN_ANY_IN_BACKGROUND:IGNORE',100,200,300,'retained','WAKEUP_ALARMS_PER_H')")
            v8.execSQL("INSERT INTO insight_actions (id,findingKey,type,packageName,uid,userId,status,priorStateVersion,priorState,targetState,createdAt,appliedAt,revertedAt,message,metric) VALUES (46,'app-active','RESTRICT_BACKGROUND','victim.app',10002,0,'ONE_SHOT',1,'RUN_ANY_IN_BACKGROUND:ALLOW','RUN_ANY_IN_BACKGROUND:IGNORE',100,200,300,'retained','WAKEUP_ALARMS_PER_H')")
            v8.version = 8
        }
        val db = open(name)
        try {
            val session = db.sessionDao().byId("v8")!!
            assertEquals(AppUsageStatus.READY, session.appUsageStatus)
            assertEquals(AppUsageBasis.DELTA, session.appUsageBasis)
            assertNull(session.appCaptureStartMs)
            assertNull(session.appCaptureEndMs)
            assertEquals(3_600_000L, session.observedMs)
            assertEquals(200_000L, session.screenOffUah)
            assertEquals(3_000_000L, session.screenOffSuspendMs)
            assertEquals(2_500_000L, session.dozeMs)
            assertEquals(2_500_000L, session.screenOffDozeMs)
            assertEquals("open-v8", db.sessionDao().active()?.sessionId)
            val day = db.dailySummaryDao().byDay(0)!!
            assertEquals(200_000L, day.screenOffDischargeUah)
            assertEquals(3_000_000L, day.screenOffSuspendMs)
            assertEquals(2_500_000L, day.dozeMs)
            assertEquals(2_500_000L, day.screenOffDozeMs)
            val sample = db.batteryDao().samplesBetween(0, Long.MAX_VALUE).first().single()
            assertEquals(2_800_000L, sample.chargeCounterUah)
            assertEquals(-100_000L, sample.currentNowUa)
            assertEquals("observed", sample.source)

            val usage = db.appUsageDao()
            assertTrue(usage.snapshots().isEmpty())
            assertNull(usage.latestSnapshot("open-v8", AppSnapshotKind.BASELINE))
            assertEquals(0, db.count("app_snapshot_uids"))
            assertEquals(0, db.count("snapshot_device_wakers"))
            assertTrue(usage.sessionWakers(listOf("v8")).isEmpty())
            val rows = usage.sessionUsage("v8").first()
            assertEquals(listOf(SessionAppUsage(
                "v8", 0, 10_002, "victim.app", 5.0, cpuTimeMs = 1_000,
                basis = AppUsageBasis.DELTA, wakeupAlarms = 100_000, jobCount = 500, jobMs = 600_000,
                topAlarmTag = "forged alarm", topJobName = "forged job",
            )), rows)

            val findings = db.insightDao().findingsOnce().associateBy { it.key }
            assertEquals(4, findings.size)
            assertEquals(InsightFindingStatus.RESOLVED, findings.getValue("app-active").status)
            assertEquals(InsightFindingStatus.RESOLVED, findings.getValue("device-active").status)
            assertEquals(InsightFindingStatus.DISMISSED, findings.getValue("app-dismissed").status)
            assertEquals(InsightFindingStatus.RESOLVED, findings.getValue("app-resolved").status)
            assertEquals(mapOf("app-active" to 1.5, "device-active" to 2.0, "app-dismissed" to 3.0, "app-resolved" to 4.0),
                findings.mapValues { it.value.feedbackMultiplier })
            assertTrue(db.insightDao().findings().first().none { it.status == InsightFindingStatus.ACTIVE })
            // Resolving preserves history; the old device payload really carries app attribution and an action.
            val device = FindingCodec.decode(findings.getValue("device-active"))!!
            assertEquals("victim.app", device.attributions.single().packageName)
            assertEquals("RESTRICT_BACKGROUND", device.recommendations.single().action.name)
            val originalAction = InsightActionEntity(
                id = 41, findingKey = "app-active", type = "RESTRICT_BACKGROUND", packageName = "victim.app",
                uid = 10_002, userId = 0, status = InsightActionStatus.PREPARED, priorStateVersion = 1,
                priorState = "RUN_ANY_IN_BACKGROUND:ALLOW", targetState = "RUN_ANY_IN_BACKGROUND:IGNORE",
                createdAt = 100, appliedAt = 200, revertedAt = 300, message = "retained", metric = "WAKEUP_ALARMS_PER_H",
            )
            val statuses = listOf(InsightActionStatus.PREPARED, InsightActionStatus.APPLIED, InsightActionStatus.FAILED,
                InsightActionStatus.UNKNOWN, InsightActionStatus.REVERTED, InsightActionStatus.ONE_SHOT)
            assertEquals(statuses.mapIndexed { index, status -> originalAction.copy(id = 41L + index, status = status) },
                db.insightDao().actionsOnce().sortedBy { it.id })

            val inputs = InsightInputsBuilder.build(
                nowMs = 5_000_000, todayEpochDay = 0, fullUah = 4_000_000, privileged = true,
                sessions = listOf(session), days = listOf(day), appRows = rows,
                wakers = usage.sessionWakers(listOf("v8")), capacity = emptyList(), dozeWhitelist = emptySet(),
                actions = db.insightDao().actionsOnce(), findings = findings.values.toList(),
            )
            assertTrue(AppWindows.select(inputs).isEmpty())
            assertTrue(inputs.deviceWakers.isEmpty())
            // Positive control: these plausible legacy counters would be eligible without the invalidation.
            val trustedLooking = inputs.copy(sessions = inputs.sessions.map {
                it.copy(appWindow = AppWindowInput(WindowBasis.DELTA, 1_000_000, 4_600_000, 1, false))
            })
            assertEquals(1, AppWindows.select(trustedLooking).size)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse("Migration left dangling foreign keys", cursor.moveToFirst())
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun open(name: String) = Room.databaseBuilder(context, BatteryDatabase::class.java, name)
        .addMigrations(BatteryDatabase.MIGRATION_1_2, BatteryDatabase.MIGRATION_2_3, BatteryDatabase.MIGRATION_3_4, BatteryDatabase.MIGRATION_4_5, BatteryDatabase.MIGRATION_5_6, BatteryDatabase.MIGRATION_6_7, BatteryDatabase.MIGRATION_7_8, BatteryDatabase.MIGRATION_8_9).build()

    private fun BatteryDatabase.names(type: String): Set<String> =
        openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = ?", arrayOf(type)).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun BatteryDatabase.count(table: String): Int =
        openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private fun assertV5Tables(db: BatteryDatabase) {
        val tables = db.names("table")
        assertFalse("alarm_rules" in tables); assertFalse("app_energy_stats" in tables)
        assertTrue(tables.containsAll(listOf("daily_summaries", "app_snapshots", "app_snapshot_uids", "session_app_usage")))
        assertFalse("index_battery_samples_status" in db.names("index"))
    }

    private fun migrate(version: Int) = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL("CREATE TABLE battery_samples (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, levelPercent INTEGER NOT NULL, status INTEGER NOT NULL, plugged INTEGER NOT NULL, currentNowUa INTEGER, chargeCounterUah INTEGER, voltageMv INTEGER, temperatureDeciC INTEGER, health INTEGER, screenOn INTEGER NOT NULL)")
            legacy.execSQL("CREATE INDEX index_battery_samples_timestamp ON battery_samples(timestamp)")
            legacy.execSQL("CREATE INDEX index_battery_samples_status ON battery_samples(status)")
            legacy.execSQL("CREATE TABLE charge_sessions (sessionId TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER, startLevel INTEGER NOT NULL, endLevel INTEGER, deltaUah INTEGER, avgCurrentUa INTEGER, estCapacityMah INTEGER)")
            legacy.execSQL("CREATE INDEX index_charge_sessions_startTime ON charge_sessions(startTime)")
            legacy.execSQL("CREATE INDEX index_charge_sessions_type ON charge_sessions(type)")
            legacy.execSQL("CREATE TABLE alarm_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, enabled INTEGER NOT NULL, threshold INTEGER NOT NULL, notifyOnce INTEGER NOT NULL)")
            if (version >= 2) {
                legacy.execSQL("CREATE TABLE app_energy_stats (bucketStart INTEGER NOT NULL, packageName TEXT NOT NULL, mode TEXT NOT NULL, energyMah REAL NOT NULL, samples INTEGER NOT NULL, PRIMARY KEY(bucketStart,packageName,mode))")
                legacy.execSQL("CREATE INDEX index_app_energy_stats_bucketStart ON app_energy_stats(bucketStart)")
                legacy.execSQL("CREATE INDEX index_app_energy_stats_packageName ON app_energy_stats(packageName)")
                legacy.execSQL("INSERT INTO app_energy_stats VALUES(0,'example.app','HEURISTIC',1.5,1)")
            }
            legacy.execSQL("INSERT INTO battery_samples VALUES(1,1000,0,3,0,0,0,4000,0,2,1)")
            legacy.execSQL("INSERT INTO battery_samples VALUES(2,2000,50,3,0,-9223372036854775808,-9223372036854775808,0,250,2,0)")
            legacy.execSQL("INSERT INTO charge_sessions VALUES('finished','DISCHARGE',1000,2000,51,50,10000,-100000,NULL)")
            legacy.execSQL("INSERT INTO charge_sessions VALUES('interrupted','CHARGE',2000,NULL,50,NULL,NULL,NULL,NULL)")
            legacy.version = version
        }
        val db = open(name)
        try {
            val samples = db.batteryDao().samplesBetween(0, Long.MAX_VALUE).first()
            assertEquals(2, samples.size)
            assertEquals(0L, samples.first().currentNowUa)
            assertEquals(0, samples.first().levelPercent)
            assertNull(samples.last().currentNowUa)
            assertNull(samples.last().chargeCounterUah)
            assertNull(samples.last().voltageMv)
            assertNull(db.sessionDao().active())
            val history = db.sessionDao().filteredSessions(null, "", 10).first()
            assertEquals(2, history.size)
            assertEquals(2000L, history.single { it.sessionId == "interrupted" }.endTime)
            assertEquals("legacy", samples.first().source)
            assertV5Tables(db)
            val active = ChargeSession("new", SessionType.DISCHARGE, 3000, null, 50, null, null, null, null)
            db.sessionDao().upsert(active)
            try {
                db.sessionDao().upsert(active.copy(sessionId = "duplicate"))
                fail("Only one active session may exist")
            } catch (_: SQLiteConstraintException) { }
            assertEquals("new", db.sessionDao().active()?.sessionId)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun sample(time: Long, sessionId: String) = BatterySample(timestamp = time, levelPercent = 70, status = 3, plugged = 0,
        currentNowUa = -300_000, chargeCounterUah = 3_000_000, voltageMv = 3900, temperatureDeciC = 300, health = 2, screenOn = true,
        elapsedMs = time, uptimeMs = time, observationId = "o", sessionId = sessionId, source = "BatteryManager")
    private fun app(uid: Int, mah: Double = 1.0) = AppUsageRow(uid, "app.$uid", mah, cpuTimeMs = 10, mobileBytes = 5)

    @Test fun versionFourKeepsHistoryDropsLegacyTablesAndGainsWorkingV5Tables() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { v4 ->
            // schemas/…/4.json createSql, verbatim (scripts/check_migrations.py verifies it).
            v4.execSQL("CREATE TABLE IF NOT EXISTS `battery_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `levelPercent` INTEGER, `status` INTEGER NOT NULL, `plugged` INTEGER, `currentNowUa` INTEGER, `chargeCounterUah` INTEGER, `voltageMv` INTEGER, `temperatureDeciC` INTEGER, `health` INTEGER, `screenOn` INTEGER NOT NULL, `elapsedMs` INTEGER, `uptimeMs` INTEGER, `observationId` TEXT, `sessionId` TEXT, `currentAverageUa` INTEGER, `energyNwh` INTEGER, `cycleCount` INTEGER, `etaMs` INTEGER, `etaBasis` TEXT, `source` TEXT NOT NULL DEFAULT 'legacy', `boundaryReason` TEXT)")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_timestamp` ON `battery_samples` (`timestamp`)")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_status` ON `battery_samples` (`status`)")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_samples_sessionId` ON `battery_samples` (`sessionId`)")
            v4.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_battery_samples_observationId_elapsedMs` ON `battery_samples` (`observationId`, `elapsedMs`)")
            v4.execSQL("CREATE TABLE IF NOT EXISTS `charge_sessions` (`sessionId` TEXT NOT NULL, `type` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `startLevel` INTEGER, `endLevel` INTEGER, `deltaUah` INTEGER, `avgCurrentUa` INTEGER, `estCapacityMah` INTEGER, `activeKey` INTEGER, `observationId` TEXT, `lastSampleTime` INTEGER, `observedMs` INTEGER NOT NULL DEFAULT 0, `counterCoveredMs` INTEGER NOT NULL DEFAULT 0, `screenOnMs` INTEGER NOT NULL DEFAULT 0, `screenOffMs` INTEGER NOT NULL DEFAULT 0, `screenOnUah` INTEGER, `screenOffUah` INTEGER, `cpuSuspendMs` INTEGER, `closeReason` TEXT, `source` TEXT NOT NULL DEFAULT 'legacy', PRIMARY KEY(`sessionId`))")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_startTime` ON `charge_sessions` (`startTime`)")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_type` ON `charge_sessions` (`type`)")
            v4.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_charge_sessions_activeKey` ON `charge_sessions` (`activeKey`)")
            v4.execSQL("CREATE TABLE IF NOT EXISTS `alarm_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `threshold` INTEGER NOT NULL, `notifyOnce` INTEGER NOT NULL)")
            v4.execSQL("CREATE TABLE IF NOT EXISTS `app_energy_stats` (`bucketStart` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `mode` TEXT NOT NULL, `energyMah` REAL NOT NULL, `samples` INTEGER NOT NULL, PRIMARY KEY(`bucketStart`, `packageName`, `mode`))")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_app_energy_stats_bucketStart` ON `app_energy_stats` (`bucketStart`)")
            v4.execSQL("CREATE INDEX IF NOT EXISTS `index_app_energy_stats_packageName` ON `app_energy_stats` (`packageName`)")
            v4.execSQL("INSERT INTO battery_samples (id,timestamp,levelPercent,status,plugged,currentNowUa,chargeCounterUah,voltageMv,temperatureDeciC,health,screenOn,elapsedMs,uptimeMs,observationId,sessionId,source) VALUES (1,1000,80,3,0,-250000,3200000,4000,280,2,1,1000,900,'o','closed','BatteryManager')")
            v4.execSQL("INSERT INTO battery_samples (id,timestamp,levelPercent,status,plugged,currentNowUa,chargeCounterUah,voltageMv,temperatureDeciC,health,screenOn,elapsedMs,uptimeMs,observationId,sessionId,source) VALUES (2,2000,79,3,0,-260000,3150000,3990,285,2,0,2000,1800,'o','open','BatteryManager')")
            v4.execSQL("INSERT INTO charge_sessions (sessionId,type,startTime,endTime,startLevel,endLevel,deltaUah,avgCurrentUa,activeKey,observationId,lastSampleTime,observedMs,counterCoveredMs,screenOnMs,screenOffMs,screenOnUah,cpuSuspendMs,closeReason,source) VALUES ('closed','DISCHARGE',1000,1500,80,80,50000,-250000,NULL,'o',1500,500,500,500,0,50000,100,'Power state changed','BatteryManager observed interval')")
            v4.execSQL("INSERT INTO charge_sessions (sessionId,type,startTime,endTime,startLevel,activeKey,observationId,lastSampleTime,observedMs,source) VALUES ('open','DISCHARGE',2000,NULL,79,1,'o',2000,0,'BatteryManager observed interval')")
            v4.execSQL("INSERT INTO alarm_rules (type,enabled,threshold,notifyOnce) VALUES ('TEMP_HIGH',1,45,1)")
            v4.execSQL("INSERT INTO app_energy_stats VALUES (0,'example.app','HEURISTIC',1.5,1)")
            v4.version = 4
        }
        val db = open(name)
        try {
            // History survives; the new session columns start empty.
            val samples = db.batteryDao().samplesBetween(0, Long.MAX_VALUE).first()
            assertEquals(listOf(1000L, 2000L), samples.map { it.timestamp })
            assertEquals(3_150_000L, samples.last().chargeCounterUah)
            val closed = db.sessionDao().byId("closed")!!
            assertEquals(50_000L, closed.deltaUah); assertEquals(100L, closed.cpuSuspendMs); assertEquals(1500L, closed.endTime)
            assertTrue(listOf(closed.chargerType, closed.energyNwh, closed.peakPowerMw, closed.peakTemperatureDeciC, closed.screenOffSuspendMs,
                closed.capacityEstimateMah, closed.capacityConfidence, closed.capacityBasis, closed.appUsageStatus, closed.appUsageBasis).all { it == null })
            assertEquals("open", db.sessionDao().active()?.sessionId)
            assertV5Tables(db)

            // PersistPolicy's write: day rows, session row and sample commit together or not at all.
            val day = DailySummary(epochDay = 20_000, screenOnMs = 1000, screenOnDischargeUah = 50_000, updatedAt = 3000)
            val open = db.sessionDao().active()!!.copy(lastSampleTime = 3000, peakTemperatureDeciC = 310, appUsageStatus = AppUsageStatus.PENDING)
            assertTrue(db.persistDao().persistSample(sample(3000, "open"), open, listOf(day.copy(epochDay = -1), day)) > 0)
            assertEquals(open, db.sessionDao().byId("open"))
            assertEquals(day, db.dailySummaryDao().byDay(20_000))
            assertEquals(-1L, db.persistDao().persistSample(sample(3000, "open"), open.copy(lastSampleTime = 3100), listOf(day.copy(screenOnMs = 2000))))
            assertEquals(2000L, db.dailySummaryDao().byDay(20_000)?.screenOnMs)
            assertEquals(3100L, db.sessionDao().byId("open")?.lastSampleTime)
            try {
                db.persistDao().persistSample(sample(4000, "second"), open.copy(sessionId = "second"), listOf(day.copy(epochDay = 20_001)))
                fail("A second active session must be rejected")
            } catch (_: SQLiteConstraintException) { }
            assertNull(db.dailySummaryDao().byDay(20_001))
            assertEquals(3, db.batteryDao().count())
            assertEquals(listOf(-1L, 20_000L), db.dailySummaryDao().between(-10, 30_000).first().map { it.epochDay })

            // Snapshots: the open session's baseline plus the last 3 survive; pruned uids cascade.
            val dao = db.appUsageDao()
            val baseline = dao.insertSnapshot(AppSnapshot(sessionId = "open", kind = AppSnapshotKind.BASELINE, capturedAt = 2100,
                windowStartedAt = 500, windowStartCount = 7), listOf(app(10_002), app(10_001)))
            val ends = (1..4).map { i ->
                dao.insertSnapshot(AppSnapshot(sessionId = "closed", kind = AppSnapshotKind.END, capturedAt = 2100L + i,
                    windowStartedAt = 500, windowStartCount = 7), listOf(app(10_001, i.toDouble())))
            }
            assertEquals(listOf(ends[3], ends[2], ends[1], baseline), dao.snapshots().map { it.id })
            assertEquals(listOf(app(10_001), app(10_002)), dao.snapshotUids(baseline).map { it.toRow() })
            assertEquals(baseline, dao.latestSnapshot("open", AppSnapshotKind.BASELINE)?.id)
            assertEquals(5, db.count("app_snapshot_uids"))

            // A session's breakdown: ranked, marks the session READY, replaced whole, FK-checked.
            val others = AppUsageRow(-1, "", 0.5, isOthers = true)
            dao.replaceSessionUsage("closed", AppUsageBasis.WINDOW_RESET, listOf(app(10_001, 3.0), app(10_002, 2.0), others))
            dao.replaceSessionUsage("closed", AppUsageBasis.DELTA, listOf(app(10_002, 4.0), app(10_001, 3.0), others))
            val usage = dao.sessionUsage("closed").first()
            assertEquals(listOf(0, 1, 2), usage.map { it.rank })
            assertEquals(listOf(app(10_002, 4.0), app(10_001, 3.0), others), usage.map { it.toRow() })
            assertTrue(usage.all { it.basis == AppUsageBasis.DELTA })
            db.sessionDao().byId("closed")!!.let {
                assertEquals(AppUsageStatus.READY, it.appUsageStatus); assertEquals(AppUsageBasis.DELTA, it.appUsageBasis)
            }
            try {
                dao.replaceSessionUsage("missing", AppUsageBasis.ABSOLUTE, listOf(app(1)))
                fail("App usage needs its session")
            } catch (_: SQLiteConstraintException) { }

            // Retention: the closed session goes, its breakdown cascades, its snapshots become orphans and go,
            // days before the cutoff's day go; the open session's baseline stays.
            HistoryPolicy.purgeExpired(db, 1600, ZoneOffset.UTC)
            assertNull(db.sessionDao().byId("closed"))
            assertEquals(0, db.count("session_app_usage"))
            assertEquals(listOf(baseline), dao.snapshots().map { it.id })
            assertEquals(2, db.count("app_snapshot_uids"))
            assertEquals(listOf(20_000L), db.dailySummaryDao().between(-10, 30_000).first().map { it.epochDay })
            assertEquals(listOf(2000L, 3000L), db.batteryDao().samplesBetween(0, Long.MAX_VALUE).first().map { it.timestamp })
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
