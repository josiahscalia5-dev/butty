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

The targeted test tries that same URL first with unchanged WebView settings, records JavaScript/DOM storage/cookie policy, default-UA equality, WebView package/version and the real response document. An instrumentation-only DevTools connection records HTTP status, redirects, failed requests and JavaScript exceptions without intercepting requests, changing headers/cookies or substituting responses. One normal reload is allowed after the baseline to capture the main HTTP response with DevTools attached; an explicit bot/CAPTCHA challenge gets passive observation only. Cookie inspection collects only cookie names; request/response headers are not recorded. Do not treat a provider-branded error document as successful search results.

The environment provides an emulator, not a physical phone. Do not claim Yahoo works (or fails) on a phone from CI evidence alone. No compatibility changes should be made without evidence that the failing behavior is controlled by Mylo; do not spoof a desktop UA or bypass provider protections.

### Observed response, 2026-10-05

Run [37246756707](https://github.com/josiahscalia5-dev/butty/actions/runs/37246756707), production commit `a77dee4`, captured the original Yahoo request on Android 15 with `com.google.android.webview` 124.0.6367.219. JavaScript, DOM storage and first-party cookies were enabled; third-party cookies remained disabled and the user agent matched Android's default mobile WebView user agent.

- `https://search.yahoo.com/search?p=Facebook` redirected with HTTP 307 through Yahoo's `/_bv/v.gif` and another HTTP 307 back to the correct search URL. The final main document returned HTTP 200 over HTTP/2.
- Yahoo rendered its own “We had temporary problems searching for web pages” message. One diagnostic reload also returned HTTP 200 and the same error. Yahoo's `beacon/t.gif` script reported `TypeError: Cannot read properties of undefined (reading 'query')` at lines 24 and 52. This exception is an observation, not a proven cause of the search error.
- Mylo submitted the selected provider's correct URL from the existing Home field using the Android keyboard Search action. Google produced real Facebook results; `example.com` opened directly. The Home artwork/layout and Settings provider flow were preserved.
- No Yahoo compatibility workaround was justified by the evidence. No user-agent, cookie policy, redirect handling, provider fallback or webpage-content changes were made. Physical-phone behavior remains unverified; this is an observed CI failure, not proof that the issue is exclusive to CI.

The [Android evidence artifact](https://github.com/josiahscalia5-dev/butty/actions/runs/37246756707/artifacts/11318489743) contains Home, Shield, Google and Yahoo screenshots, per-flow reports and the bounded network trace. The run stopped later on a toolbar-test accessibility assertion, so it must not be reported as a fully passing run. Subsequent test-only follow-ups retain the same production implementation and run only navigation and Shield via `MYLO_POLISH_CASES`.

## Shield truthfulness

The strip reads Android `TRANSPORT_VPN`: “Mylo Shield / Not connected / Set up” when absent, “Mylo Shield / VPN connected / Manage” when present. No country, fake toggle or built-in VPN is supplied. The setup panel explains that an installed VPN provider is required. The targeted check compares the displayed status to the actual emulator network capabilities. It does not synthesize an active VPN. A real active tunnel and network-callback transition require an installed VPN provider/server and remain outside this emulator's available setup.

Current CI results and provider limitations must be reported with the exact tested commit and artifact. Existing per-tab, bookmark/history, Private Mode and scanner code was not changed; their whole historical suites are not rerun by this targeted task.

## Verified build and focused follow-up

[Run 37248075820](https://github.com/josiahscalia5-dev/butty/actions/runs/37248075820) passed at `db980e7` with the same production code as `a77dee4`. The app and instrumentation APKs built successfully, APK signature verification passed, and all 18 unit tests passed. Only `shieldState` and `browserBackForward` ran on Android in this follow-up; Google, Yahoo and the direct-domain results above were retained without repeating provider requests.

The two device tests passed in 13.27 and 31.00 seconds. They verified the actual disconnected Android VPN state and setup panel, Home input with the real keyboard, native address editing and IME submission, Back/Forward through WebView history, reuse of the current tab, and toolbar/bottom-navigation bounds at 393 and 360 dp portrait widths. Saved screenshots were reviewed. Active VPN tunnel behavior remains untested because no real VPN provider/server is available.

- [Download the verified APK ZIP](https://github.com/josiahscalia5-dev/butty/actions/runs/37248075820/artifacts/11319778036). On Android, open in Chrome, sign into GitHub if prompted, extract the ZIP, then install `Mylo-debug.apk`.
- [Home, Shield setup, address editing and portrait toolbar screenshots](https://github.com/josiahscalia5-dev/butty/actions/runs/37248075820/artifacts/11319104593).
- APK SHA-256: `a51d1b0e3300d2e1db2082a66a9a7149c06c3c31f4addb5d572c1b29a7aa5f7c`.

All changes were limited to `josiahscalia5-dev/butty` / `codex-development`. The inspected `ccr-*` branches contained Claude UI work rather than missing Codex changes and were not merged. `claude-ui` and `hyuuu` were not modified.
