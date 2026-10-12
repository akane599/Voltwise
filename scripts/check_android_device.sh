#!/usr/bin/env bash
# Run only against a disposable Android16 emulator. Screenshots contain simulated test data.
set -euo pipefail
batstats_prebuilt=false
case "${1:-}" in
  '') ;;
  --prebuilt) batstats_prebuilt=true ;;
  *) echo 'Usage: bash scripts/check_android_device.sh [--prebuilt]' >&2; exit 2 ;;
esac
batstats_serial="${ANDROID_SERIAL:-$(adb get-serialno)}"
[[ "$batstats_serial" == emulator-* ]] || { echo 'Select a disposable emulator with ANDROID_SERIAL.' >&2; exit 1; }
export ANDROID_SERIAL="$batstats_serial"
batstats_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$batstats_api" == 36 ]]
batstats_hardware="$(adb shell getprop ro.hardware | tr -d '\r')"
[[ "$batstats_hardware" == ranchu || "$batstats_hardware" == goldfish ]]
batstats_boot_completed="$(adb shell getprop sys.boot_completed | tr -d '\r')"
[[ "$batstats_boot_completed" == 1 ]] || {
  echo 'Android has not completed boot; no device tests were started.' >&2
  exit 1
}
batstats_page_size="$(adb shell getconf PAGE_SIZE | tr -d '\r')"
[[ "$batstats_page_size" == 4096 || "$batstats_page_size" == 16384 ]] || {
  echo "Unexpected Android page size: $batstats_page_size" >&2; exit 1
}
if [[ -n "${BATSTATS_EXPECTED_PAGE_SIZE:-}" && "$batstats_page_size" != "$BATSTATS_EXPECTED_PAGE_SIZE" ]]; then
  echo "Expected $BATSTATS_EXPECTED_PAGE_SIZE-byte pages, found $batstats_page_size; no tests started." >&2
  exit 1
fi
batstats_report_group="${BATSTATS_REPORT_GROUP:-standard}"
[[ "$batstats_report_group" =~ ^[a-z0-9-]+$ ]] || { echo 'Invalid device report group.' >&2; exit 1; }
batstats_report_dir="app/build/reports/device-validation/$batstats_report_group"
batstats_annotation=com.akane.voltwise.test.RequiresShizuku
batstats_cadence=com.akane.voltwise.battery.data.sampling.SamplingControllerCadenceDeviceTest
batstats_on="$batstats_cadence#screenOnLastReleaseAfterStampProducesImmediateHandoff"
batstats_off="$batstats_cadence#screenOffLastReleaseAfterStampProducesImmediateHandoff"
batstats_shizuku=com.akane.voltwise.battery.shizuku.ShizukuDeviceTest#shellServiceAuthorizationHelperRestartAndAccessLossPreserveOrdinaryReadings
batstats_phase=initialization
batstats_prepare_result=0
batstats_metadata_result=0
batstats_remove_result=0
batstats_install_result=1
batstats_restore_result=0
batstats_ordinary_inventory=1
batstats_shizuku_inventory=1
batstats_core_result=1
batstats_on_result=1
batstats_off_result=1
batstats_shizuku_result=1
batstats_ordinary_result=1
batstats_apks_ready=false

