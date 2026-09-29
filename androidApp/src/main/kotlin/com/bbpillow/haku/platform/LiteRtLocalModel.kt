package com.bbpillow.haku.platform

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
import kotlinx.coroutines.withContext

// Modelled on Google AI Edge Gallery LlmChatModelHelper (Apache 2.0): Engine -> Conversation -> sendMessageAsync.
class LiteRtLocalModel : LocalModel {
  @Volatile private var engine: Engine? = null
  @Volatile private var conversationConfig: ConversationConfig? = null
  @Volatile private var conversation: Conversation? = null

  override suspend fun load(config: ModelConfig) {
    unload()
    engine = openEngine(config)
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
        override fun onMessage(message: Message) { trySend(message.toString()) } // never fails: unlimited buffer below
        override fun onDone() { finished.set(true); close() }
        override fun onError(throwable: Throwable) {
          finished.set(true)
          if (throwable is CancellationException) close() else close(throwable)
        }
      },
      emptyMap(),
    )
    awaitClose { if (!finished.get()) this@LiteRtLocalModel.cancel() } // bare cancel() would hit ProducerScope.cancel, not the native stop
  }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.Default)

  override fun cancel() {
    try { conversation?.cancelProcess() } catch (_: IllegalStateException) { /* conversation already closed */ }
  }

  // Native close blocks, so it runs off the caller's thread and is not skipped when the caller is cancelled.
  override suspend fun unload() = withContext(Dispatchers.Default + NonCancellable) {
    val conv = conversation
    val e = engine
    conversation = null
    conversationConfig = null
    engine = null
    runCatching { conv?.close() }
    runCatching { e?.close() }
    Unit
  }
}

/** Creates and initializes an [Engine] off the caller's thread; shared with the agent session. */
internal suspend fun openEngine(config: ModelConfig): Engine = withContext(Dispatchers.Default) {
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
