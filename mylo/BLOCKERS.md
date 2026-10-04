# Build blocker — current session

- Available: JDK 21, Kotlin/Compose project source, bundled artwork, unit-test source, Android Layoutlib/Paparazzi preview test.
- Missing: Gradle 8.9, Android SDK platform 35, build tools, emulator and adb. No cached local alternatives were found.
- Attempts to reach `services.gradle.org` and `dl.google.com` through the configured proxy failed immediately with `curl: (7) Failed to connect to proxy port 8080` in the default sandbox.
- Requests for sandboxed network permission did not execute before being interrupted. There was no reported automatic approval rejection. Curl timeouts do not run while a command is awaiting permission.
- All download attempts were stopped. No further font/asset downloads are required; the UI uses Android fonts and local artwork.
- Build, resolver tests, APK installation and native portrait capture have **not run**. No rendered mockup is being represented as a running Android preview.

To unblock, use an Android-equipped environment with the required dependencies, or enable network access for the SDK/Gradle setup. Open the existing project; do not regenerate or redesign it.
