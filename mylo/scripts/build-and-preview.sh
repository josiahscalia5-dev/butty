#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v gradle >/dev/null 2>&1; then
  echo 'Gradle 8.9 is required. Open this folder in Android Studio or install Gradle 8.9.' >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" && ! -f local.properties ]]; then
  echo 'Set ANDROID_HOME to an Android SDK containing platform 35 and build-tools 35.0.0, or let Android Studio create local.properties.' >&2
  exit 1
fi
gradle --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:recordPaparazziDebug
