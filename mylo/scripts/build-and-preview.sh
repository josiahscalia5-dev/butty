#!/usr/bin/env bash
# Build and render the actual production Compose Home locally; this script never publishes it.
set -euo pipefail
cd "$(dirname "$0")/.."
gradle_command="${MYLO_GRADLE:-gradle}"
output_directory="${1:-$PWD/artifacts/production-home}"
if ! command -v "$gradle_command" >/dev/null 2>&1; then
  echo 'Cannot render production Home: Gradle 8.9 is missing. Set MYLO_GRADLE to its executable or install it locally.' >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" && ! -f local.properties ]]; then
  echo 'Cannot render production Home: set ANDROID_HOME to an SDK containing platform 35 and build-tools 35.0.0, or create local.properties in Android Studio.' >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo 'Python 3 is required to collect the rendered PNGs and their provenance.' >&2
  exit 1
fi
gradle_options=(--no-daemon --stacktrace)
if [[ "${MYLO_RENDER_OFFLINE:-0}" == 1 ]]; then gradle_options+=(--offline); fi
"$gradle_command" "${gradle_options[@]}" :app:assembleDebug :app:testDebugUnitTest --tests com.mylo.browser.BrowserStateTest
# Rerun when invoked for a second comparison pass, even if Gradle considers the tasks current.
"$gradle_command" "${gradle_options[@]}" --rerun-tasks :app:recordPaparazziDebug --tests 'com.mylo.browser.Home*PreviewTest'
python3 - "$output_directory" <<'PY'
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import shutil
import struct
import sys

output = Path(sys.argv[1]).resolve()
output.mkdir(parents=True, exist_ok=True)
snapshots = Path('app/src/test/snapshots/images')
requested = [
    ('mylo_home_production_393x851', 'production-393x851.png', (786, 1702), 'VPN inactive, zero tabs'),
    ('mylo_home_reference_state_393x851', 'reference-state-393x851.png', (786, 1702), 'Test-only reference state: VPN active, Singapore, one tab'),
    ('mylo_home_production_360x640', 'production-360x640.png', (720, 1280), 'VPN inactive, zero tabs'),
]
rendered = []
for token, destination, expected_size, state in requested:
    matches = sorted(snapshots.glob(f'*{token}*.png'))
    if len(matches) != 1:
        raise SystemExit(f'Expected one fresh production render for {token}; found {len(matches)} in {snapshots}')
    source = matches[0]
    image = source.read_bytes()
    if image[:8] != b'\x89PNG\r\n\x1a\n':
        raise SystemExit(f'Invalid rendered PNG: {source}')
    dimensions = struct.unpack('>II', image[16:24])
    if dimensions != expected_size:
        raise SystemExit(f'{source} rendered at {dimensions}; expected {expected_size}')
    shutil.copyfile(source, output / destination)
    rendered.append({'file': destination, 'pixelSize': dimensions, 'densityDpi': 320, 'state': state})
sources = [
    Path('app/src/main/java/com/mylo/browser/HomeScreen.kt'),
    Path('app/src/main/java/com/mylo/browser/MainActivity.kt'),
    Path('app/src/test/java/com/mylo/browser/HomePreviewTest.kt'),
]
report = {
    'renderer': 'Android Layoutlib via Paparazzi; production HomeScreen, BottomBar, MyloViewport and MyloTheme',
    'generatedAt': datetime.now(timezone.utc).isoformat(),
    'published': False,
    'renders': rendered,
    'sourceSha256': {str(source): hashlib.sha256(source.read_bytes()).hexdigest() for source in sources},
    'note': 'Reference VPN state is only a visual test fixture. No VPN service or connection is simulated in the app.',
}
(output / 'production-home-render.json').write_text(json.dumps(report, indent=2) + '\n')
print(f'Actual production Home renders ready for comparison: {output}')
PY
