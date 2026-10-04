# Current verification blockers

- The committed version `2e3bcf3edd80c52c3cf7cf1538825f5f388bc1b2` built a debug APK in [CI run 37232720149](https://github.com/josiahscalia5-dev/butty/actions/runs/37232720149). All 16 resolver unit tests and eight Home cases passed before the latest visual correction. Google, DuckDuckGo and Bing returned real search results; Brave Search and Startpage presented CAPTCHAs. Google link-selector and tab-test synchronization issues leave full browser verification incomplete.
- The latest uncommitted Home visual correction and updated 393×851 dp Layoutlib/Paparazzi previews have **not been compiled or rendered**. Previous CI results do not validate them. User instruction: do not commit or push until visual verification is complete.
- Available locally: JDK 21, Kotlin/Compose source, bundled reference artwork and test sources. Missing: Gradle 8.9, Android SDK platform 35, build tools, emulator and adb. No cached local alternatives were found.
- Attempts to reach `services.gradle.org` and `dl.google.com` through the configured proxy failed immediately with `curl: (7) Failed to connect to proxy port 8080` in the default sandbox.
- Requests for sandboxed network permission did not execute before being interrupted. There was no reported automatic approval rejection.
- Download attempts were stopped. No further font or artwork downloads are required.

To unblock, use an Android-equipped environment or restore network access for SDK/Gradle setup. Build the existing project and render both native portrait cases: the default production state and the reference-only VPN-on/Singapore/one-tab state. Compare the latter with `1-3751.jpg` before considering the visual correction complete. Preserve the existing browser and artwork; no further features are planned.
