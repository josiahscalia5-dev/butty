#!/usr/bin/env bash
# Test the actual app at portrait dp sizes, with screenshots of each real device configuration.
# Usage: verify-home-layout.sh APP_APK OUTPUT_DIRECTORY TEST_APK
set -euo pipefail

app_apk="${1:?Provide the debug APK}"
evidence_dir="${2:?Provide an output directory}"
test_apk="${3:?Provide the instrumentation APK}"
adb_command="${ADB:-adb}"
app_package="com.mylo.browser"
remote_artifacts="/sdcard/Android/data/$app_package/files/home-layout-artifacts"
failed=0

for apk in "$app_apk" "$test_apk"; do
  if [[ ! -f "$apk" ]]; then
    echo "Missing APK: $apk" >&2
    exit 1
  fi
done
mkdir -p "$evidence_dir"
evidence_dir="$(realpath "$evidence_dir")"
"$adb_command" wait-for-device
original_size="$("$adb_command" shell wm size | tr -d '\r')"
original_density="$("$adb_command" shell wm density | tr -d '\r')"
original_font_scale="$("$adb_command" shell settings get system font_scale | tr -d '\r')"
original_ime="$("$adb_command" shell settings get secure show_ime_with_hard_keyboard | tr -d '\r')"
original_rotation="$("$adb_command" shell settings get system user_rotation | tr -d '\r')"
original_accelerometer="$("$adb_command" shell settings get system accelerometer_rotation | tr -d '\r')"
original_overlays="$("$adb_command" shell cmd overlay list --user current 2>&1 | tr -d '\r' || true)"
original_navigation="$(printf '%s\n' "$original_overlays" | sed -n 's/^\[x\] \(com\.android\.internal\.systemui\.navbar\.[A-Za-z0-9._-]*\)$/\1/p' | head -n 1)"
printf '%s\n' "$original_overlays" > "$evidence_dir/initial-navigation-overlays.txt"
printf '%s\n' "$original_size" "$original_density" > "$evidence_dir/initial-display.txt"
printf 'case\tnavigation\twidthDp\theightDp\tfontScale\tstatus\n' > "$evidence_dir/matrix.tsv"

restore_setting() {
  local namespace="$1" name="$2" value="$3"
  if [[ "$value" == "null" || -z "$value" ]]; then
    "$adb_command" shell settings delete "$namespace" "$name" >/dev/null 2>&1 || true
  else
    "$adb_command" shell settings put "$namespace" "$name" "$value" >/dev/null 2>&1 || true
  fi
}

finish() {
  local result=$?
  trap - EXIT
  "$adb_command" pull "$remote_artifacts/." "$evidence_dir/" >/dev/null 2>&1 || true
  "$adb_command" logcat -d -v threadtime > "$evidence_dir/logcat.txt" 2>&1 || true
  "$adb_command" shell am force-stop "$app_package" >/dev/null 2>&1 || true
  local size_override density_override
  size_override="$(printf '%s\n' "$original_size" | sed -n 's/^Override size: //p')"
  density_override="$(printf '%s\n' "$original_density" | sed -n 's/^Override density: //p')"
  "$adb_command" shell wm size "${size_override:-reset}" >/dev/null 2>&1 || true
  "$adb_command" shell wm density "${density_override:-reset}" >/dev/null 2>&1 || true
  restore_setting system font_scale "$original_font_scale"
  restore_setting secure show_ime_with_hard_keyboard "$original_ime"
  restore_setting system user_rotation "$original_rotation"
  restore_setting system accelerometer_rotation "$original_accelerometer"
  if [[ -n "$original_navigation" ]]; then
    "$adb_command" shell cmd overlay enable-exclusive --user current --category "$original_navigation" >/dev/null 2>&1 || true
  fi
  python3 - "$evidence_dir" <<'PY'
import csv, json, pathlib, sys
directory = pathlib.Path(sys.argv[1])
rows = list(csv.DictReader((directory / 'matrix.tsv').open(), delimiter='\t'))
for row in rows:
    evidence = directory / row['case'] / 'layout-evidence.json'
    if evidence.is_file():
        row['evidence'] = json.loads(evidence.read_text())
    if row['status'] == 'passed' and not row.get('evidence', {}).get('verified'):
        row['status'] = 'evidence-missing'
tested = [row for row in rows if row['status'] != 'unsupported']
unsupported = {row['navigation'] for row in rows if row['status'] == 'unsupported'}
expected_modes = {'gestural', 'threebutton'} - unsupported
if not expected_modes:
    expected_modes = {'current'}
expected_cases = {
    f'{width}x{height}-{mode}-font{font}'
    for mode in expected_modes
    for width, height, font in ((360, 640, '1.0'), (393, 851, '1.0'), (412, 915, '1.0'), (360, 640, '1.3'))
}
complete = {row['case'] for row in tested} == expected_cases
report = {
    'verified': complete and all(row['status'] == 'passed' for row in tested),
    'allNavigationModesVerified': complete and not unsupported and all(row['status'] == 'passed' for row in tested),
    'completeMatrix': complete,
    'missingCases': sorted(expected_cases - {row['case'] for row in tested}),
    'source': 'Android instrumentation and real emulator screenshots; no simulated UI',
    'cases': rows,
}
(directory / 'home-layout-matrix.json').write_text(json.dumps(report, indent=2) + '\n')
if any(row['status'] == 'evidence-missing' for row in rows):
    sys.exit(1)
PY
  local report_result=$?
  if (( report_result != 0 )); then result=1; fi
  exit "$result"
}
trap finish EXIT

