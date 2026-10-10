# Modules and Packages

*Last Updated: 2026-10-11*

There is one Gradle module, `:app`. The packages below are under `app/src/main/java/com/akane/voltwise/`, and
unit tests mirror them under `app/src/test/java/com/akane/voltwise/`.

| Package | Purpose | Key files |
| --- | --- | --- |
| `battery/` | App + launcher activity | `BatteryApp.kt` (Koin start, warm-up, migrations), `BatteryMainActivity.kt` |
| `battery/service/` | Monitoring lifecycle | `BatteryMonitorService.kt` (FGS), `MonitoringController.kt` / `MonitoringControl` (start/stop), `SamplingDemand.kt` (2 s demand tokens), `BootReceiver.kt` |
| `battery/data/` | History repository and services | `BatteryRepository.kt` (single writer, `realtimeFlow`, sessions, `HistoryWriter`), `CalibrationStore.kt`, `DesignCapacitySource.kt`, `FullCapacity.kt` (`resolveFullUah`: charge counter first only at ≥ 50 %, else the stored Health estimate; never design capacity; `usableStoredEstimates` = local estimates, or imported ones only when no local one exists; shared by `storedFullUah`, `NowMapping.healthSummary`, the Health summary and SessionDetails), `ExportImport.kt`, `HistoryFiles.kt`, `HistoryRetention.kt`, `HistoryPolicy.kt`, `PowerTransition.kt`, `SessionDrain.kt`, `SessionEvidence.kt`, `LiveReading.kt` |
| `battery/data/db/` | Room | see [database.md](database.md) |
| `battery/data/sampling/` | Sampler and session rows | `SamplingController.kt` (the only sampler, `HandlerThread`), `SessionReport.kt` (open/report session rows, `ChargerType`, `SessionExtremes`), `SamplerState.kt`, `DailySummaryReplay.kt`, `KeyValueStore.kt` |
| `battery/measurement/` | Pure measurement logic (no Android; heavily unit-tested) | `ObservationEngine.kt` (intervals, `Boundary`, gaps), `PersistPolicy.kt`, `SamplingPolicy.kt`, `StateEventSequencer.kt` (compound boundaries: each changed dimension needs its own confirming event), `Calibration.kt` + `CurrentCalibrator.kt` (unit/sign detection), `CapacityEstimator.kt`, `ChargeEta.kt`, `DischargeEta.kt`, `EtaHold.kt`, `EtaModel.kt`, `HealthSummary.kt`, `DailySummaryAggregator.kt`, `BatteryAlerts.kt`, `BatteryReading.kt` |
| `battery/insights/` | Insights: findings engine, actions, notifier | `InsightRepository.kt`, `InsightInputsBuilder.kt`, `InsightNotifier.kt`, `FindingCodec.kt`, `engine/` (detectors, stats, trends, recommender), `actions/`, `model/` (see [insights.md](insights.md)) |
| `battery/actions/` | Privileged action templates | `PrivilegedCommand.kt`, `CommandPolicy.kt` (allow-list + protected packages, also used by `ShellUserService`), `ActionReadback.kt` |
| `battery/apps/` | Per-app usage | `AppStatsRepository.kt`, `SessionSnapshotCollector.kt`, `SessionSnapshotStore.kt`, `AppUsage*.kt`, `TopApps.kt`, `AppInfo*.kt`, `AppLabel.kt` (see [privileged-shell.md](privileged-shell.md)) |
| `battery/shizuku/` | Shizuku binding | `ShizukuBridge.kt`, `ShellUserService.kt` |
| `battery/util/` | Shell + parsing + misc | `ShellRunner.kt`, `BatteryStatsParser.kt`, `CommandOutput.kt`, `CommandProtocol.kt`, `DumpOutput.kt`, `RootStatsCollector.kt`, `PrivilegeChecker.kt`, `TimeEstimator.kt`, `UpdateGate.kt` (dedupe/gate pushes), `Notifier.kt` |
| `battery/drain/` | Ongoing notification | `DrainNotificationManager.kt`, `OngoingPosts.kt` (one stable `when` per monitoring session so refreshes update in place), `NotificationContent.kt` (%/h when full capacity is known, else mA), `NotificationFitter.kt` (longest text form that fits; `rows(fontScale)` hides the summary and footer above 1.3x), `NotificationInputs.kt`, `StatusIconRenderer.kt` / `StatusIconText.kt` (status-bar icon text), `DrainState.kt`, `DrainNotificationReceiver.kt`; layouts `res/layout/notification_*.xml` |
| `battery/widget/` | RemoteViews widgets | `WidgetUpdater.kt`, `Battery{Level,Temp,Time}Widget.kt`, `WidgetIdCache.kt` |
| `battery/tile/` | QS tile | `MonitorTileService.kt`, `TileListenSession.kt`, `TileText.kt` |
| `battery/diagnostics/` | Privacy-safe local diagnostics | `DiagnosticStore.kt`, `DiagnosticLog.kt` (fixed codes only), `DiagnosticReport.kt` |
| `settings/` | Settings schema (kmp-settings, KSP) | `SettingsDefinition.kt` (`AppSettings`, schema v3), `SettingsMigrations.kt`, `SettingsMigrator.kt`, `SettingsWrites.kt`, `SettingsImportPolicy.kt`, `SettingsDataStore.kt` (raw DataStore with a corruption handler; `withDefaultsOnReadFailure()` view for normal reads (defaults, then retry), raw store for `SettingsBackupManager`) |
| `di/` | Koin | `AppModules.kt` (`appModule`: singletons + `viewModel {}` with parameters) |
| `data/` | Constants | `Constants.kt` |
| `viewmodel/` | One VM per screen + its repository interface | `NowViewModel.kt` (+ `NowRepository.kt`, `NowUiState.kt`, `NowMapping.kt`), `HistoryViewModel.kt`, `SessionDetailsViewModel.kt`, `AppsViewModel.kt`, `AppDetailsViewModel.kt`, `HealthViewModel.kt`, `InsightsViewModel.kt` (+ `InsightsRepository.kt`, `InsightsUiState.kt`), `FindingDetailsViewModel.kt`, `DataViewModel.kt`, `StatusViewModel.kt`, `SettingsViewModel.kt`, `ShizukuState.kt` |
| `ui/` | Compose UI | `NavGraph.kt`, `TestTags.kt`; `navigation/` (Routes, TopLevelBackStack, Destinations) |
| `ui/screens/` | Screens | `MainScreen.kt` (tab shell: bar/rail), `now/` (`NowScreen`, `NowHero`, `NowPanels`, `NowTrace`, `NowContent`, `NowInsights`), `insights/` (`InsightsScreen`, `InsightsPanels`, `InsightLabels`, `InsightApplyDialog`, `FindingDetailsScreen`, `FindingChart`), `HistoryScreen`, `SessionDetailsScreen`, `AppsScreen`, `AppDetailsScreen`, `HealthScreen`, `SettingsScreen`, `DataScreen`, `StatusScreen`, `DesignCapacityDialog`, `DrainCell` |
| `ui/components/` | Shared composables | `Panel`, `StatCell`, `AppRow`, `AppIcon`, `Notice`, `EmptyState`, `InfoSheet`, `SegmentedTabs`, `DetailTopBar`, `CalibrationNotice`, `QuietText`, `AppLabels` |
| `ui/components/chart/` | Canvas charts | `TimeSeriesChart.kt`, `BarChart.kt`, `ChartDrawing.kt`, `ChartMath.kt`, `ChartModel.kt`, `ChartData.kt`, `ChartScrubState.kt`, `PlotLayout.kt`, `TimeAxisFormatter.kt` |
| `ui/format/` | Formatting | `Formats.kt` (numbers, durations, `percentText`, locale-aware) |
| `ui/theme/` | Design tokens | `Theme.kt` (`MainTheme(oled, dynamicColor)`, `MaterialTheme.batColors`), `Color.kt` (`BatColors`), `ChartColors.kt`, `Type.kt` (Space Grotesk, tabular numbers), `Shape.kt`, `Spacing.kt` (`MaterialTheme.spacing`), `Motion.kt` |

