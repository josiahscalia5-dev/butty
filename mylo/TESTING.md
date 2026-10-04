# Settings-based Home search verification

The current change keeps Codex's Home artwork/layout and WebView implementation unchanged. The Home gear opens **Mylo Settings → Default search provider**. All six choices persist locally; the existing Home field submits directly through that default with the Android keyboard Search action. The intermediate input/provider screen has been removed.

Default CI builds the app and test APK, runs `BrowserStateTest`, then runs only `SettingsSearchFlowTest` on a Pixel 5/API 35. The device script has a six-minute total limit and 60-second per-case limits, stops on the first app/test failure, and never retries provider challenges. The full Home matrix and Paparazzi renders are opt-in workflow-dispatch checks.

```sh
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests com.mylo.browser.BrowserStateTest
timeout -k 10s 360s bash scripts/verify-settings-search.sh \
  app/build/outputs/apk/debug/app-debug.apk dist/settings-search \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

The focused device checks choose Google, Brave, DuckDuckGo, Bing, Yahoo and Startpage through the real Settings UI, then type Facebook in the existing Home field and tap the real Android IME Search key. They assert the corgi remains visible and the field remains above the keyboard. Each actual WebView `originalUrl` is checked against a literal expected URL independently of the app's resolver. A direct-domain case must open example.com; a fresh instrumentation process verifies the previously selected Yahoo default survives process death. Unit tests separately check every persisted provider, query encoding, direct URLs, rejected schemes and tab reuse.

Screenshots and JSON evidence distinguish successful provider routing from actual live-page availability. Each provider gets one bounded observation window; CAPTCHA, consent, blocking, redirects or network errors must be reported as limitations. No HTML, WebView client or provider response is replaced. An observed live document is not by itself proof that every result link works.

Historical navigation/QR tests remain available separately in `LiveSearchFlowTest` and `QrScannerTest`; the focused workflow does not rerun their full suites. Physical-camera scanning requires a camera-equipped Android device.

Current build/device outcomes will be reported with the exact CI commit and artifact; do not infer success from these instructions.
