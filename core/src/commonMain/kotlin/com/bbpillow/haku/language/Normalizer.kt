package com.bbpillow.haku.language

private val ZERO_WIDTH = setOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF')

/** D13: every text goes through here before store/index/compare. Returns a new value; the original is kept. */
object Normalizer {
  fun normalize(input: TextInput): NormalizedText {
    val composed = nfc(input.text).filterNot { it in ZERO_WIDTH }
    val ruled = rulesFor(input.languageCode)?.normalize(composed) ?: composed
    return NormalizedText(original = input.text, normalized = collapseRuns(ruled))
  }

  // "มากกกกก" -> "มากก"; digits are kept ("1000") and only letters/marks collapse.
  private fun collapseRuns(text: String): String {
    val out = StringBuilder(text.length)
    var run = 0
    for (c in text) {
      run = if (out.isNotEmpty() && out.last() == c) run + 1 else 1
      val collapsible = c.isLetter() || c.category == CharCategory.NON_SPACING_MARK
      if (run <= 2 || !collapsible) out.append(c)
    }
    return out.toString()
  }
}