## Screen → ViewModel → repository
| Screen | ViewModel | Repository impl (in the VM file) |
| --- | --- | --- |
| Now | `NowViewModel` (SavedStateHandle) | `DefaultNowRepository` (`NowRepository.kt`) |
| History | `HistoryViewModel` (SavedStateHandle) | `DefaultHistoryRepository` |
| Session details | `SessionDetailsViewModel(sessionId)` | `DefaultSessionDetailsRepository` |
| Apps | `AppsViewModel` (SavedStateHandle) | `DefaultAppsRepository` |
| App details | `AppDetailsViewModel(uid, packageName)` | `DefaultAppDetailsRepository` |
| Insights | `InsightsViewModel` | `DefaultInsightsRepository` (`InsightsRepository.kt`) |
| Finding details | `FindingDetailsViewModel` (SavedStateHandle `key`) | `DefaultInsightsRepository` + application scope |
| Health | `HealthViewModel` | `DefaultHealthRepository` |
| Settings | `SettingsViewModel` | `KmpSettingsStore` (over the settings `DataStore`; also `retentionUnset` via `settings/Retention.kt` `resolveRetention`) |
| Settings › Data | `DataViewModel` (SavedStateHandle) | `DefaultDataRepository` |
| Settings › Status | `StatusViewModel` | `DefaultStatusRepository` |
