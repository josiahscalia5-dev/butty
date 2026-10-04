# Verification environment and remaining limits

- CI builds the APK and test APK and renders the production Compose Home. The user authorized verification commits only to `butty/codex-development`; `claude-ui` is untouched.
- Final Home acceptance requires the real Pixel 5 393×851 comparison and portrait/keyboard checks. Layoutlib does not execute `MainActivity.enableEdgeToEdge`, so its diagnostic system-bar composition is not the final device evidence.
- Local Gradle, Android SDK, emulator and adb are unavailable. The configured proxy fails with connection refused; earlier network permission requests were interrupted, not automatically rejected. Use the working GitHub Android runner rather than claiming a local build.
- Previous Google, DuckDuckGo and Bing checks reached real provider pages. Brave and Startpage challenged the CI address. Link-following and per-tab navigation checks are still being verified; external challenges must remain visible rather than replaced with mock search results.
- Reference screenshot tests inject VPN-on/Singapore/one-tab sample data only for comparison. The actual app uses Android VPN connection detection and its real tab count.

Continue the existing project and approved artwork. Do not add AI, VPN tunnel, privacy or custom search-results features during this work.
