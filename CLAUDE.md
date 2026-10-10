<!-- android-kit -->
# CLAUDE.md — Voltwise (formerly BatStats)

Battery readings, observed charging/discharging sessions and privileged per-app statistics for Android users.
Android, developed on Ubuntu from the CLI.

<!-- STACK:BEGIN -->
- Origin: existing · Integration branch: main (origin/HEAD); current work branch: feat/insights
- Language: Kotlin only (no Java) · New code in Kotlin
- UI: Compose Material 3, dark-only + OLED; 6 RemoteViews XML layouts · screenshot tests: compose-preview, AGP screenshotTest suite (0.0.1-alpha16)
- UI profile: previews 12 (detector count) · dynamic color opt-in (API 31+, accents only) · custom typography Space Grotesk, numeric tnum · literal colors outside theme 0
- JDK target 21 · JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64; not pinned in gradle.properties · Gradle 9.7.1 · AGP 9.5.0-alpha07 · Kotlin 2.4.20 · compileSdk 37 · minSdk 26
- Modules: :app · Architecture: single-module MVVM; ui/, viewmodel/, data/, di/, settings/ + battery/ feature packages
- DI/DB/Net: Koin / Room / none · Async: coroutines/Flow · Navigation: Navigation 3 · Firebase: no · Version catalog: yes
- App id: com.akane.voltwise (debug .debug, preview .preview; was org.mlm.batstats before 2026-10-08) · namespace: com.akane.voltwise (was app.batstats) · Launcher: com.akane.voltwise.battery.BatteryMainActivity
- Tests: JUnit4 + kotlinx-coroutines-test; androidTest present, no Espresso · Lint: Android lint; no detekt/ktlint/spotless
- Baseline: full gate green on feat/insights @ 6d81b43 (2026-10-10): unit 1795/0 fail, androidTest compile, assembleDebug, 274 screenshots/0 fail, migrations; assemblePreview last OK @ 257ddc4
<!-- STACK:END -->

## How work flows here
- **The main session orchestrates; it doesn't implement.** Work becomes Sidequest tickets, executors do it in isolated worktrees on the model each ticket's category routes to (Claude, or GPT through Model Gateway), and the main session integrates after verification. Quick one-line edits to named files stay inline.
- **Routing** (Sidequest profile `android-kit`; `sidequest models` shows it live): GPT implements (GPT-6 Luna mechanical, GPT-6.1 Sol default, GPT-6 Astra hard via `coding.hard.frontier`); UI, review and escalation stay on Claude (Opus, Fable only after Opus stalls); research on Sonnet. UI is never routed to GPT.
- **Big or multi-step changes:** plan mode → `/plan-audit` (routed review ticket with the Android checklist) → pin the plan as a Sidequest story → dispatch waves.
- **UI overhaul:** `/ui-overhaul <scope>`.
- **Bugs:** every ticket's tests must pass before it's integrated; risky ones (weak tests, unchecked callers, high-stakes) also get an Opus review. `/bug-hunt [area]` hunts in existing code; `/code-review` checks a diff before you push.
- **End of a session or story:** `/wrap-up` turns corrections and decisions into rules, CLAUDE.md lines and the decision log (you approve each). Personal preferences go to auto memory ("remember that …").
- **Conventions** live in `.claude/live-rules/rules/` and are injected into every session and executor, so they're not repeated here. **Project map:** `.claude/.codebase-info/` (codebase-mapper).
- **Board:** "show the Sidequest board"; side issues mentioned mid-task get filed, not worked.
- **Plugin health / updates:** `/quartermaster:toolshed-doctor`, `/quartermaster:update-toolshed`. After a few weeks of real work: `/quartermaster:resupply`.
- **Compaction:** your last instructions are saved before compaction and re-injected after; re-read them before acting.

## Environment
- ANDROID_HOME=/home/dev/android-sdk; use the environment SDK, never create/edit local.properties.
- LSP: official JetBrains kotlin-lsp at /home/dev/.local/bin/kotlin-lsp; no Kotlin LSP install needed.
- Devices: no AVD possible (KVM unavailable). Device QA uses the user's phone through a reverse-forwarded adb server: the user runs `adb start-server` locally with the phone attached, then `ssh -R 5037:127.0.0.1:5037 dev@<host>`; here `adb devices` must list it before any device ticket. Both sides need the same platform-tools version (host: adb 1.0.41), or the client restarts the user's server.
- Disk: repo, Android SDK and Gradle user home (`~/.gradle` → /mnt/HC_Volume_106903020/gradle-home) live on the volume; Sidequest worktrees stay on the small root disk.

## Commands
- Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug --console=plain -q`
- Unit: `./gradlew :app:testDebugUnitTest --console=plain -q` · Lint: `./gradlew :app:lintDebug --console=plain -q`
- Preview APK: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assemblePreview --console=plain -q` — minified, debug-key signed unless `PREVIEW_*` env vars are set, installs as `.preview`; split APKs in `app/build/outputs/apk/preview/` (`app-universal-preview.apk`, `app-arm64-v8a-preview.apk`). Release needs `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`.
- Gradle helper: `bash .claude/kit/gradle-check.sh :app:assembleDebug` (retains full log and actual exit code; uses --console=plain -q).
- Screenshots: `bash .claude/scripts/run_screenshot_tests.sh` runs `:app:testDebugScreenshotTestDefaultTestSuite --rerun --console=plain -q`; the template's updateDebugScreenshotTest/validateDebugScreenshotTest tasks do not match this AGP suite.
- Instrumented: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.akane.voltwise.test.RequiresShizuku --console=plain -q` (requires a device; Shizuku tests are a separate phase).
- Device: `./gradlew :app:installDebug --console=plain -q && adb shell am start -n com.akane.voltwise.debug/com.akane.voltwise.battery.BatteryMainActivity`
- Logs: `adb logcat -d --pid=$(adb shell pidof -s com.akane.voltwise.debug) | tail -80`
- Emulator: none on this host (see Devices); the emulator phases run in CI (`.github/workflows/build-apk.yml`).
- CI logs: `gh run view --log-failed` comes back empty here; use `gh api repos/akane599/Voltwise/actions/jobs/<job-id>/logs`. Device-phase results: artifact `validation-reports-*` → `reports/device-validation/standard/ordinary-results/*.xml` and `phase-status.txt` (`ordinary_exit`, `shizuku_exit`).
- Known CI flake: the 16 KB page-size emulator step can fail with `Error on ZipFile unknown archive` (corrupt system-image download, emulator never boots); check `ordinary_exit`/`shizuku_exit` are 0, then `gh run rerun <run-id> --failed`.
