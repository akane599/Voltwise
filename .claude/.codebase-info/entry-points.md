# Entry Points

*Last Updated: 2026-10-10*

Everything is declared in `app/src/main/AndroidManifest.xml`. Paths below are under
`app/src/main/java/com/akane/voltwise/`.

| Entry | Class / file | What starts it | Notes |
| --- | --- | --- | --- |
| Application | `battery/BatteryApp.kt` (`BatteryApp`) | process start | `startKoin(appModule)`, `ShizukuBridge.warmUp()`, runs `SettingsMigrator`, then `backfillDailySummariesOnce()`. After the settings migration it reconciles (on IO) the Insights action journal, runs a catch-up insights refresh when the last analysis is missing/older than 6 h/in the future, and starts insights notifications. Its application scope subscribes to finalized discharge snapshots and closed non-discharge sessions for coalesced refresh (see [insights.md](insights.md)). `BatteryGraph` is a legacy Koin accessor. |
| Launcher activity | `battery/BatteryMainActivity.kt` | launcher, `QS_TILE_PREFERENCES` (tile long-press) | Edge-to-edge dark bars, asks for `POST_NOTIFICATIONS` (API 33+), reads the `destination` extra in `onCreate`/`onNewIntent`, hosts `MainTheme` → `ui/screens/MainScreen.kt`. |
| Foreground service | `battery/service/BatteryMonitorService.kt` | `MonitoringController.start()` | `specialUse` FGS. Promotes to the foreground first, then awaits application-owned Insights refresh subscriptions before `repository.startSampling()` and `SessionSnapshotCollector.run()`; also runs the notification loop and widget pushes. Service cancellation does not cancel session refresh. |
| Boot receiver | `battery/service/BootReceiver.kt` | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` (`resumesMonitoring`) | When `autoStartOnBoot` is set, starts monitoring through `MonitoringControl` (after an app update only if monitoring was wanted: `MonitoringControl.monitoringWanted`, SharedPreferences `monitoring_state`, absent counts as wanted); if Android blocks it, `Notifier.promptStartOnBoot` shows a notification. |
| QS tile | `battery/tile/MonitorTileService.kt` | the user adds the tile | Live value while the shade is open; holds `SamplingDemand` only while monitoring is on (`TileListenSession`); a tap toggles monitoring, behind `unlockAndRun` when locked on API 31+ (`tile/TileClick.kt`, same rule as the notification Stop/Reset). |
| Widgets | `battery/widget/BatteryLevelWidget.kt`, `BatteryTempWidget.kt`, `BatteryTimeWidget.kt` | `APPWIDGET_UPDATE`, `com.akane.voltwise.battery.widget.ACTION_REFRESH` | RemoteViews (`res/layout/widget_*.xml`, `res/xml/widget_*.xml`). `WidgetUpdater` builds the content. Activity PendingIntents use distinct request codes (`OPEN_APP_REQUEST_CODE`: alerts 20, tile 21, widgets 22; `InsightNotifier.REQUEST_CODE` 23), pinned distinct by `ActivityPendingIntentRequestCodesTest`, because PendingIntent identity ignores extras and `FLAG_UPDATE_CURRENT` would rewrite another entry's `destination`. |
| Notification actions | `battery/drain/DrainNotificationReceiver.kt` | `…drain.ACTION_RESET`, `…drain.ACTION_STOP` | `ACTION_RESET` calls `repository.resetObservation()`, which the writer applies only to an open DISCHARGE session (`resetApplies` in `data/BatteryRepository.kt`); `ACTION_STOP` calls `MonitoringControl.stop()`. Both actions require unlock on API 31+ (`setAuthenticationRequired`). |
| Insights notification | `battery/insights/InsightNotifier.kt` | a refreshed `InsightReport` with a qualifying finding | Channel `insights` (IMPORTANCE_LOW); at most one HIGH, confidence ≥ MEDIUM finding per 24 h; tapping opens the Insights tab (`destination=insights`). |
| Shizuku provider | `rikka.shizuku.ShizukuProvider` (library) | the Shizuku manager | Authority `${applicationId}.shizuku`; `battery/shizuku/ShellUserService.kt` is the Shizuku user service binder, which runs in a privileged helper process and exposes only fixed commands. |

## In-app navigation
- `ui/navigation/Routes.kt`: a `@Serializable sealed interface Routes : NavKey` with the tabs `Now`,
  `Insights`, `History`, `Apps`, `Settings` (`TOP_LEVEL_TABS`) and the detail routes `SessionDetails(sessionId)`,
  `AppDetails(uid, packageName)`, `FindingDetails(key)`, `Health`, `SettingsData`, `SettingsStatus`.
- `ui/navigation/TopLevelBackStack.kt`: one `NavBackStack` per tab; `select`, `navigate` (skips a route
  already on top), `onBack(entry)` (pops only while that entry is on top), `openDestination(value)`, and
  `blockLeaving(entry, isBusy, onBlocked)` leave blockers held in a `LeaveBlockers` ViewModel. DataScreen
  registers through `ViewModel.blockLeavingWhileBusy`, so the block lives as long as `DataViewModel`;
  `popToRoot`/`openRoot` refuse to drop a blocked entry. At Now's root, `MainScreen` keeps its `BackHandler` on while
  `isLeavingBlocked()`; `onBack` then selects the busy tab and calls its `onBlocked` instead of letting Back leave the app.
- `ui/NavGraph.kt`: the `entry<Routes.X>` → screen mapping. Screens get Koin VMs (`koinViewModel`).
  `AppDetails`, `SessionDetails` and `FindingDetails` take parameters; Now's insights card, App details' Findings rows and the Insights tab navigate to `FindingDetails(key)`.
- `ui/navigation/Destinations.kt`: string values for the `destination` deep-link extra (`now`,
  `insights`, `history`, `apps`, `settings`, `health`, `status`, `session:<id>`). `initialDestination(extra, restored,
  launchedFromHistory)` ignores the extra after a state restore and on a launch from Recents.

## Build / CLI entry points
See [onboarding.md](onboarding.md). There are Gradle tasks, plus helper scripts in `scripts/`
(`check_resources.py`, `check_migrations.py`, `check_history_queries.py`, device-test helpers) and in
`.claude/scripts/run_screenshot_tests.sh`.
