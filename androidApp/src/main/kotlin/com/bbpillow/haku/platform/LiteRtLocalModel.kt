package com.bbpillow.haku.platform

import android.util.Log
import com.bbpillow.haku.thinking.GenerateInput
import com.bbpillow.haku.thinking.LocalModel
import com.bbpillow.haku.thinking.ModelBackend
import com.bbpillow.haku.thinking.ModelConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Modelled on Google AI Edge Gallery LlmChatModelHelper (Apache 2.0): Engine -> Conversation -> sendMessageAsync.
class LiteRtLocalModel : LocalModel {
  @Volatile private var engine: Engine? = null
  @Volatile private var conversationConfig: ConversationConfig? = null
  @Volatile private var conversation: Conversation? = null
  /** load/unload one at a time, so a racing second load cannot drop the first engine reference. */
  private val loading = Mutex()

  override suspend fun load(config: ModelConfig) = loading.withLock {
    release()
    engine = SharedEngines.acquire(config)
    conversationConfig = ConversationConfig(samplerConfig = samplerConfig(config), systemInstruction = null, tools = emptyList())
  }

  // Stateless contract: a fresh Conversation per call so history never carries over
  // (Gallery LlmChatModelHelper.resetConversation: close old, createConversation).
  override fun generate(input: GenerateInput): Flow<String> = callbackFlow {
    val e = checkNotNull(engine) { "model not loaded" }
    conversation?.let { runCatching { it.close() } }
    val conv = e.createConversation(checkNotNull(conversationConfig))
    conversation = conv
    val finished = AtomicBoolean(false)
    conv.sendMessageAsync(
      Contents.of(listOf(Content.Text(input.prompt))),
      object : MessageCallback {
        override fun onMessage(message: Message) {
          val text = message.toString()
          // As LiteRtAgentSession / Gallery: drop control tokens such as "<ctrl99>". trySend never fails: unlimited buffer below
          if (text.isNotEmpty() && !text.startsWith("<ctrl")) trySend(text)
        }
        override fun onDone() { finished.set(true); close() }
        override fun onError(throwable: Throwable) {
          finished.set(true)
          if (throwable is CancellationException) close() else close(throwable)
        }
      },
      // Gemma 4 can think out loud; Gallery runs with thinking off and so do we (summaries and chat want the answer only).
      mapOf("enable_thinking" to false),
    )
    awaitClose { if (!finished.get()) this@LiteRtLocalModel.cancel() } // bare cancel() would hit ProducerScope.cancel, not the native stop
  }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.Default)

  override fun cancel() {
    try { conversation?.cancelProcess() } catch (_: IllegalStateException) { /* conversation already closed */ }
  }

  override suspend fun unload() = withContext(NonCancellable) { loading.withLock { release() } }

  // Native close blocks, so it runs off the caller's thread and is not skipped when the caller is cancelled.
  private suspend fun release() = withContext(Dispatchers.Default + NonCancellable) {
    val conv = conversation
    val e = engine
    conversation = null
    conversationConfig = null
    engine = null
    runCatching { conv?.close() }
    e?.let { SharedEngines.release(it) }
    Unit
  }
}

/**
 * One [Engine] per model file + backend + token limit for the whole process, shared by [LiteRtLocalModel] and the agent
 * session (a multi-GB model loaded twice would not fit). Ref-counted: closed when the last user releases it.
 * Opening happens under the lock, so concurrent first loads wait for one engine instead of each opening their own.
 */
internal object SharedEngines {
  private data class Key(val path: String, val backend: ModelBackend, val maxTokens: Int)
  private class Entry(val key: Key, val engine: Engine, var refs: Int)

  private val lock = Mutex()
  private val open = mutableListOf<Entry>()

  suspend fun acquire(config: ModelConfig): Engine = lock.withLock {
    val key = Key(config.modelPath, config.backend, config.maxTokens)
    open.firstOrNull { it.key == key }?.let { it.refs++; return@withLock it.engine }
    // NonCancellable: a cancelled caller must not drop an engine that finished initializing
    val e = withContext(NonCancellable) { openEngine(config) }
    open += Entry(key, e, 1)
    e
  }

  suspend fun release(engine: Engine) = withContext(Dispatchers.Default + NonCancellable) {
    lock.withLock {
      val entry = open.firstOrNull { it.engine === engine } ?: return@withLock
      if (--entry.refs > 0) return@withLock
      open -= entry
      runCatching { engine.close() }
    }
    Unit
  }
}

/**
 * Creates and initializes an [Engine] off the caller's thread. Use [SharedEngines], not this, to get one.
 * A GPU that LiteRT can't drive (no OpenCL, broken delegate) falls back to CPU: slower, but the chat still answers.
 */
private suspend fun openEngine(config: ModelConfig): Engine = try {
  openEngineOn(config)
} catch (e: Exception) {
  if (config.backend != ModelBackend.Gpu) throw e
  Log.w(TAG, "GPU engine failed, retrying on CPU", e)
  openEngineOn(config.copy(backend = ModelBackend.Cpu))
}

private suspend fun openEngineOn(config: ModelConfig): Engine = withContext(Dispatchers.Default) {
  val e = Engine(
    EngineConfig(
      modelPath = config.modelPath,
      backend = if (config.backend == ModelBackend.Gpu) Backend.GPU() else Backend.CPU(),
      maxNumTokens = config.maxTokens,
      cacheDir = config.cacheDir,
    )
  )
  try {
    e.initialize()
    e
  } catch (t: Throwable) {
    runCatching { e.close() }
    throw t
  }
}

internal fun samplerConfig(config: ModelConfig) = SamplerConfig(config.topK, config.topP, config.temperature)
private const val TAG = "HakuModel"
