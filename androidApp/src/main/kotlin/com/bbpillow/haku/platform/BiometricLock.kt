package com.bbpillow.haku.platform

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity

/**
 * D11 app lock through the system prompt: the app only learns pass/fail. Screen lock PIN is the fallback so a failed
 * sensor never locks the user out of their own diary.
 * ponytail: a gate only; tying it to a Keystore key that unlocks the encrypted DB comes with D11 encryption.
 */
object BiometricLock {
  private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

  fun available(activity: FragmentActivity): Boolean =
    BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

  fun prompt(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
    val callback = object : BiometricPrompt.AuthenticationCallback() {
      override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
      override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
      // onAuthenticationFailed = one bad finger; the prompt stays open for another try
    }
    BiometricPrompt(activity, activity.mainExecutor, callback)
      .authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTHENTICATORS).build())
  }
}
