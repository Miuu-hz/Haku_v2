package com.bbpillow.haku.platform

import android.app.ActivityManager
import android.content.Context
import com.bbpillow.haku.thinking.LlmDefaults
import com.bbpillow.haku.thinking.LlmSettings
import com.bbpillow.haku.thinking.LlmSettingsRepository

/** P3.8 step 0: [LlmSettings] in SharedPreferences (settings, not personal data). Stored values are untrusted on read. */
class LlmSettingsPrefs(context: Context) : LlmSettingsRepository {
  private val prefs = context.getSharedPreferences("llm_settings", Context.MODE_PRIVATE)

  val totalMemBytes: Long = ActivityManager.MemoryInfo().also {
    context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
  }.totalMem

  override fun load(): LlmSettings {
    val d = LlmDefaults.forDevice(totalMemBytes)
    if (!prefs.contains(CONTEXT)) return d
    return LlmSettings(
      contextTokens = prefs.getInt(CONTEXT, d.contextTokens).takeIf { it in LlmDefaults.CONTEXT_STEPS } ?: d.contextTokens,
      backend = enumOr(prefs.getString(BACKEND, null), d.backend),
      speculativeDecoding = prefs.getBoolean(SPECULATIVE, d.speculativeDecoding),
      loadPolicy = enumOr(prefs.getString(POLICY, null), d.loadPolicy),
      idleReleaseSeconds = prefs.getInt(IDLE, d.idleReleaseSeconds).coerceAtLeast(MIN_IDLE_SECONDS),
      temperature = prefs.getFloat(TEMPERATURE, d.temperature.toFloat()).toDouble().coerceIn(0.0, 2.0),
      topK = prefs.getInt(TOP_K, d.topK).coerceIn(1, 100),
      topP = prefs.getFloat(TOP_P, d.topP.toFloat()).toDouble().coerceIn(0.0, 1.0),
    )
  }

  override fun save(settings: LlmSettings) {
    prefs.edit()
      .putInt(CONTEXT, settings.contextTokens)
      .putString(BACKEND, settings.backend.name)
      .putBoolean(SPECULATIVE, settings.speculativeDecoding)
      .putString(POLICY, settings.loadPolicy.name)
      .putInt(IDLE, settings.idleReleaseSeconds)
      .putFloat(TEMPERATURE, settings.temperature.toFloat())
      .putInt(TOP_K, settings.topK)
      .putFloat(TOP_P, settings.topP.toFloat())
      .apply()
  }

  private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

  private companion object {
    const val CONTEXT = "context_tokens"
    const val BACKEND = "backend"
    const val SPECULATIVE = "speculative"
    const val POLICY = "load_policy"
    const val IDLE = "idle_release_s"
    const val TEMPERATURE = "temperature"
    const val TOP_K = "top_k"
    const val TOP_P = "top_p"
    const val MIN_IDLE_SECONDS = 10
  }
}
