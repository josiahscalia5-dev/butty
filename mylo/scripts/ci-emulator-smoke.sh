#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?Usage: ci-emulator-smoke.sh APK_PATH OUTPUT_DIRECTORY [TEST_APK_PATH]}"
evidence_dir="${2:?Provide an output directory}"
test_apk_path="${3:-}"
app_package="com.mylo.browser"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$evidence_dir"
apk_path="$(realpath "$apk_path")"
evidence_dir="$(realpath "$evidence_dir")"

collect_diagnostics() {
  adb logcat -d -v threadtime > "$evidence_dir/logcat.txt" 2>&1 || true
  adb shell dumpsys activity activities > "$evidence_dir/activities.txt" 2>&1 || true
}
trap collect_diagnostics EXIT

adb wait-for-device
adb install -r "$apk_path"
adb logcat -c
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

if [[ -n "$test_apk_path" && -f "$test_apk_path" ]]; then
  test_apk_path="$(realpath "$test_apk_path")"
  adb install -r -t "$test_apk_path"
  adb shell am instrument -w -r \
    -e class 'com.mylo.browser.LiveSearchFlowTest#searchEngineSettingsPersistEverySupportedProvider' \
    com.mylo.browser.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$evidence_dir/provider-settings-test.txt"
  if ! rg -q '^OK \(1 test\)' "$evidence_dir/provider-settings-test.txt"; then
    echo 'The on-device search provider settings test failed.' >&2
    exit 1
  fi
  # The recording helper owns the real provider navigation and instrumentation run.
  MYLO_APP_APK="$apk_path" MYLO_TEST_APK="$test_apk_path" \
    bash "$script_dir/record-live-search.sh" "$evidence_dir"
else
  echo 'Home launch passed. Live search instrumentation APK was unavailable.' > "$evidence_dir/live-search-unavailable.txt"
fi
