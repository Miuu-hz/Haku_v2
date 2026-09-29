package com.bbpillow.haku.thinking

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Scripted backend: advances [time] by [loadTime] on load and by [gap] before each chunk. */
class FakeLocalModel(
  private val time: TestTimeSource,
  private val chunks: List<String> = emptyList(),
  private val loadTime: Duration = 0.milliseconds,
  private val gap: Duration = 0.milliseconds,
  private val failOnLoad: Throwable? = null,
  private val failAfterChunks: Throwable? = null,
) : LocalModel {
  var loaded = false
  var cancelled = false
  var loadedConfig: ModelConfig? = null

  override suspend fun load(config: ModelConfig) {
    failOnLoad?.let { throw it }
    time += loadTime
    loadedConfig = config
    loaded = true
  }

  override fun generate(input: GenerateInput): Flow<String> = flow {
    check(loaded) { "not loaded" }
    cancelled = false
    for (c in chunks) {
      if (cancelled) return@flow
      time += gap
      emit(c)
    }
    failAfterChunks?.let { throw it }
  }

  override fun cancel() { cancelled = true }
  override suspend fun unload() { loaded = false }
}
