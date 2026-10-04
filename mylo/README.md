# Mylo for Android

Kotlin + Jetpack Compose Android browser with the navy/purple nighttime Mylo Home/Search screen. Reference artwork is bundled locally; no remotely loaded fonts or artwork are required at runtime.

## Repository and verification status

Work continues only in `josiahscalia5-dev/butty` on `codex-development`, based on `mylo-development` at `e878f7e`. The `claude-ui` branch belongs to Claude and must not be modified by this work. No other repository is used.

The corrected Home has built successfully and rendered through Android Layoutlib. The first real-device comparison in [run 37234373243](https://github.com/josiahscalia5-dev/butty/actions/runs/37234373243) exposed a hero seam, excessive letter spacing, a dark VPN title and 4 dp of discovery-banner clipping at 393×851; seven other portrait/keyboard cases passed. The second visual pass compiled and rendered in [run 37235247124](https://github.com/josiahscalia5-dev/butty/actions/runs/37235247124). The current sky/border correction still requires the final Pixel 5 capture and comparison.

All 16 resolver unit tests passed. Earlier real-provider checks verified Google, DuckDuckGo and Bing; Brave and Startpage presented CAPTCHA challenges. Full link-following and independent-tab checks are still under verification. The current change adjusts the tab test's synchronization, not production browsing behavior.

The user authorized CI verification commits on `codex-development`; completion requires review of the actual Android capture against the approved reference. Local SDK/Gradle limitations are documented in [BLOCKERS.md](BLOCKERS.md).

## Current visual correction

`HomeScreen.kt` follows the supplied `1-3751.jpg` reference, bundled as `drawable-nodpi/approved_home.jpg`. It preserves the supplied artwork through runtime crops alongside native interactive Compose controls, accessible header targets and edge-to-edge artwork. Existing browser behavior is unchanged; no additional features are planned.

`HomePreviewTest` is ready to render the actual Compose UI through Android Layoutlib/Paparazzi at 393×851 dp. It covers the default production state (VPN off, zero tabs) and a reference-only sample state (VPN on, Singapore, one tab). The sample values apply only to the test; production reads the device VPN state and actual tab count. The 393×851 Layoutlib previews have run; real-device screenshots remain authoritative for Android system-bar and cutout handling.

## Build and run

Open this directory in Android Studio. Use Gradle 8.9, JDK 17 or 21, Android platform 35 and Build Tools 35.0.0. Minimum Android version is 9 / API 28. The project has no downloaded Gradle wrapper binary; select an installed Gradle 8.9 distribution or generate the official wrapper with `gradle wrapper --gradle-version 8.9`.

Once dependencies and the SDK are available:

```sh
./scripts/build-and-preview.sh
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`. Install it on an Android emulator or phone with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Paparazzi output is under `app/src/test/snapshots/images/`. It is a native layout preview, not a substitute for device testing. On an emulator, use `adb exec-out screencap -p > mylo-android-portrait.png` for a running-device capture.

## Implemented behavior

- Home uses native Compose elements with the approved dark palette, corgi hero, prominent search, shortcuts, browser cards, VPN strip, discovery area, and bottom navigation.
- URLs open in Android WebView; words search DuckDuckGo, Google, Bing, Brave Search or Startpage according to the saved preference. Only HTTP(S) navigation is accepted.
- Bookmarks and normal browsing history persist locally. Tabs can be created, selected and closed; per-tab WebView navigation state is retained while the app process lives and through rotation. Tabs are not restored after process death.
- Voice search uses the installed Android speech recognizer when available. The optional QR scanner from the mockup is not implemented in this focused build.
- Private browsing runs in a separate process and WebView data directory, blocks cookies, disables persistent web storage and disk caching, clears private website data when opened/closed, and does not write Mylo history. It is not network anonymity; some websites need cookies.
- VPN status reads Android's real network state. The strip opens VPN settings; Mylo has no VPN server or tunnel implementation and never displays a fabricated Singapore connection.
- Tools open search preferences, Android downloads, VPN settings and app settings. Mylo opens a local about/preferences panel.

## Focused validation after setup

1. Build the latest correction, rerun unit tests and render both 393×851 dp portrait cases using the build script. Compare the full reference-state portrait with `1-3751.jpg` before considering the visual correction complete.
2. Open a direct URL, then search words using each provider. Submit a second URL before the first finishes. Verify Back returns through page history.
3. Add/remove a bookmark, revisit history, clear history with confirmation, create/select/close tabs, and rotate the device.
4. Check 360×640, 393×851 and 412×915 dp portrait layouts with gesture and three-button navigation. Focus search and verify IME resizing, scroll access and bottom-navigation insets.
5. Open and close Private twice, confirming normal-session cookies and Mylo history remain separate. VPN status should follow the device's actual VPN.

Keep this pass limited to the requested visual correction and verification.
