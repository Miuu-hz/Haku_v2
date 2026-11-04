package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.thinking.LlmDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatModeTest {
  private fun agent(context: Int) = ChatModes.options(context).first { it.mode == ChatMode.AGENT }

  @Test
  fun allThreeButtonsShowInOrderAtEveryContextSize() {
    LlmDefaults.CONTEXT_STEPS.forEach { size ->
      assertEquals(listOf("ทั่วไป", "เอเจนต์", "A2A"), ChatModes.options(size).map { it.label })
    }
  }

  @Test
  fun agentNeedsTheMinimumContextAndSaysWhy() {
    assertFalse(agent(LlmDefaults.AGENT_MIN_CONTEXT / 2).enabled)
    assertNotNull(agent(LlmDefaults.AGENT_MIN_CONTEXT / 2).disabledReason)
    assertTrue(agent(LlmDefaults.AGENT_MIN_CONTEXT).enabled)
    assertNull(agent(LlmDefaults.AGENT_MIN_CONTEXT).disabledReason)
  }

  @Test
  fun generalAndA2aStayEnabledOnSmallContext() {
    val small = ChatModes.options(2048)
    assertTrue(small.first { it.mode == ChatMode.GENERAL }.enabled)
    assertTrue(small.first { it.mode == ChatMode.A2A }.enabled)
  }

  @Test
  fun loweringContextDropsAgentBackToGeneral() {
    assertEquals(ChatMode.AGENT, ChatModes.effective(ChatMode.AGENT, 8192))
    assertEquals(ChatMode.GENERAL, ChatModes.effective(ChatMode.AGENT, 4096))
    assertEquals(ChatMode.GENERAL, ChatModes.effective(ChatMode.GENERAL, 4096))
  }

  @Test
  fun onlyAgentPicksToolsByItself() {
    assertTrue(ChatModes.picksToolsByItself(ChatMode.AGENT))
    assertFalse(ChatModes.picksToolsByItself(ChatMode.GENERAL))
    assertFalse(ChatModes.picksToolsByItself(ChatMode.A2A))
  }
}
