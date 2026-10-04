# Mylo for Android

Kotlin + Jetpack Compose implementation of the approved navy/purple nighttime Mylo Home/Search screen. The existing reference and corgi artwork are bundled locally. No remotely loaded fonts or artwork are required at runtime.

## Current verification status

The previous CI run built the app and ran the URL resolver tests; only the instrumentation test file failed to compile (fixed here). This correction pass was compiled and rendered locally with the production Home composables on Compose Multiplatform 1.7.3 (the same Compose 1.7 / Material3 1.3 line as the app), because this workspace cannot reach Google's Android SDK or Maven host. Android compilation and the emulator capture run in CI.

## Approved Home correction

The approved reference (`design/approved_home_reference.jpg`, 393 × 851 dp) is the visual source of truth. Home was corrected against it side by side at 393 × 851 dp.

- Hero: the bundled corgi illustration is drawn at the exact crop registered against the reference (1.3825 screen widths, offset −0.292), edge to edge behind the transparent status bar. The greeting and settings button sit over the artwork. The Mylo wordmark is a vector traced from the reference (`HomeArt.kt`), so it keeps the approved letterforms and stays sharp. The glowing star is also vector.
- Search bar: 54 dp pill with 12 dp margins, search icon, placeholder, divider, microphone and code scanner. The scanner uses Google Play services' code scanner; a scanned link or text opens like typed input.
- Shortcuts: 67 dp rings with the approved compass, play, bag and sparkle glyphs.
- Cards: approved wording, 43 dp gradient tiles, 74 dp cards with 12 dp corners, 9 dp gutter and right-hand chevrons.
- VPN strip: shield with lock, title and subtitle, divider, location and switch. Live status is never simulated: "VPN protected" appears only when Android reports an active VPN. Otherwise it reads "VPN protection / Not connected / Set up". The Singapore/ON state exists only as a design-comparison preview supplied by the render harness.
- Discovery banner: the approved lake/cabin/moon artwork, with its baked-in text removed and upscaled 3× for sharpness (`mylo_discovery_night.webp`). The title uses bundled Nunito Black (OFL, `third_party/nunito/OFL.txt`).
- Bottom navigation: 62 dp bar, lavender Home pill, thin search glyph, tab count, and the approved Mylo face icon.
- Layout: the page scrolls above the fixed navigation on short phones. On tall phones the spare height is shared between sections. Other screens keep their own top safe-area padding.

## Build and run

Open this directory in Android Studio. Use Gradle 8.9, JDK 17 or 21, Android platform 35 and Build Tools 35.0.0. Minimum Android version is 9 / API 28. The project has no downloaded Gradle wrapper binary; select an installed Gradle 8.9 distribution or generate the official wrapper with `gradle wrapper --gradle-version 8.9`.

Once dependencies and the SDK are available:

```sh
./scripts/build-and-preview.sh
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`. Install it on an Android emulator or phone with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The Paparazzi test renders the production Compose screen with Android Layoutlib at a tall Pixel-style portrait size. Its output is under `app/src/test/snapshots/images/`; this is a native layout preview, not a substitute for device testing. On an emulator, use `adb exec-out screencap -p > mylo-android-portrait.png` for a real running-device capture.

## Implemented behavior

- Home uses native Compose elements with the approved dark palette, corgi hero, prominent search, shortcuts, browser cards, VPN strip, discovery area, and bottom navigation.
- Android safe-area and keyboard insets are applied once to the app shell. The middle section scrolls on short screens; search remains available. The hero collapses when the keyboard leaves very little height.
- URLs open in Android WebView; words search DuckDuckGo, Google, or Bing according to the saved preference. Only HTTP(S) navigation is accepted.
- Bookmarks and normal browsing history persist locally. Tabs can be created, selected and closed; per-tab WebView navigation state is retained while the app process lives and through rotation. Tabs are not restored after process death.
- Voice search uses the installed Android speech recognizer when available. The search-bar scanner uses Google Play services' code scanner and shows a message where it is unavailable.
- Private browsing runs in a separate process and WebView data directory, blocks cookies, disables persistent web storage and disk caching, clears private website data when opened/closed, and does not write Mylo history. It is not network anonymity; some websites need cookies.
- VPN status reads Android's real network state. The strip opens VPN settings; Mylo has no VPN server or tunnel implementation and never displays a fabricated Singapore connection.
- Tools open search preferences, Android downloads, VPN settings and app settings. Mylo opens a local about/preferences panel.

## Focused validation after setup

1. Run the resolver unit tests and portrait render test using the build script.
2. Open a direct URL, then search words using each provider. Submit a second URL before the first finishes. Verify Back returns through page history.
3. Add/remove a bookmark, revisit history, clear history with confirmation, create/select/close tabs, and rotate the device.
4. Check 360×640, 393×851 and 412×915 dp portrait layouts with gesture and three-button navigation. Focus search and verify IME resizing, scroll access and bottom-navigation insets.
5. Open and close Private twice, confirming normal-session cookies and Mylo history remain separate. VPN status should follow the device's actual VPN.

Stop for design approval after a successful full portrait preview. No additional features are planned in this pass.
