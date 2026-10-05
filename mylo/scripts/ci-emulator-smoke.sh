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

# Private Mode: the approved screen, private browsing on the local test site (tracker blocking, separate
# cookies, Burn, no history), Lock tabs with a real screen lock, and a recording of the entrance animation.
if [[ "${MYLO_SCOPE:-full}" == "private" ]]; then
  private_status=0
  private_dir="$evidence_dir/private"
  private_summary="$evidence_dir/private-test.txt"
  mkdir -p "$private_dir"
  : > "$private_summary"
  python3 "$script_dir/../compat-site/serve.py" > "$private_dir/test-site.log" 2>&1 &
  site_pid=$!
  adb reverse tcp:8080 tcp:8080
  adb reverse tcp:8081 tcp:8081
  sleep 2
  curl -sf http://localhost:8080/trackers.html > /dev/null || { echo 'The test site did not start.' >&2; exit 1; }
  run_private() {
    local method="$1" limit="$2"
    timeout 15 adb shell am force-stop "$app_package" || true
    timeout 15 adb logcat -c || true
    local output="$private_dir/$method.txt"
    timeout "$limit" adb shell am instrument -w -r -e class "com.mylo.browser.PrivateModeFlowTest#$method" \
      com.mylo.browser.test/androidx.test.runner.AndroidJUnitRunner > "$output" 2>&1 || true
    # Android's own record of activities, windows and input during the case (no page content).
    timeout 30 adb logcat -d -b events -v time 2> /dev/null | grep -E '(wm|am)_[a-z_]+' | tail -n 300 > "$private_dir/$method-android-events.txt" || true
    timeout 30 adb logcat -d -v time 2> /dev/null | grep -E 'ActivityTaskManager|ActivityManager|InputDispatcher|AndroidRuntime|InputMethod|ImeTracker|LifecycleMonitor' | grep -vE 'AppsFilter|WindowManagerShell' | tail -n 400 > "$private_dir/$method-android-system.txt" || true
    local result
    if grep -q '^OK (1 test)' "$output"; then result=passed; else result=failed; private_status=1; fi
    echo "$method: $result" | tee -a "$private_summary"
    [[ "$result" == passed ]] || grep -E '^(INSTRUMENTATION_(STATUS: stack|RESULT|CODE|ABORTED)|java\.|junit\.|Process crashed)' "$output" \
      | cut -c1-500 | head -n 8 | sed 's/^/    /' >> "$private_summary" || true
    timeout 60 adb pull /sdcard/Android/data/com.mylo.browser/files/test-artifacts/private/. "$private_dir/" > /dev/null 2>&1 || true
  }
  run_private privateSessionIsSeparateBlocksTrackersAndBurns 300
  run_private lockTabsNeedsTheScreenLock 240
  # The entrance animation needs Android's animations, which the emulator runs with off.
  for setting in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global "$setting" 1; done
  timeout 15 adb shell rm -f /sdcard/Movies/private-entrance.mp4 || true
  adb shell screenrecord --bit-rate 6000000 --time-limit 25 /sdcard/Movies/private-entrance.mp4 > /dev/null 2>&1 &
  recorder=$!
  sleep 1
  run_private entranceAnimation 120
  timeout 10 adb shell pkill -INT screenrecord > /dev/null 2>&1 || true
  sleep 2
  kill "$recorder" 2> /dev/null || true
  for setting in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global "$setting" 0; done
  timeout 60 adb pull /sdcard/Movies/private-entrance.mp4 "$private_dir/private-entrance.mp4" > /dev/null 2>&1 || true
  kill "$site_pid" 2> /dev/null || true
  { echo 'Crash and memory events during the Private Mode run:'
    timeout 30 adb logcat -d -v threadtime | grep -E 'FATAL EXCEPTION| [EF] AndroidRuntime: |ANR in|Process com\.mylo\.browser.* has died' | cut -c1-300 | tail -n 20 || echo '  none recorded'
  } >> "$private_summary"
  if (( private_status )); then echo 'Private Mode device checks failed. See private evidence.' >&2; exit 1; fi
  echo 'Every Private Mode device check passed.'
  exit 0
