package com.bbpillow.haku.language

/** normalize -> script runs -> word segmentation -> language token rules. Language is a parameter (D20). */
class TextPipeline(private val segmenter: WordSegmenter) {
  fun process(input: TextInput): ProcessedText {
    val normalized = Normalizer.normalize(input).normalized
    val words = ScriptSplitter.split(normalized).flatMap { wordsOf(normalized, it, input.languageCode) }
    val tokens = rulesFor(input.languageCode)?.expandTokens(words) ?: words
    return ProcessedText(input.text, normalized, tokens, tokens.joinToString(" ") { it.text })
  }

  private fun wordsOf(text: String, run: ScriptRun, languageCode: String): List<WordToken> {
    val slice = text.substring(run.start, run.end)
    return when (run.script) {
      Script.SPACE, Script.PUNCTUATION -> emptyList()
      // ponytail: Latin/digit runs are already single words; only unspaced scripts need the OS breaker.
      Script.LATIN, Script.DIGIT -> listOf(WordToken(slice, run.start, run.end))
      Script.THAI, Script.OTHER ->
        segmenter.segment(slice, languageCode).map { it.copy(start = it.start + run.start, end = it.end + run.start) }
    }
  }
}
