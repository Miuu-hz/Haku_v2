package com.bbpillow.haku.thinking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class AgentSessionTest {
  private val config = AgentConfig(ModelConfig("m.litertlm", ModelBackend.Cpu), systemInstruction = "sys")
  private val time = ToolCallRecord("get_current_time", emptyMap(), "{time=14:05}")

  @Test fun executeJoinsTokensAndKeepsToolCallsInOrder() = runTest {
    val weather = ToolCallRecord("get_weather", mapOf("city" to "เชียงใหม่"), "sunny")
    val agent = FakeAgentSession(listOf(listOf(
      AgentEvent.ToolCalled(time),
      AgentEvent.Token("ตอนนี้ "),
      AgentEvent.ToolCalled(weather),
      AgentEvent.Token("บ่ายสองโมง"),
    )))
    agent.start(config)
    val out = agent.execute(AgentInput("กี่โมงแล้ว"))
    assertEquals("ตอนนี้ บ่ายสองโมง", out.text)
    assertEquals(listOf(time, weather), out.toolCalls)
  }

  @Test fun executeWithNoEventsIsEmpty() = runTest {
    val agent = FakeAgentSession(listOf(emptyList()))
    agent.start(config)
    assertEquals(AgentOutput("", emptyList()), agent.execute(AgentInput("x")))
  }

  @Test fun runtimeErrorPropagatesFromExecute() = runTest {
    val agent = FakeAgentSession(listOf(listOf(AgentEvent.Token("a"))), failWith = RuntimeException("oom"))
    agent.start(config)
    assertEquals("oom", assertFailsWith<RuntimeException> { agent.execute(AgentInput("x")) }.message)
  }

  @Test fun sendBeforeStartFails() = runTest {
    assertFailsWith<IllegalStateException> { FakeAgentSession(listOf(emptyList())).send(AgentInput("x")).toList() }
  }

  @Test fun turnsCarryHistoryUntilReset() = runTest {
    val agent = FakeAgentSession(listOf(listOf(AgentEvent.Token("1")), listOf(AgentEvent.Token("2")), listOf(AgentEvent.Token("3"))))
    agent.start(config)
    agent.execute(AgentInput("a"))
    assertEquals("2", agent.execute(AgentInput("b")).text)
    assertEquals(2, agent.history.size)
    agent.reset()
    assertEquals("1", agent.execute(AgentInput("c")).text)
  }

  @Test fun cancelStopsTurnEarly() = runTest {
    val agent = FakeAgentSession(listOf(listOf(AgentEvent.Token("a"), AgentEvent.Token("b"))))
    agent.start(config)
    val seen = mutableListOf<AgentEvent>()
    agent.send(AgentInput("x")).collect { seen += it; agent.cancel() }
    assertEquals(listOf<AgentEvent>(AgentEvent.Token("a")), seen)
  }

  @Test fun closeReleasesSession() = runTest {
    val agent = FakeAgentSession(listOf(emptyList()))
    agent.start(config)
    agent.close()
    assertTrue(agent.closed)
    assertFailsWith<IllegalStateException> { agent.execute(AgentInput("x")) }
  }
}
