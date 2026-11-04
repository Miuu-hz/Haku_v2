package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.thinking.ChatExchange
import com.bbpillow.haku.thinking.ChatThread

/**
 * P3.8 what the chat has said so far. Only general exchanges reach the model as history: the diary path stays stateless so
 * an earlier answer can never pass as a fact about the user. A diary follow-up carries the earlier question instead.
 *
 * @param turns every general exchange so far, including ones [fitted] dropped
 */
data class ChatSessionState(
  val id: Long,
  val general: List<ChatExchange> = emptyList(),
  val turns: Int = 0,
  val lastRoute: ChatRoute? = null,
  val lastDiaryQuestion: String? = null,
) {
  fun thread(system: String) = ChatThread(id, system, general, turns)

  /**
   * Keeps the history from overflowing the engine. Past half the context, drops down to a quarter, so the backend rebuilds
   * its conversation once every few turns rather than on each one.
   */
  // ponytail: oldest turns are forgotten outright, and one character counts as one token (safe for Thai); stage 1 item 6 summarises them.
  fun fitted(maxTokens: Int): ChatSessionState {
    if (general.sumOf { it.chars } <= maxTokens / 2) return this
    var used = 0
    return copy(general = general.asReversed().takeWhile { used += it.chars; used <= maxTokens / 4 }.asReversed())
  }

  /** An answer that only raised a card (appointment, skill) or was stopped before any text moves neither path along. */
  fun after(answer: ChatAnswerOutput): ChatSessionState = when {
    answer.appointment != null || answer.actionId != null -> this
    answer.stopped && answer.source != ChatAnswerSource.GENERAL -> this
    answer.source == ChatAnswerSource.GENERAL ->
      copy(general = general + ChatExchange(answer.question, answer.text), turns = turns + 1, lastRoute = ChatRoute.GENERAL)
    else -> {
      val chained = lastDiaryQuestion?.takeIf { ChatRouter.isFollowUp(answer.question) }?.let { "$it ${answer.question}" } ?: answer.question
      copy(lastRoute = ChatRoute.DIARY, lastDiaryQuestion = chained.takeLast(MAX_TOPIC_CHARS))
    }
  }

  private val ChatExchange.chars get() = user.length + model.length

  private companion object {
    const val MAX_TOPIC_CHARS = 200
  }
}
