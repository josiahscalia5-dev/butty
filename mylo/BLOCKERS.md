# Current verification blockers

- No build blockers. With network access to Google Maven and `dl.google.com`, Gradle and the Android SDK (platform 35, build tools 35.0.0) install normally; the debug APK, instrumentation APK, unit tests and Paparazzi renders all build.
- No local emulator: the cloud workspace has no KVM, so device checks run in the `Mylo debug APK` workflow's Pixel emulator. Its `search-flow` scope covers Settings → provider → Home search box → real results, a relaunch, and a direct domain.
- Live providers can refuse CI's datacenter network: in earlier full runs Startpage served its "Startpage Blocked" CAPTCHA page and Brave Search returned no results within 30 seconds. A denial stays a visible failure; no results are simulated.
- The `full` device scope has not yet been run on the Settings-only search flow.
