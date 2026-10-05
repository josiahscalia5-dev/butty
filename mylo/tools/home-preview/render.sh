#!/usr/bin/env bash
# Usage: ./render.sh [OUT_DIR] [scenes|PHONE_FILTER]
#   scenes  → resting Home, and Home's own search box typed into above the keyboard (393×851)
#   default → Home at 393×851, 412×915 and 360×640 profiles
set -euo pipefail
cd "$(dirname "$0")"
out="$(realpath -m "${1:-build/renders}")"
mkdir -p build/gen/kotlin "$out"
python3 extract.py ../../app/src/main/java/com/mylo/browser build/gen/kotlin
gradle --no-daemon -q run -Pout="$out" -Ponly="${2:-}"
echo "Renders written to $out"
