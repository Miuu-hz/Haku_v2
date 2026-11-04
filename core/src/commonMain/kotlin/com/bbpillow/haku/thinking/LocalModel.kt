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
  /**
   * Speculative decoding (MTP drafter inside the .litertlm, Gemma 4 files from 2026-05-05 on). Used only when the file has a
   * drafter; a failed start retries without it. Off by default until measured on Z Fold5.
   */
  val speculativeDecoding: Boolean = false,
)

/** One finished back-and-forth the model may read on the next turn. */
data class ChatExchange(val user: String, val model: String)

/**
 * A conversation that continues across [LocalModel.generate] calls. The backend keeps its native state while [id],
 * [system], [turnsBefore] and the size of [history] still match what it holds, and rebuilds from [history] otherwise
 * (a reloaded engine, trimmed history, a stopped answer), so [history] is always enough to carry on.
 */
data class ChatThread(val id: Long, val system: String, val history: List<ChatExchange>, val turnsBefore: Int)

/** [thread] = null: stateless, no history in or out (the diary path, summaries, the skill picker). */
data class GenerateInput(val prompt: String, val thread: ChatThread? = null)

/** Raw backend surface: text chunks only. Timing and metrics are added by [LocalModelClient]. */
interface LocalModel {
  /** Blocking native work is the implementation's job to keep off the caller's thread. */
  suspend fun load(config: ModelConfig)

  /** Emits text chunks; completes normally when done or cancelled; throws on runtime error. */
  fun generate(input: GenerateInput): Flow<String>

  fun cancel()

  /** Releases native resources; like [load], keeps blocking work off the caller's thread. */
  suspend fun unload()
}

data class LoadOutput(val loadMillis: Long)

data class GenerateMetrics(
  val timeToFirstTokenMillis: Long?,
  val totalMillis: Long,
  val chunkCount: Int,
  /**
   * Chunks per second after the first chunk; null with fewer than 2 chunks. Equals tokens/s only without speculative
   * decoding: with MTP one chunk carries several accepted tokens, so compare speeds by output length over decode time.
   */
  val tokensPerSecond: Double?,
  val cancelled: Boolean,
)

data class GenerateOutput(val text: String, val metrics: GenerateMetrics)

sealed interface GenerateEvent {
  data class Token(val text: String) : GenerateEvent
  data class Finished(val output: GenerateOutput) : GenerateEvent
}
