package com.bbpillow.haku.memory.index

/** One record to index. [text] is stored untouched and returned as-is in hits (D13). */
data class IndexRecordInput(val id: String, val text: String, val languageCode: String = "th")

data class SearchQueryInput(
  val text: String,
  val languageCode: String = "th",
  val limit: Int = 20,
  /** D19: also match hand-written synonyms of each query word. */
  val expandSynonyms: Boolean = true,
)

enum class MatchKind { WORDS, SUBSTRING }

/** [score]: higher is better. For [MatchKind.SUBSTRING] it is 0 (no ranking in the fallback). */
data class SearchHitOutput(val id: String, val text: String, val score: Double, val kind: MatchKind)
