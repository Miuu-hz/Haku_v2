package com.bbpillow.haku.thinking

import kotlinx.coroutines.flow.Flow

enum class ModelBackend { Cpu, Gpu }

data class ModelConfig(
  val modelPath: String,
  val backend: ModelBackend,
  val maxTokens: Int = 2048,
  val topK: Int = 64,
  val topP: Double = 0.95,
  val temperature: Double = 1.0,
  /** Needed when modelPath is under /data/local/tmp (the runtime cannot write next to it). */
  val cacheDir: String? = null,
)

data class GenerateInput(val prompt: String)

/** Raw backend surface: text chunks only. Timing and metrics are added by [LocalModelClient]. */
interface LocalModel {
  /** Blocking native work is the implementation's job to keep off the caller's thread. */
  suspend fun load(config: ModelConfig)

  /** Emits text chunks; completes normally when done or cancelled; throws on runtime error. */
  fun generate(input: GenerateInput): Flow<String>

  fun cancel()

  fun unload()
}

data class LoadOutput(val loadMillis: Long)

data class GenerateMetrics(
  val timeToFirstTokenMillis: Long?,
  val totalMillis: Long,
  val chunkCount: Int,
  /** Chunks per second after the first chunk (decode speed); null with fewer than 2 chunks. */
  val tokensPerSecond: Double?,
  val cancelled: Boolean,
)

data class GenerateOutput(val text: String, val metrics: GenerateMetrics)

sealed interface GenerateEvent {
  data class Token(val text: String) : GenerateEvent
  data class Finished(val output: GenerateOutput) : GenerateEvent
}
