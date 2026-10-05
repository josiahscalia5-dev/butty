# Targeted browser polish verification

Work stays in `josiahscalia5-dev/butty` on `codex-development`, based on verified Settings-search build `df81f16`. The Home hero, search, shortcuts, cards, banner and bottom navigation remain unchanged. Only the Shield strip and native browser toolbar presentation change; provider resolution, WebView settings/client, saved browser data, private browsing and scanner implementation stay unchanged.

Default CI builds the app and test APK, runs the 18 `BrowserStateTest` checks, and runs five isolated Android checks: the existing Yahoo URL, Google Home search, example.com, toolbar editing/Back/Forward at 393 and 360 dp widths, and Shield state/setup. Each case has a 60-second limit (75 for Yahoo diagnostics); the whole device script has a six-minute cap. Full Home rendering/matrices remain opt-in.

```sh
timeout -k 10s 360s bash scripts/verify-browser-polish.sh \
  app/build/outputs/apk/debug/app-debug.apk dist/browser-polish \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Node 22 and an Android API 35 emulator/device are required for the script. Screenshots and per-case JSON are uploaded with the APK in GitHub Actions; Android downloads should use the HTTPS artifact link, extract the ZIP, then install `Mylo-debug.apk`.

## Yahoo investigation

The baseline verified at `df81f16` reached `https://search.yahoo.com/search?p=Facebook` on the real Android 15 WebView (Chrome 124 mobile UA). Yahoo rendered its own “temporary problems searching for web pages” message. This was neither a fabricated results page nor a fallback to another engine.

The targeted test tries that same URL first with unchanged WebView settings, records JavaScript/DOM storage/cookie policy, default-UA equality, WebView package/version and the real response document. An instrumentation-only DevTools connection records HTTP status, redirects, failed requests and JavaScript exceptions without intercepting requests, changing headers/cookies or substituting responses. One normal reload is allowed after the baseline to capture the main HTTP response with DevTools attached; an explicit bot/CAPTCHA challenge gets passive observation only. Cookie values and request/response headers are not recorded. Do not treat a provider-branded error document as successful search results.

The environment provides an emulator, not a physical phone. Do not claim Yahoo works (or fails) on a phone from CI evidence alone. No compatibility changes should be made without evidence that the failing behavior is controlled by Mylo; do not spoof a desktop UA or bypass provider protections.

## Shield truthfulness

The strip reads Android `TRANSPORT_VPN`: “Mylo Shield / Not connected / Set up” when absent, “Mylo Shield / VPN connected / Manage” when present. No country, fake toggle or built-in VPN is supplied. The setup panel explains that an installed VPN provider is required. The targeted check compares the displayed status to the actual emulator network capabilities. It does not synthesize an active VPN. A real active tunnel and network-callback transition require an installed VPN provider/server and remain outside this emulator's available setup.

Current CI results and provider limitations must be reported with the exact tested commit and artifact. Existing per-tab, bookmark/history, Private Mode and scanner code was not changed; their whole historical suites are not rerun by this targeted task.
