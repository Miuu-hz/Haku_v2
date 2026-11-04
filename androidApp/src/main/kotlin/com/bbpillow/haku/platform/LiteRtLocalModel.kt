package com.bbpillow.haku.platform

import android.util.Log
import com.bbpillow.haku.thinking.ChatThread
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
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
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
  @Volatile private var config: ModelConfig? = null
  /** Stateless calls only, closed here. A thread's conversation belongs to its engine (see [SharedEngines.chat]). */
  @Volatile private var owned: Conversation? = null
  /** What [cancel] stops. */
  @Volatile private var active: Conversation? = null
  /** load/unload one at a time, so a racing second load cannot drop the first engine reference. */
  private val loading = Mutex()

  override suspend fun load(config: ModelConfig) = loading.withLock {
    release()
    engine = SharedEngines.acquire(config)
    this.config = config
  }

  // No thread: a fresh Conversation per call so history never carries over (Gallery LlmChatModelHelper.resetConversation:
  // close old, createConversation). A thread: the engine's chat conversation, which outlives this object.
  override fun generate(input: GenerateInput): Flow<String> = callbackFlow {
    val e = checkNotNull(engine) { "model not loaded" }
    val cfg = checkNotNull(config)
    owned?.let { runCatching { it.close() } }
    owned = null
    val chat = input.thread?.let { SharedEngines.chat(e, it, cfg) }
    val conv = chat?.conversation ?: e.createConversation(statelessConfig(cfg)).also { owned = it }
    active = conv
    chat?.begin()
    val finished = AtomicBoolean(false)
    conv.sendMessageAsync(
      Contents.of(listOf(Content.Text(input.prompt))),
      object : MessageCallback {
        override fun onMessage(message: Message) {
          val text = message.toString()
          // As LiteRtAgentSession / Gallery: drop control tokens such as "<ctrl99>". trySend never fails: unlimited buffer below
          if (text.isNotEmpty() && !text.startsWith("<ctrl")) trySend(text)
        }
        override fun onDone() {
          chat?.finish()
          // measured characters per token decide when ChatSessionState summarises
          if (chat != null) runCatching { Log.d(TAG, "chat tokens used: ${conv.getTokenCount()} of ${cfg.maxTokens}") }
          finished.set(true)
          close()
        }
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
    try { active?.cancelProcess() } catch (_: IllegalStateException) { /* conversation already closed */ }
  }

  override suspend fun unload() = withContext(NonCancellable) { loading.withLock { release() } }

  // Native close blocks, so it runs off the caller's thread and is not skipped when the caller is cancelled.
  private suspend fun release() = withContext(Dispatchers.Default + NonCancellable) {
    val conv = owned
    val e = engine
    owned = null
    active = null
    config = null
    engine = null
    runCatching { conv?.close() }
    e?.let { SharedEngines.release(it) }
    Unit
  }
}

private fun statelessConfig(config: ModelConfig) = ConversationConfig(samplerConfig = samplerConfig(config), systemInstruction = null, tools = emptyList())

/**
 * The conversation a chat continues in, and what it has been told. A stopped or failed answer leaves it [clean] = false:
 * what it holds is then unknown, so the next turn rebuilds it from the kept history.
 */
internal class ChatSlot(val conversation: Conversation, thread: ChatThread, config: ModelConfig) {
  private val id = thread.id
  private val system = thread.system
  private val sampler = samplerKey(config)
  private var turns = thread.turnsBefore
  private var historySize = thread.history.size
  @Volatile private var clean = true

  fun fits(thread: ChatThread, config: ModelConfig) = clean && id == thread.id && system == thread.system &&
    turns == thread.turnsBefore && historySize == thread.history.size && sampler == samplerKey(config)

  fun begin() { clean = false }

  fun finish() { turns++; historySize++; clean = true }

  private fun samplerKey(c: ModelConfig) = Triple(c.topK, c.topP, c.temperature)
}

/**
 * One [Engine] per model file + backend + token limit for the whole process, shared by [LiteRtLocalModel] and the agent
 * session (a multi-GB model loaded twice would not fit). Ref-counted: closed when the last user releases it.
 * Opening happens under the lock, so concurrent first loads wait for one engine instead of each opening their own.
 */
internal object SharedEngines {
  private data class Key(val path: String, val backend: ModelBackend, val maxTokens: Int, val speculative: Boolean)
  private class Entry(val key: Key, val engine: Engine, var refs: Int, var chat: ChatSlot? = null)

  private val lock = Mutex()
  private val open = mutableListOf<Entry>()

  /**
   * Runs [block] (creating a non-LLM LiteRT engine) only when no LLM engine is open, else returns null. Under the same
   * lock as [acquire], so no LLM is initializing meanwhile and none starts until [block] returns (measured: an unlocked
   * check let the embedder in while the nightly LLM was still initializing, and that hung it too).
   */
  internal suspend fun <T : Any> whenNoneOpen(block: suspend () -> T): T? = lock.withLock { if (open.isEmpty()) block() else null }

  suspend fun acquire(config: ModelConfig): Engine = lock.withLock {
    val key = Key(config.modelPath, config.backend, config.maxTokens, config.speculativeDecoding)
    open.firstOrNull { it.key == key }?.let { it.refs++; return@withLock it.engine }
    // NonCancellable: a cancelled caller must not drop an engine that finished initializing
    val e = withContext(NonCancellable) { openEngine(config) }
    open += Entry(key, e, 1)
    e
  }

  /**
   * [thread]'s conversation on [engine]: the live one if it still matches, else one rebuilt from the kept history. Lives as
   * long as the engine, so a model loaded for one question does not lose it; a stateless call beside it is fine (Gallery's
   * ephemeral inference does the same on 0.18.0).
   */
  suspend fun chat(engine: Engine, thread: ChatThread, config: ModelConfig): ChatSlot = lock.withLock {
    val entry = open.first { it.engine === engine }
    entry.chat?.takeIf { it.fits(thread, config) }?.let { return@withLock it }
    entry.chat?.let { runCatching { it.conversation.close() } }
    entry.chat = null
    val conversation = engine.createConversation(
      ConversationConfig(
        samplerConfig = samplerConfig(config),
        systemInstruction = Contents.of(thread.system),
        tools = emptyList(),
        initialMessages = thread.history.flatMap { listOf(Message.user(Contents.of(it.user)), Message.model(Contents.of(it.model))) },
      )
    )
    ChatSlot(conversation, thread, config).also { entry.chat = it }
  }

  suspend fun release(engine: Engine) = withContext(Dispatchers.Default + NonCancellable) {
    lock.withLock {
      val entry = open.firstOrNull { it.engine === engine } ?: return@withLock
      if (--entry.refs > 0) return@withLock
      open -= entry
      runCatching { entry.chat?.conversation?.close() }
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
  openWithMtpFallback(config)
} catch (e: Exception) {
  if (config.backend != ModelBackend.Gpu) throw e
  Log.w(TAG, "GPU engine failed, retrying on CPU", e)
  openWithMtpFallback(config.copy(backend = ModelBackend.Cpu))
}

/** MTP only when asked for and the file carries a drafter; if the engine will not start with it, start without. */
private suspend fun openWithMtpFallback(config: ModelConfig): Engine {
  val mtp = config.speculativeDecoding && withContext(Dispatchers.IO) { hasDrafter(config.modelPath) }
  if (!mtp) return openEngineOn(config, speculative = false)
  return try {
    openEngineOn(config, speculative = true)
  } catch (e: Exception) {
    Log.w(TAG, "engine failed with speculative decoding, retrying without", e)
    openEngineOn(config, speculative = false)
  }
}

private fun hasDrafter(modelPath: String): Boolean =
  runCatching { ModelInfo.from(modelPath).use { (it as? ModelInfo.Llm)?.hasSpeculativeDecodingSupport() == true } }.getOrDefault(false)

@OptIn(ExperimentalApi::class)
private suspend fun openEngineOn(config: ModelConfig, speculative: Boolean): Engine = withContext(Dispatchers.Default) {
  val e = Engine(
    EngineConfig(
      modelPath = config.modelPath,
      backend = if (config.backend == ModelBackend.Gpu) Backend.GPU() else Backend.CPU(),
      maxNumTokens = config.maxTokens,
      cacheDir = config.cacheDir,
    )
  )
  try {
    // ponytail: a process-wide flag read during initialize() (as Gallery does); SharedEngines' lock keeps LLM opens serial,
    // the embedder never sets it. Per-engine config if LiteRT-LM ever offers one.
    ExperimentalFlags.enableSpeculativeDecoding = speculative
    try {
      e.initialize()
    } finally {
      ExperimentalFlags.enableSpeculativeDecoding = false
    }
    if (speculative) Log.i(TAG, "speculative decoding on")
    e
  } catch (t: Throwable) {
    runCatching { e.close() }
    throw t
  }
}

internal fun samplerConfig(config: ModelConfig) = SamplerConfig(config.topK, config.topP, config.temperature)
private const val TAG = "HakuModel"
