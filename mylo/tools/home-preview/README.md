# Home preview tool

Renders the **production** Mylo Home and focused-search composables (`HomeScreen.kt`, `HomeArt.kt`,
and the theme from `MainActivity.kt`) to PNG with Compose Multiplatform
(desktop), at phone sizes and densities. It exists for fast visual iteration in environments that
cannot reach Google's Android Maven repository. It is **not** a substitute for the Android build or
the emulator checks in `.github/workflows/mylo-debug.yml`.

- `extract.py` copies the production sources into `build/gen/kotlin` on every run, so renders always
  reflect the current app code.
- `src/main/kotlin/shim` provides small desktop stand-ins for Android-only APIs (`R`, `painterResource`,
  `Font(resId)`, `BackHandler`), all backed by the app's own `res/` files.
- Compose 1.7.3 matches the app's Compose BOM line (Compose 1.7 / Material3 1.3). `androidx.collection`
  is built from the official AndroidX sources because it is published only on Google Maven.
- `Render.kt` draws the device shell (status bar, gesture or 3-button navigation). The keyboard in the
  search scene is an illustration of where Android's keyboard sits.

```sh
./setup.sh                 # once: AndroidX collection sources and the Roboto font
./render.sh build/renders  # Home at 393×851, 412×915 and 360×640
./render.sh build/flow scenes   # resting Home, Home search box typed into above the keyboard
python3 compare.py side-by-side.png build/renders/pixel5_393x851.png   # next to the approved reference
```

Requires JDK 21, Gradle 8.x, Python 3 with Pillow, git and npm.
