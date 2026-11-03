package com.bbpillow.haku.skill

import com.bbpillow.haku.thinking.TextEmbedder
import com.bbpillow.haku.thinking.cosine

/**
 * Picks a skill by EmbeddingGemma cosine between the message and each skill's one-line description (+ title, aliases).
 * Returns a skill only when it clears [floor] and beats the runner-up by [margin]; otherwise plain chat, because a
 * wrong skill costs more (a confusing card) than no skill (the user can still type `/`).
 * Description vectors are cached per text, so a list change re-embeds only what changed.
 * ponytail: floor/margin are first guesses; the week 2 spike sets them from the eval set.
 */
class EmbeddingSkillPicker(
  private val embedder: TextEmbedder,
  private val floor: Float = 0.55f,
  private val margin: Float = 0.03f,
) : SkillPicker {
  private val cache = mutableMapOf<String, FloatArray>()

  override suspend fun pick(text: String, skills: List<SkillRecord>): SkillRecord? = rank(text, skills).let { ranked ->
    val best = ranked.firstOrNull() ?: return null
    val second = ranked.getOrNull(1)?.second ?: -1f
    best.first.takeIf { best.second >= floor && best.second - second >= margin }
  }

  /** Every enabled skill with its score, best first (the spike and the debug screen read this). */
  suspend fun rank(text: String, skills: List<SkillRecord>): List<Pair<SkillRecord, Float>> {
    val enabled = skills.filter { it.enabled }
    if (enabled.isEmpty() || text.isBlank()) return emptyList()
    val docs = enabled.map(::document)
    val missing = docs.filter { it !in cache }.distinct()
    if (missing.isNotEmpty()) missing.zip(embedder.embed(missing)).forEach { (d, v) -> cache[d] = v }
    val q = embedder.embed(listOf(QUERY_PREFIX + text.trim())).single()
    return enabled.zip(docs).map { (s, d) -> s to cosine(q, cache.getValue(d)) }.sortedByDescending { it.second }
  }

  private fun document(s: SkillRecord) = DOC_PREFIX + (listOf(s.title, s.description) + s.aliases).joinToString(" · ")

  private companion object {
    // EmbeddingGemma's retrieval prompts (model card): query vs document sides
    const val QUERY_PREFIX = "task: search result | query: "
    const val DOC_PREFIX = "title: none | text: "
  }
}
