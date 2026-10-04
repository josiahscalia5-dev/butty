# Mylo for Android

Kotlin + Jetpack Compose implementation of the approved navy/purple nighttime Mylo Home/Search screen. The existing reference and corgi artwork are bundled locally. No remotely loaded fonts or artwork are required at runtime.

## Current verification status

The native source is implemented, but this workspace cannot yet compile or run it. It has JDK 21, but no Gradle, Android SDK, emulator, or adb. SDK/Gradle download attempts failed because the sandbox could not reach its configured proxy; network-enabled requests remained pending and were stopped. No APK or native preview has been generated. Kotlin compilation, unit tests, Android rendering, keyboard behavior, and on-device interactions remain unverified. See BLOCKERS.md.

## Focused polish pass

The current project was preserved. The latest approved reference is bundled in `drawable-nodpi` alongside the local hero artwork, preventing Android density scaling from changing illustration coordinates.

- Search spacing increased to 14 dp above / 20 dp below; shortcut artwork reduced while keeping larger touch targets.
- The hero wordmark has a constrained left-hand area and scales as decorative branding, preventing accessibility text settings from pushing it over the mascot.
- Card titles wrap, labels are more readable, greeting and bottom navigation use minimum heights, and navigation items share available width equally.
- The VPN strip has more balanced spacing and a 48 dp toggle target; its status continues to reflect the device rather than a fabricated Singapore connection.
- The discovery banner is shorter and uses the approved landscape illustration, with native text and button controls.
- Settings, tools, VPN and Mylo panels scroll on short screens. Private browsing handles webpage Back. Home stops reopening the keyboard after an earlier Search action.
- The portrait render test now uses the same safe-area and keyboard-inset shell as the production activity.

No new concept, remote asset download, or feature expansion was introduced. These changes have had source review only; native compilation and rendering remain blocked as described above.

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
- Voice search uses the installed Android speech recognizer when available. The optional QR scanner from the mockup is not implemented in this focused build.
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
