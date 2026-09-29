package com.bbpillow.haku.language

/** Raw user text plus its language (BCP-47 code, e.g. "th"). Language is always a parameter (D20). */
data class TextInput(val text: String, val languageCode: String)

/** A word with [start, end) offsets into [ProcessedText.normalized]. Repeated words (ๆ) share offsets. */
data class WordToken(val text: String, val start: Int, val end: Int)

enum class Script { THAI, LATIN, DIGIT, PUNCTUATION, SPACE, OTHER }

/** A maximal span of one [Script], offsets into the text that was split. */
data class ScriptRun(val script: Script, val start: Int, val end: Int)

/** Normalization result. [original] is never modified (D13); [normalized] is the search/compare copy. */
data class NormalizedText(val original: String, val normalized: String)

/** Full pipeline result: original kept, normalized copy, word list, and space-joined text for the index. */
data class ProcessedText(
  val original: String,
  val normalized: String,
  val tokens: List<WordToken>,
  val segmented: String,
)
