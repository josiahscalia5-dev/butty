// Harness-only: stand-ins for Android resource APIs, backed by the project's own res/ files.
package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File

internal fun resFile(id: Int) = File(System.getProperty("mylo.res"), com.mylo.browser.R.files.getValue(id))
private fun load(id: Int): ImageBitmap = Image.makeFromEncoded(resFile(id).readBytes()).toComposeImageBitmap()

@Composable fun painterResource(id: Int): Painter = remember(id) { BitmapPainter(load(id)) }
@Composable fun ImageBitmap.Companion.imageResource(id: Int): ImageBitmap = remember(id) { load(id) }
