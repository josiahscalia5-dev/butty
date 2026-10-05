#!/usr/bin/env bash
# Targeted browser polish: existing Yahoo URL first, Google, direct domain, navigation and Shield.
set -euo pipefail
apk_path="${1:?Provide the app APK}"
evidence_dir="${2:?Provide an evidence directory}"
test_apk_path="${3:?Provide the test APK}"
mkdir -p "$evidence_dir"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
trace_pid=""
collect() {
  if [[ -n "$trace_pid" ]]; then kill "$trace_pid" 2>/dev/null || true; fi
  timeout -k 2s 10s adb pull /sdcard/Android/data/com.mylo.browser/files/test-artifacts/settings-search "$evidence_dir/" >/dev/null 2>&1 || true
  timeout -k 2s 8s adb logcat -d -v threadtime > "$evidence_dir/logcat.txt" 2>&1 || true
  timeout -k 2s 5s adb shell am force-stop com.mylo.browser >/dev/null 2>&1 || true
}
trap collect EXIT
timeout 30s adb install -r "$apk_path"
timeout 30s adb install -r -t "$test_apk_path"
timeout 5s adb shell settings put secure show_ime_with_hard_keyboard 1
timeout 5s adb shell settings put system accelerometer_rotation 0
timeout 5s adb shell settings put system user_rotation 0
timeout 5s adb shell wm size 1080x2340
timeout 5s adb shell wm density 440
timeout 5s adb shell input keyevent KEYCODE_WAKEUP
timeout 5s adb shell wm dismiss-keyguard
# A freshly booted Google APIs image can leave Pixel Launcher ANRing over the app.
# It is not used by this test: ActivityScenario launches Mylo directly.
timeout 5s adb shell am force-stop com.google.android.apps.nexuslauncher
# Only requested flows. One baseline Yahoo submission and one diagnostic reload;
# CAPTCHA/bot pages get passive observation, never retries or a UA change.
for method in yahoo google domainOpensDirectly browserBackForward shieldState; do
  timeout -k 2s 5s adb shell am force-stop com.mylo.browser
  limit=60
  if [[ "$method" == yahoo ]]; then
    node "$script_dir/capture-yahoo-network.mjs" "$evidence_dir/yahoo-network.json" > "$evidence_dir/yahoo-network-recorder.txt" 2>&1 &
    trace_pid=$!
    limit=75
  fi
  timeout -k 2s "${limit}s" adb shell am instrument -w -r \
    -e class "com.mylo.browser.SettingsSearchFlowTest#$method" \
    com.mylo.browser.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$evidence_dir/$method.txt"
  if [[ -n "$trace_pid" ]]; then wait "$trace_pid"; trace_pid=""; fi
  timeout -k 2s 10s adb pull /sdcard/Android/data/com.mylo.browser/files/test-artifacts/settings-search "$evidence_dir/" >/dev/null 2>&1 || true
  if ! grep -q '^OK (1 test)' "$evidence_dir/$method.txt"; then
    echo "Stopped after $method failed; inspect evidence before running again." >&2
    exit 1
  fi
done
echo 'Targeted Google/Yahoo routing, direct domain, toolbar Back/Forward, portrait fit and Shield checks passed. Inspect Yahoo response evidence for provider-side limitations.'
