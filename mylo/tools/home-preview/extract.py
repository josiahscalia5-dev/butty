"""Copy the production Home/search and Private Mode UI so the harness compiles the real code."""
import sys, shutil, os
app = sys.argv[1]; out = sys.argv[2]
for f in os.listdir(out): os.remove(os.path.join(out, f))
src = open(os.path.join(app, "MainActivity.kt")).read()
imports = [l for l in src.splitlines() if l.startswith("import ")]
drop = ("import android.", "import androidx.lifecycle.", "import androidx.activity.", "import com.google.",
        "import androidx.compose.ui.viewinterop.", "import androidx.compose.ui.platform.LocalContext",
        "import com.mylo.browser.shield.", "import com.mylo.browser.web.")
imports = [l for l in imports if not l.startswith(drop)]
i = src.index("val Night"); j = src.index("class MyloApplication", i)
open(os.path.join(out, "Theme.kt"), "w").write('@file:Suppress("unused")\npackage com.mylo.browser\n\n' + "\n".join(imports) + "\n\n" + src[i:j])
for f in ("HomeScreen.kt", "HomeArt.kt", "PrivateModeScreen.kt", "PrivateArt.kt", "VoiceModeScreen.kt", "VoiceArt.kt"):
    shutil.copy(os.path.join(app, f), os.path.join(out, f))
state = open(os.path.join(app, "BrowserState.kt")).read()
i = state.index("enum class SearchProvider"); j = state.index("\n}\n", i) + 3
open(os.path.join(out, "SearchProvider.kt"), "w").write("package com.mylo.browser\n\nimport java.net.URLEncoder\n\n" + state[i:j])