write_phase_status() {
  batstats_ordinary_result=0
  local result
  for result in "$batstats_prepare_result" "$batstats_metadata_result" "$batstats_install_result" "$batstats_ordinary_inventory" "$batstats_remove_result" "$batstats_core_result" "$batstats_restore_result" "$batstats_on_result" "$batstats_off_result"; do
    if [[ "$batstats_ordinary_result" == 0 && "$result" != 0 ]]; then batstats_ordinary_result="$result"; fi
  done
  printf 'apk_prepare_exit=%s\napk_metadata_exit=%s\napk_remove_exit=%s\ninstall_exit=%s\napk_restore_exit=%s\nordinary_inventory_exit=%s\nshizuku_inventory_exit=%s\ncore_exit=%s\nscreen_on_exit=%s\nscreen_off_exit=%s\nordinary_exit=%s\nshizuku_exit=%s\n' \
    "$batstats_prepare_result" "$batstats_metadata_result" "$batstats_remove_result" \
    "$batstats_install_result" "$batstats_restore_result" "$batstats_ordinary_inventory" "$batstats_shizuku_inventory" \
    "$batstats_core_result" "$batstats_on_result" "$batstats_off_result" "$batstats_ordinary_result" "$batstats_shizuku_result" \
    | tee "$batstats_report_dir/phase-status.txt"
}
collect_screenshots() {
  adb pull /sdcard/Download/batstats-validation-screenshots "${batstats_report_dir}/" >/dev/null 2>&1 || true
  # AGP collects these before uninstall, even when connected tests fail.
  local collected=app/build/outputs/connected_android_test_additional_output
  if [[ -d "$collected" ]]; then
    mkdir -p "${batstats_report_dir}/${batstats_phase}-screenshots"
    cp -R "$collected"/. "${batstats_report_dir}/${batstats_phase}-screenshots/"
  fi
}
collect_logcat() {
  adb logcat -d -b crash > "${batstats_report_dir}/${batstats_phase}-crash-logcat.txt" 2>/dev/null || true
  adb logcat -d -v threadtime -t 4000 > "${batstats_report_dir}/${batstats_phase}-logcat-tail.txt" 2>/dev/null || true
}
wait_for_power() {
  local expected="$1" power states ready attempts=0 deadline=$((SECONDS + 10))
  # Readiness polling only: no arbitrary delay and no retry of a test invocation.
  while (( attempts < 20 && SECONDS <= deadline )); do
    attempts=$((attempts + 1))
    power="$(timeout 2s adb shell dumpsys power)" || return 1
    power="${power//$'\r'/}"
    printf '%s\n' "$power" > "${batstats_report_dir}/${batstats_phase}-power.txt" || return 1
    # Dumps can include both global and power-group wakefulness. Require every
    # reported group to agree, rather than treating one matching line as ready.
    states="$(grep -Ec '^[[:space:]]*mWakefulness=' <<< "$power")" || states=0
    ready="$(grep -Ec "^[[:space:]]*mWakefulness=${expected}[[:space:]]*$" <<< "$power")" || ready=0
    if [[ "$states" != 0 && "$states" == "$ready" ]]; then
      return 0
    fi
  done
  echo "Could not confirm $expected power state for $batstats_phase." >&2
  return 1
}
wake_device() {
  timeout 5s adb shell input keyevent 224 || return 1
  timeout 5s adb shell wm dismiss-keyguard || return 1
  wait_for_power Awake
}
sleep_device() {
  timeout 5s adb shell input keyevent 223 || return 1
  wait_for_power Asleep
}
cleanup() {
  local result=$? restoration=0
  trap - EXIT
  collect_screenshots || true
  [[ -f "$batstats_report_dir/phase-status.txt" ]] || write_phase_status || true
  batstats_phase=cleanup
  wake_device || restoration=1
  printf 'cleanup_exit=%s\n' "$restoration" >> "$batstats_report_dir/phase-status.txt" || restoration=1
  if [[ "$result" == 0 && "$restoration" != 0 ]]; then result=1; fi
  exit "$result"
}
# No EXIT mutation is armed until EVERY disposable/API/page/report guard succeeds.
trap cleanup EXIT
# Only this script's generated reports and screenshots are replaced.
rm -rf "$batstats_report_dir" app/build/outputs/connected_android_test_additional_output
mkdir -p "$batstats_report_dir"
printf 'api=36\nhardware=%s\npage_size=%s\n' "$batstats_hardware" "$batstats_page_size" > "$batstats_report_dir/device-info.txt"
adb shell rm -rf /sdcard/Download/batstats-validation-screenshots
adb logcat -c 2>/dev/null || true
adb logcat -b crash -c 2>/dev/null || true

