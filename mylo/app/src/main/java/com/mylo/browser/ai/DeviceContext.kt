package com.mylo.browser.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Base64
import android.view.View
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Location and screenshots for Mylo AI, read only when the switchboard allowed them for a question. */
object DeviceContext {
    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * The phone's approximate location (coarse permission only; [AiPrivacy] rounds it to about a kilometre), or
     * null without permission or a fix.
     */
    suspend fun approximateLocation(context: Context): Pair<Double, Double>? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        val last = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val location = last ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fresh(context, manager, providers) else null
        return location?.let { it.latitude to it.longitude }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun fresh(context: Context, manager: LocationManager, providers: List<String>): Location? {
        val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in providers } ?: return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { done ->
                runCatching { manager.getCurrentLocation(provider, null, context.mainExecutor) { if (done.isActive) done.resume(it) } }
                    .onFailure { if (done.isActive) done.resume(null) }
            }
        }
    }

    /** The page as the person sees it: downscaled to [maxWidth] px, JPEG, base64. Main thread; null if it can't be drawn. */
    fun snapshot(view: View, maxWidth: Int = 900): String? = runCatching {
        if (view.width <= 0 || view.height <= 0) return null
        val scale = minOf(1f, maxWidth.toFloat() / view.width)
        val bitmap = Bitmap.createBitmap((view.width * scale).toInt(), (view.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.scale(scale, scale)
        view.draw(canvas)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
        bitmap.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}
