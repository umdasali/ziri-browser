package com.ziribrowser.passwords

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Asks for fingerprint, face or the screen lock before saved passwords are used or shown. */
object Authenticator {
    private const val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** After a successful check, the next ones within this time don't ask again (so filling then viewing isn't two prompts). */
    private const val GRACE_MS = 30_000L
    @Volatile private var lastOk = 0L

    fun activity(context: Context): FragmentActivity? {
        var c = context
        while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
        return null
    }

    /** False when the phone has no screen lock or biometrics set up; then there is nothing to ask. */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    fun recentlyUnlocked() = System.currentTimeMillis() - lastOk < GRACE_MS

    /** Runs [onResult] with true once the person has been verified (or no check is possible or wanted). */
    fun require(context: Context, needed: Boolean, title: String, onResult: (Boolean) -> Unit) {
        val host = activity(context)
        if (!needed || host == null || !available(context) || recentlyUnlocked()) { onResult(true); return }
        val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { lastOk = System.currentTimeMillis(); onResult(true) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onResult(false) }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(ALLOWED).build())
    }
}
