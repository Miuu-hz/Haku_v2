package com.bbpillow.haku.thinking.intent

import com.bbpillow.haku.language.TextInput
import com.bbpillow.haku.language.TextPipeline

/**
 * P5.3 which intentions a new event sets off. Rules only, no model: same scope (or ANY), still live, out of cooldown,
 * and every trigger word present in the event, both sides through the index's [TextPipeline] (normalize + segment).
 * A place-pinned intention ([Intention.placeId]) fires on that place's arrival only.
 * ponytail: token overlap in memory, not an FTS query; a few dozen intentions per event is nothing. Use the index if it grows.
 */
class IntentMatcher(private val pipeline: TextPipeline) {
  fun match(intentions: List<Intention>, event: IntentTriggerEvent): List<Intention> {
    val words by lazy { tokens(event.text).toSet() }
    return intentions.filter { i ->
      (i.scope == IntentScope.ANY || i.scope == event.scope) &&
        i.isLive(event.atMs) &&
        (i.lastFiredAtMs == null || event.atMs - i.lastFiredAtMs >= i.cooldownMs) &&
        (
          if (i.placeId != null && event.scope == IntentScope.PLACE) i.placeId == event.placeId
          else keywords(i.trigger).let { k -> k.isNotEmpty() && words.containsAll(k) }
        )
    }
  }

  /** Trigger words that must all appear; filler words ("ที่", "กับ", "เรื่อง") never count. */
  fun keywords(trigger: String): Set<String> = tokens(trigger).filterNot { it in FILLER }.toSet()

  private fun tokens(text: String): List<String> = pipeline.process(TextInput(text, "th")).tokens.map { it.text.lowercase() }

  private companion object {
    val FILLER = setOf(
      "ที่", "กับ", "ของ", "เรื่อง", "คำ", "ว่า", "ไป", "มา", "ถึง", "ให้", "และ", "หรือ", "ด้วย", "นะ", "ครับ", "ค่ะ", "คะ",
      "ฉัน", "ผม", "หนู", "เรา",
    )
  }
}
