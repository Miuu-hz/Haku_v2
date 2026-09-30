package com.bbpillow.haku.memory.dream

/** Hard-rule check of a model-phrased fact (same idea as SummaryChecker). Any reason means: use the template. */
object DreamFactChecker {
  const val MAX_CHARS = 120
  private val number = Regex("""\d+""")
  private val latin = Regex("""[A-Za-z]+""")
  private val quoted = Regex("[“\"]([^”\"]+)[”\"]")
  private val hidden = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u200B-\\u200F\\u2028-\\u202E\\u2060\\uFEFF]")

  fun check(text: String, subject: String, sourceTexts: List<String>, supportDays: Int, minDays: Int): List<String> {
    val t = text.trim()
    val src = sourceTexts.joinToString("\n")
    return buildList {
      if (supportDays < minDays) add("unsupported")
      if (t.isEmpty()) add("empty")
      if (t.length > MAX_CHARS) add("too-long")
      if (hidden.containsMatchIn(t)) add("control-chars")
      if (subject !in t) add("missing-subject")
      val knownNumbers = number.findAll(src).map { it.value.trimStart('0') }.toSet()
      if (number.findAll(t).any { it.value.trimStart('0') !in knownNumbers }) add("invented-number")
      val knownLatin = latin.findAll(src).map { it.value.lowercase() }.toSet()
      if (latin.findAll(t).any { it.value.lowercase() !in knownLatin }) add("latin-word")
      if (quoted.findAll(t).any { it.groupValues[1] !in src }) add("invented-quote")
    }
  }
}
