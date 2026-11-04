package com.bbpillow.haku.surface.settings

import com.bbpillow.haku.thinking.LlmDefaults
import com.bbpillow.haku.thinking.LlmSettings

/** Latest figures for the settings screen's band; null until measured in this process. */
data class LlmMetrics(
  val loadMillis: Long? = null,
  val firstTokenMillis: Long? = null,
  val charsPerSecond: Double? = null,
  val ramMb: Int? = null,
)

data class LlmContextOption(val tokens: Int, val label: String, val selected: Boolean)

data class LlmSettingsUiState(
  val settings: LlmSettings,
  val modelLabel: String,
  val contextOptions: List<LlmContextOption>,
  val recommendedLabel: String,
  val contextNote: String,
  val metrics: List<Pair<String, String>>,
  /** A step-down or reload to tell the user about; null when there is none. */
  val notice: String?,
)

/** Pure mapping from the settings to what the screen shows. Effects (save, reload) live in androidApp. */
object LlmSettingsPresenter {
  private const val UNKNOWN = "–"

  fun state(
    settings: LlmSettings,
    totalMemBytes: Long,
    modelLabel: String,
    metrics: LlmMetrics,
    notice: String?,
  ): LlmSettingsUiState = LlmSettingsUiState(
    settings = settings,
    modelLabel = modelLabel,
    contextOptions = LlmDefaults.CONTEXT_STEPS.map { LlmContextOption(it, label(it), it == settings.contextTokens) },
    recommendedLabel = "แนะนำสำหรับเครื่องนี้: ${label(LlmDefaults.recommendedContext(totalMemBytes))}",
    contextNote = contextNote(settings.contextTokens),
    metrics = listOf(
      "โหลด" to seconds(metrics.loadMillis),
      "คำแรก" to seconds(metrics.firstTokenMillis),
      "ความเร็ว" to (metrics.charsPerSecond?.let { "${it.toInt()} ตัว/วิ" } ?: UNKNOWN),
      "แรมที่ใช้" to (metrics.ramMb?.let { "${oneDecimal(it / 1024.0)} GB" } ?: UNKNOWN),
    ),
    notice = notice,
  )

  fun label(tokens: Int): String = "${tokens / 1024}K"

  // RAM is what Gemma 4 E2B on GPU reached on a Fold5, 2026-11-04. 32K was not measured, so it states no number.
  private fun contextNote(tokens: Int): String = when {
    tokens < LlmDefaults.AGENT_MIN_CONTEXT && tokens <= 2048 -> "เบาสุด โหมดเอเจนต์ต้องการ 8K ขึ้นไป · แรมราว 2.1 GB"
    tokens < LlmDefaults.AGENT_MIN_CONTEXT -> "โหมดทั่วไปลื่น โหมดเอเจนต์ต้องการ 8K ขึ้นไป · แรมราว 2.3 GB"
    tokens <= 8192 -> "ใช้ได้ทุกโหมด · แรมราว 2.8 GB"
    tokens <= 16384 -> "ข้อความยาวได้มากขึ้น ข้อความยาวเริ่มตอบช้าลง · แรมราว 4 GB"
    else -> "สูงสุด เครื่องอาจโหลดไม่ขึ้น ถ้าไม่พอจะถอยลงขั้นเองและแจ้งให้ทราบ"
  }

  private fun seconds(millis: Long?): String = millis?.let { "${oneDecimal(it / 1000.0)} วิ" } ?: UNKNOWN

  private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
  }
}
