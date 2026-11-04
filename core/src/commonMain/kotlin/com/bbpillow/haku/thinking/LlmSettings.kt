package com.bbpillow.haku.thinking

/** When the chat's models load. All three still free them when the app leaves the screen (see [LlmSettings.idleReleaseSeconds]). */
enum class ModelLoadPolicy { ON_APP_OPEN, ON_CHAT_OPEN, ON_ASK }

/**
 * P3.8 step 0: how the on-device LLM runs. Not personal data. The one source for [ModelConfig] in the app.
 *
 * ponytail: no "max reply length" yet; the engine has none per conversation. Add it with the chat that needs it.
 */
data class LlmSettings(
  val contextTokens: Int,
  val backend: ModelBackend = ModelBackend.Gpu,
  val speculativeDecoding: Boolean = false,
  val loadPolicy: ModelLoadPolicy,
  /** Seconds with no question, with the app on screen, before the models are freed. Leaving the app frees them at once. */
  val idleReleaseSeconds: Int = LlmDefaults.IDLE_RELEASE_SECONDS,
  val temperature: Double = LlmDefaults.TEMPERATURE,
  val topK: Int = LlmDefaults.TOP_K,
  val topP: Double = LlmDefaults.TOP_P,
) {
  fun toModelConfig(modelPath: String, cacheDir: String?): ModelConfig = ModelConfig(
    modelPath = modelPath,
    backend = backend,
    maxTokens = contextTokens,
    topK = topK,
    topP = topP,
    temperature = temperature,
    cacheDir = cacheDir,
    speculativeDecoding = speculativeDecoding,
  )

  /** True when moving to [other] needs a new Engine. Sampler values apply from the next conversation without one. */
  fun needsReload(other: LlmSettings): Boolean =
    contextTokens != other.contextTokens || backend != other.backend || speculativeDecoding != other.speculativeDecoding
}

object LlmDefaults {
  const val TEMPERATURE = 1.0
  const val TOP_K = 64
  const val TOP_P = 0.95
  const val IDLE_RELEASE_SECONDS = 60
  const val AGENT_MIN_CONTEXT = 8192
  val CONTEXT_STEPS: List<Int> = listOf(2048, 4096, 8192, 16384, 32768)

  // totalMem reports less than the box says: a "12 GB" Fold5 has 11.7e9 bytes, an "8 GB" phone about 7.5e9. Measured on Fold5 only (2026-11-04).
  private const val LARGE_RAM_BYTES = 10_000_000_000L
  private const val MEDIUM_RAM_BYTES = 7_000_000_000L

  fun recommendedContext(totalMemBytes: Long): Int = when {
    totalMemBytes >= LARGE_RAM_BYTES -> 8192
    totalMemBytes >= MEDIUM_RAM_BYTES -> 4096
    else -> 2048
  }

  fun forDevice(totalMemBytes: Long): LlmSettings = LlmSettings(
    contextTokens = recommendedContext(totalMemBytes),
    loadPolicy = if (totalMemBytes >= LARGE_RAM_BYTES) ModelLoadPolicy.ON_APP_OPEN else ModelLoadPolicy.ON_CHAT_OPEN,
  )

  /** The next smaller step after a load failure; null at the smallest. */
  fun stepDown(contextTokens: Int): Int? = CONTEXT_STEPS.lastOrNull { it < contextTokens }
}

/** Implemented over SharedPreferences in androidApp. [load] returns [LlmDefaults.forDevice] until the first [save]. */
interface LlmSettingsRepository {
  fun load(): LlmSettings
  fun save(settings: LlmSettings)
}
