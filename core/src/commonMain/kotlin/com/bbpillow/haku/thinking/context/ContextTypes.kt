package com.bbpillow.haku.thinking.context

import com.bbpillow.haku.memory.MemoryRef
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.memory.index.SearchHitOutput

/** Fixed assembly order (P1.9): standing instructions, curated core, recalled daily records. */
enum class ContextSection { STANDING, CORE, RECALL }

/** D18 retrieval tier that found an item; ALWAYS = included without retrieval (standing instructions). */
enum class RetrievalTier { ALWAYS, STRUCTURAL, WORDS, SEMANTIC, LINK, CATEGORY }

data class AssembleInput(
  val question: String,
  val budgetTokens: Int,
  val nowEpochMs: Long,
  /** Device UTC offset, so "yesterday" means the user's yesterday. */
  val utcOffsetMinutes: Int,
  val languageCode: String = "th",
)

/** One line sent to the model. [text] is exactly that line; [reason] and [tiers] say why it is there (E5 inspector). */
data class ContextItem(
  val section: ContextSection,
  val text: String,
  val tokens: Int,
  val source: MemoryRef,
  val provenance: Provenance,
  val atEpochMs: Long,
  val tiers: List<RetrievalTier>,
  val reason: String,
  val value: Double,
)

/** Everything one model call gets. [items] are what is sent, in order; [dropped] did not fit the budget. */
data class AssembledContext(
  val question: String,
  val budgetTokens: Int,
  val items: List<ContextItem>,
  val dropped: List<ContextItem>,
  /** Human-readable log of the steps taken (tiers run, filters, fallbacks). */
  val trace: List<String>,
) {
  val usedTokens: Int get() = items.sumOf { it.tokens }

  /** The text placed in the prompt. Matches [items] exactly. */
  fun promptText(): String = items.joinToString("\n") { it.text }
}

data class AssemblerLimits(
  val maxCategories: Int = 2,
  val maxOtherFactsPerCategory: Int = 5,
  val maxWordHits: Int = 10,
  val maxLinkSeeds: Int = 3,
  /** New lines the semantic tier may add (ชิ้น ค); its cosine floor lives in VectorSemanticTier.MIN_COSINE. */
  val maxSemanticHits: Int = 8,
)

/** Semantic tier (ชิ้น ค, VectorSemanticTier). Null = disabled; a throwing implementation falls back to word search. */
fun interface SemanticTier { fun search(question: String, limit: Int): List<SearchHitOutput> }

/** Link-following tier hook (D18 step 4): records related to the given ones. Null = disabled. */
fun interface LinkTier { fun follow(seeds: List<MemoryRef>): List<SearchHitOutput> }
