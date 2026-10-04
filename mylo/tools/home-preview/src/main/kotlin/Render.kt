import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mylo.browser.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** A phone profile: logical size in dp, density, and the system-bar insets the app is drawn inside. */
data class Phone(val name: String, val w: Int, val h: Int, val density: Float, val status: Dp, val nav: Dp, val gesture: Boolean,
                 val designPreview: Boolean = true, val vpnLive: Boolean = false)

val phones = listOf(
    Phone("pixel5_393x851", 393, 851, 2.75f, 30.dp, 24.dp, gesture = true),
    Phone("pixel5_393x851_live_vpn_off", 393, 851, 2.75f, 30.dp, 24.dp, gesture = true, designPreview = false),
    Phone("pixel5_393x851_status44", 393, 851, 2.75f, 44.dp, 24.dp, gesture = true),
    Phone("pixel7pro_412x915", 412, 915, 3.5f, 32.dp, 24.dp, gesture = true),
    Phone("compact_360x640_3button", 360, 640, 2f, 24.dp, 48.dp, gesture = false),
)

/** Harness-only flag for the approved design comparison; production never shows a fabricated location. */
@Composable fun SingaporeFlag() {
    Canvas(Modifier.fillMaxSize()) {
        val u = size.width / 22f
        drawRect(Color(0xFFEF3340), size = size.copy(height = size.height / 2))
        drawRect(Color.White, topLeft = Offset(0f, size.height / 2), size = size.copy(height = size.height / 2))
        drawCircle(Color.White, 3.7f * u, Offset(7.2f * u, 5.6f * u))
        drawCircle(Color(0xFFEF3340), 3.3f * u, Offset(8.5f * u, 5.6f * u))
        for (i in 0 until 5) {
            val a = Math.toRadians(-90.0 + i * 72.0)
            drawCircle(Color.White, .55f * u, Offset((11.6 + 2.1 * Math.cos(a)).toFloat() * u, (5.7 + 2.1 * Math.sin(a)).toFloat() * u))
        }
    }
}

@Composable fun SystemStatusBar(height: Dp) {
    Row(Modifier.fillMaxWidth().height(height).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("9:41", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.SignalCellular4Bar, null, tint = Color.White, modifier = Modifier.size(15.dp))
        Icon(Icons.Rounded.Wifi, null, tint = Color.White, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        Icon(Icons.Rounded.BatteryFull, null, tint = Color.White, modifier = Modifier.padding(start = 2.dp).size(17.dp))
    }
}

@Composable fun SystemNavBar(phone: Phone) {
    Box(Modifier.fillMaxWidth().height(phone.nav), contentAlignment = Alignment.Center) {
        if (phone.gesture) Box(Modifier.width(108.dp).height(4.dp).background(Color.White.copy(alpha = .9f), CircleShape))
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White.copy(alpha = .85f))
            Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = Color.White.copy(alpha = .85f))
            Icon(Icons.Rounded.CropSquare, null, tint = Color.White.copy(alpha = .85f))
        }
    }
}

/** Preview scenes at 393×851 with the same system bars: resting Home, focused search, provider sheet. */
const val QUERY = "best night photography spots"
val scenes: Map<String, @Composable (Phone) -> Unit> = mapOf(
    "1_resting_home" to { p -> HomeShell(p) { HomeScreen(vpnActive = true, vpnLocation = "Singapore", statusBarInset = p.status) } },
    "2_focused_search_keyboard" to { p -> SearchShell(p, keyboard = true) },
    "3_provider_sheet" to { p -> SearchShell(p, keyboard = false) },
)

@Composable fun SearchShell(phone: Phone, keyboard: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            MyloViewport(edgeToEdgeHome = true) {
                Box(Modifier.weight(1f)) {
                    SearchInputScreen(QUERY, {}, provider = SearchProvider.GOOGLE, defaultProvider = SearchProvider.GOOGLE,
                        onUseOnce = {}, onSetDefault = {}, onSubmit = {}, onClose = {}, statusBarInset = phone.status)
                }
            }
        }
        if (keyboard) MockKeyboard()
    }
}

