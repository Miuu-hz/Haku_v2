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
// alwaysAllow and the ToolAction types (JS/MCP/permission prompts) were dropped; runTool added.

package com.bbpillow.haku.platform.agent

import com.bbpillow.haku.thinking.ToolCallRecord
import com.google.ai.edge.litertlm.ToolSet

/**
 * Execution context attached to every [ToolDefinition] when a session starts.
 *
 * @property onToolCall receives each finished tool call; the session turns it into an agent event.
 */
class ToolExecutionContext(val onToolCall: (ToolCallRecord) -> Unit)

/**
 * A single tool, extending LiteRT-LM's [ToolSet]. Implementations annotate methods with
 * `@Tool` and parameters with `@ToolParam`, and wrap the body in [runTool] so the call is reported.
 */
interface ToolDefinition : ToolSet {
  var executionContext: ToolExecutionContext?

  fun onAttach(context: ToolExecutionContext) {
    executionContext = context
  }

  // ponytail: every tool call routes through here; P4.1 permission levels, audit log and rate limit go here
  // (LiteRT-LM automatic tool calling invokes @Tool methods directly, so there is no other choke point).
  fun <T : Any> runTool(name: String, arguments: Map<String, String>, block: () -> T): T {
    val result = block()
    executionContext?.onToolCall?.invoke(ToolCallRecord(name, arguments, result.toString()))
    return result
  }
}
