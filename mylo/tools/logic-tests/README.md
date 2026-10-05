# Logic tests

Compiles Mylo's pure-Kotlin logic (files without `android.*` imports, listed in `build.gradle.kts`) and
runs their unit tests on a plain JVM. It exists for environments without the Android SDK or Google's Maven
repository; the same tests also run in the app's `testDebugUnitTest` task in CI.

```sh
gradle test
```
