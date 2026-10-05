# Mylo for Android

Kotlin + Jetpack Compose Android browser with the navy/purple nighttime Mylo Home/Search screen. Reference artwork is bundled locally; no remotely loaded fonts or artwork are required at runtime.

## Current verification status

The committed version `2e3bcf3edd80c52c3cf7cf1538825f5f388bc1b2` built in [CI run 37232720149](https://github.com/josiahscalia5-dev/butty/actions/runs/37232720149), producing a debug APK. All 16 URL resolver unit tests and all eight Home test cases passed before the latest visual correction. Real search results were verified for Google, DuckDuckGo and Bing; Brave Search and Startpage presented CAPTCHAs. Google result-link selection and tab-test synchronization issues leave full browser verification incomplete.

The latest working-tree visual correction has **not been compiled or rendered**. Earlier test results do not validate this correction. Local Gradle/Android SDK setup remains blocked; see [BLOCKERS.md](BLOCKERS.md). The user has authorized verification commits on `mylo-development` so GitHub can produce the Android renders. The visual correction remains under verification until the side-by-side review is complete.

## Current visual correction (claude-ui)

`HomeScreen.kt` is matched side by side against the approved reference (`drawable-nodpi/approved_design.jpg`) at 393 × 851 dp. Existing browser and search behavior is unchanged.

- Hero: the bundled high-resolution corgi illustration (`mylo_night_hero.png`) is drawn at the crop registered against the reference, edge to edge behind the status bar. Greeting and settings sit over the art. The Mylo wordmark is a vector traced from the reference, and the star is vector too (`HomeArt.kt`), so the hero stays sharp at every density.
- Search bar: search icon, placeholder, divider, microphone and code scanner. The scanner uses Google Play services' code scanner; a scanned link or text opens like typed input.
- Shortcuts, cards (approved wording and chevrons), VPN strip and bottom navigation (Mylo face icon) use the reference's measured sizes, spacing and colours.
- Discovery banner: the approved lake/cabin/moon scene, with baked-in text removed and upscaled 3× (`mylo_discovery_night.webp`). The title uses bundled Nunito Black (OFL, `third_party/nunito/OFL.txt`).
- Greeting and search stay pinned. On short screens only the middle content scrolls; on tall screens spare height is shared between sections.
- VPN: "VPN protected" appears only when Android reports an active VPN. The Singapore/ON sample exists only in reference renders that pass `vpnLocation`.
- Polish (B + C): a taller search pill with a soft lavender glow, a slightly shorter hero, 16 dp card corners with top-lit edges, more even spacing and a compact banner. Shortcut rings keep the approved size. Taps get a subtle press-in, and the selected tab pill animates.
- Search: the search provider is chosen only in **Settings** (the gear on Home → Search engine): Google, Brave, DuckDuckGo, Bing, Yahoo or Startpage. The choice is saved on the device and kept after Mylo restarts. Back on Home, the user types straight into the same Home search box and presses the keyboard's Search key: words open the saved provider's real results page for the exact text, and a web address such as `facebook.com` opens directly. There is no separate search page, per-search provider control or one-off provider choice. The bottom **Search** button returns to Home and focuses that same box.

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
- URLs open in Android WebView; words open the real results page of the provider saved in Settings (Google, Brave, DuckDuckGo, Bing, Yahoo or Startpage). Only HTTP(S) navigation is accepted.
- Bookmarks and normal browsing history persist locally. Tabs can be created, selected and closed; per-tab WebView navigation state is retained while the app process lives and through rotation. Tabs are not restored after process death.
- Voice search uses the installed Android speech recognizer when available. The search-bar scanner uses Google Play services' code scanner and shows a message where it is unavailable.
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
