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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

// Modelled on Google AI Edge Gallery LlmChatModelHelper (Apache 2.0): Engine -> Conversation -> sendMessageAsync.
class LiteRtLocalModel : LocalModel {
  private var engine: Engine? = null
  private var conversation: Conversation? = null

  override suspend fun load(config: ModelConfig) {
    unload()
    withContext(Dispatchers.Default) {
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
        conversation = e.createConversation(
          ConversationConfig(
            samplerConfig = SamplerConfig(config.topK, config.topP, config.temperature),
            systemInstruction = null,
            tools = emptyList(),
          )
        )
        engine = e
      } catch (t: Throwable) {
        runCatching { e.close() }
        throw t
      }
    }
  }

  override fun generate(input: GenerateInput): Flow<String> = callbackFlow {
    val conv = checkNotNull(conversation) { "model not loaded" }
    var finished = false
    conv.sendMessageAsync(
      Contents.of(listOf(Content.Text(input.prompt))),
      object : MessageCallback {
        override fun onMessage(message: Message) { trySend(message.toString()) }
        override fun onDone() { finished = true; close() }
        override fun onError(throwable: Throwable) {
          finished = true
          if (throwable is CancellationException) close() else close(throwable)
        }
      },
      emptyMap(),
    )
    awaitClose { if (!finished) cancel() }
  }

  override fun cancel() {
    try { conversation?.cancelProcess() } catch (_: IllegalStateException) { /* conversation already closed */ }
  }

  override fun unload() {
    runCatching { conversation?.close() }
    runCatching { engine?.close() }
    conversation = null
    engine = null
  }
}
