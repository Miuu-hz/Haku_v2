package com.bbpillow.haku.language

/** Splits text into maximal same-script runs (Thai / Latin / digits / punctuation / space / other). */
object ScriptSplitter {
  fun split(text: String): List<ScriptRun> {
    val runs = mutableListOf<ScriptRun>()
    var start = 0
    for (i in 1..text.length) {
      if (i == text.length || scriptOf(text[i]) != scriptOf(text[start])) {
        runs += ScriptRun(scriptOf(text[start]), start, i)
        start = i
      }
    }
    return if (text.isEmpty()) emptyList() else runs
  }

  // Thai block includes its combining marks, so marks stay glued to their consonant.
  private fun scriptOf(c: Char): Script = when {
    c in '\u0E00'..'\u0E7F' -> Script.THAI
    c in '0'..'9' -> Script.DIGIT
    c.isWhitespace() -> Script.SPACE
    c in 'A'..'Z' || c in 'a'..'z' || c in '\u00C0'..'\u024F' -> Script.LATIN
    c.isLetter() || c.category == CharCategory.NON_SPACING_MARK -> Script.OTHER
    else -> Script.PUNCTUATION
  }
}
