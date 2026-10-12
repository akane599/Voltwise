# Patterns, Conventions and Testing

*Last Updated: 2026-10-10*

Project-wide rules (Kotlin/Compose conventions, ticketing, Gradle discipline) live in
`.claude/live-rules/rules/` and are injected into every session. This page covers what the code
actually does.

## Architecture patterns
- **ViewModel + repository interface per screen.** Each `viewmodel/<X>ViewModel.kt` declares
  `<X>UiState` (a `val`-only data class or sealed interface), a `sealed interface <X>Event`, an
  `interface <X>Repository`, and a `Default<X>Repository` that adapts the app singletons. The VM exposes
  `val state: StateFlow<…>` (usually `combine(...).stateIn`) and `fun onEvent(event)`. Koin wires the
  `Default*` impl in `di/AppModules.kt`, and tests pass a fake.
- **Process-death state.** Now, History, Apps and Data VMs take the nav entry's `SavedStateHandle` (a
  `get()` in Koin) and keep chart range, filter, selection, the History page limit, the Data range/toggles
  and pending export requests there.
- **Single writer.** `BatteryRepository` serializes every write through `HistoryWriter` (FIFO).
  `SamplingController` confines sampler state to its `HandlerThread`. `ShellRunner` serializes commands
  with a `Mutex`, and `AppStatsRepository` shares one in-flight dump between callers.
  `InsightRepository` and `InsightActionRepository` also serialize under a `Mutex`.
- **Work that must outlive a screen** (finding feedback) runs on the injected application
  `CoroutineScope`, never `viewModelScope` (`viewmodel/FindingDetailsViewModel.kt`).
- **Privileged writes are journalled**: record PREPARED, run a `CommandPolicy`-checked command, read the
  state back, then mark APPLIED/FAILED/UNKNOWN; startup `reconcile()` settles leftovers (see [insights.md](insights.md)).
- **Pure logic split out** into `battery/measurement/` and small `internal` helpers (e.g.
  `selectShellMode`, `shouldMarkContinuityLoss`, `IdleCountdown`) so they can be JVM-tested.
- **Seams for Android types:** `KeyValueStore` (SharedPreferences), `StatsShell` (shell),
  `SessionSnapshotStore`, `SamplerSink`, `SamplingDemand`, `MonitoringControl`.
- **Raw in, calibrated out.** Rows keep raw `CURRENT_NOW`. Calibration is applied to live flows and
  display only (`CalibrationStore`, `measurement/Calibration.kt`).
- **Never guess.** Unavailable values stay null and render as missing (`drain/DrainState.kt`). ETAs and
  capacity carry a basis/confidence (`EtaModel.kt`, `CapacityEstimator.kt`).

## Error handling and diagnostics
- Shell results are sealed (`ShellRunner.Outcome` Success/Failure/NoAccess, `ShizukuBridge.RunResult`,
  `AppStatsResult` Ready/NoAccess/Failed). Screens show retry panels, not crashes.
- `DiagnosticStore` / `DiagnosticLog` record **fixed codes only**: no commands, package names or
  exception messages (privacy). Log the exception *type* at most.
- Coroutine code rethrows `CancellationException` (e.g. `DataViewModel` resets its running state on it).
- The Koin app scope (`di/AppModules.kt`) has a `CoroutineExceptionHandler` that records
  `DiagnosticCode.APP_SCOPE_FAILED` instead of crashing the process.
- A corrupt settings DataStore is replaced with empty preferences (`settings/SettingsDataStore.kt`,
  `ReplaceFileCorruptionHandler`), so the app starts on defaults; no diagnostic is recorded for it. Any other read
  `IOException` yields defaults through `withDefaultsOnReadFailure()` (the unqualified Koin `DataStore`) and retries the read
  after 1 s (`retryWhen`), so settings collectors never crash the service and lifetime collectors still see later values; settings export uses the raw store (`named("rawSettingsDataStore")`) so it
  fails instead of writing an empty backup.
- Renamed from BatStats (2026-10-08): applicationId and namespace are `com.akane.voltwise`, but the settings-export
  `appId = "app.batstats"` (`di/AppModules.kt`), `DiagnosticLog.HEADER = "BatStatsDiagnostics1"`, the history export
  format and the DataStore/SharedPreferences file names keep their old values on purpose, so old backups still import.

## UI conventions
- Dark-only (`MainTheme(oled, dynamicColor)`). Dynamic color is opt-in on API 31+ and changes accents only.
- Tokens only: `MaterialTheme.colorScheme/typography/shapes`, `MaterialTheme.spacing`
  (`ui/theme/Spacing.kt`), `MaterialTheme.batColors`. No literal colors or raw dp in screens.
  Space Grotesk with tabular numbers (`ui/theme/Type.kt`).
- Screens split into a VM-bound `XScreen` and a stateless `XContent` that previews render.
  `ui/TestTags.kt` holds test tags.
- Formatting goes through `ui/format/Formats.kt`, locale-aware, with string templates from resources.
- Widgets and the notification are RemoteViews XML (`res/layout/widget_*.xml`, `notification_*.xml`) with
  their own `colors_widget.xml` / `dimens_*.xml`.
- Strings live in per-screen files `res/values{,-es,-tr}/strings_<screen>.xml`. Add all three locales.

## Testing
| Kind | Location | Run |
| --- | --- | --- |
| JVM unit (JUnit4, coroutines-test, hand-written fakes; no mocking library) | `app/src/test/java/com/akane/voltwise/**` | `./gradlew :app:testDebugUnitTest --console=plain -q` (narrow with `--tests '<Class>'`) |
| Screenshot (`@PreviewTest`, layoutlib, UTC/en-US pinned, 2 GB heap) | previews in `app/src/screenshotTest/kotlin/com/akane/voltwise/ui/**` (`ScreenshotPreviews.kt`, `screens/*ScreenshotTest.kt`, `components/…`); references in `app/src/screenshotTestDefaultDebug/reference/com/akane/voltwise/ui/**/<File>Kt/` | `bash .claude/scripts/run_screenshot_tests.sh` (wraps `:app:testDebugScreenshotTestDefaultTestSuite --rerun`) |
| Instrumented (device) | `app/src/androidTest/java/com/akane/voltwise/**`; Shizuku tests are marked `@RequiresShizuku` (`app/src/androidTest/java/com/akane/voltwise/test/`) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.akane.voltwise.test.RequiresShizuku` |
| Host scripts | `scripts/check_resources.py`, `check_migrations.py`, `check_history_queries.py` | `python3 scripts/<name>.py` |

- VM tests: `StandardTestDispatcher` + `Dispatchers.setMain`, a private `Fake<X>Repository` inside the test.
- `app/src/test/java/com/akane/voltwise/support/EnglishStrings.kt` resolves real English templates from
  `res/values/strings*.xml` in JVM tests.
- `testOptions.unitTests.isIncludeAndroidResources = true` (needed by the screenshot suite).
- Failing screenshots: the `screenshot-diff-triager` agent (`.claude/agents/`) and the
  `screenshot-rebaseline` skill (`.claude/skills/`).

## Style
- Kotlin official style, `val` by default, sealed interfaces for state/events, no `!!` outside tests.
  There is no formatter config (no `.editorconfig`, ktlint or detekt).
- KDoc explains *why* and the invariants, often citing the test that pins them. Comments are dense
  where behavior is subtle (sampler, writer, Shizuku). Match that density.
- `internal` for test-visible helpers; enums are stored by name.
