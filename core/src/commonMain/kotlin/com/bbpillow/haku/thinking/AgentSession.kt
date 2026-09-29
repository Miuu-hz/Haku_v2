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

// Modified for Haku (2026): derived from Google AI Edge Gallery 1.0.19 agent/AgentRuntimeExecutor,
// AgentRequest, AgentEvent and AgentResponse. Rewritten as a platform-free contract so core never
// sees Gallery or LiteRT-LM types; attachments, metadata maps and callbacks were dropped.

package com.bbpillow.haku.thinking

import kotlinx.coroutines.flow.Flow

data class AgentConfig(
  val model: ModelConfig,
  val systemInstruction: String? = null,
)

data class AgentInput(val text: String)

/** One tool the model ran during a turn. [result] is the tool's return value as text. */
data class ToolCallRecord(
  val name: String,
  val arguments: Map<String, String>,
  val result: String,
)

sealed interface AgentEvent {
  data class Token(val text: String) : AgentEvent
  data class ToolCalled(val call: ToolCallRecord) : AgentEvent
}

data class AgentOutput(val text: String, val toolCalls: List<ToolCallRecord>)

/**
 * Multi-turn agent chat: unlike [LocalModel], history carries over between [send] calls until
 * [reset]. Tools are wired by the platform implementation; core only sees what they did.
 */
interface AgentSession {
  /** Loads the model and opens a conversation; blocking native work stays off the caller's thread. */
  suspend fun start(config: AgentConfig)

  /** Streams one turn; completes when the model is done or cancelled; throws on runtime error. */
  fun send(input: AgentInput): Flow<AgentEvent>

  fun cancel()

  /** Drops history, keeps the loaded model and config. */
  suspend fun reset()

  suspend fun close()
}

/** Collects one turn into its final text and tool calls (Gallery AgentRuntimeExecutor.execute). */
suspend fun AgentSession.execute(input: AgentInput): AgentOutput {
  val text = StringBuilder()
  val calls = mutableListOf<ToolCallRecord>()
  send(input).collect { event ->
    when (event) {
      is AgentEvent.Token -> text.append(event.text)
      is AgentEvent.ToolCalled -> calls += event.call
    }
  }
  return AgentOutput(text.toString(), calls)
}