"$adb_command" install -r "$app_apk"
"$adb_command" install -r -t "$test_apk"
"$adb_command" shell rm -rf "$remote_artifacts"
"$adb_command" shell settings put secure show_ime_with_hard_keyboard 1
"$adb_command" shell settings put system accelerometer_rotation 0
"$adb_command" shell settings put system user_rotation 0
"$adb_command" shell input keyevent KEYCODE_WAKEUP
"$adb_command" shell wm dismiss-keyguard

run_case() {
  local navigation="$1" width="$2" height="$3" font_scale="$4"
  local case_name="${width}x${height}-${navigation}-font${font_scale}"
  local output="$evidence_dir/$case_name"
  mkdir -p "$output"
  # Display reconfiguration can briefly take adb offline; wait before the next case.
  timeout 30s "$adb_command" wait-for-device
  "$adb_command" shell am force-stop "$app_package"
  # Density 320 makes the requested physical size exactly 2 px per logical dp.
  "$adb_command" shell wm density 320
  "$adb_command" shell wm size "$((width * 2))x$((height * 2))"
  timeout 30s "$adb_command" wait-for-device
  "$adb_command" shell settings put system font_scale "$font_scale"
  sleep 2
  "$adb_command" shell wm size > "$output/display-size.txt"
  "$adb_command" shell wm density > "$output/display-density.txt"
  "$adb_command" shell cmd overlay list --user current > "$output/navigation-overlays.txt" 2>&1 || true
  local status=passed
  if ! timeout 120s "$adb_command" shell am instrument -w -r \
    -e class 'com.mylo.browser.HomeLayoutTest#pinnedHomeAndKeyboardRespectPortraitInsets' \
    -e layoutCase "$case_name" -e navigationMode "$navigation" \
    -e expectedWidthDp "$width" -e expectedHeightDp "$height" -e expectedFontScale "$font_scale" \
    com.mylo.browser.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$output/instrumentation.txt"; then
    status=failed
  elif ! grep -Eq '^OK \(1 test\)' "$output/instrumentation.txt"; then
    status=failed
  fi
  if [[ "$case_name" == '393x851-gestural-font1.0' ]]; then
    # Render the approved image's sample state using the production composables in a test-only Activity composition.
    if ! timeout 120s "$adb_command" shell am instrument -w -r \
      -e class 'com.mylo.browser.HomeReferenceRenderTest#approvedReferenceStateAt393x851' \
      -e layoutCase "$case_name" \
      com.mylo.browser.test/androidx.test.runner.AndroidJUnitRunner \
      | tee "$output/reference-instrumentation.txt"; then
      status=failed
    elif ! grep -Eq '^OK \(1 test\)' "$output/reference-instrumentation.txt"; then
      status=failed
    fi
    if ! timeout 120s "$adb_command" shell am instrument -w -r \
      -e class 'com.mylo.browser.HomeVariationsRenderTest#threePolishVariationsAt393x851' \
      -e layoutCase "$case_name" \
      com.mylo.browser.test/androidx.test.runner.AndroidJUnitRunner \
      | tee "$output/variations-instrumentation.txt"; then
      status=failed
    elif ! grep -Eq '^OK \(1 test\)' "$output/variations-instrumentation.txt"; then
      status=failed
    fi
  fi
  "$adb_command" pull "$remote_artifacts/$case_name/." "$output/" >/dev/null 2>&1 || true
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$case_name" "$navigation" "$width" "$height" "$font_scale" "$status" >> "$evidence_dir/matrix.tsv"
  if [[ "$status" != passed ]]; then failed=1; fi
}

supported_modes=0
for navigation in gestural threebutton; do
  overlay="com.android.internal.systemui.navbar.$navigation"
  # Only switch modes when we can restore the original navigation overlay.
  if [[ -n "$original_navigation" ]] && printf '%s\n' "$original_overlays" | grep -F "$overlay" >/dev/null && \
      "$adb_command" shell cmd overlay enable-exclusive --user current --category "$overlay" > "$evidence_dir/navigation-$navigation.txt" 2>&1 && \
      "$adb_command" shell cmd overlay list --user current | tr -d '\r' | grep -Fx "[x] $overlay" >/dev/null; then
    supported_modes=$((supported_modes + 1))
    run_case "$navigation" 360 640 1.0
    run_case "$navigation" 393 851 1.0
    run_case "$navigation" 412 915 1.0
    run_case "$navigation" 360 640 1.3
  else
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "navigation-$navigation" "$navigation" '' '' '' unsupported >> "$evidence_dir/matrix.tsv"
  fi
done
if (( supported_modes == 0 )); then
  run_case current 360 640 1.0
  run_case current 393 851 1.0
  run_case current 412 915 1.0
  run_case current 360 640 1.3
fi
if (( failed )); then
  echo "Home layout checks failed. See $evidence_dir for screenshots, bounds, and instrumentation output." >&2
  exit 1
fi
echo "Home layout checks passed for the available navigation modes. Evidence: $evidence_dir"
