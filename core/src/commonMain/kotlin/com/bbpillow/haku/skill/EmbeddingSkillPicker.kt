package com.bbpillow.haku.skill

import com.bbpillow.haku.thinking.TextEmbedder
import com.bbpillow.haku.thinking.cosine

/**
 * Picks a skill by EmbeddingGemma cosine between the message and each skill's one-line description (+ title, aliases).
 * Returns a skill only when it clears [floor] and beats the runner-up by [margin]; otherwise plain chat, because a
 * wrong skill costs more (a confusing card) than no skill (the user can still type `/`).
 * [anchors] stand for plain chat (Haku's main job: questions about the user's own days). They compete like skills; if
 * one wins, the answer is "no skill". Without them a single installed skill has no runner-up and wins every message
 * that clears the floor (seen on Fold5 2026-11-03: "เมื่อวานไปไหนมาบ้าง" went to add-appointment).
 * Description vectors are cached per text, so a list change re-embeds only what changed.
 * ponytail: chatMargin = -0.02 reaches 26/40 recall at 1/40 FP on the 2026-11-03 eval; lower flips chat rows (row 31 margin -0.025). Retune Thai anchor/description texts to push recall higher.
 */
class EmbeddingSkillPicker(
  private val embedder: TextEmbedder,
  private val floor: Float = DEFAULT_FLOOR,
  private val margin: Float = DEFAULT_MARGIN,
  private val chatMargin: Float = DEFAULT_CHAT_MARGIN,
  private val anchors: List<String> = CHAT_ANCHORS,
) : SkillPicker {
  private val cache = mutableMapOf<String, FloatArray>()

  override suspend fun pick(text: String, skills: List<SkillRecord>): SkillRecord? {
    val ranked = rank(text, skills)
    val best = ranked.firstOrNull() ?: return null
    val second = ranked.getOrNull(1)?.second ?: -1f
    val chat = anchorScore(text)
    return best.first.takeIf {
      best.second >= floor && best.second - second >= margin && best.second - chat >= chatMargin
    }
  }

  /** Best plain-chat anchor score for [text] (the query vector is cached from the [rank] call just before). */
  suspend fun anchorScore(text: String): Float {
    if (anchors.isEmpty()) return -1f
    val docs = anchors.map { DOC_PREFIX + it }
    val missing = docs.filter { it !in cache }
    if (missing.isNotEmpty()) missing.zip(embedder.embed(missing)).forEach { (d, v) -> cache[d] = v }
    val q = query(text)
    return docs.maxOf { cosine(q, cache.getValue(it)) }
  }

  private suspend fun query(text: String): FloatArray {
    val key = QUERY_PREFIX + text.trim()
    lastQuery?.takeIf { it.first == key }?.let { return it.second }
    return embedder.embed(listOf(key)).single().also { lastQuery = key to it }
  }

  private var lastQuery: Pair<String, FloatArray>? = null

  /** Every enabled skill with its score, best first (the spike and the debug screen read this). */
  suspend fun rank(text: String, skills: List<SkillRecord>): List<Pair<SkillRecord, Float>> {
    val enabled = skills.filter { it.enabled }
    if (enabled.isEmpty() || text.isBlank()) return emptyList()
    val docs = enabled.map(::document)
    val missing = docs.filter { it !in cache }.distinct()
    if (missing.isNotEmpty()) missing.zip(embedder.embed(missing)).forEach { (d, v) -> cache[d] = v }
    val q = query(text)
    return enabled.zip(docs).map { (s, d) -> s to cosine(q, cache.getValue(d)) }.sortedByDescending { it.second }
  }

  private fun document(s: SkillRecord) = DOC_PREFIX + (listOf(s.title, s.description) + s.aliases).joinToString(" · ")

  companion object {
    const val DEFAULT_FLOOR = 0.55f
    const val DEFAULT_MARGIN = 0.03f
    const val DEFAULT_CHAT_MARGIN = -0.02f

    /** Plain chat about the user's own life: recall, summaries, small talk. */
    val CHAT_ANCHORS = listOf(
      "ถามว่าเมื่อวานหรือวันก่อนไปไหนมา ทำอะไรบ้าง",
      "สรุปวันนี้ สัปดาห์นี้ ใช้เวลาที่ไหนนานเท่าไร",
      "ถามเรื่องที่เคยบันทึกหรือเคยเล่าไว้",
      "คุยเล่น ทักทาย ถามความเห็น",
    )

    // EmbeddingGemma's retrieval prompts (model card): query vs document sides
    private const val QUERY_PREFIX = "task: search result | query: "
    private const val DOC_PREFIX = "title: none | text: "
  }
}
