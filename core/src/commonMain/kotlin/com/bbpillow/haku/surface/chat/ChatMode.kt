package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.thinking.LlmDefaults

/**
 * P3.8 ข้อ 13: the switch under the chat input. [GENERAL] answers from the diary or the model and never picks a tool by
 * itself; [AGENT] may also pick a skill or MCP tool; [A2A] hands work to the user's own PC once one is paired.
 * An explicit `@name` and the appointment card work in every mode: the user asked for them, and the gate still confirms.
 */
enum class ChatMode { GENERAL, AGENT, A2A }

/** One button of the switch. [disabledReason] is set exactly when [enabled] is false; the button stays visible. */
data class ChatModeOption(val mode: ChatMode, val label: String, val enabled: Boolean, val disabledReason: String? = null)

object ChatModes {
  const val AGENT_NEEDS_CONTEXT = "โหมดเอเจนต์ต้องการบริบท 8K ขึ้นไป ปรับได้ในตั้งค่า AI ในเครื่อง"

  fun options(contextTokens: Int): List<ChatModeOption> {
    val agentFits = contextTokens >= LlmDefaults.AGENT_MIN_CONTEXT
    return listOf(
      ChatModeOption(ChatMode.GENERAL, "ทั่วไป", enabled = true),
      ChatModeOption(ChatMode.AGENT, "เอเจนต์", enabled = agentFits, disabledReason = AGENT_NEEDS_CONTEXT.takeUnless { agentFits }),
      ChatModeOption(ChatMode.A2A, "A2A", enabled = true),
    )
  }

  /** The mode to keep once the context size is known: a lowered context drops [ChatMode.AGENT] back to general. */
  fun effective(mode: ChatMode, contextTokens: Int): ChatMode =
    if (options(contextTokens).first { it.mode == mode }.enabled) mode else ChatMode.GENERAL

  /** Whether Haku may choose a skill or MCP tool without an `@name`. */
  fun picksToolsByItself(mode: ChatMode): Boolean = mode == ChatMode.AGENT
}
