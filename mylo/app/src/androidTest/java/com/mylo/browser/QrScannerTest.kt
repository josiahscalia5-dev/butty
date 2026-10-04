package com.mylo.browser

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.client.android.Intents
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.ScanContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Scanner integration and QR decoding without pretending to exercise a physical camera. */
@RunWith(AndroidJUnit4::class)
class QrScannerTest {
    @Test fun decodedWebsiteReachesNormalUrlResolverUnchanged() {
        val address = "https://example.com/mylo?q=moon&name=Mylo%20Browser#hello"
        val matrix = QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, 360, 360)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val decoder = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            .createDecoder(emptyMap<DecodeHintType, Any>())
        val decoded = decoder.decode(RGBLuminanceSource(matrix.width, matrix.height, pixels))
        assertEquals(address, decoded.text)

        val scan = ScanContract().parseResult(Activity.RESULT_OK,
            Intent().putExtra(Intents.Scan.RESULT, decoded.text).putExtra(Intents.Scan.RESULT_FORMAT, "QR_CODE"))
        val received = mutableListOf<String>()
        dispatchMyloScanResult(scan, { received += it }) { throw AssertionError(it) }
        assertEquals(listOf(address), received)
        SearchProvider.entries.forEach { provider -> assertEquals(address, resolveInput(received.single(), provider)) }
    }

    @Test fun cancelNeverNavigatesOrDisplaysAnError() {
        val cancelled = ScanContract().parseResult(Activity.RESULT_CANCELED, null)
        dispatchMyloScanResult(cancelled,
            { throw AssertionError("Cancel must not navigate: $it") },
            { throw AssertionError("Cancel must not produce an error: $it") })
    }

    @Test fun permissionDenialDoesNotNavigate() {
        val denied = ScanContract().parseResult(Activity.RESULT_CANCELED,
            Intent().putExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, true))
        var error: String? = null
        dispatchMyloScanResult(denied,
            { throw AssertionError("Denied camera access must not navigate") }, { error = it })
        assertTrue(error?.contains("Camera access") == true)
    }

    @Test fun emptyCodesDoNotNavigateAndUnsafeCodesAreRejectedByExistingResolver() {
        val blank = ScanContract().parseResult(Activity.RESULT_OK, Intent().putExtra(Intents.Scan.RESULT, "  "))
        var error: String? = null
        dispatchMyloScanResult(blank,
            { throw AssertionError("An empty QR code must not navigate") }, { error = it })
        assertTrue(error?.contains("does not contain") == true)

        val unsafe = ScanContract().parseResult(Activity.RESULT_OK,
            Intent().putExtra(Intents.Scan.RESULT, "javascript:alert(1)"))
        dispatchMyloScanResult(unsafe, { assertNull(resolveInput(it)) }, { throw AssertionError(it) })
    }

    @Test fun scanUsesOwnPortraitActivityAndDoesNotSaveImages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ScanContract().createIntent(context, myloScanOptions())
        assertEquals(MyloCaptureActivity::class.java.name, intent.component?.className)
        assertEquals("QR_CODE", intent.getStringExtra(Intents.Scan.FORMATS))
        assertTrue(intent.getBooleanExtra(Intents.Scan.ORIENTATION_LOCKED, false))
        assertFalse(intent.getBooleanExtra(Intents.Scan.BARCODE_IMAGE_ENABLED, true))
    }
}
