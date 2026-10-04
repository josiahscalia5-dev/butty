package com.mylo.browser

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions

/** Camera access starts only when the returned click action is invoked. */
@Composable
fun rememberMyloScanner(onResult: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentResult by rememberUpdatedState(onResult)
    val currentError by rememberUpdatedState(onError)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        dispatchMyloScanResult(result, currentResult, currentError)
    }
    val launchScanner = remember(scanner) {
        {
            runCatching { scanner.launch(myloScanOptions()) }
                .onFailure { currentError("The camera could not open. Please try again.") }
            Unit
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanner()
        else currentError("Camera access is needed to scan a QR code. You can allow it in Android app settings.")
    }
    return remember(context, permission, launchScanner) {
        {
            when {
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ->
                    currentError("This device does not have a camera for scanning QR codes.")
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ->
                    launchScanner()
                else -> permission.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

internal fun myloScanOptions(): ScanOptions = ScanOptions()
    .setCaptureActivity(MyloCaptureActivity::class.java)
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Point your camera at a website QR code")
    .setOrientationLocked(true)
    .setBeepEnabled(false)
    .setBarcodeImageEnabled(false)
    .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false)

/** Leave cancellation alone; the caller routes a successful scan through the existing URL resolver. */
internal fun dispatchMyloScanResult(
    result: ScanIntentResult,
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val contents = result.contents
    when {
        contents != null && contents.isNotBlank() -> onResult(contents)
        contents != null -> onError("This QR code does not contain an address or search text.")
        result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true ->
            onError("Camera access is needed to scan a QR code.")
    }
}

/** Native camera preview with Mylo chrome. No image is saved and no Play Services are required. */
class MyloCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun initializeContent(): DecoratedBarcodeView {
        val night = Color.rgb(9, 20, 46)
        val lavender = Color.rgb(206, 197, 255)
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + .5f).toInt()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(night)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(12), dp(8))
            addView(TextView(context).apply {
                text = "Scan QR code"
                textSize = 20f
                setTextColor(Color.WHITE)
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Close"
                isAllCaps = false
                setTextColor(lavender)
                contentDescription = "Close QR scanner"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        }
        page.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val camera = DecoratedBarcodeView(this).apply {
            statusView?.apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0xD909142E.toInt())
                setPadding(dp(20), dp(16), dp(20), dp(16))
                textSize = 16f
            }
            viewFinder.setMaskColor(0x8809142E.toInt())
            viewFinder.setLaserVisibility(false)
        }
        page.addView(camera, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(page)
        ViewCompat.setOnApplyWindowInsetsListener(page) { view: View, insets: WindowInsetsCompat ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(page)
        return camera
    }
}
