# Mylo for Android

Kotlin + Jetpack Compose Android browser with the navy/purple nighttime Mylo Home/Search screen. Reference artwork is bundled locally; no remotely loaded fonts or artwork are required at runtime.

## Repository and verification status

Work continues only in `josiahscalia5-dev/butty` on `codex-development`, based on `mylo-development` at `e878f7e`. The `claude-ui` branch belongs to Claude and must not be modified by this work. No other repository is used.

Commit `3564ef7` built successfully in [CI run 37237562737](https://github.com/josiahscalia5-dev/butty/actions/runs/37237562737): all 17 unit tests and five Paparazzi cases passed, and the test APK built. Native Android verification passed all eight Home cases: 360×640, 393×851, 412×915 and 360×640 at 1.3 font scale, each with gesture and three-button navigation. Keyboard restoration passed. All three Home candidates passed the native 393×851 check with the full discovery banner visible and zero scrolling.

The browser verification job is still running; current-batch live provider, picker, link-following and independent-tab results remain pending. Earlier checks reached Google, DuckDuckGo and Bing; Brave and Startpage presented CAPTCHA challenges. No physical-camera scan has been verified.

Actual Android captures of A/B/C have been presented for the user's choice. A remains the app default while that choice is pending. Local SDK/Gradle limitations are documented in [BLOCKERS.md](BLOCKERS.md); verification steps are in [TESTING.md](TESTING.md).

## Home polish candidates

`HomeScreen.kt` follows the supplied `1-3751.jpg` reference, bundled as `drawable-nodpi/approved_home.jpg`. Runtime crops preserve its artwork alongside native interactive Compose controls, accessible header targets and edge-to-edge artwork. Three `HomePolish` candidates retain the same hero and wording:

- A — `REFERENCE`: reference balance; remains the app default pending the user's choice.
- B — `SEARCH_FOCUS`: a taller, more prominent search control with slightly smaller shortcuts.
- C — `ROOMY_CARDS`: taller cards and larger card spacing with more compact shortcuts.

`HomePreviewTest` and `HomeVariationsRenderTest` rendered the 393×851 dp candidates successfully for `3564ef7`. Layoutlib provides diagnostics, while native Android captures show system-bar and cutout behavior. The reference comparison test alone injects VPN-on/Singapore/one-tab sample data. Candidate tests use the default state (VPN off, zero tabs); the app reads the device VPN state and actual tab count.

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
- URLs open in Android WebView; words search Google, Brave Search, DuckDuckGo, Bing, Yahoo or Startpage. Results remain the provider's real webpage. Only HTTP(S) navigation is accepted.
- In the provider selector, selecting a row only changes the draft choice. **Use for this search** applies it to the current search without saving it. **Set as default** saves it for future searches as well. Closing the selector discards an unapplied choice; each new search starts with the saved default.
- Bookmarks and normal browsing history persist locally. Tabs can be created, selected and closed; per-tab WebView navigation state is retained while the app process lives and through rotation. Tabs are not restored after process death.
- Voice search uses the installed Android speech recognizer when available.
- The Home scanner opens a native QR camera screen. Camera permission is requested only when scanning is invoked; denial explains how to enable it, and a device without a camera receives an error. **Close** or Android Back cancels without navigating. A decoded QR address or search text goes through the existing URL resolver; unsupported URL schemes remain rejected. Scan images are not saved. Physical-camera capture and permission behavior still need device verification.
- Private browsing runs in a separate process and WebView data directory, blocks cookies, disables persistent web storage and disk caching, clears private website data when opened/closed, and does not write Mylo history. It is not network anonymity; some websites need cookies.
- VPN status reads Android's real network state. The strip opens VPN settings; Mylo has no VPN server or tunnel implementation and never displays a fabricated Singapore connection.
- Tools open search preferences, Android downloads, VPN settings and app settings. Mylo opens a local about/preferences panel.

Keep this pass limited to the requested Home polish, provider selection and QR scanner. Follow [TESTING.md](TESTING.md) before claiming verification or a final visual choice.
