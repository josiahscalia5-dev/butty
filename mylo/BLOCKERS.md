# Current verification blockers

- No build blockers. With network access to Google Maven and `dl.google.com`, Gradle and the Android SDK (platform 35, build tools 35.0.0) install normally; the debug APK, instrumentation APK, unit tests and Paparazzi renders all build.
- No local emulator: the cloud workspace has no KVM, so device checks run in the `Mylo debug APK` workflow's Pixel emulator. Its `search-flow` scope covers Settings → provider → Home search box → real results, a relaunch, and a direct domain.
- Live providers can refuse CI's datacenter network: in earlier full runs Startpage served its "Startpage Blocked" CAPTCHA page and Brave Search returned no results within 30 seconds. A denial stays a visible failure; no results are simulated.
- The `full` device scope has not yet been run on the Settings-only search flow.
- Mylo Shield has no real gateway yet. The client is complete and tested (unit tests, Layoutlib renders, the unconfigured device check), but the first milestone (a real tunnel and the public IP changing) needs one VPS set up with `shield-gateway/` and the `MYLO_SHIELD_TEST_URL` / `MYLO_SHIELD_TEST_TOKEN` secrets.
- This Claude workspace can't reach Google's Maven repository or `dl.google.com`, so Android builds, Layoutlib
  renders and device checks run in the `Mylo debug APK` workflow (`build`, `private` and other scopes); its
  screenshots are published to the `mylo-ci-evidence` branch. `tools/home-preview` (desktop Compose) and
  `tools/logic-tests` (pure-Kotlin tests) run locally.
- Private Mode limits: Android lets one process use one WebView data directory, so all private tabs share one
  session (no per-tab capsules yet). A burn deletes cookies and storage through WebView's APIs and ends the process;
  files of a session Android killed are removed on Mylo's next start, not instantly.
- Mylo AI has no real service yet. The client, the switchboard, the privacy receipt, the realtime voice call and the
  reference gateway are built and tested (unit tests, gateway tests, and device tests against a test provider that is
  not an AI, including a real WebRTC call). Mylo's real voices and answers need it. Real answers
  need the gateway deployed with an OpenAI API key (`ai-gateway/README.md`) and its address/token given to the build
  (`MYLO_AI_API_BASE_URL`, `MYLO_AI_DEV_TOKEN` for debug builds) or entered on a debug device (Voice Mode → ⚙).
- Speech on the CI emulator: there is no microphone audio and no offline language pack, so the device test checks the
  permission flow, the listening state or its explanation, and that the microphone turns off; real speech-to-text is
  checked on a phone.
- Voice audio quality, echo cancellation and interruption timing with real speech can only be judged on a phone: the
  CI emulator records silence, so the test provider simulates the person's turn.
