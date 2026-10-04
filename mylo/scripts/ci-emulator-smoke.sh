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
  adb logcat -d -v threadtime > "$evidence_dir/logcat.txt" 2>&1 || true
  adb shell dumpsys activity activities > "$evidence_dir/activities.txt" 2>&1 || true
  adb pull /sdcard/Android/data/com.mylo.browser/files/test-artifacts/. "$evidence_dir/" >/dev/null 2>&1 || true
  if [[ "$original_ime_setting" == "null" ]]; then
    adb shell settings delete secure show_ime_with_hard_keyboard >/dev/null 2>&1 || true
  elif [[ -n "$original_ime_setting" ]]; then
    adb shell settings put secure show_ime_with_hard_keyboard "$original_ime_setting" >/dev/null 2>&1 || true
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
  elif ! rg -q '^OK \(1 test\)' "$evidence_dir/$output"; then
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
