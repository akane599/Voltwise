# Database

*Last Updated: 2026-10-10*

Room database `battery.db`, **version 9**, `exportSchema = true`.
- Definition and migrations: `app/src/main/java/com/akane/voltwise/battery/data/db/BatteryDatabase.kt`
- Entities: `data/db/Entities.kt`, `data/db/AppUsageTables.kt`, `data/db/DailySummary.kt`, `data/db/InsightTables.kt`
- DAOs: `data/db/Dao.kt`
- Exported schemas: `app/schemas/com.akane.voltwise.battery.data.db.BatteryDatabase/4.json`, `5.json`, `6.json`, `7.json`, `8.json`, `9.json`
  (KSP arg `room.schemaLocation` in `app/build.gradle.kts`)

## Tables

| Table | Entity | Key | Purpose |
| --- | --- | --- | --- |
| `battery_samples` | `BatterySample` | `id` autoinc; unique (`observationId`,`elapsedMs`); idx `timestamp`, `sessionId` | Persisted captures: level, status, plugged, **raw** `currentNowUa`, charge counter, voltage, temperature, screen, monotonic clocks, ETA, `source`, `boundaryReason`. |
| `charge_sessions` | `ChargeSession` | `sessionId` (UUID text); unique `activeKey` (1 while open, so at most one open session); idx `startTime`, `type` | Observed sessions. `type` is a `SessionType` (CHARGE, DISCHARGE, PLUGGED, UNKNOWN). The v5 nullable columns are charger type, energy, peaks, screen-off suspend, capacity estimate/confidence/basis, `appUsageStatus`, `appUsageBasis`. v6 adds nullable `screenOnCoveredMs` / `screenOffCoveredMs` (time actually covered by measured intervals per screen bucket; `SessionDrain` rates = covered charge / covered ms, ≥ 60 s; null on legacy rows keeps the old rule). |
| `daily_summaries` | `DailySummary` | `epochDay` | Per-local-day screen on/off time and discharge, charged µAh, min/max level, peak temperature, and (v6) nullable `screenOnCoveredMs` / `screenOffCoveredMs`; a day without coverage shows as unmeasured ("—") in History/Now. Upserted with each persisted sample (`DailySummaryAggregator`). |
| `app_snapshots` | `AppSnapshot` | `id` autoinc; idx `sessionId` | BASELINE / END batterystats snapshot headers (`AppSnapshotKind`). |
| `app_snapshot_uids` | `AppSnapshotUid` | (`snapshotId`,`uid`), FK → `app_snapshots` ON DELETE CASCADE | Per-UID power, CPU, foreground/background, wakelock, data bytes. |
| `session_app_usage` | `SessionAppUsage` | (`sessionId`,`rank`), FK → `charge_sessions` ON DELETE CASCADE | The ranked per-session app breakdown (top-N plus an `isOthers` row) with `basis`. v7 adds nullable process-state proxies (wakeup alarms, partial wakelocks, jobs, syncs, FGS/top time, mobile radio, GPS, sensors), also on `app_snapshot_uids`. |
| `snapshot_device_wakers` | `SnapshotDeviceWaker` | (`snapshotId`,`kind`,`name`), FK → `app_snapshots` ON DELETE CASCADE | v7: device-level wakeup sources per snapshot (count, total ms). |
| `session_device_wakers` | `SessionDeviceWaker` | (`sessionId`,`kind`,`name`), FK → `charge_sessions` ON DELETE CASCADE | v7: per-session device waker deltas. |
| `insight_findings` | `InsightFindingEntity` | `key` (stable finding key); idx `status` | v7: current/past findings: type, uid/package, severity, confidence, score, first/last seen, status, `feedbackMultiplier` (default 1.0), versioned `evidenceJson` (`battery/insights/FindingCodec.kt`). |
| `insight_actions` | `InsightActionEntity` | `id` autoinc; idx `status` | v7: privileged-action journal (PREPARED→APPLIED/FAILED/UNKNOWN, undo): finding key, type, package/uid/user, prior and target state; v8 adds nullable `metric` (the fired finding's lead metric, frozen at apply time for Action effect); reconciled at startup (`InsightActionRepository.reconcile()`). Age retention (`purgeTerminalActionsBefore`) deletes REVERTED/FAILED/ONE_SHOT rows and FORCE_STOP rows left UNKNOWN (no Undo or reconciliation) past the cutoff; other UNKNOWN, PREPARED and APPLIED rows are kept. |

## DAOs (`Dao.kt`)
- `BatteryDao`: sample insert/lookups, chart queries (`chartSamples`, `sessionChartSamples` bucketed), `latestSamplesBetween` (newest N, returned ascending), `boundStorage`, `purge`, `clearAll`. `ExportImport.kt`'s `BatteryDao.exportSamples` uses it for an ALL export, so that export holds the newest `MAX_SAMPLES`.
- `SessionDao`: `active()` / `activeFlow()`, `filteredSessions`, `capacityEstimates`, `closeInterrupted`, `deleteSession(id, recordingGeneration)` (deletes samples, snapshots and the row together), `purge`.
- `PersistDao`: `persistSample(sample, session, days)` is the single transactional write the repository uses per persisted capture.
- `DailySummaryDao`: per-day upsert/read/range, `purgeBefore`.
- `AppUsageDao`: snapshot header + UID rows, session app usage. `insertSnapshot` writes header, UID rows and wakers in one transaction, then `pruneSnapshots` keeps the `SNAPSHOTS_KEPT` (3) last inserted snapshots by `id` (not `capturedAt`, so a backward clock change can't prune the new row) plus open-session BASELINEs; readers (`latestSnapshot`, `snapshots`) order by `capturedAt`.
- `InsightDao`: findings (flow/once, upsert (also carries Not-a-problem feedback), status, clear, `purgeFindingsSeenBefore`) and the action journal (`actions`, `actionsOnce`, `actionsWithStatus`, …).

`EnumConverters` (in `BatteryDatabase.kt`) never uses `valueOf`: unknown enum text reads as null, or
as a documented fallback for the two NOT NULL enum columns.

## Migrations
| Step | Change |
| --- | --- |
| 1→2 | adds `app_energy_stats` |
| 2→3 | no-op (keeps rows) |
| 3→4 | rebuilds `battery_samples` and `charge_sessions` with range-sanitised copies; legacy sessions get a close reason |
| 4→5 | **irreversible**: drops `alarm_rules` and `app_energy_stats`, adds the v5 session columns and creates `daily_summaries`, `app_snapshots`, `app_snapshot_uids`, `session_app_usage` (DDL copied from `5.json`). No 5→4 path. |
| 5→6 | additive: four nullable `screen{On,Off}CoveredMs` columns on `charge_sessions` and `daily_summaries` (`MIGRATION_5_6`). |
| 6→7 | additive, forward-only: Doze / app-capture columns on `charge_sessions` and `daily_summaries`, process-state proxy columns on `app_snapshot_uids` and `session_app_usage`, new tables `snapshot_device_wakers`, `session_device_wakers`, `insight_findings`, `insight_actions` (`MIGRATION_6_7`). Reverting the app does not downgrade `battery.db`. |
| 7→8 | additive, forward-only: nullable `metric` TEXT on `insight_actions` (`MIGRATION_7_8`); pre-v8 rows stay null and fall back to the finding's lead evidence. `scripts/check_migrations.py` checks every migration path. |
| 8→9 | data-only provenance upgrade: clear legacy app capture markers, discard transient snapshots and device-waker attribution, resolve old ACTIVE findings. Keep per-app chart rows, ordinary history, finding feedback and all action journals. No downgrade path. `scripts/check_migrations.py` checks every 1–8→9 path. |

There's no destructive fallback: every version needs an explicit `MIGRATION_a_b` registered in `get()`.

## Checks
- Host-side: `python3 scripts/check_migrations.py` (replays migrations on SQLite against the newest
  exported schema) and `python3 scripts/check_history_queries.py` (DAO SQL on host SQLite).
- Device: `app/src/androidTest/java/com/akane/voltwise/battery/data/DatabaseMigrationTest.kt`,
  `HistoryBrowseTest.kt`, `HistoryImportTest.kt`, `RepositoryRecoveryTest.kt`.
- JVM: `app/src/test/java/com/akane/voltwise/battery/data/db/EnumConvertersTest.kt`.

## Other persistence
- Settings: DataStore `batstats_settings` via kmp-settings (`settings/`; schema v3, `SettingsMigrations`). A corrupt file is
  replaced with only the `SETTINGS_RECOVERED` marker (`SettingsDataStore.kt`), which pauses history age cleanup until a retention is chosen again; meanwhile Settings shows the retention as "not set" (`KmpSettingsStore.retentionUnset`).
- SharedPreferences: `CalibrationStore.PREFS_NAME` (calibration) and `SamplerState.PREFS_NAME` (sampler
  state), both wrapped in `SharedPreferencesStore` / `KeyValueStore` (`data/sampling/KeyValueStore.kt`).
- Export/import: `data/ExportImport.kt` (`BatteryExport` format 5 = `HISTORY_FORMAT_VERSION`, carries portable battery measurements and excludes local per-app/capture evidence; formats 1–4 still import) and
  `data/HistoryFiles.kt`. Backup rules: `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`.
- Age retention (`data/HistoryRetention.kt`): the cutoff is `minOf(trustedNow, wallNow) - retentionDays`, where the trusted
  clock (`SamplerState.RetentionClock`: wall, elapsedRealtime, `BOOT_COUNT`) lives in the non-backed-up `sampler_state`
  prefs, never regresses and advances only by monotonic time, so forward clock jumps, RTC fallbacks and Auto Backup restores
  can't purge recent rows. The reference wall time is the newest local sample (`BatteryDao.lastLocalSample`, skipping `import:` sources), so
  imported rows can't seed or advance it. An absent retention key means the 90-day default unless `SETTINGS_RECOVERED` is set.
- Size retention: `data/HistoryPolicy.kt`; `boundStorage` trims to
  `HistoryLimits.SAMPLE_TRIM_TARGET`/`SESSION_TRIM_TARGET` (cap − 200) every `CLEANUP_SAMPLE_INTERVAL` inserts
  (`data/HistoryFiles.kt`), so tables may sit slightly over `MAX_SAMPLES`/`MAX_SESSIONS` between trims; import
  refuses only its own growth past the cap (`importWithinLimit`).
- Import validation (`HistoryPolicy.kt`): timestamps may be at most one day in the future; unknown `status` becomes UNKNOWN and an out-of-range `plugged` becomes null instead of rejecting the file; session coverage is clamped to the span within a clock-correction
  allowance (5 s + span/10, capped at 15 min; `normalizeCoverage`, which scales screen-on/off charge with the clamped time, `sampleInSessionWindow`), and
  `planSessionImport` decides ADDED/UPDATED/UNCHANGED/STALE from the raw stored and raw incoming rows.
