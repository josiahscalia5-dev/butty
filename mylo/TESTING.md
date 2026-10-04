# Verification for the current change

This batch adds six-provider selection, a native QR scanner and three Home polish candidates on `codex-development`, based on `mylo-development` at `e878f7e`.

Verified at `3564ef7` in [CI run 37237562737](https://github.com/josiahscalia5-dev/butty/actions/runs/37237562737): APK/test-APK builds, all 17 unit tests, five Paparazzi cases, all eight native Home portrait/font/navigation cases and keyboard restoration. The native matrix covered 360×640, 393×851, 412×915 and 360×640 at font scale 1.3 under both gesture and three-button navigation. Each A/B/C candidate showed the complete banner with zero scrolling at 393×851. Actual Android comparisons have been presented; A (`REFERENCE`) remains the default pending the user's choice.

The browser verification job is still running. Live provider, picker, link-following, independent-tab and QR result-handling results remain pending. No physical-camera scan is verified.

## Build and native previews

With Gradle 8.9, JDK 17 or 21, Android platform 35 and Build Tools 35.0.0 available, run from this directory:

```sh
./scripts/build-and-preview.sh
gradle --no-daemon :app:assembleDebugAndroidTest
./scripts/verify-home-layout.sh \
  app/build/outputs/apk/debug/app-debug.apk \
  dist/home-verification \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

The last command requires a connected Android emulator/device and records portrait/keyboard evidence for available navigation modes. On the 393×851 gesture-navigation case, it also runs `HomeVariationsRenderTest` to capture `REFERENCE`, `SEARCH_FOCUS` and `ROOMY_CARDS`. Review all three native captures before asking the user to choose; keep `REFERENCE` as the app default until that choice. Confirm the approved hero and wording remain unchanged, controls avoid system bars/cutouts, and the complete discovery banner fits in the initial reference-size portrait. If gesture navigation is unavailable, the script does not generate the three candidate captures; do not treat that run as candidate verification.

`HomePreviewTest` supplies Layoutlib diagnostics for each candidate. Its reference-state case injects VPN-on/Singapore/one-tab data only for screenshot comparison. Production continues to use the device VPN state and real tab count.

## Provider behavior

Verify Google, Brave Search, DuckDuckGo, Bing, Yahoo and Startpage independently:

1. Enter search text containing spaces, Unicode and punctuation; verify the real provider URL and results. Direct HTTP(S) URLs must still navigate unchanged. Record CAPTCHAs or unavailable providers as limitations.
2. Select another provider and dismiss the picker without applying it; the active choice must remain unchanged.
3. Choose **Use for this search**, submit, then start a new search. The temporary choice must apply to the submitted search while the next search starts with the saved default.
4. Choose **Set as default**, then reopen search and restart the app. The selected default must persist. Changing provider must preserve typed text and must not submit until requested.
5. Follow a real result, go Back, and verify independent navigation in two tabs. Recheck bookmarks, history and private-session separation.

## QR scanner

`QrScannerTest` checks generated QR decoding, successful result dispatch through the normal URL resolver, cancellation, missing-permission handling, empty/unsupported content and scanner configuration. Those are automated integration checks, not evidence that a camera scanned a physical code.

After installing the app and test APKs on a connected Android device/emulator, run:

```sh
adb shell am instrument -w -r \
  -e class com.mylo.browser.QrScannerTest \
  com.mylo.browser.test/androidx.test.runner.AndroidJUnitRunner
```

On a camera-equipped Android device, verify:

1. Camera permission is requested only after tapping the scanner. Denial shows an explanation without navigation; allow access on retry or through Android settings and open scanning again.
2. The live preview scans a real QR URL and opens that address through normal browsing. Scan plain search text and verify it uses the applicable search provider. Unsupported schemes must not load.
3. **Close** and Android Back return without navigation or an error, preserving the prior browsing state. Repeat open/cancel and successful scan flows.
4. Check safe-area layout and camera errors on the supported device. Where available, verify the no-camera path on a device/emulator without a camera.

Report device/model, Android version and the exact checks performed. Do not mark camera behavior verified from generated images, result injection or an emulator screenshot alone.
