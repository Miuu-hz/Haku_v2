package com.bbpillow.haku.thinking

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile

/** Wraps any [LocalModel] and measures load time, TTFT and tokens/sec with a monotonic clock. */
class LocalModelClient(
  private val model: LocalModel,
  private val clock: TimeSource = TimeSource.Monotonic,
  /** Each generation's output, once it completes without error, before its Finished event (a collector stopping there still triggers it). */
  private val onFinished: (GenerateOutput) -> Unit = {},
) {
  /** Set by [cancel] (even before collection starts), consumed and cleared by the generation it applies to. */
  @Volatile private var cancelRequested = false

  suspend fun load(config: ModelConfig): LoadOutput {
    val start = clock.markNow()
    model.load(config)
    return LoadOutput(start.elapsedNow().inWholeMilliseconds)
  }

  fun generate(input: GenerateInput): Flow<GenerateEvent> = flow {
    try {
      generateMeasured(input)
    } finally {
      cancelRequested = false
    }
  }

  private suspend fun FlowCollector<GenerateEvent>.generateMeasured(input: GenerateInput) {
    val start = clock.markNow()
    val text = StringBuilder()
    var chunks = 0
    var firstAt = Duration.ZERO
    var lastAt = Duration.ZERO
    model.generate(input).takeWhile { !cancelRequested }.collect { chunk ->
      if (chunk.isNotEmpty()) {
        val now = start.elapsedNow()
        if (chunks == 0) firstAt = now
        chunks++
        lastAt = now
        text.append(chunk)
        emit(GenerateEvent.Token(chunk))
      }
    }
    val decode = lastAt - firstAt
    val tps = if (chunks >= 2 && decode > Duration.ZERO) (chunks - 1) / decode.toDouble(DurationUnit.SECONDS) else null
    val metrics = GenerateMetrics(
      timeToFirstTokenMillis = if (chunks > 0) firstAt.inWholeMilliseconds else null,
      totalMillis = start.elapsedNow().inWholeMilliseconds,
      chunkCount = chunks,
      tokensPerSecond = tps,
      cancelled = cancelRequested,
    )
    val output = GenerateOutput(text.toString(), metrics)
    onFinished(output)
    emit(GenerateEvent.Finished(output))
  }

  fun cancel() {
    cancelRequested = true
    model.cancel()
  }

  suspend fun unload() = model.unload()
}
