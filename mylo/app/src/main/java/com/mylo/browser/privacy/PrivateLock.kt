package com.mylo.browser.privacy

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Whether this device can lock private tabs. */
enum class LockAvailability { Ready, NoScreenLock, Unavailable }

/**
 * Lock tabs: private tabs are hidden whenever Private Mode leaves the screen and come back only after the
 * device's own fingerprint, face, PIN, pattern or password (Android's BiometricPrompt). Mylo never sees or
 * stores any credential.
 */
object PrivateLock {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun availability(context: Context): LockAvailability = when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
        BiometricManager.BIOMETRIC_SUCCESS -> LockAvailability.Ready
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> LockAvailability.NoScreenLock
        else -> LockAvailability.Unavailable
    }

    /** Android's screen-lock settings, to set a PIN, pattern or password. */
    fun screenLockSettings(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)

    /** Shows Android's authentication. [onResult] gets true only for a real success. */
    fun authenticate(activity: FragmentActivity, title: String, subtitle: String, onResult: (Boolean, String?) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true, null)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false, errString.toString())
            // A single failed attempt keeps the prompt open; Android decides when to give up.
            override fun onAuthenticationFailed() = Unit
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { onResult(false, it.message) }
    }
}
