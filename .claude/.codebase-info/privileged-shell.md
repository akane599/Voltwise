# Privileged Shell and Per-App Stats

*Last Updated: 2026-10-11*

Per-app battery use needs `dumpsys batterystats`, which normal apps can't run. Everything that needs
privilege goes through one path. Paths below are under `app/src/main/java/com/akane/voltwise/battery/`.

```
AppsViewModel / AppDetailsViewModel / SessionSnapshotCollector
        │
AppStatsRepository (apps/AppStatsRepository.kt)   ── StatsShell seam; ShellRunnerStatsShell on device
        │  COMMAND = "dumpsys batterystats --proto --charged"; concurrent callers share one dump; cached
ShellRunner (util/ShellRunner.kt)                   ── Mode { ROOT, SHIZUKU, ADB, NONE }, Outcome sealed class
        │  selectShellMode(): SHIZUKU (running + authorized) → ROOT → ADB (DUMP granted) → NONE; cached 10 s (root access loss clears it)
        ├─ SHIZUKU: ShizukuBridge.run() (shizuku/ShizukuBridge.kt) → bound ShellUserService (shizuku/ShellUserService.kt)
        ├─ ROOT:    CommandOutput.run(["su","-c",cmd])           (util/CommandOutput.kt)
        └─ ADB:     CommandOutput.run(cmd.split(' '))            (needs `pm grant … DUMP`; PrivilegeChecker.hasAdvancedViaAdb)
        │
BatteryStatsBinaryOutput.decode → BatteryStatsProtoParser (util/BatteryStatsProtoParser.kt) → FullSnapshot / AppPowerStats
```

## Pieces
| File | Role |
| --- | --- |
| `shizuku/ShizukuBridge.kt` | Binds the Shizuku user service (`SERVICE_VERSION` 10, bumped on every policy change so a helper from an older APK is replaced; bind timeout; the service's `NOT_STARTED:UNSUPPORTED_COMMAND` / `NOT_STARTED:HELPER_BUSY` errors are certain no-runs, every other pipe error is UNKNOWN), runs commands over a pipe (`runViaPipe`), retries ping, and unbinds after `IDLE_UNBIND_MS` (60 s) idle (`IdleCountdown`, `HelperBinding`). `RunResult.Success/Error(Failure)`; `classifyAfterRead` separates lost access (not running / no permission) from transport and command failures. Exposes a `blocked` flow when the user denied permission permanently (`shizukuPermissionBlocked`); `requestPermission` is then a no-op. |
| `shizuku/ShellUserService.kt` | A `Binder` running in the Shizuku helper process. It runs only what `battery/actions/CommandPolicy.allows` admits (`ShellUserService.allows`): only `dumpsys batterystats --proto --charged` (`BatteryStatsBinaryOutput.ARGV`) among dumpsys argv, and the Insights action templates from `battery/actions/PrivilegedCommand.kt` (`am get/set-standby-bucket`, `am force-stop`, `cmd appops get/set` with allow/ignore only, `cmd deviceidle whitelist [+/-pkg]`), each with a validated package name; mutations (`set-standby-bucket`, `force-stop`, `appops set`, `whitelist +/-pkg`) on protected packages are refused while read-only gets stay allowed (`CommandPolicy.isProtected` adds uid < 10000 and non-primary users for the action layer). Transactions: `TRANSACTION_RUN_PIPE`, `TRANSACTION_CANCEL`, `TRANSACTION_DESTROY` (the literal 16777115, pinned against Shizuku's constant by `ShellUserServiceTest`). |
| `util/CommandProtocol.kt`, `util/CommandOutput.kt` | Bounded process execution and the pipe framing between the helper and the app. `CommandOutput.Result.accessFailure` (`DENIED` / `EXECUTABLE_UNAVAILABLE`) classifies a su denial or missing `su`; `ShellRunner` then drops the cached ROOT mode and reports `NoAccess` (`rootAccessLost`). An ordinary command failure keeps the mode. |
| `util/DumpOutput.kt` | Recognizes refusal/failure text in dump output. |
| `util/RootStatsCollector.kt` | Root probe (`su -c id`, 15 s for the grant prompt; only uid=0 or a typed denial/missing `su` is cached for 60 s, a timeout retries) and the one root sysfs read (`charge_full_design` from `/sys/class/power_supply/battery/uevent`) used by `data/DesignCapacitySource.kt`. |
| `util/PrivilegeChecker.kt` | Detects the ADB-granted `DUMP` permission. |
| `apps/AppInfoRepository.kt`, `apps/AppInfoCache.kt` | Labels and icons for UIDs/packages (needs `QUERY_ALL_PACKAGES`); icons are dropped on trim memory. |
| `apps/AppUsageSnapshot.kt`, `apps/AppUsageDelta.kt`, `apps/TopApps.kt` | Snapshot model, the BASELINE→END delta (in a DELTA with a modern baseline — any non-null extended counter — a counter that is non-null in some row of either dump is observed: null on a side counts as 0, so an app with no records of that kind gets 0, while end null with a non-null baseline stays unknown; a counter no row reports, or an all-null pre-upgrade baseline, stays unknown), and the top-N plus "others" ranking. Shared UIDs: `UidIdentity` / `AppPowerStats.identity()`. |
| `apps/SessionSnapshotCollector.kt`, `apps/SessionSnapshotStore.kt` | Per-discharge-session breakdown (BASELINE on discharge open, END at plug-in, abandoned → FAILED), stored via `RoomSessionSnapshotStore` in `app_snapshots`, `app_snapshot_uids` and `session_app_usage`. An incomplete dump (a UID with counters but no `power_use_item`, or a rejected record) skips the baseline or stores END non-comparable (`captureStartMs = null`) and records `DiagnosticCode.ADVANCED_INCOMPLETE` (diagnostic report only; no Status issue). |
| `viewmodel/ShizukuState.kt` | UI-facing Shizuku state (`running`, `granted`, `blocked`). A blocked permission shows an Open Shizuku action instead of Allow (Apps, AppDetails, Status). |

## Gotchas
- Never widen `CommandPolicy.allows` casually: it is the privilege boundary for both the Shizuku helper and the Insights actions (see [insights.md](insights.md)).
- Production app statistics use aggregate proto on API 28+; the legacy checkin decoder serves compatibility fixtures. No checkin fallback can certify app evidence.
- `BatteryStatsBinaryOutput` wraps bounded raw stdout in an internal Base64 envelope before String IPC; stderr is separate, and the fixed command preserves existing command policy and helper framing.
- `ShellRunner.access` / `lastError` feed the notification issue line and the Status screen.
- Proto names are opaque string payloads, including LF, CR, quotes and row-looking content. Unknown fields are skipped within bounded wire framing; malformed consumed data remains unavailable or uncertified.
- `ShizukuBridge.readPipeResult` reports a helper call the service refused as a refusal, not a timeout.
- Instrumented Shizuku tests are annotated `com.akane.voltwise.test.RequiresShizuku` and run as a separate
  phase (see `scripts/prepare_shizuku.py`, `scripts/test_device_phases.py`).
- Background: `docs/PLATFORM_NOTES.md`, `docs/MEASUREMENTS.md`.
