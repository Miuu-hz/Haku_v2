package com.bbpillow.haku.platform

import android.util.Log
import com.bbpillow.haku.thinking.TextEmbedder
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * EmbeddingGemma-2 through LiteRT-LM `EmbeddingEngine` (0.17+). Raw text in, L2-normalized vector out, as Gallery's
 * smart_album does (UniversalEmbedder.embedText + setL2Normalize(true), no task prefix). GPU first, CPU on failure.
 * [cacheDir] is needed when [modelPath] is under /data/local/tmp.
 */
class LiteRtTextEmbedder(
  val modelPath: String,
  private val cacheDir: String? = null,
  private val gpu: Boolean = true,
) : TextEmbedder {
  private val lock = Mutex()
  @Volatile private var engine: EmbeddingEngine? = null

  /** True while the engine is open (between the first [embed]/[load] and [close]). */
  val isLoaded: Boolean get() = engine != null

  /** "GPU" or "CPU" once loaded. */
  @Volatile var backend: String? = null
    private set

  @Volatile override var dims: Int = 0
    private set

  /** Opens the engine now (otherwise the first [embed] does). */
  suspend fun load() = lock.withLock { ensureEngine(); Unit }

  override suspend fun embed(texts: List<String>): List<FloatArray> {
    if (texts.isEmpty()) return emptyList()
    return lock.withLock {
      val e = ensureEngine()
      withContext(Dispatchers.Default) {
        e.computeEmbeddingBatch(texts.map { listOf(InputData.Text(it)) }, EmbeddingOptions(normalize = true))
          .map { it.embedding }
      }.also { dims = it.first().size }
    }
  }

  suspend fun close() = withContext(Dispatchers.Default + NonCancellable) {
    lock.withLock {
      runCatching { engine?.close() }
      engine = null
      backend = null
    }
  }

  private suspend fun ensureEngine(): EmbeddingEngine = engine ?: withContext(Dispatchers.Default + NonCancellable) {
    val e = if (gpu) {
      try {
        open(Backend.GPU()).also { backend = "GPU" }
      } catch (t: Exception) {
        Log.w(TAG, "GPU embedding engine failed, retrying on CPU", t)
        open(Backend.CPU()).also { backend = "CPU" }
      }
    } else {
      open(Backend.CPU()).also { backend = "CPU" }
    }
    engine = e
    e
  }

  private fun open(b: Backend): EmbeddingEngine {
    val e = EmbeddingEngine(EmbeddingEngineConfig(modelPath = modelPath, backend = b, cacheDir = cacheDir))
    try {
      e.initialize()
      return e
    } catch (t: Throwable) {
      runCatching { e.close() }
      throw t
    }
  }

  private companion object {
    const val TAG = "HakuEmbed"
  }
}
