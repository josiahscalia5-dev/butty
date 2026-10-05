# Mylo for Android

Kotlin + Jetpack Compose Android browser with the navy/purple nighttime Mylo Home/Search screen. Reference artwork is bundled locally; no remotely loaded fonts or artwork are required at runtime.

## Current verification status

**Private Mode (Milestone 1)** — verified on a real Android 35 emulator (Pixel 6) by `PrivateModeFlowTest` in the
workflow's `private` scope, all three cases passed, most recently on `claude-ui` itself at `6703008`
([run 37292933926](https://github.com/josiahscalia5-dev/butty/actions/runs/37292933926); first on the session branch in
[run 37261817092](https://github.com/josiahscalia5-dev/butty/actions/runs/37261817092)). Between those runs the device
test was made robust to the CI emulator: the emulator's launcher sometimes stopped responding and its system dialog
covered Mylo, dropping typed characters. The test now hides other apps' error dialogs (and answers any that still
appear with Wait, never one about Mylo), fills fields through Android's set-text action, and records failures before
the test closes the activity. Each run's screenshots are on the `mylo-ci-evidence-<scope>` branches.

- The approved Private Mode screen opens from Home's Private card, in its own process. Layoutlib renders and the
  device screenshot match `design/reference/private-mode-reference.png` (`design/previews/claude-ui/09-…`).
- A normal tab and a private tab load the same local test page (`compat-site/www/trackers.html`): each sees
  "visit 1", so private cookies and storage are separate. On the private page all four third-party tracker requests
  (Google Analytics, DoubleClick, Facebook Pixel, Bing Ads) were blocked and listed in Block trackers.
- Burn on Exit → Clear now cleared tabs, cookies and storage (the page saw a fresh cookie and storage afterwards);
  leaving through Home burned the session and the private process ended. Normal history contained only the normal
  tab's visit.
- Lock tabs: turning it on required Android's screen lock (a test PIN); after leaving Mylo and coming back, the
  private tabs stayed hidden until the PIN was entered.
- The entrance animation was recorded on the device (`13-…`).

Screens: `design/previews/claude-ui/09-…` to `13-…`. Earlier verification (search provider flow) follows.

**Voice Mode (Milestones 1–2)** — the approved Voice Mode screen, the typed chat (Type instead), the AI switchboard and
the microphone, verified on the same emulator by `VoiceModeFlowTest` in the workflow's `voice` scope
([run 37294002700](https://github.com/josiahscalia5-dev/butty/actions/runs/37294002700)), both cases passed:

- Home's Mylo button opens the Voice Mode screen; it matches `design/reference/voice-mode-reference.png`
  (`design/previews/claude-ui/14-…`). Close voice mode returns to Home or the page.
- Without a Mylo AI service (the default build), Type instead sends nothing and says so ("Not sent"); What Mylo can see
  lists all seven sources with Off / Allow once / Always (`15-…`).
- With the reference gateway (`ai-gateway/`) in front of a **test upstream that is not an AI** and says so in every
  reply: "Explain this page" streamed an answer about the open page; the card number on the page arrived at the
  service hidden; the privacy receipt listed what was and wasn't shared; with Current Page off the next question
  carried no page; "Is this site safe?" asked before reading the page, and the one-time grant was used up (`16-…`).
- Microphone: Tap to talk asks for Android's microphone permission, then Mylo listens (on-device recognition first,
  Android's standard recognizer when the phone lacks the language pack) or explains plainly why it can't; the
  microphone is off afterwards. The CI emulator has no microphone audio, so real speech-to-text is for a phone.

Not built yet: Mylo's spoken replies (OpenAI Realtime voice, milestone 3), and the page actions beyond asking (scrolling
to pricing, translating, cancel guidance, tab comparison, site checks: milestones 5–8).

`claude-ui` at `aa63420` (Settings-only search provider):

- Local build: debug APK, instrumentation APK and all 16 URL resolver/state unit tests pass. Layoutlib (Paparazzi) Home renders at 393×851 and 360×640 dp are pixel-identical to the approved B+C Home before the search change.
- Real Android 35 emulator, [CI run 37245994561](https://github.com/josiahscalia5-dev/butty/actions/runs/37245994561) (`search-flow` scope, `SettingsSearchFlowTest`), all four cases passed:
  - Settings → Google, "Facebook" typed in the Home box, keyboard Search → `https://www.google.com/search?q=Facebook`, "Facebook - Google Search", fully loaded.
  - Settings → Yahoo, same steps → `https://search.yahoo.com/search?p=Facebook`, "Facebook - Yahoo Search Results".
  - App stopped and relaunched without touching Settings → Yahoo still used for the same search.
  - `facebook.com` typed in the Home box → `https://facebook.com` opened directly (loaded as `m.facebook.com`, "Facebook - log in or sign up"; the CI screenshot was taken before Facebook's page painted).
  - Screens: `design/previews/claude-ui/07-…` and `08-…`.
- Not yet run on this flow: the `full` device scope (portrait layout matrix, tabs, Back/Forward, every provider live). Earlier full runs found Brave Search and Startpage showing CAPTCHAs to CI's datacenter network; see [BLOCKERS.md](BLOCKERS.md).

## Current visual correction (claude-ui)

`HomeScreen.kt` is matched side by side against the approved reference (`drawable-nodpi/approved_design.jpg`) at 393 × 851 dp. Existing browser and search behavior is unchanged.

- Hero: the bundled high-resolution corgi illustration (`mylo_night_hero.png`) is drawn at the crop registered against the reference, edge to edge behind the status bar. Greeting and settings sit over the art. The Mylo wordmark is a vector traced from the reference, and the star is vector too (`HomeArt.kt`), so the hero stays sharp at every density.
- Search bar: search icon, placeholder, divider, microphone and code scanner. The scanner uses Google Play services' code scanner; a scanned link or text opens like typed input.
- Shortcuts, cards (approved wording and chevrons), VPN strip and bottom navigation (Mylo face icon) use the reference's measured sizes, spacing and colours.
- Discovery banner: the approved lake/cabin/moon scene, with baked-in text removed and upscaled 3× (`mylo_discovery_night.webp`). The title uses bundled Nunito Black (OFL, `third_party/nunito/OFL.txt`).
- Greeting and search stay pinned. On short screens only the middle content scrolls; on tall screens spare height is shared between sections.
- VPN strip: shows Mylo Shield's real state ("Server setup required" while no gateway is configured; a city only when connected through it), or that another app's VPN is on. The Singapore/ON sample exists only in reference renders that pass `vpnLocation`.
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
- **Voice Mode** (the Mylo button): the approved screen, with the conversation shared between talking and typing.
  Mylo AI runs as a separate service ([docs/ai/BACKEND_API.md](docs/ai/BACKEND_API.md), reference gateway in
  [`ai-gateway/`](ai-gateway/README.md)): the app never holds an AI provider key; voice will use short-lived session
  secrets the service mints. Without a configured service nothing is sent and the app says so.
  - *What Mylo can see* decides what each question carries: the current page (visible text and address, read only
    when asked), selected text, other tabs, history; Screenshot, Location and Saved Memory are marked "not used yet".
    Card numbers, ID numbers, bank accounts and secrets in links are hidden before anything leaves the phone, and
    every answer has a privacy receipt.
  - The six page actions send the matching question (asking first when the page is off); Mylo answers in text until
    spoken replies arrive.
- Voice search uses the installed Android speech recognizer when available. The search-bar scanner uses Google Play services' code scanner and shows a message where it is unavailable.
- **Private Mode** (Home's Private card): the approved Private Mode screen with its entrance animation (the
  sunglasses corgi settles onto the moon, the lenses catch the light, the shield pulses, Active fades in; instant when
  Android's animations are off). Private tabs run on the same browser engine as normal tabs, in a separate process
  and WebView data directory: first-party cookies and storage work for the session only, third-party cookies are
  blocked, site permissions last only for the session, nothing is written to Mylo history, bookmarks and downloads
  are off, and screenshots/Recents previews are blocked.
  - *Block trackers* blocks third-party ad, analytics, social and identity-tracking requests from Mylo's built-in
    list (`web/TrackerList.kt`, 114 domains; a company's trackers still load on its own sites) and shows what was
    blocked.
  - *Lock tabs* hides private tabs whenever Private Mode leaves the screen until Android's screen lock
    (BiometricPrompt: fingerprint, face, PIN, pattern or password) is passed; it can only be turned on after the
    lock is confirmed.
  - *Burn session on exit* (on by default) and *Burn on Exit → Clear everything now* destroy the session's tabs,
    cookies, storage, cache, permissions, tracker log and anything other features register for the session; the
    private process then ends. If Android ends Mylo first, the private profile's files are deleted the next time
    Mylo starts.
  - Private Mode never claims to hide activity from websites, employers/schools or internet providers, and says so.
- **Mylo Shield** ([docs/shield](docs/shield/README.md)): a WireGuard VPN client on Android's `VpnService`, with a disclosure, Android's VPN permission, a foreground service, real connection states, reconnect/renewal, server switching, fastest-server measurement, auto-connect and Always-on/kill-switch guidance. **No gateway is configured yet**, so the app shows *VPN unavailable · Server setup required* and never shows a location, speed or protection it doesn't have. One real test gateway is set up with [`shield-gateway/`](shield-gateway/README.md). The planned differentiators are in [docs/ROADMAP.md](docs/ROADMAP.md).
- Tools open search preferences, Android downloads, Mylo Shield and app settings. Mylo opens a local about/preferences panel.

## Focused validation after setup

1. Build the latest correction, rerun unit tests and render both 393×851 dp portrait cases using the build script. Compare the full reference-state portrait with `1-3751.jpg` before considering the visual correction complete.
2. Open a direct URL, then choose each provider in Settings and search words from the Home box. Submit a second URL before the first finishes. Verify Back returns through page history.
3. Add/remove a bookmark, revisit history, clear history with confirmation, create/select/close tabs, and rotate the device.
4. Check 360×640, 393×851 and 412×915 dp portrait layouts with gesture and three-button navigation. Focus search and verify IME resizing, scroll access and bottom-navigation insets.
5. Open and close Private twice, confirming normal-session cookies and Mylo history remain separate. Home's VPN strip should follow Mylo Shield's real state, or another app's VPN.

Keep this pass limited to the requested visual correction and verification.
