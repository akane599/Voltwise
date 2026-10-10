---
name: device-check
description: Run BatStats' Android 16 device validation locally, as CI does — ordinary instrumented tests, then Shizuku integration tests — on the disposable batstats-api36 emulator, and summarise the phase results and reports.
argument-hint: "[--prebuilt]"
disable-model-invocation: true
---

# Device validation on the disposable emulator

Wraps `scripts/check_android_device.sh`, the same entry point `.github/workflows/build-apk.yml` uses. The script installs a checksum-pinned official Shizuku (`scripts/prepare_shizuku.py`, needs network), uninstalls/reinstalls the app, and wipes `app/build/reports/device-validation/<group>/`. **Emulator only:** it refuses anything that isn't an `emulator-*` serial on API 36 with ranchu/goldfish hardware. Never point it at a physical phone. The dev host has no KVM, so no emulator runs there (CLAUDE.md → Devices): use this skill on a KVM machine, or rely on CI's device phases. The shell is zsh: redirect long output to a file and check `$?`.

1. **Emulator.** `adb devices`. If `batstats-api36` isn't running:
   `emulator -avd batstats-api36 -no-window -no-audio -gpu swiftshader_indirect &` then
   `adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'`.
   With several devices attached, `export ANDROID_SERIAL=emulator-5554` (the script requires an `emulator-*` serial).
2. **Run** (takes several minutes):
   - Default (Gradle builds and runs `connectedDebugAndroidTest` per phase):
     `bash scripts/check_android_device.sh > app/build/device-check.log 2>&1; echo $?`
   - `$ARGUMENTS` = `--prebuilt`: build first with `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain -q`, then run the script with `--prebuilt` (installs `app-universal-debug.apk` + the test APK and drives `am instrument` directly).
   - 16 KiB pages (CI's second job) need a 16 KB-page API 36 AVD; only then prefix `BATSTATS_REPORT_GROUP=16k BATSTATS_EXPECTED_PAGE_SIZE=16384`.
3. **Read results** from `app/build/reports/device-validation/standard/` (or the group you set):
   - `phase-status.txt` — `ordinary_exit` and `shizuku_exit`; both must be 0.
   - `device-info.txt`, `*-instrumentation.txt` (prebuilt mode) or `*-results/`, `*-html/` (Gradle mode).
   - `*-screenshots/` — the tests' own screenshots with simulated data; Read a few only if a phase failed.
   - On failure: `grep -E "FAILED|Exception|AssertionError" -A5` in the phase output, plus `adb logcat -d | grep -E "FATAL EXCEPTION" -A10 | head -40`.
4. **Report** one line per phase (exit, tests run/failed), the failing test names with their first assertion line, and log the run in the PROGRESS.md audit table (`Emulator QA` row).