fi

# Website compatibility: the shared engine against the local test site (two origins through adb reverse)
# and real, unrelated websites reached from every search provider. Each case runs in a fresh app process
# with a screen recording; evidence is pulled after every case.
if [[ "${MYLO_SCOPE:-full}" == "compat" ]]; then
  compat_status=0
  compat_dir="$evidence_dir/compat"
  compat_summary="$evidence_dir/compat-test.txt"
  mkdir -p "$compat_dir/recordings"
  : > "$compat_summary"
  python3 "$script_dir/../compat-site/serve.py" > "$compat_dir/test-site.log" 2>&1 &
  site_pid=$!
  adb reverse tcp:8080 tcp:8080
  adb reverse tcp:8081 tcp:8081
  sleep 2
  curl -sf http://localhost:8080/index.html > /dev/null || { echo 'The compat test site did not start.' >&2; exit 1; }
  adb shell cmd location set-location-enabled true || true
  # A steady GPS position (Sydney Opera House) for the location case; sent from the host like a real fix.
  ( while true; do adb emu geo fix 151.2153 -33.8568 > /dev/null 2>&1 || true; sleep 2; done ) &
  geo_pid=$!
  adb logcat -c || true
  adb logcat -v threadtime > "$compat_dir/logcat.txt" 2>&1 &
  compat_logcat_pid=$!

  run_compat() {
    local class="$1" method="$2" label="$3" limit="$4"
    shift 4
    if ! timeout 15 adb shell true > /dev/null 2>&1; then
      echo "$label: not run (emulator stopped responding)" | tee -a "$compat_summary"; compat_status=1; return
    fi
    timeout 15 adb shell am force-stop "$app_package" || true
    timeout 15 adb shell rm -f "/sdcard/Movies/$label.mp4" || true
    adb shell screenrecord --bit-rate 1500000 --size 720x1600 --time-limit 180 "/sdcard/Movies/$label.mp4" > /dev/null 2>&1 &
    local recorder=$!
    sleep 1
    local output="$compat_dir/$label.txt"
    timeout "$limit" adb shell am instrument -w -r "$@" -e class "com.mylo.browser.$class#$method" \
      com.mylo.browser.test/androidx.test.runner.AndroidJUnitRunner > "$output" 2>&1 || true
    timeout 10 adb shell pkill -INT screenrecord > /dev/null 2>&1 || true
    sleep 2
    kill "$recorder" 2> /dev/null || true
    timeout 60 adb pull "/sdcard/Movies/$label.mp4" "$compat_dir/recordings/$label.mp4" > /dev/null 2>&1 || true
    local result
    if grep -q '^OK (1 test)' "$output"; then result=passed; else result=failed; compat_status=1; fi
    echo "$label: $result" | tee -a "$compat_summary"
    [[ "$result" == passed ]] || grep -E '^(INSTRUMENTATION_(STATUS: stack|RESULT|CODE|ABORTED)|java\.|junit\.|Process crashed)' "$output" \
      | cut -c1-500 | head -n 6 | sed 's/^/    /' >> "$compat_summary" || true
    timeout 60 adb pull /sdcard/Android/data/com.mylo.browser/files/test-artifacts/compat/. "$compat_dir/" > /dev/null 2>&1 || true
  }

  for method in staticSite singlePageApp newWindows signInPopup cookiesAndStorage fileUpload fileDownload camera microphone \
      location fullscreenVideo appLinks backForwardAfterComplexNavigation multipleTabs entryPointsShareOneEngine dialogs; do
    run_compat CompatibilityMatrixTest "$method" "matrix-$method" 180
  done
  run_compat RealSiteCompatTest emergentGetStarted real-emergent-get-started 240
  run_compat RealSiteCompatTest popupLoginSite real-popup-login-site 300
  for provider in GOOGLE YAHOO BING BRAVE DUCKDUCKGO STARTPAGE; do
    run_compat RealSiteCompatTest searchResultsOpenInTheSharedEngine "real-provider-${provider,,}" 300 -e provider "$provider"
  done
  kill "$geo_pid" "$compat_logcat_pid" "$site_pid" 2> /dev/null || true

  # Same engine everywhere: compare what every real destination page saw of Mylo.
  python3 - "$compat_dir" >> "$compat_summary" <<'PY'