capture_apk_metadata() {
  local stage="$1" signer candidate name apk
  signer="$(command -v apksigner)" || signer=''
  if [[ -z "$signer" ]]; then
    # Use an already-installed SDK tool; never install tooling or inspect keys.
    for candidate in "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"/build-tools/*/apksigner; do
      if [[ -x "$candidate" ]]; then signer="$candidate"; fi
    done
  fi
  [[ -n "$signer" ]] || { echo 'Installed apksigner is required for public APK receipts.' >&2; return 1; }
  for name in app test; do
    if [[ "$name" == app ]]; then
      apk=app/build/outputs/apk/debug/app-universal-debug.apk
    else
      apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    fi
    sha256sum "$apk" > "${batstats_report_dir}/${stage}-${name}-sha256.txt" || return $?
    grep -Eq '^[[:xdigit:]]{64}  ' "${batstats_report_dir}/${stage}-${name}-sha256.txt" || return 1
    "$signer" verify --print-certs "$apk" > "${batstats_report_dir}/${stage}-${name}-signer.txt" || return $?
    grep -Eq '^Signer #[0-9]+ certificate SHA-256 digest: [[:xdigit:]]{64}$' \
      "${batstats_report_dir}/${stage}-${name}-signer.txt" || return 1
  done
}
remove_inventoried_apks() {
  local package output
  # Fixed disposable fixture packages only. No mismatch retry or retained app data.
  for package in com.akane.voltwise.debug.test com.akane.voltwise.debug; do
    output="$(adb uninstall "$package")" || return $?
    printf '%s\n' "$output" >> "${batstats_report_dir}/apk-removal.txt" || return 1
    [[ "${output//$'\r'/}" == Success ]] || return 1
  done
}
install_apks() {
  # Use the current output pair. Never install between sleep and raw off.
  adb install -r app/build/outputs/apk/debug/app-universal-debug.apk || return $?
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
}
capture_raw() {
  local phase="$1"
  shift
  adb shell am instrument -w -r -e expectedPageSize "$batstats_page_size" "$@" \
    com.akane.voltwise.debug.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "${batstats_report_dir}/${phase}-instrumentation.txt"
}
discover_inventory() {
  local phase="$1"
  shift
  capture_raw "inventory-$phase" -e log true "$@" || return $?
  python3 scripts/check_instrumentation_result.py "inventory-$phase" \
    "${batstats_report_dir}/inventory-${phase}-instrumentation.txt" "$batstats_report_dir"
}
revalidate_inventories() {
  local result=0 phase
  # Compare independent FULL discoveries to the ORIGINAL expected files. Never
  # call inventory-* parsers here: they write/replace expected authority.
  for phase in ordinary shizuku; do
    if [[ "$phase" == ordinary ]]; then
      capture_raw inventory-restored-ordinary -e log true -e notAnnotation "$batstats_annotation" || { result=1; continue; }
    else
      capture_raw inventory-restored-shizuku -e log true -e annotation "$batstats_annotation" || { result=1; continue; }
    fi
    python3 scripts/check_instrumentation_result.py raw \
      "${batstats_report_dir}/inventory-restored-${phase}-instrumentation.txt" \
      --expected "${batstats_report_dir}/${phase}-identities.txt" || result=1
  done
  return "$result"
}
run_raw_phase() {
  capture_raw "$batstats_phase" "$@" || return $?
  python3 scripts/check_instrumentation_result.py raw \
    "${batstats_report_dir}/${batstats_phase}-instrumentation.txt" \
    --expected "${batstats_report_dir}/${batstats_phase}-identities.txt"
}
run_gradle_core() {
  local result=0 checked=0 canonical=app/build/outputs/androidTest-results/connected/debug
  # Fresh canonical AGP XML is the ONLY connected-result authority. HTML/mirrors
  # remain diagnostics and cannot rescue a missing, assumed, or conflicting case.
  rm -rf app/build/outputs/androidTest-results app/build/reports/androidTests \
    app/build/outputs/connected_android_test_additional_output || return 1
  ./gradlew :app:connectedDebugAndroidTest --no-daemon --no-configuration-cache --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.expectedPageSize="$batstats_page_size" \
    -Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=/sdcard/Download/batstats-validation-screenshots \
    -Pandroid.testInstrumentationRunnerArguments.notAnnotation="$batstats_annotation" \
    -Pandroid.testInstrumentationRunnerArguments.notClass="$batstats_cadence" || result=$?
  if [[ -d "$canonical" ]]; then
    cp -R "$canonical" "${batstats_report_dir}/core-results" || checked=1
  fi
  python3 scripts/check_instrumentation_result.py xml "${batstats_report_dir}/core-results" \
    --expected "${batstats_report_dir}/core-identities.txt" || checked=1
  if [[ -d app/build/reports/androidTests ]]; then
    cp -R app/build/reports/androidTests "${batstats_report_dir}/core-html" || checked=1
  fi
  collect_screenshots || checked=1
  if [[ "$result" != 0 ]]; then return "$result"; fi
  return "$checked"
}

if ! "$batstats_prebuilt"; then
  # Prepare in the SAME connected build environment, strictly behind all guards.
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --no-configuration-cache --stacktrace \
    || batstats_prepare_result=$?
  if [[ "$batstats_prepare_result" == 0 ]]; then
    capture_apk_metadata before-core || batstats_metadata_result=$?
  fi
fi
if [[ "$batstats_prepare_result" == 0 && "$batstats_metadata_result" == 0 ]] && install_apks; then
  batstats_install_result=0
  batstats_apks_ready=true
  # FULL ordinary discovery has NO cadence exclusion. Do not infer inventory from
  # core results. The separate annotation discovery pins exact privileged identity.
  discover_inventory ordinary -e notAnnotation "$batstats_annotation" && batstats_ordinary_inventory=0
  discover_inventory shizuku -e annotation "$batstats_annotation" && batstats_shizuku_inventory=0
else
  batstats_install_result=$?
fi
if "$batstats_apks_ready" && [[ "$batstats_ordinary_inventory" == 0 ]] \
  && { "$batstats_prebuilt" || [[ "$batstats_shizuku_inventory" == 0 ]]; }; then
  batstats_phase=core
  if wake_device; then
    if "$batstats_prebuilt"; then
      run_raw_phase -e notAnnotation "$batstats_annotation" -e notClass "$batstats_cadence" && batstats_core_result=0 || batstats_core_result=$?
    else
      # Discovery owns the original expected sets; AGP now owns a FRESH install.
      # Partial/failed removal leaves no authority to execute any raw phase.
      batstats_apks_ready=false
      if remove_inventoried_apks; then
        run_gradle_core && batstats_core_result=0 || batstats_core_result=$?
        # Retain public receipts from current AGP outputs, restore the pair, and
        # revalidate BOTH FULL inventories before any raw controls/off preparation.
        capture_apk_metadata after-core || batstats_metadata_result=$?
        if [[ "$batstats_metadata_result" == 0 ]] && install_apks && revalidate_inventories; then
          batstats_apks_ready=true
        else
          batstats_restore_result=$?
        fi
      else
        batstats_remove_result=$?
      fi
    fi
    [[ "$batstats_core_result" == 0 ]] || collect_logcat
    batstats_phase=screen-on
    # Core tests may themselves manipulate power; re-establish on readiness.
    if "$batstats_apks_ready" && wake_device; then
      run_raw_phase -e class "$batstats_on" && batstats_on_result=0 || batstats_on_result=$?
      collect_screenshots
      [[ "$batstats_on_result" == 0 ]] || collect_logcat
      batstats_phase=screen-off
      if sleep_device; then
        # No install, setup, connected task, or wake between this check and execution.
        run_raw_phase -e class "$batstats_off" && batstats_off_result=0 || batstats_off_result=$?
      fi
      [[ "$batstats_off_result" == 0 ]] || collect_logcat
    fi
  fi
fi
# Independent privileged evidence still runs after ordinary assertion/parser errors,
# but never without APK/inventory authority and a confirmed safe interactive state.
batstats_phase=shizuku
if "$batstats_apks_ready" && wake_device && [[ "$batstats_shizuku_inventory" == 0 ]]; then
  collect_screenshots
  rm -rf app/build/outputs/connected_android_test_additional_output
  adb shell rm -rf /sdcard/Download/batstats-validation-screenshots
  if python3 scripts/prepare_shizuku.py --serial "$batstats_serial"; then
    run_raw_phase -e class "$batstats_shizuku" && batstats_shizuku_result=0 || batstats_shizuku_result=$?
  else
    batstats_shizuku_result=$?
    echo 'Shizuku setup failed; integration assertions could not run.' >&2
  fi
fi
[[ "$batstats_shizuku_result" == 0 ]] || collect_logcat
write_phase_status
[[ "$batstats_ordinary_result" == 0 && "$batstats_shizuku_result" == 0 ]]
