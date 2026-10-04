#!/usr/bin/env bash
# Capture the real installed Android app, never an HTML or search-result mockup.
# Build APKs first: gradle :app:assembleDebug :app:assembleDebugAndroidTest
# Optional: ANDROID_SERIAL=emulator-5554 ./scripts/record-live-search.sh
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_dir="${1:-$project_dir/artifacts/live-search}"
app_apk="${MYLO_APP_APK:-$project_dir/app/build/outputs/apk/debug/app-debug.apk}"
test_apk="${MYLO_TEST_APK:-$project_dir/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
adb_command="${ADB:-adb}"
remote_recording="/sdcard/mylo-live-search-$(date +%s).mp4"
recording_pid=""
original_ime_setting=""

for apk in "$app_apk" "$test_apk"; do
  if [[ ! -f "$apk" ]]; then
    echo "Missing APK: $apk. Build assembleDebug and assembleDebugAndroidTest first." >&2
    exit 1
  fi
done
mkdir -p "$artifact_dir"
rm -f "$artifact_dir/Mylo-live-search.mp4"
"$adb_command" get-state
"$adb_command" install -r "$app_apk"
"$adb_command" install -r -t "$test_apk"
original_ime_setting="$("$adb_command" shell settings get secure show_ime_with_hard_keyboard | tr -d '\r')"
"$adb_command" shell settings put secure show_ime_with_hard_keyboard 1

finish_capture() {
  if [[ -n "$recording_pid" ]]; then
    "$adb_command" shell kill -2 "$recording_pid" >/dev/null 2>&1 || true
    sleep 2
    "$adb_command" pull "$remote_recording" "$artifact_dir/Mylo-live-search.mp4" >/dev/null 2>&1 || true
    "$adb_command" shell rm -f "$remote_recording" >/dev/null 2>&1 || true
    recording_pid=""
  fi
  if [[ "$original_ime_setting" == "null" ]]; then
    "$adb_command" shell settings delete secure show_ime_with_hard_keyboard >/dev/null 2>&1 || true
  elif [[ -n "$original_ime_setting" ]]; then
    "$adb_command" shell settings put secure show_ime_with_hard_keyboard "$original_ime_setting" >/dev/null 2>&1 || true
  fi
  "$adb_command" pull /sdcard/Android/data/com.mylo.browser/files/test-artifacts/. "$artifact_dir/" >/dev/null 2>&1 || true
}
trap finish_capture EXIT

# The PID is the specific recorder started here; other device sessions are untouched.
recording_pid="$("$adb_command" shell "screenrecord --time-limit 180 $remote_recording >/dev/null 2>&1 & echo \$!" | tr -d '\r')"
"$adb_command" shell am instrument -w -r \
  -e class 'com.mylo.browser.LiveSearchFlowTest#googleSearchLoadsRealResultsAndBackReturnsHome' \
  com.mylo.browser.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$artifact_dir/instrumentation.txt"

if ! grep -q '^OK (1 test)' "$artifact_dir/instrumentation.txt"; then
  echo "Live verification failed or was blocked. See $artifact_dir/instrumentation.txt and live-search-evidence.json." >&2
  exit 1
fi
finish_capture
if [[ ! -s "$artifact_dir/Mylo-live-search.mp4" ]]; then
  echo "The flow test passed, but device recording failed. See $artifact_dir for screenshots and evidence." >&2
  exit 1
fi
echo "Verified real provider flow. Device screenshots, recording, and evidence: $artifact_dir"
