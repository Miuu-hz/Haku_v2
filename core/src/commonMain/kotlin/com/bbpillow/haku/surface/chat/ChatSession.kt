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
  /** When this chat last moved on; 0 until its first answer. */
  val lastAtMs: Long = 0,
  /** How many times older history was summarised: tells the backend its conversation no longer matches, even at the same size. */
  val compactions: Int = 0,
) {
  /** Quiet this long and the screen starts a new chat; the old one stays one tap away ("ต่อเรื่องเมื่อกี้"). */
  fun isStale(nowMs: Long) = lastAtMs > 0 && nowMs - lastAtMs > IDLE_NEW_CHAT_MS

  fun thread(system: String) = ChatThread(id + compactions, system, general, turns)

  /** The history would crowd out the next answer: a long one is about 1,000 tokens (measured, as below). */
  fun needsFitting(maxTokens: Int) = general.sumOf { it.chars } > maxTokens * CHARS_PER_TOKEN * 3 / 5

  /**
   * Keeps the history from overflowing the engine. Past 60% of the context, drops down to a quarter, so the backend rebuilds
   * its conversation once every few turns rather than on each one. [ChatPresenter.compact] summarises what this would drop.
   */
  fun fitted(maxTokens: Int): ChatSessionState {
    if (!needsFitting(maxTokens)) return this
    var used = 0
    return copy(general = general.asReversed().takeWhile { used += it.chars; used <= maxTokens * CHARS_PER_TOKEN / 4 }.asReversed())
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

  companion object {
    private const val MAX_TOPIC_CHARS = 200
    /** Thai on Gemma 4 E2B measured about 2.4 on Fold5 (5,076 characters of answers took 2,236 tokens with the prompt); rounded down to be safe. */
    private const val CHARS_PER_TOKEN = 2
    const val IDLE_NEW_CHAT_MS = 30 * 60_000L
  }
}
