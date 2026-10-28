package com.bbpillow.haku.memory.index

import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryRef
import com.bbpillow.haku.memory.Provenance

/** Link from an index row back to its memory record, plus the stamps a filter needs (P1.4/P1.7). */
data class IndexedMemory(val ref: MemoryRef, val provenance: Provenance, val atEpochMs: Long)

/** One record to index. [text] is stored untouched and returned as-is in hits (D13). [memory] is null for bare benchmark rows. */
data class IndexRecordInput(
  val id: String,
  val text: String,
  val languageCode: String = "th",
  val memory: IndexedMemory? = null,
)

data class SearchQueryInput(
  val text: String,
  val languageCode: String = "th",
  val limit: Int = 20,
  /** D19: also match hand-written synonyms of each query word. */
  val expandSynonyms: Boolean = true,
  /** Structural filters (D18 tier 1). Null = no restriction. Rows without memory metadata never pass a non-null filter. */
  val levels: Set<MemoryLevel>? = null,
  val provenances: Set<Provenance>? = null,
  val fromEpochMs: Long? = null,
  /** Exclusive. */
  val toEpochMs: Long? = null,
)

/** SEMANTIC = cosine of embeddings (score in [-1, 1]), from thinking/context/VectorSemanticTier. */
enum class MatchKind { WORDS, SUBSTRING, SEMANTIC }

/** [score]: higher is better. For [MatchKind.SUBSTRING] it is 0 (no ranking in the fallback). */
data class SearchHitOutput(
  val id: String,
  val text: String,
  val score: Double,
  val kind: MatchKind,
  val memory: IndexedMemory? = null,
)
