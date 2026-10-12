package com.akane.voltwise.battery.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.akane.voltwise.battery.apps.AppUsageBasis
import com.akane.voltwise.battery.apps.AppUsageStatus

@TypeConverters(EnumConverters::class)
@Database(
    entities = [BatterySample::class, ChargeSession::class, DailySummary::class,
        AppSnapshot::class, AppSnapshotUid::class, SessionAppUsage::class,
        SnapshotDeviceWaker::class, SessionDeviceWaker::class, InsightFindingEntity::class, InsightActionEntity::class],
    version = 9,
    exportSchema = true
)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun batteryDao(): BatteryDao
    abstract fun sessionDao(): SessionDao
    abstract fun persistDao(): PersistDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun insightDao(): InsightDao

    companion object {
        @Volatile private var INSTANCE: BatteryDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS app_energy_stats (bucketStart INTEGER NOT NULL, packageName TEXT NOT NULL, mode TEXT NOT NULL, energyMah REAL NOT NULL, samples INTEGER NOT NULL, PRIMARY KEY(bucketStart,packageName,mode))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_energy_stats_bucketStart ON app_energy_stats(bucketStart)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_energy_stats_packageName ON app_energy_stats(packageName)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit // Same schema; retain historical rows.
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE battery_samples_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, levelPercent INTEGER, status INTEGER NOT NULL, plugged INTEGER, currentNowUa INTEGER, chargeCounterUah INTEGER, voltageMv INTEGER, temperatureDeciC INTEGER, health INTEGER, screenOn INTEGER NOT NULL, elapsedMs INTEGER, uptimeMs INTEGER, observationId TEXT, sessionId TEXT, currentAverageUa INTEGER, energyNwh INTEGER, cycleCount INTEGER, etaMs INTEGER, etaBasis TEXT, source TEXT NOT NULL DEFAULT 'legacy', boundaryReason TEXT)")
                db.execSQL("INSERT INTO battery_samples_new (id,timestamp,levelPercent,status,plugged,currentNowUa,chargeCounterUah,voltageMv,temperatureDeciC,health,screenOn) SELECT id,timestamp,CASE WHEN levelPercent BETWEEN 0 AND 100 THEN levelPercent END,status,plugged,CASE WHEN currentNowUa BETWEEN -100000000 AND 100000000 THEN currentNowUa END,CASE WHEN chargeCounterUah BETWEEN 0 AND 200000000 THEN chargeCounterUah END,CASE WHEN voltageMv BETWEEN 1 AND 30000 THEN voltageMv END,CASE WHEN temperatureDeciC BETWEEN -500 AND 1500 THEN temperatureDeciC END,health,screenOn FROM battery_samples")
                db.execSQL("DROP TABLE battery_samples")
                db.execSQL("ALTER TABLE battery_samples_new RENAME TO battery_samples")
                db.execSQL("CREATE INDEX index_battery_samples_timestamp ON battery_samples(timestamp)")
                db.execSQL("CREATE INDEX index_battery_samples_status ON battery_samples(status)")
                db.execSQL("CREATE INDEX index_battery_samples_sessionId ON battery_samples(sessionId)")
                db.execSQL("CREATE UNIQUE INDEX index_battery_samples_observationId_elapsedMs ON battery_samples(observationId,elapsedMs)")
                db.execSQL("CREATE TABLE charge_sessions_new (sessionId TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER, startLevel INTEGER, endLevel INTEGER, deltaUah INTEGER, avgCurrentUa INTEGER, estCapacityMah INTEGER, activeKey INTEGER, observationId TEXT, lastSampleTime INTEGER, observedMs INTEGER NOT NULL DEFAULT 0, counterCoveredMs INTEGER NOT NULL DEFAULT 0, screenOnMs INTEGER NOT NULL DEFAULT 0, screenOffMs INTEGER NOT NULL DEFAULT 0, screenOnUah INTEGER, screenOffUah INTEGER, cpuSuspendMs INTEGER, closeReason TEXT, source TEXT NOT NULL DEFAULT 'legacy')")
                db.execSQL("INSERT INTO charge_sessions_new (sessionId,type,startTime,endTime,startLevel,endLevel,deltaUah,avgCurrentUa,estCapacityMah,closeReason) SELECT sessionId,type,startTime,COALESCE(endTime,startTime),startLevel,endLevel,deltaUah,avgCurrentUa,estCapacityMah,'Legacy record; continuity not recorded' FROM charge_sessions")
                db.execSQL("DROP TABLE charge_sessions")
                db.execSQL("ALTER TABLE charge_sessions_new RENAME TO charge_sessions")
                db.execSQL("CREATE INDEX index_charge_sessions_startTime ON charge_sessions(startTime)")
                db.execSQL("CREATE INDEX index_charge_sessions_type ON charge_sessions(type)")
                db.execSQL("CREATE UNIQUE INDEX index_charge_sessions_activeKey ON charge_sessions(activeKey)")
            }
        }
        /**
         * DDL only; every CREATE is the `createSql` from schemas/…/5.json. Irreversible: it drops alarm_rules and
         * app_energy_stats (their rows are lost) and there is no 5→4 path — after a rollback, clear app data.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `alarm_rules`")
                db.execSQL("DROP TABLE IF EXISTS `app_energy_stats`")
                db.execSQL("DROP INDEX IF EXISTS `index_battery_samples_status`")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `chargerType` TEXT")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `energyNwh` INTEGER")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `peakPowerMw` INTEGER")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `peakTemperatureDeciC` INTEGER")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `screenOffSuspendMs` INTEGER")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `capacityEstimateMah` INTEGER")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `capacityConfidence` TEXT")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `capacityBasis` TEXT")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `appUsageStatus` TEXT")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `appUsageBasis` TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `daily_summaries` (`epochDay` INTEGER NOT NULL, `screenOnMs` INTEGER NOT NULL, `screenOffMs` INTEGER NOT NULL, `screenOnDischargeUah` INTEGER NOT NULL, `screenOffDischargeUah` INTEGER NOT NULL, `chargedUah` INTEGER NOT NULL, `cpuSuspendMs` INTEGER, `minLevel` INTEGER, `maxLevel` INTEGER, `peakTemperatureDeciC` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `app_snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT, `kind` TEXT NOT NULL, `capturedAt` INTEGER NOT NULL, `windowStartedAt` INTEGER, `windowStartCount` INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_snapshots_sessionId` ON `app_snapshots` (`sessionId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `app_snapshot_uids` (`snapshotId` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `powerMah` REAL NOT NULL, `cpuTimeMs` INTEGER, `foregroundTimeMs` INTEGER, `backgroundTimeMs` INTEGER, `wakelockTimeMs` INTEGER, `mobileBytes` INTEGER, `wifiBytes` INTEGER, PRIMARY KEY(`snapshotId`, `uid`), FOREIGN KEY(`snapshotId`) REFERENCES `app_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE TABLE IF NOT EXISTS `session_app_usage` (`sessionId` TEXT NOT NULL, `rank` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `powerMah` REAL NOT NULL, `cpuTimeMs` INTEGER, `foregroundTimeMs` INTEGER, `backgroundTimeMs` INTEGER, `wakelockTimeMs` INTEGER, `mobileBytes` INTEGER, `wifiBytes` INTEGER, `isOthers` INTEGER NOT NULL, `basis` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `rank`), FOREIGN KEY(`sessionId`) REFERENCES `charge_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_app_usage_sessionId` ON `session_app_usage` (`sessionId`)")
            }
        }

        /** Additive: historical rows retain unknown (null) screen counter coverage. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `screenOnCoveredMs` INTEGER")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `screenOffCoveredMs` INTEGER")
                db.execSQL("ALTER TABLE `daily_summaries` ADD COLUMN `screenOnCoveredMs` INTEGER")
                db.execSQL("ALTER TABLE `daily_summaries` ADD COLUMN `screenOffCoveredMs` INTEGER")
            }
        }

        /** Additive only: existing rows retain unknown (null) insight measurements. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `dozeMs` INTEGER")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `screenOffDozeMs` INTEGER")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `appCaptureStartMs` INTEGER")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `appCaptureEndMs` INTEGER")
                db.execSQL("ALTER TABLE `daily_summaries` ADD COLUMN `dozeMs` INTEGER")
                db.execSQL("ALTER TABLE `daily_summaries` ADD COLUMN `screenOffDozeMs` INTEGER")
                db.execSQL("ALTER TABLE `daily_summaries` ADD COLUMN `screenOffSuspendMs` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshot_uids` ADD COLUMN `wakeupAlarms` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshot_uids` ADD COLUMN `partialWakelockCount` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshot_uids` ADD COLUMN `partialWakelockBgMs` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshot_uids` ADD COLUMN `jobCount` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshot_uids` ADD COLUMN `jobMs` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshot_uids` ADD COLUMN `syncCount` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshot_uids` ADD COLUMN `fgServiceMs` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshot_uids` ADD COLUMN `topMs` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshot_uids` ADD COLUMN `mobileActiveMs` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshot_uids` ADD COLUMN `gpsMs` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshot_uids` ADD COLUMN `sensorMs` INTEGER")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `wakeupAlarms` INTEGER")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `partialWakelockCount` INTEGER")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `partialWakelockBgMs` INTEGER")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `jobCount` INTEGER")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `jobMs` INTEGER")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `syncCount` INTEGER")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `fgServiceMs` INTEGER")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `topMs` INTEGER")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `mobileActiveMs` INTEGER")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `gpsMs` INTEGER")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `sensorMs` INTEGER")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `topWakelockTag` TEXT")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `topAlarmTag` TEXT")
                db.execSQL("ALTER TABLE `session_app_usage` ADD COLUMN `topJobName` TEXT")
                db.execSQL("ALTER TABLE `app_snapshots` ADD COLUMN `deepIdleMs` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshots` ADD COLUMN `deepIdleCount` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshots` ADD COLUMN `lightIdleMs` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshots` ADD COLUMN `lightIdleCount` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshots` ADD COLUMN `screenOffMs` INTEGER")
                db.execSQL("ALTER TABLE `app_snapshots` ADD COLUMN `wakersComplete` INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS `snapshot_device_wakers` (`snapshotId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `count` INTEGER NOT NULL, `totalMs` INTEGER NOT NULL, PRIMARY KEY(`snapshotId`, `kind`, `name`), FOREIGN KEY(`snapshotId`) REFERENCES `app_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE TABLE IF NOT EXISTS `session_device_wakers` (`sessionId` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `count` INTEGER NOT NULL, `totalMs` INTEGER NOT NULL, `rank` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `kind`, `name`), FOREIGN KEY(`sessionId`) REFERENCES `charge_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE TABLE IF NOT EXISTS `insight_findings` (`key` TEXT NOT NULL, `type` TEXT NOT NULL, `uid` INTEGER, `packageName` TEXT, `severity` TEXT NOT NULL, `confidence` TEXT NOT NULL, `score` REAL NOT NULL, `firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `feedbackMultiplier` REAL NOT NULL DEFAULT 1.0, `evidenceVersion` INTEGER NOT NULL, `evidenceJson` TEXT NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_insight_findings_status` ON `insight_findings` (`status`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `insight_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `findingKey` TEXT NOT NULL, `type` TEXT NOT NULL, `packageName` TEXT, `uid` INTEGER, `userId` INTEGER NOT NULL, `status` TEXT NOT NULL, `priorStateVersion` INTEGER NOT NULL, `priorState` TEXT, `targetState` TEXT, `createdAt` INTEGER NOT NULL, `appliedAt` INTEGER, `revertedAt` INTEGER, `message` TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_insight_actions_status` ON `insight_actions` (`status`)")
            }
        }

        /** Additive: legacy actions keep their journal data and use finding evidence as a fallback. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `insight_actions` ADD COLUMN `metric` TEXT")
            }
        }

        /** Legacy checkin text cannot certify app captures or waker attribution; retain browsing and undo history. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE `charge_sessions` SET `appCaptureStartMs` = NULL, `appCaptureEndMs` = NULL")
                // Migration connections may not enforce foreign keys, so retire children explicitly.
                db.execSQL("DELETE FROM `app_snapshot_uids`")
                db.execSQL("DELETE FROM `snapshot_device_wakers`")
                db.execSQL("DELETE FROM `app_snapshots`")
                db.execSQL("DELETE FROM `session_device_wakers`")
                // Device payloads can also carry app attributions from those untrusted captures.
                db.execSQL("UPDATE `insight_findings` SET `status` = 'RESOLVED' WHERE `status` = 'ACTIVE'")
            }
        }

        fun get(context: Context): BatteryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BatteryDatabase::class.java,
                    "battery.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .build().also { INSTANCE = it }
            }
    }
}

/**
 * Enum columns can hold any text (older builds, imports, hand edits), so reads never use `valueOf`: an unknown
 * name reads as null in the nullable columns, and as a documented fallback in the NOT NULL ones.
 */