/** Illustration of the Android keyboard (IME) occupying the bottom of the screen. */
@Composable fun MockKeyboard() {
    val key = Color(0xFF2C3043)
    Column(Modifier.fillMaxWidth().background(Color(0xFF1A1D29)).padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            listOf("spots", "spots near me", "spotsylvania").forEach { Text(it, color = Color(0xFFCFD3E6), fontSize = 14.sp) }
        }
        @Composable fun row(keys: List<String>, pad: Dp = 0.dp) = Row(Modifier.fillMaxWidth().padding(horizontal = pad).height(44.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            keys.forEach { k -> Box(Modifier.weight(if (k.length > 1) 1.5f else 1f).fillMaxHeight().background(key, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Text(k, color = Color.White, fontSize = if (k.length > 1) 13.sp else 19.sp) } }
        }
        row("qwertyuiop".map { it.toString() })
        row("asdfghjkl".map { it.toString() }, 16.dp)
        row(listOf("⇧") + "zxcvbnm".map { it.toString() } + listOf("⌫"))
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.weight(1.4f).fillMaxHeight().background(key, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Text("?123", color = Color.White, fontSize = 13.sp) }
            Box(Modifier.weight(1f).fillMaxHeight().background(key, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Text(",", color = Color.White, fontSize = 18.sp) }
            Box(Modifier.weight(4.6f).fillMaxHeight().background(key, RoundedCornerShape(6.dp)))
            Box(Modifier.weight(1f).fillMaxHeight().background(key, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Text(".", color = Color.White, fontSize = 18.sp) }
            Box(Modifier.weight(1.4f).fillMaxHeight().background(Color(0xFFB8ACFF), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Search, null, tint = Color(0xFF231C5C)) }
        }
    }
}

@Composable fun HomeShell(phone: Phone, nav: @Composable () -> Unit = { BottomBar(true, 1, {}, {}, {}, {}) }, home: @Composable () -> Unit) {
    MyloViewport(edgeToEdgeHome = true) {
        Box(Modifier.weight(1f)) { home() }
        nav()
    }
}

@Composable fun SceneDevice(phone: Phone, scene: @Composable (Phone) -> Unit) {
    MyloTheme {
        Box(Modifier.fillMaxSize().background(Night)) {
            Column(Modifier.fillMaxSize().padding(bottom = phone.nav)) { Box(Modifier.weight(1f)) { scene(phone) } }
            SystemStatusBar(phone.status)
            Box(Modifier.align(Alignment.BottomCenter)) { SystemNavBar(phone) }
        }
    }
}

@Composable fun Device(phone: Phone) {
    // Mirrors MainActivity: edge-to-edge Night surface; Home draws behind the status bar, the shell pads the bottom.
    MyloTheme {
        Box(Modifier.fillMaxSize().background(Night)) {
            Column(Modifier.fillMaxSize().padding(bottom = phone.nav)) {
                MyloViewport(edgeToEdgeHome = true) {
                    Box(Modifier.weight(1f)) {
                        HomeScreen(vpnActive = phone.designPreview || phone.vpnLive, statusBarInset = phone.status,
                            vpnLocation = if (phone.designPreview) "Singapore" else null)
                    }
                    BottomBar(true, 1, {}, {}, {}, {})
                }
            }
            SystemStatusBar(phone.status)
            Box(Modifier.align(Alignment.BottomCenter)) { SystemNavBar(phone) }
        }
    }
}

fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "out" }).apply { mkdirs() }
    val only = args.getOrNull(1)?.takeIf { it.isNotBlank() }
    if (only == "scenes") {
        val p = phones.first()
        for ((name, scene) in scenes) {
            val sc = ImageComposeScene((p.w * p.density).toInt(), (p.h * p.density).toInt(), Density(p.density)) { SceneDevice(p, scene) }
            var t = 0L
            fun frame(ms: Long) { t += ms * 1_000_000; sc.render(t) }
            repeat(40) { frame(16) }
            if (name.startsWith("3_")) {
                // Tap the "Search with Google" control exactly as a user would.
                val at = Offset(90f * p.density, (p.status.value + 202f + 58f + 12f + 18f) * p.density)
                sc.sendPointerEvent(PointerEventType.Move, at); frame(16)
                sc.sendPointerEvent(PointerEventType.Press, at); frame(16)
                sc.sendPointerEvent(PointerEventType.Release, at)
                repeat(60) { frame(16) }
                // Then pick Brave, so the chosen provider and the saved default are visibly different.
                val brave = Offset(200f * p.density, 488f * p.density)
                sc.sendPointerEvent(PointerEventType.Move, brave); frame(16)
                sc.sendPointerEvent(PointerEventType.Press, brave); frame(16)
                sc.sendPointerEvent(PointerEventType.Release, brave)
                repeat(30) { frame(16) }
            }
            File(out, "$name.png").writeBytes(sc.render(t + 16_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            sc.close(); println("rendered $name")
        }
        return
    }
    for (phone in phones.filter { only == null || it.name.contains(only) }) {
        val scene = ImageComposeScene(
            width = (phone.w * phone.density).toInt(), height = (phone.h * phone.density).toInt(),
            density = Density(phone.density),
        ) { Device(phone) }
        scene.render(0); scene.render(16_000_000)
        val image = scene.render(600_000_000)
        File(out, "${phone.name}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("rendered ${phone.name}")
    }
}
