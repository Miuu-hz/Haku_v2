package com.bbpillow.haku.thinking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Scripted [AgentSession]: each [send] plays the next turn in [turns] and remembers the input. */
class FakeAgentSession(
  private val turns: List<List<AgentEvent>>,
  private val failWith: Throwable? = null,
) : AgentSession {
  var config: AgentConfig? = null
  val history = mutableListOf<AgentInput>()
  var cancelled = false
  var closed = false

  override suspend fun start(config: AgentConfig) {
    this.config = config
    closed = false
  }

  override fun send(input: AgentInput): Flow<AgentEvent> = flow {
    checkNotNull(config) { "agent not started" }
    val turn = turns[history.size]
    history += input
    for (e in turn) {
      if (cancelled) break
      emit(e)
    }
    failWith?.let { throw it }
  }

  override fun cancel() { cancelled = true }

  override suspend fun reset() { history.clear() }

  override suspend fun close() {
    config = null
    closed = true
  }
}
