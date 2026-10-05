#!/usr/bin/env bash
# Exercise the installed Android app and collect evidence even when a provider blocks CI.
set -euo pipefail

apk_path="${1:?Usage: ci-emulator-smoke.sh APK_PATH OUTPUT_DIRECTORY TEST_APK_PATH}"
evidence_dir="${2:?Provide an output directory}"
test_apk_path="${3:?Provide the live-search instrumentation APK}"
app_package="com.mylo.browser"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$evidence_dir"
apk_path="$(realpath "$apk_path")"
evidence_dir="$(realpath "$evidence_dir")"
test_apk_path="$(realpath "$test_apk_path")"
original_ime_setting=""
failed=0

collect_diagnostics() {
  # Bounded, so an unresponsive emulator cannot hold the job until its time limit.
  timeout 30 adb logcat -d -v threadtime > "$evidence_dir/logcat.txt" 2>&1 || true
  timeout 30 adb shell dumpsys activity activities > "$evidence_dir/activities.txt" 2>&1 || true
  timeout 60 adb pull /sdcard/Android/data/com.mylo.browser/files/test-artifacts/. "$evidence_dir/" >/dev/null 2>&1 || true
  if [[ "$original_ime_setting" == "null" ]]; then
    timeout 15 adb shell settings delete secure show_ime_with_hard_keyboard >/dev/null 2>&1 || true
  elif [[ -n "$original_ime_setting" ]]; then
    timeout 15 adb shell settings put secure show_ime_with_hard_keyboard "$original_ime_setting" >/dev/null 2>&1 || true
  fi
}
trap collect_diagnostics EXIT

run_device_test() {
  local method="$1"
  local output="$2"
  # Android's instrumentation runner can return exit 0 even when JUnit fails.
  if ! adb shell am instrument -w -r \
    -e class "com.mylo.browser.LiveSearchFlowTest#$method" \
    com.mylo.browser.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$evidence_dir/$output"; then
    failed=1
  elif ! grep -q '^OK (1 test)' "$evidence_dir/$output"; then
    failed=1
  fi
}

adb wait-for-device
adb install -r "$apk_path"
adb install -r -t "$test_apk_path"
adb logcat -c
original_ime_setting="$(adb shell settings get secure show_ime_with_hard_keyboard | tr -d '\r')"
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am force-stop "$app_package"
adb shell am start -W -n "$app_package/.MainActivity" > "$evidence_dir/home-launch.txt"
sleep 3

if ! adb shell pidof "$app_package" > "$evidence_dir/app-pid.txt"; then
  echo 'Mylo did not stay running after launch.' >&2
  exit 1
fi
adb exec-out screencap -p > "$evidence_dir/Mylo-Home-Android.png"
if [[ ! -s "$evidence_dir/Mylo-Home-Android.png" ]]; then
  echo 'The Android Home screenshot is empty.' >&2
  exit 1
fi

# Keep layout and live-network results independent so either failure retains the other evidence.
# The final search flow, one screenshot per step: Home's Settings gear → choose the provider →
# back on Home, type into the same Home search box → keyboard Search → the provider's real results
# page (Google, then Yahoo), Yahoo again after Mylo is stopped and relaunched, and a typed domain
# opening directly. Each case runs alone in a fresh app process with its own time limit and its
# evidence is pulled straight away, so one stalled page cannot hide the other cases' results.
flow_status=0
flow_summary="$evidence_dir/provider-flow-test.txt"
: > "$flow_summary"
adb logcat -c || true
adb logcat -v threadtime > "$evidence_dir/provider-flow-logcat.txt" 2>&1 &
flow_logcat_pid=$!
for flow_case in googleFromSettings yahooFromSettings savedYahooAfterRelaunch domainOpensDirectly; do
  if ! timeout 15 adb shell true >/dev/null 2>&1; then
    echo "$flow_case: not run (emulator stopped responding)" | tee -a "$flow_summary"; flow_status=1; continue
  fi
  # The relaunch case must find only what Mylo saved, so stop the app first.
  [[ "$flow_case" == savedYahooAfterRelaunch ]] && timeout 15 adb shell am force-stop "$app_package" || true
  case_output="$evidence_dir/provider-flow-$flow_case.txt"
  timeout 130 adb shell am instrument -w -r -e class "com.mylo.browser.SettingsSearchFlowTest#$flow_case" \
    com.mylo.browser.test/androidx.test.runner.AndroidJUnitRunner > "$case_output" 2>&1 || true
  if grep -q '^OK (1 test)' "$case_output"; then flow_result=passed; else flow_result='failed or blocked'; flow_status=1; fi
  echo "$flow_case: $flow_result" | tee -a "$flow_summary"
  [[ "$flow_result" == passed ]] || grep -E '^(INSTRUMENTATION_(STATUS: stack|RESULT|CODE|ABORTED)|java\.|junit\.|Process crashed)' "$case_output" \
    | cut -c1-400 | head -n 8 | sed 's/^/    /' >> "$flow_summary" || true
  timeout 60 adb pull /sdcard/Android/data/com.mylo.browser/files/test-artifacts/provider-flow "$evidence_dir/" >/dev/null 2>&1 || true
done
kill "$flow_logcat_pid" 2>/dev/null || true
# Error-level AndroidRuntime, system Watchdog and actual lmkd kills only: every `am instrument` call
# logs informational AndroidRuntime lines, and PackageWatchdog, keystore and lmkd setup chatter is routine.
{ echo 'Crash, renderer and memory events during the flow:'
  grep -E 'FATAL EXCEPTION| [EF] AndroidRuntime: |Render process|renderer.*(crash|gone)|lowmemorykiller: Kill|ANR in|Process com\.mylo\.browser.* has died| [WEF] Watchdog: ' \
    "$evidence_dir/provider-flow-logcat.txt" | cut -c1-300 | tail -n 30 || echo '  none recorded'
} >> "$flow_summary"
if [[ "${MYLO_SCOPE:-full}" == "search-flow" ]]; then
  if (( flow_status )); then echo 'Search-flow verification failed or was blocked. See provider-flow evidence.' >&2; exit 1; fi
  echo 'Verified Settings → provider → Home search box → real Google and Yahoo results, Yahoo kept after relaunch, and facebook.com opened directly.'
  exit 0
fi
(( flow_status )) && failed=1

if ! bash "$script_dir/verify-home-layout.sh" "$apk_path" "$evidence_dir/home-layout" "$test_apk_path"; then
  failed=1
fi
run_device_test searchInputFocusKeyboardAndProviderPersistence provider-selection-test.txt
# This helper records the actual device during Home → input/keyboard → picker → live results.
if ! MYLO_APP_APK="$apk_path" MYLO_TEST_APK="$test_apk_path" \
  bash "$script_dir/record-live-search.sh" "$evidence_dir"; then
  failed=1
fi
run_device_test directUrlsBackForwardAndHomeReuseCurrentTab direct-url-navigation-test.txt
run_device_test switchingTabsPreservesEachBackForwardHistory per-tab-navigation-test.txt
run_device_test allRealSearchProvidersLoadInsideCurrentTab all-providers-test.txt

if (( failed )); then
  echo 'One or more device checks failed or were blocked. See the recording, screenshots, test output, and JSON evidence.' >&2
  exit 1
fi
echo 'Verified search input, Android keyboard, all five live providers, direct URLs, Back/Forward, and independent tab history.'