import glob, json, sys
seen = {}
for path in sorted(glob.glob(sys.argv[1] + "/provider-*/evidence.json")):
    data = json.load(open(path))
    for result in data.get("results", []):
        if "fingerprint" in result:
            key = json.dumps(result["fingerprint"], sort_keys=True)
            seen.setdefault(key, []).append("%s → %s" % (data.get("provider"), result.get("expectedHost")))
print("Engine capabilities seen by real destination pages: %d distinct fingerprint(s) across %d page loads"
      % (len(seen), sum(len(v) for v in seen.values())))
for key, pages in seen.items():
    print("  " + ", ".join(pages))
PY
  { echo 'Crash, renderer and memory events during the compatibility run:'
    grep -E 'FATAL EXCEPTION| [EF] AndroidRuntime: |Render process|renderer.*(crash|gone)|lowmemorykiller: Kill|ANR in|Process com\.mylo\.browser.* has died| [WEF] Watchdog: ' \
      "$compat_dir/logcat.txt" | cut -c1-300 | tail -n 30 || echo '  none recorded'
  } >> "$compat_summary"
  if (( compat_status )); then echo 'Some compatibility cases failed or were blocked. See compat evidence.' >&2; exit 1; fi
  echo 'Every compatibility case passed on the emulator.'
  exit 0
fi

# Mylo Shield: the honest unconfigured screen always; the real-tunnel milestone only with a test gateway
# from CI secrets, passed as instrumentation arguments so it never ends up in the APK or the logs.
if [[ "${MYLO_SCOPE:-full}" == "shield" ]]; then
  shield_status=0
  shield_summary="$evidence_dir/shield-test.txt"
  : > "$shield_summary"
  shield_args=()
  if [[ -n "${MYLO_SHIELD_TEST_URL:-}" ]]; then
    shield_args=(-e shieldApiBaseUrl "$MYLO_SHIELD_TEST_URL")
    # adb shell drops empty words, which would shift the arguments: pass the token only when there is one.
    [[ -n "${MYLO_SHIELD_TEST_TOKEN:-}" ]] && shield_args+=(-e shieldDevToken "$MYLO_SHIELD_TEST_TOKEN")
  else
    echo 'No MYLO_SHIELD_TEST_URL secret: the real-tunnel milestone is skipped.' | tee -a "$shield_summary"
  fi
  for shield_case in withoutAGatewayShieldIsUnavailable realTunnelMilestone; do
    case_output="$evidence_dir/shield-$shield_case.txt"
    timeout 420 adb shell am instrument -w -r "${shield_args[@]}" -e class "com.mylo.browser.ShieldFlowTest#$shield_case" \
      com.mylo.browser.test/androidx.test.runner.AndroidJUnitRunner > "$case_output" 2>&1 || true
    if grep -q 'INSTRUMENTATION_STATUS_CODE: -4' "$case_output"; then shield_result='skipped (precondition not met)'
    elif grep -q '^OK (1 test)' "$case_output"; then shield_result=passed
    else shield_result='failed'; shield_status=1; fi
    echo "$shield_case: $shield_result" | tee -a "$shield_summary"
    [[ "$shield_result" == failed ]] && grep -E '^(INSTRUMENTATION_(STATUS: stack|RESULT|CODE|ABORTED)|java\.|junit\.|Process crashed)' "$case_output" \
      | cut -c1-400 | head -n 8 | sed 's/^/    /' >> "$shield_summary" || true
  done
  timeout 60 adb pull /sdcard/Android/data/com.mylo.browser/files/test-artifacts/shield "$evidence_dir/" >/dev/null 2>&1 || true
  if (( shield_status )); then echo 'Mylo Shield device checks failed. See shield evidence.' >&2; exit 1; fi
  echo 'Mylo Shield device checks finished.'
  exit 0
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
