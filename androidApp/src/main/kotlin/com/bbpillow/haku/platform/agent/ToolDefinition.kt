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

// Modified for Haku (2026): derived from Google AI Edge Gallery 1.0.19 tools/ToolDefinition.kt.
// ToolExecutionContext reports calls to our session instead of a Gallery ToolAction channel;
// alwaysAllow and the ToolAction types (JS/MCP/permission prompts) were dropped; runTool added
// and routed through P4.1 ActionGate (origin MODEL_TOOL; AwaitingUser returns "รอผู้ใช้ยืนยัน").

package com.bbpillow.haku.platform.agent

import com.bbpillow.haku.action.ActionDecision
import com.bbpillow.haku.action.ActionGate
import com.bbpillow.haku.action.ActionOrigin
import com.bbpillow.haku.action.ActionRequestInput
import com.bbpillow.haku.action.BlockReason
import com.bbpillow.haku.thinking.ToolCallRecord
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.runBlocking

/**
 * Execution context attached to every [ToolDefinition] when a session starts.
 *
 * @property onToolCall receives each finished tool call; the session turns it into an agent event.
 * @property actionGate optional P4.1 gate; when present, every [ToolDefinition.runTool] submits with [ActionOrigin.MODEL_TOOL].
 */
class ToolExecutionContext(
  val actionGate: ActionGate? = null,
  val onToolCall: (ToolCallRecord) -> Unit,
)

/**
 * A single tool, extending LiteRT-LM's [ToolSet]. Implementations annotate methods with
 * `@Tool` and parameters with `@ToolParam`, and wrap the body in [runTool] so the call goes through [ActionGate].
 */
interface ToolDefinition : ToolSet {
  var executionContext: ToolExecutionContext?

  fun onAttach(context: ToolExecutionContext) {
    executionContext = context
  }

  /**
   * Every tool call routes through here (LiteRT-LM automatic tool calling invokes @Tool methods directly on its worker
   * thread, so there is no other choke point and runBlocking is safe). With a gate, [block] is never run: the gate's
   * registered ActionHandler for [name] does the work (or waits for the user's confirm tap), so a model cannot bypass
   * the permission level. Without a gate (debug spikes) [block] runs directly.
   */
  fun runTool(
    name: String,
    arguments: Map<String, String>,
    title: String = arguments["title"] ?: name,
    detail: String = arguments["detail"].orEmpty(),
    warning: String? = arguments["warning"],
    block: () -> Any = { "" },
  ): String {
    val ctx = executionContext
    val gate = ctx?.actionGate
    val text = if (gate == null) {
      block().toString()
    } else {
      val request = ActionRequestInput(name, arguments, title, detail, ActionOrigin.MODEL_TOOL, warning)
      when (val decision = runBlocking { gate.submit(request) }) {
        is ActionDecision.AwaitingUser -> AWAITING_USER_TEXT
        is ActionDecision.Ran -> decision.proposal.message.orEmpty()
        is ActionDecision.Blocked -> when (decision.reason) {
          BlockReason.UNKNOWN_ACTION -> "ไม่พบคำสั่งนี้"
          BlockReason.RATE_LIMITED -> "เรียกซ้ำเร็วเกินไป"
          BlockReason.STOPPED -> "หยุดคำสั่งทั้งหมดไว้"
        }
      }
    }
    ctx?.onToolCall?.invoke(ToolCallRecord(name, arguments, text))
    return text
  }

  companion object {
    const val AWAITING_USER_TEXT = "รอผู้ใช้ยืนยัน"
  }
}