class EnumConverters {
    @TypeConverter fun fromSessionType(t: SessionType?): String? = t?.name
    /** NOT NULL column: an unknown type reads as UNKNOWN, which gets no type-specific stats or breakdown. */
    @TypeConverter fun toSessionType(s: String?): SessionType? = s?.let { name -> SessionType.entries.firstOrNull { it.name == name } ?: SessionType.UNKNOWN }

    @TypeConverter fun fromSnapshotKind(k: AppSnapshotKind?): String? = k?.name
    /** NOT NULL column: an unknown kind reads as END, so it is never used as (or protected like) a baseline. */
    @TypeConverter fun toSnapshotKind(s: String?): AppSnapshotKind? = s?.let { name -> AppSnapshotKind.entries.firstOrNull { it.name == name } ?: AppSnapshotKind.END }

    @TypeConverter fun fromAppUsageStatus(t: AppUsageStatus?): String? = t?.name
    @TypeConverter fun toAppUsageStatus(s: String?): AppUsageStatus? = s?.let { name -> AppUsageStatus.entries.firstOrNull { it.name == name } }

    @TypeConverter fun fromAppUsageBasis(t: AppUsageBasis?): String? = t?.name
    @TypeConverter fun toAppUsageBasis(s: String?): AppUsageBasis? = s?.let { name -> AppUsageBasis.entries.firstOrNull { it.name == name } }

    @TypeConverter fun fromInsightFindingStatus(t: InsightFindingStatus?): String? = t?.name
    /** Unknown lifecycle text is not an active recommendation; retain the row as RESOLVED. */
    @TypeConverter fun toInsightFindingStatus(s: String?): InsightFindingStatus? = s?.let { name ->
        InsightFindingStatus.entries.firstOrNull { it.name == name } ?: InsightFindingStatus.RESOLVED
    }

    @TypeConverter fun fromInsightActionStatus(t: InsightActionStatus?): String? = t?.name
    /** Unknown journal text maps to UNKNOWN without claiming success; recovery requires a valid journal and live-state validation. */
    @TypeConverter fun toInsightActionStatus(s: String?): InsightActionStatus? = s?.let { name ->
        InsightActionStatus.entries.firstOrNull { it.name == name } ?: InsightActionStatus.UNKNOWN
    }
}
