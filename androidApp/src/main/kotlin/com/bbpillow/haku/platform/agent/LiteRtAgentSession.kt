/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Modified for Haku (2026): derived from Google AI Edge Gallery 1.0.19
// agent/DefaultAgentRuntimeExecutor.kt, agent/sessions/DefaultLlmSessionManager.kt and
// ui/llmchat/LlmChatModelHelper.kt (initialize / resetConversation / runInference / stopResponse).
// Hilt, Model.kt, ChatSessionRepository persistence, feedback links, image/audio attachments,
// skills and MCP were removed; the model comes from our ModelConfig and events are our AgentEvent.

package com.bbpillow.haku.platform.agent

import com.google.ai.edge.litertlm.OpenApiTool
import com.bbpillow.haku.platform.SharedEngines
import com.bbpillow.haku.platform.samplerConfig
import com.bbpillow.haku.thinking.AgentConfig
import com.bbpillow.haku.thinking.AgentEvent
import com.bbpillow.haku.thinking.AgentInput
import com.bbpillow.haku.thinking.AgentSession
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.tool
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

/**
 * Agent chat on LiteRT-LM with automatic tool calling: the engine runs the `@Tool` methods of
 * [tools] itself and each call is reported as [AgentEvent.ToolCalled] via [ToolExecutionContext].
 *
 * ponytail: tools are fixed at construction. Gallery's ToolsProvider.registerTool (runtime add of
 * skills/MCP) comes with P4.2/P4.5; pass a new list and start() again until then.
 */
/** [jsonTools]: tools built at runtime (skills, MCP tools: see SkillTool); they report to the gate themselves. */
class LiteRtAgentSession(
  private val tools: List<ToolDefinition>,
  private val jsonTools: List<OpenApiTool> = emptyList(),
) : AgentSession {
  @Volatile private var engine: Engine? = null
  @Volatile private var config: AgentConfig? = null
  @Volatile private var conversation: Conversation? = null
  /** Where tool calls go while a turn is streaming; null between turns. */
  @Volatile private var sink: ((AgentEvent) -> Unit)? = null

  private val toolContext = ToolExecutionContext { call -> sink?.invoke(AgentEvent.ToolCalled(call)) }
  /** start/close one at a time, so a racing second start cannot drop the first engine reference. */
  private val starting = Mutex()

  override suspend fun start(config: AgentConfig) = starting.withLock {
    release()
    tools.forEach { it.onAttach(toolContext) } // Gallery RuntimeToolDispatcher.setupExecutionContext
    val e = SharedEngines.acquire(config.model)
    engine = e
    try {
      conversation = withContext(Dispatchers.Default) { e.createConversation(conversationConfig(config)) }
    } catch (t: Throwable) {
      release() // give the engine reference back
      throw t
    }
    this.config = config
  }

  // Gallery LlmChatModelHelper.resetConversation with empty initialMessages: history persistence is
  // the memory layer's job (P1.3), not the session's.
  override suspend fun reset() = withContext(Dispatchers.Default + NonCancellable) {
    val e = checkNotNull(engine) { "agent not started" }
    conversation?.let { runCatching { it.close() } }
    conversation = e.createConversation(conversationConfig(checkNotNull(config)))
  }

  override fun send(input: AgentInput): Flow<AgentEvent> = callbackFlow {
    val conv = checkNotNull(conversation) { "agent not started" }
    val finished = AtomicBoolean(false)
    sink = { trySend(it) } // never fails: unlimited buffer below
    conv.sendMessageAsync(
      Contents.of(listOf(Content.Text(input.text))),
      object : MessageCallback {
        override fun onMessage(message: Message) {
          val text = message.toString()
          // Gallery DefaultAgentRuntimeExecutor drops control tokens such as "<ctrl99>".
          if (text.isNotEmpty() && !text.startsWith("<ctrl")) trySend(AgentEvent.Token(text))
        }
        override fun onDone() { finished.set(true); close() }
        override fun onError(throwable: Throwable) {
          finished.set(true)
          if (throwable is CancellationException) close() else close(throwable)
        }
      },
      // Gallery LlmChatModelHelper.runInference default: thinking off.
      mapOf("enable_thinking" to false),
    )
    awaitClose {
      sink = null
      if (!finished.get()) this@LiteRtAgentSession.cancel()
    }
  }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.Default)

  override fun cancel() {
    try { conversation?.cancelProcess() } catch (_: IllegalStateException) { /* conversation already closed */ }
  }

  override suspend fun close() = withContext(NonCancellable) { starting.withLock { release() } }

  private suspend fun release() = withContext(Dispatchers.Default + NonCancellable) {
    val conv = conversation
    val e = engine
    conversation = null
    engine = null
    config = null
    runCatching { conv?.close() }
    e?.let { SharedEngines.release(it) }
    Unit
  }

  private fun conversationConfig(config: AgentConfig) = ConversationConfig(
    systemInstruction = config.systemInstruction?.let { Contents.of(it) },
    tools = tools.map { tool(it) } + jsonTools.map { tool(it) },
    samplerConfig = samplerConfig(config.model),
  )
}
